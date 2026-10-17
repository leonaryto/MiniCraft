package com.minicraft.mcserver.playit

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.minicraft.mcserver.runtime.NativeLauncher
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Orquesta el daemon playitd: arranca el binario desde nativeLibraryDir,
 * se conecta al IPC (socket de FS en files/playit/), lee el puerto público del log y expone
 * el estado del túnel al UI.
 *
 * Flujo verificado en hardware:
 *  1. Verifica libplayitd.so en nativeLibraryDir (jniLib extraído por Android).
 *  2. Verifica / crea el secret en files/playit/playit.toml.
 *  3. Arranca playitd: fork+execve desde nativeLibraryDir (NO desde files/ — W^X).
 *  4. Espera ~3 s a que el daemon se conecte a playit.gg y cargue túneles.
 *  5. Conecta al IPC (socket abstracto @playit_ipc via syscall nativas) → display_address + stats.
 *  6. Lee files/playit/playit.log → puerto público (connect_addr: IP:PORT).
 *  7. Combina: publicAddress = display_address + ":" + port.
 *
 * El claim code "dc1b44f2ea" está EXPIRADO (verificado vía curl):
 *   POST /claim/exchange → {"status":"fail","data":"CodeExpired"}
 * Por eso el secret se pre-provisiona en files/playit/playit.toml antes de
 * instalar, y PlayitAgent solo hace el claim si el archivo no existe.
 *
 * Ver CONTEXTO.md §3 (W^X, jniLib), §4.3 (claim), §4.4 (--secret-path).
 */
class PlayitAgent private constructor(private val ctx: Context) {

    companion object {
        const val TAG = "PlayitAgent"
        private const val CLAIM_CODE = "dc1b44f2ea"
        private const val SOCKET_NAME = "@playit_ipc"
        private const val PLAYIT_DIR = "playit"
        private const val SECRET_FILE = "playit.toml"
        private const val LOG_FILE = "playit.log"

        @Volatile private var INSTANCE: PlayitAgent? = null

        /** Singleton: MinecraftService y MainActivity comparten la misma instancia. */
        fun get(ctx: Context): PlayitAgent = INSTANCE ?: synchronized(this) {
            INSTANCE ?: PlayitAgent(ctx.applicationContext).also { INSTANCE = it }
        }

        private const val PLAYIT_API = "https://api.playit.gg"
        private const val CLAIM_SETUP_URL = "$PLAYIT_API/claim/setup"
        private const val CLAIM_EXCHANGE_URL = "$PLAYIT_API/claim/exchange"
        private const val RUNDATA_URL = "$PLAYIT_API/v1/agents/rundata"
    }

    // ------------------------------------------------------------------ estado

    data class PlayitState(
        val tunnelState: String,       // "Starting" | "Connecting" | "Online" | "Offline" | "Error"
        val publicAddress: String?,    // "fried-jacuzzi.tun.ply.gg:34544"
        val displayAddress: String?,   // "fried-jacuzzi.tun.ply.gg"
        val localAddress: String?,     // "127.0.0.1:25565"
        val bytesIn: Long,
        val bytesOut: Long,
        val activeTcp: Int,
        val activeUdp: Int,
        val error: String?
    )

    /** UI lambda: recibe cada actualización de estado (en hilo principal). */
    var listener: ((PlayitState) -> Unit)? = null

    // ------------------------------------------------------------------ internals

    private val ipcClient = TunnelIpcClient()
    private val uiHandler = Handler(Looper.getMainLooper())
    private val isRunning = AtomicBoolean(false)
    private var daemonThread: Thread? = null
    private var logThread: Thread? = null

    @Volatile private var currentSnapshot: TunnelUrlParser.Snapshot? = null
    @Volatile private var currentPort: Int? = null

    private val playitDir: File get() = File(ctx.filesDir, PLAYIT_DIR)
    private val secretFile: File get() = File(playitDir, SECRET_FILE)
    private val logFile: File get() = File(playitDir, LOG_FILE)

