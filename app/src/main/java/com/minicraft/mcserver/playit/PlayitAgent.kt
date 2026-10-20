package com.minicraft.mcserver.playit

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.minicraft.mcserver.runtime.NativeLauncher
import com.minicraft.mcserver.ui.ServerConfigSheet
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
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
 * 6. Lee files/playit/playit.log → puerto público (connect_addr: IP:PORT).
 * 7. Combina: publicAddress = display_address + ":" + port.
 *
 * CLAIM PROPIO POR INSTALACIÓN: si no hay secret en files/playit/playit.toml,
 * la app GENERA un claim code aleatorio nuevo (10 hex), abre el navegador en
 * playit.gg/claim/<code> para que el usuario lo reclame en SU cuenta y hace
 * HEARTBEAT de POST /claim/setup hasta que responde "UserAccepted"; recién
 * entonces POST /claim/exchange entrega el secret. (Sin ese heartbeat la web
 * se queda en "Waiting for agent...". Antes se usaba un claim code hardcodeado
 * de desarrollo, ya reclamado → "Agente ya reclamado".)
 *
 * TÚNEL AUTOMÁTICO: un agente recién reclamado NO tiene túnel. Cuando el daemon
 * reporta por IPC su agent_id y no hay ningún túnel, la app crea uno
 * (minecraft-java) vía la API con Agent-Key y lo configura a 127.0.0.1:25565.
 *
 * El agente se ejecuta desde jniLibs (nativeLibraryDir), el claim usa la API
 * HTTP de playit.gg y el secret se guarda en files/playit/playit.toml.
 */
class PlayitAgent private constructor(private val ctx: Context) {

