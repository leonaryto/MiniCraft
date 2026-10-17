package com.minicraft.mcserver.playit

import android.util.Log
import org.json.JSONObject
import com.minicraft.mcserver.runtime.NativeLauncher

/**
 * Cliente IPC para el daemon playitd.
 *
 * Se conecta al socket IPC del daemon (abstracto @playit_ipc) y mantiene
 * una sesión: hello → get_state → subscribe → [event loop]
 *
 * TRANSPORTE NATIVO: en MIUI 14 / Android 13 el LocalSocket de Java lanza
 * UnsupportedOperationException para ambos Namespaces (ABSTRACT y FILESYSTEM).
 * El daemon escucha en el socket abstracto @playit_ipc y un probe C confirma
 * que las llamadas al sistema directas (socket+connect) funcionan. Por eso
 * este cliente usa connectIpcNative/readLineIpcNative/writeLineIpcNative,
 * que llaman a socket(AF_UNIX) + connect() desde código C nativo.
 *
 * El nombre del socket se pasa SIN el '@' al código nativo: la función C
 * pone sun_path[0]='\0' para activar el namespace abstracto del kernel.
 *
 * El daemon ya lee el secret de --secret-path al arrancar, así que este
 * cliente NO necesita enviar set_secret (verificado: has_secret=true en
 * get_status).
 *
 * Ver TunnelUrlParser para el formato JSON exacto de cada mensaje.
 */
class TunnelIpcClient {

    companion object {
        private const val TAG = "TunnelIpc"
        private const val REQ_GET_STATE =
            """{"ipc_version":2,"request_id":1,"request":{"type":"get_state"}}"""
        private const val REQ_SUBSCRIBE =
            """{"ipc_version":2,"request_id":2,"request":{"type":"subscribe"}}"""
    }

    /** Actualización del estado del túnel. Se llama desde el hilo de IPC. */
    interface Listener {
        fun onSnapshot(snap: TunnelUrlParser.Snapshot)
        fun onError(msg: String)
        fun onDisconnected()
    }

    @Volatile
    private var running = false
    private var thread: Thread? = null
    /** El snapshot acumulado; se va actualizando con cada evento. */
    @Volatile
    private var snapshot: TunnelUrlParser.Snapshot? = null

    /**
     * Conecta al socket abstracto, hace el handshake y entra en el bucle de
     * eventos. Bloquea en un hilo de fondo: nunca bloquea el hilo principal.
     *
     * @param socketName  Nombre del socket, con o sin @ (p.ej. "@playit_ipc"
     *                    o "playit_ipc"). El @ se quita antes de pasarlo al
     *                    código nativo.
     * @param listener    Recibe snapshots y errores (desde el hilo de IPC).
     * @param log         Callback para diagnosticar.
     */
    fun connect(
        socketName: String,
        listener: Listener,
        log: (String) -> Unit = { Log.i(TAG, it) }
    ) {
        if (running) return
        running = true
        snapshot = null
        thread = Thread({ ipcLoop(socketName, listener, log) }, "playit-ipc")
        thread?.start()
    }

    /** Cierra la conexión y detiene el hilo. Devuelve inmediatamente. */
    fun disconnect() {
        running = false
        thread?.interrupt()
        thread = null
    }

    /** Devuelve el último snapshot recibido (thread-safe), o null si no hay datos. */
    fun currentSnapshot(): TunnelUrlParser.Snapshot? = snapshot

    // -----------------------------------------------------------------------

    private fun ipcLoop(
        socketName: String,
        listener: Listener,
        log: (String) -> Unit
    ) {
        // El código nativo activa el namespace abstracto poniendo sun_path[0]='\0'.
        // El nombre debe pasarse SIN el '@' (ej. "playit_ipc").
        val cleanName = if (socketName.startsWith("@"))
            socketName.substring(1) else socketName

        var fd = -1
        try {
            NativeLauncher.preload()
            log("[IPC] conectando a socket abstracto (nativo): $cleanName")
            fd = NativeLauncher.connectIpc(cleanName)
            if (fd < 0) {
                listener.onError("no se pudo conectar al socket IPC (ver log nativo)")
                return
            }
            log("[IPC] conectado al daemon (fd=$fd)")

            // 1. Hello
            val hello = readLine(fd, log)
            if (hello == null) {
                listener.onError("el daemon cerró la conexión (hello)")
                return
            }
            val helloJson = try { JSONObject(hello) } catch (e: Exception) { null }
            if (helloJson?.optString("message_kind") != "hello") {
                log("[IPC] respuesta inesperada en hello: $hello")
                listener.onError("respuesta hello inesperada")
                return
            }
            log("[IPC] hello OK")

            // 2. get_state — obtiene display_address, phase, etc.
            writeLine(fd, REQ_GET_STATE, log)
            val stateResp = readLine(fd, log)
            if (stateResp == null) {
                listener.onError("el daemon cerró la conexión (get_state)")
                return
            }
            val stateSnap = TunnelUrlParser.parseStateResponse(stateResp)
            if (stateSnap == null) {
                log("[IPC] get_state ilegible: $stateResp")
                listener.onError("respuesta get_state ilegible")
                return
            }
            log("[IPC] state: phase=${stateSnap.phase} addr=${stateSnap.displayAddress}")
            snapshot = stateSnap
            listener.onSnapshot(stateSnap)

            // 3. subscribe — obtiene snapshot con stats + lifecycle
            writeLine(fd, REQ_SUBSCRIBE, log)
            val subResp = readLine(fd, log)
            if (subResp == null) {
                listener.onError("el daemon cerró la conexión (subscribe)")
                return
            }
            val subSnap = TunnelUrlParser.parseSubscribeSnapshot(subResp)
            if (subSnap != null) {
                log("[IPC] subscribe OK: ${subSnap.tunnelState} bytes=${subSnap.bytesIn}/${subSnap.bytesOut}")
                snapshot = subSnap
                listener.onSnapshot(subSnap)
            } else {
                log("[IPC] subscribe snapshot ilegible (se usa state anterior): $subResp")
            }

            // 4. Bucle de eventos (lifecycle + stats)
            while (running) {
                val line = readLine(fd, log)
                if (line == null) {
                    log("[IPC] daemon cerró la conexión (EOF)")
                    break
                }
                val cur = snapshot ?: break
                val updated = TunnelUrlParser.parseEvent(line, cur)
                if (updated != null) {
                    snapshot = updated
                    listener.onSnapshot(updated)
                }
            }

        } catch (e: InterruptedException) {
            log("[IPC] hilo interrumpido")
        } catch (e: Exception) {
            log("[IPC] error: ${e.javaClass.simpleName}: ${e.message}")
            listener.onError(e.message ?: "error IPC (${e.javaClass.simpleName})")
        } finally {
            running = false
            if (fd >= 0) {
                try { NativeLauncher.closeIpc(fd) } catch (_: Exception) {}
            }
            snapshot = null
            listener.onDisconnected()
        }
    }

    /** Lee una línea del fd nativo. Devuelve null en EOF/error. */
    private fun readLine(fd: Int, log: (String) -> Unit): String? {
        return try {
            NativeLauncher.readLineIpc(fd)
        } catch (e: Exception) {
            log("[IPC] readLine excepción: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** Escribe data + '\n' al fd nativo. */
    private fun writeLine(fd: Int, data: String, log: (String) -> Unit) {
        try {
            val rc = NativeLauncher.writeLineIpc(fd, data)
            if (rc < 0) {
                log("[IPC] writeLine fallo (rc=$rc)")
            }
        } catch (e: Exception) {
            log("[IPC] writeLine excepción: ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