    // ------------------------------------------------------------------ API pública

    /**
     * Arranca el daemon playitd y conecta el IPC. No bloquea.
     * El listener se invoca en el hilo principal.
     */
    fun start(log: (String) -> Unit = { Log.i(TAG, it) }) {
        if (!isRunning.compareAndSet(false, true)) {
            log("PlayitAgent ya está corriendo")
            return
        }
        log("PlayitAgent.start: iniciando")
        emit(PlayitState("Starting", null, null, null, 0, 0, 0, 0, null))

        daemonThread = Thread({ startDaemonAndConnect(log) }, "playit-daemon")
        daemonThread?.start()
    }

    /** Detiene el daemon y desconecta el IPC. No bloquea. */
    fun stop() {
        if (!isRunning.compareAndSet(true, false)) return
        Log.i(TAG, "PlayitAgent.stop: deteniendo")

        ipcClient.disconnect()
        logThread?.interrupt()
        logThread = null

        Thread({
            NativeLauncher.stopPlayitDaemon()
        }, "playit-stop").start()

        currentSnapshot = null
        currentPort = null
        emit(PlayitState("Offline", null, null, null, 0, 0, 0, 0, null))
    }

    /** Devuelve el estado actual sin esperar al listener. */
    fun currentState(): PlayitState {
        val snap = currentSnapshot
        val port = currentPort
        return if (snap != null) {
            val pub = if (snap.displayAddress != null && port != null) {
                "${snap.displayAddress}:$port"
            } else snap.displayAddress
            PlayitState(
                tunnelState = snap.tunnelState,
                publicAddress = pub,
                displayAddress = snap.displayAddress,
                localAddress = snap.destination,
                bytesIn = snap.bytesIn,
                bytesOut = snap.bytesOut,
                activeTcp = snap.activeTcp,
                activeUdp = snap.activeUdp,
                error = null
            )
        } else {
            PlayitState("Offline", null, null, null, 0, 0, 0, 0, null)
        }
    }

    // ------------------------------------------------------------------ daemon

    private fun startDaemonAndConnect(log: (String) -> Unit) {
        try {
            // 1. Verificar binario playitd en nativeLibraryDir
            val playitd = NativeLauncher.playitdBinary(ctx)
            if (!playitd.exists()) {
                setError(log, "libplayitd.so no encontrado en nativeLibraryDir. " +
                        "Verifica que el APK incluye jniLibs/arm64-v8a/libplayitd.so.")
                return
            }
            // chmod 0755: Android extrae jniLibs con el bit de ejecución, pero
            // por si acaso (p.ej. APK antiguo), forzamos permisos.
            NativeLauncher.chmod(playitd.absolutePath, 0x755)
            log("[Playit] binario: ${playitd.absolutePath} (${playitd.length()} B)")

            // 2. Asegurar secret
            val ok = ensureSecret(log)
            if (!ok) {
                setError(log, "No se obtuvo el secret de playit. " +
                        "Pre-provisioning manual requerido (ver CONTEXTO.md §4.3).")
                return
            }
            val secret = secretFile.readText().trim()
            log("[Playit] secret: ${secretFile.absolutePath} (${secret.length} bytes)")

            // 3. Detener daemon previo si existe (libera el socket FS)
            if (NativeLauncher.isPlayitRunning()) {
                log("[Playit] deteniendo daemon previo")
                NativeLauncher.stopPlayitDaemon()
            }

            // 4. Construir argv + envp
            val argv = listOf(
                playitd.absolutePath,
                "--secret-path", secretFile.absolutePath,
                "--socket-path", SOCKET_NAME,
                "--log-path", logFile.absolutePath
            )
            val envp = listOf(
                "PATH=/system/bin:/system/xbin",
                "TMPDIR=${playitDir.absolutePath}"
            )
            playitDir.mkdirs()

            // 5. execve desde nativeLibraryDir (NO desde files/ — W^X)
            val pid = NativeLauncher.startPlayitDaemon(
                playitd.absolutePath, argv, envp, playitDir.absolutePath
            )
            if (pid < 0) {
                setError(log, "execve falló — verifica que libplayitd.so es ejecutable " +
                        "(apk_data_file, no W^X).")
                return
            }
            log("[Playit] daemon arrancado pid=$pid")

            // 6. Esperar a que el daemon se conecte a playit.gg
            log("[Playit] esperando conexión del daemon a playit.gg (3 s)...")
            Thread.sleep(3000)

            // 7. Conectar IPC (socket abstracto @playit_ipc)
            ipcClient.connect(SOCKET_NAME, object : TunnelIpcClient.Listener {
                override fun onSnapshot(snap: TunnelUrlParser.Snapshot) {
                    currentSnapshot = snap
                    // Combinar con el puerto del log
                    val port = currentPort ?: readPortFromLog(log)
                    if (port != null) currentPort = port
                    emit(combineState(snap, port))
                }
                override fun onError(msg: String) {
                    log("[IPC] error: $msg")
                    setError(log, "IPC: $msg")
                }
                override fun onDisconnected() {
                    log("[IPC] desconectado")
                    if (isRunning.get()) {
                        setError(log, "IPC: daemon se desconectó")
                    }
                }
            }, log)

            // 8. Watcher del log → puerto público
            startLogWatcher(log)

        } catch (e: Throwable) {
            setError(log, "PlayitAgent: ${e.message}")
        }
    }