    companion object {
        const val TAG = "PlayitAgent"
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
        private const val TUNNELS_CREATE_URL = "$PLAYIT_API/v1/tunnels/create"
        private const val TUNNELS_CONFIG_URL = "$PLAYIT_API/v1/tunnels/config"

        /** Version del agente que exige la API (rechaza < 1.0.12 con AgentVersionTooOld). */
        private const val AGENT_VERSION = "playit 1.0.12"
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
    private val tunnelEnsured = AtomicBoolean(false)
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
        tunnelEnsured.set(false)
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
        tunnelEnsured.set(false)
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
                        "Pre-provisioning manual requerido.")
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
                    maybeCreateTunnel(snap, log)
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
        // Genera un claim code NUEVO por instalación. El código lo elige el
        // agente (no viene del servidor); un código hardcodeado haría que todos
        // los usuarios intentaran reclamar el mismo agente ya reclamado.
        val code = newClaimCode()
        log("[Playit] no hay secret; generando claim propio: $code")
        val secret = claimExchange(code, log)
        if (secret == null) {
            log("[Playit] claim fallido: no se obtuvo el secret")
            return false
        }
        playitDir.mkdirs()
        secretFile.writeText("secret_key = \"$secret\"\n")
        log("[Playit] secret escrito a ${secretFile.absolutePath}")
        return true
    }

    /** Claim code aleatorio de 10 hex (5 bytes); mismo formato que playit-cli. */
    private fun newClaimCode(): String {
        val bytes = ByteArray(5)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Reclama un agente con [code] replicando el flujo del playit-cli:
     *  1. Heartbeat de POST /claim/setup hasta recibir "UserAccepted". Mientras
     *     el agente no sondee setup en bucle, la web se queda en
     *     "Waiting for agent..." y nunca deja aprobar el agente.
     *  2. En la primera respuesta "WaitingForUserVisit"/"WaitingForUser" se abre
     *     el navegador para que el usuario apruebe el agente en SU cuenta.
     *  3. Ya aprobado, POST /claim/exchange devuelve el secret_key.
     * Devuelve null si el usuario rechaza, el código expira o se agota el tiempo.
     */
    private fun claimExchange(code: String, log: (String) -> Unit): String? {
        return try {
            if (!awaitUserAccept(code, log)) return null
            exchangeSecret(code, log)
        } catch (e: Exception) {
            log("[Playit] claim error: ${e.message}")
            null
        }
    }

    /**
     * Heartbeat de /claim/setup: abre el navegador en la primera respuesta válida
     * y sigue sondeando hasta "UserAccepted" (o rechazo/timeout). En la respuesta
     * `data` viene el estado como string (WaitingForUserVisit | WaitingForUser |
     * UserAccepted | UserRejected).
     */
    private fun awaitUserAccept(code: String, log: (String) -> Unit): Boolean {
        var browserOpened = false
        val deadline = System.currentTimeMillis() + 5 * 60_000
        while (isRunning.get() && System.currentTimeMillis() < deadline) {
            val body = JSONObject().apply {
                put("code", code)
                put("agent_type", "self-managed")
                put("version", AGENT_VERSION)
            }.toString()
            val resp = httpPost(CLAIM_SETUP_URL, body, null, log)
            val state = resp?.optString("data")
            log("[Playit] claim/setup: status=${resp?.optString("status")} data=$state")
            when (state) {
                "UserAccepted" -> {
                    log("[Playit] claim aprobado por el usuario")
                    return true
                }
                "UserRejected" -> {
                    log("[Playit] claim rechazado en el navegador")
                    return false
                }
                else -> {
                    // WaitingForUserVisit / WaitingForUser / error transitorio:
                    // abrir la web una vez y seguir sondeando hasta UserAccepted.
                    if (!browserOpened) {
                        browserOpened = true
                        openClaimPage(code, log)
                    }
                }
            }
            Thread.sleep(1000)
        }
        log("[Playit] claim/setup: timeout esperando aprobación")
        return false
    }

    /** Canjea el código ya aprobado por el secret_key. */
    private fun exchangeSecret(code: String, log: (String) -> Unit): String? {
        repeat(30) {
            if (!isRunning.get()) return null
            val resp = httpPost(
                CLAIM_EXCHANGE_URL,
                JSONObject().put("code", code).toString(),
                null, log
            )
            val key = resp?.optJSONObject("data")?.optString("secret_key")
            if (!key.isNullOrEmpty()) {
                log("[Playit] claim/exchange exitoso")
                return key
            }
            Thread.sleep(1000)
        }
        log("[Playit] claim/exchange: timeout")
        return null
    }

    /** Abre playit.gg/claim/<code> en el navegador del dispositivo. */
    private fun openClaimPage(code: String, log: (String) -> Unit) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://playit.gg/claim/$code"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            log("[Playit] navegador abierto: playit.gg/claim/$code")
        } catch (e: Exception) {
            log("[Playit] no se pudo abrir navegador: ${e.message}")
        }
    }

    // ------------------------------------------------------------------ túnel

    /**
     * Comprueba si hay que crear el túnel. Un agente recién reclamado no tiene
     * ninguno; en ese caso se crea uno minecraft-java vía la API (Agent-Key).
     * Se dispara desde el hilo de IPC: el trabajo HTTP va en su propio hilo.
     */
    private fun maybeCreateTunnel(snap: TunnelUrlParser.Snapshot, log: (String) -> Unit) {
        val agentId = snap.agentId ?: return
        // Ya hay túnel, o el daemon aún no está verificado: nada que hacer.
        if (!snap.displayAddress.isNullOrEmpty()) return
        if (snap.accountStatus != "verified") return
        if (!tunnelEnsured.compareAndSet(false, true)) return
        val secret = readSecretKey(log)
        if (secret == null) {
            tunnelEnsured.set(false)
            return
        }
        Thread({ createTunnel(secret, agentId, log) }, "playit-tunnel").start()
    }

    /** Crea el túnel minecraft-java del agente y lo apunta a 127.0.0.1:<puerto>. */
    private fun createTunnel(secret: String, agentId: String, log: (String) -> Unit) {
        try {
            val localPort = ServerConfigSheet.load(ctx).port
            log("[Playit] creando túnel minecraft-java (agent=$agentId, local=$localPort)")
            val createBody = JSONObject().apply {
                put("name", "Minicraft Android")
                put("protocol", JSONObject().apply {
                    put("type", "tunnel-type")
                    put("details", "minecraft-java")
                })
                put("origin", JSONObject().apply {
                    put("type", "agent")
                    put("data", JSONObject().apply {
                        put("agent_id", agentId)
                        put("config", JSONObject().put("fields", JSONArray()))
                    })
                })
                put("endpoint", JSONObject().apply {
                    put("type", "region")
                    put("details", JSONObject().put("region", "global"))
                })
                put("enabled", true)
                put("firewall_id", JSONObject.NULL)
            }.toString()
            val createResp = httpPost(TUNNELS_CREATE_URL, createBody, secret, log)
            val tunnelId = extractTunnelId(createResp)
            if (tunnelId == null) {
                log("[Playit] no se pudo crear el túnel: $createResp")
                tunnelEnsured.set(false)   // permitir reintento
                return
            }
            log("[Playit] túnel creado: $tunnelId")

            // Sin local_port el daemon recibe el túnel pero no redirige tráfico.
            val configBody = JSONObject().apply {
                put("tunnel_id", tunnelId)
                put("new_agent_id", JSONObject.NULL)
                put("new_config", JSONObject().apply {
                    put("fields", JSONArray().apply {
                        put(JSONObject().put("name", "local_ip").put("value", "127.0.0.1"))
                        put(JSONObject().put("name", "local_port").put("value", localPort.toString()))
                    })
                })
            }.toString()
            val configResp = httpPost(TUNNELS_CONFIG_URL, configBody, secret, log)
            log("[Playit] config túnel: status=${configResp?.optString("status")}")
        } catch (e: Exception) {
            log("[Playit] error creando túnel: ${e.message}")
            tunnelEnsured.set(false)
        }
    }

    /** Extrae el id del túnel de la respuesta de /tunnels/create. */
    private fun extractTunnelId(resp: JSONObject?): String? {
        if (resp == null) return null
        return when (val data = resp.opt("data")) {
            is JSONObject -> data.optString("id").ifEmpty { null }
            is String -> data.ifEmpty { null }
            else -> null
        }
    }

    /** Lee secret_key del TOML files/playit/playit.toml. */
    private fun readSecretKey(log: (String) -> Unit): String? {
        return try {
            if (!secretFile.exists()) return null
            val m = Regex("""secret_key\s*=\s*"([^"]+)"""").find(secretFile.readText())
            m?.groupValues?.get(1)
        } catch (e: Exception) {
            log("[Playit] no se pudo leer el secret: ${e.message}")
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