    private fun ensureSecret(log: (String) -> Unit): Boolean {
        if (secretFile.exists() && secretFile.readText().isNotBlank()) {
            log("[Playit] secret ya existe en ${secretFile.name}")
            return true
        }
        // El claim code dc1b44f2ea está EXPIRADO. Intentamos el exchange,
        // pero si falla, no hay forma de obtener un nuevo secret sin un
        // código fresco. Para el MVP se pre-provisiona el secret vía adb.
        log("[Playit] no hay secret; intentando claim con código $CLAIM_CODE")
        val secret = claimExchange(log)
        if (secret == null) {
            log("[Playit] claim falló (CodeExpired). Verifica el secret manualmente.")
            return false
        }
        playitDir.mkdirs()
        secretFile.writeText("secret_key = \"$secret\"\n")
        log("[Playit] secret escrito a ${secretFile.absolutePath}")
        return true
    }

    /** Intento de claim HTTP. Falla con CodeExpired para dc1b44f2ea (verificado). */
    private fun claimExchange(log: (String) -> Unit): String? {
        return try {
            val setupBody = JSONObject().apply {
                put("code", CLAIM_CODE)
                put("agent_type", "self-managed")
                put("version", "playit 1.0.12")
            }.toString()
            val setupResp = httpPost(CLAIM_SETUP_URL, setupBody, null, log)
            val setupStatus = setupResp?.optString("status")
            val setupData = setupResp?.optJSONObject("data")?.optString("status")
            log("[Playit] claim/setup: status=$setupStatus data=$setupData")

            // Abrir navegador para que el usuario confirme el claim
            try {
                val uri = Uri.parse("https://playit.gg/claim/$CLAIM_CODE")
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(intent)
                log("[Playit] navegador abierto: playit.gg/claim/$CLAIM_CODE")
            } catch (e: Exception) {
                log("[Playit] no se pudo abrir navegador: ${e.message}")
            }

            // Polling del exchange (caduca en ~5 min)
            for (i in 0 until 60) {
                Thread.sleep(5000)
                val exchangeBody = JSONObject().put("code", CLAIM_CODE).toString()
                val resp = httpPost(CLAIM_EXCHANGE_URL, exchangeBody, null, log)
                val key = resp?.optJSONObject("data")?.optString("secret_key")
                if (!key.isNullOrEmpty()) {
                    log("[Playit] claim/exchange exitoso")
                    return key
                }
                val failData = resp?.optJSONObject("data")?.optString("data")
                if (resp?.optString("status") == "fail" && failData == "CodeExpired") {
                    log("[Playit] claim code expirado (CodeExpired)")
                    return null
                }
            }
            log("[Playit] claim/exchange: timeout")
            null
        } catch (e: Exception) {
            log("[Playit] claim error: ${e.message}")
            null
        }
    }

    private fun startLogWatcher(log: (String) -> Unit) {
        if (logThread?.isAlive == true) return
        logThread = Thread({
            while (isRunning.get()) {
                try {
                    val port = readPortFromLog(log)
                    if (port != null && port != currentPort) {
                        currentPort = port
                        val snap = currentSnapshot
                        if (snap != null) {
                            emit(combineState(snap, port))
                        }
                    }
                    Thread.sleep(2000)
                } catch (_: InterruptedException) {
                    break
                } catch (_: Exception) {
                    Thread.sleep(2000)
                }
            }
        }, "playit-log-watcher")
        logThread?.start()
        log("[Playit] watcher de log iniciado (busca connect_addr: IP:PORT)")
    }

    /** Lee el puerto público del log del daemon (connect_addr: IP:PORT). */
    private fun readPortFromLog(log: (String) -> Unit): Int? {
        if (!logFile.exists()) return null
        return try {
            val text = logFile.readText()
            TunnelUrlParser.parsePortFromLog(text)
        } catch (e: Exception) {
            log("[Playit] error leyendo log: ${e.message}")
            null
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun combineState(snap: TunnelUrlParser.Snapshot, port: Int?): PlayitState {
        val publicAddr = if (snap.displayAddress != null && port != null) {
            "${snap.displayAddress}:$port"
        } else snap.displayAddress

        return PlayitState(
            tunnelState = snap.tunnelState,
            publicAddress = publicAddr,
            displayAddress = snap.displayAddress,
            localAddress = snap.destination,
            bytesIn = snap.bytesIn,
            bytesOut = snap.bytesOut,
            activeTcp = snap.activeTcp,
            activeUdp = snap.activeUdp,
            error = snap.tunnelState.let {
                when (it) {
                    "Online" -> null
                    "No tunnel" -> com.minicraft.mcserver.Text.s(com.minicraft.mcserver.R.string.tunnel_err_not_configured)
                    "Connecting" -> com.minicraft.mcserver.Text.s(com.minicraft.mcserver.R.string.tunnel_err_connecting)
                    "Offline" -> com.minicraft.mcserver.Text.s(com.minicraft.mcserver.R.string.tunnel_err_disabled)
                    else -> null
                }
            }
        )
    }

    private fun setError(log: (String) -> Unit, msg: String) {
        Log.e(TAG, msg)
        log("[Playit] ERROR: $msg")
        emit(PlayitState("Error", null, null, null, 0, 0, 0, 0, msg))
    }

    private fun emit(state: PlayitState) {
        uiHandler.post { listener?.invoke(state) }
    }

    // HTTP POST genérico (HttpURLConnection, sin dependencias externas)
    private fun httpPost(url: String, body: String, agentKey: String?, log: (String) -> Unit): JSONObject? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            agentKey?.let { conn.setRequestProperty("Authorization", "Agent-Key $it") }
            conn.outputStream.use { out -> body.byteInputStream().copyTo(out) }
            if (conn.responseCode !in 200..299) {
                log("  HTTP ${conn.responseCode} en $url")
                null
            } else {
                val resp = conn.inputStream.bufferedReader().use { it.readText() }
                try { JSONObject(resp) } catch (e: Exception) { null }
            }
        } catch (e: Exception) {
            log("  HTTP error: ${e.message}")
            null
        }
    }
}
