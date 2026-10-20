package com.minicraft.mcserver.playit

import org.json.JSONArray
import org.json.JSONObject

/**
 * Parsers de JSON del protocolo IPC de playitd (verificado en hardware).
 *
 * Protocolo IPC verificado (playitd 1.0.12, Android 13):
 *
 *   1. El daemon envía "hello":
 *      {"message_kind":"hello","data":{"protocol":{"ipc_version":2,"capabilities":[...]}}}
 *
 *   2. El cliente envía get_state:
 *      {"ipc_version":2,"request_id":1,"request":{"type":"get_state"}}
 *      Respuesta:
 *      {"message_kind":"response","data":{...,"response":{"type":"state","data":{
 *        "state":"running","data":{"version":"1.0.12","tunnels":[
 *          {"display_address":"fried-jacuzzi.tun.ply.gg","destination":"127.0.0.1:25565",
 *           "is_disabled":false,"disabled_reason":null}],...}}}}
 *
 *   3. El cliente envía subscribe:
 *      {"ipc_version":2,"request_id":2,"request":{"type":"subscribe"}}
 *      Respuesta incluye un snapshot con status + lifecycle + stats.
 *
 *   4. Eventos (stream):
 *      stats:    {"message_kind":"event","data":{"ipc_version":2,"event":{
 *                 "type":"stats","data":{"bytes_in":238,"bytes_out":798,
 *                 "active_tcp":0,"active_udp":0}}}}
 *      lifecycle:{"message_kind":"event","data":{"ipc_version":2,"event":{
 *                 "type":"lifecycle","data":{"state":"running","data":{
 *                 "tunnels":[{"display_address":"...","destination":"127.0.0.1:25565",
 *                 "is_disabled":false}]}}}}
 *
 * NOTA: el protocolo real NO incluye "tunnel_state" ni "public_address" con
 * puerto. El puerto público solo aparece en el log del daemon:
 *   connect_addr: 147.185.221.213:34544
 * Por eso PlayitAgent combina display_address (del IPC) + puerto (del log).
 *
 * El host puede venir con o sin puerto; el puerto real se toma del log del agente.
 */
object TunnelUrlParser {

    /** Información del túnel extraída del IPC y del log del daemon. */
    data class Snapshot(
        val phase: String,               // "running" | "stopped" | "unsupported_protocol"
        val displayAddress: String?,      // "fried-jacuzzi.tun.ply.gg"
        val destination: String?,        // "127.0.0.1:25565"
        val version: String?,             // "1.0.12"
        val agentId: String?,             // "d274c3cf-..."
        val accountStatus: String?,       // "verified"
        val isDisabled: Boolean,
        val disabledReason: String?,
        val bytesIn: Long,
        val bytesOut: Long,
        val activeTcp: Int,
        val activeUdp: Int,
        val tunnelState: String           // derivado: "Online" | "Offline" | "No tunnel" | "Connecting"
    ) {
        fun withPort(port: Int?): Snapshot = this  // port se resuelve en PlayitAgent
    }

    private const val NO_TUNNEL = "No tunnel"
    private const val CONNECTING = "Connecting"
    private const val ONLINE = "Online"
    private const val OFFLINE = "Offline"

    /**
     * Parsea la respuesta a get_state.
     * Extrae el phase (daemon), display_address, destination, version, agent_id.
     */
    fun parseStateResponse(json: String): Snapshot? = try {
        val root = JSONObject(json)
        val respData = root
            .getJSONObject("data")
            .getJSONObject("response")
            .getJSONObject("data")
        val phase = respData.optString("state", "unknown")
        val inner = respData.getJSONObject("data")

        var displayAddress: String? = null
        var destination: String? = null
        var isDisabled = false
        var disabledReason: String? = null
        val tunnels = inner.optJSONArray("tunnels")
        if (tunnels != null && tunnels.length() > 0) {
            val t = tunnels.getJSONObject(0)
            displayAddress = optNull(t, "display_address")
            destination = optNull(t, "destination")
            isDisabled = t.optBoolean("is_disabled", false)
            disabledReason = optNull(t, "disabled_reason")
        }

        val ts = computeTunnelState(phase, displayAddress, isDisabled)
        Snapshot(
            phase = phase,
            displayAddress = displayAddress,
            destination = destination,
            version = optNull(inner, "version"),
            agentId = optNull(inner, "agent_id"),
            accountStatus = optNull(inner, "account_status"),
            isDisabled = isDisabled,
            disabledReason = disabledReason,
            bytesIn = 0L,
            bytesOut = 0L,
            activeTcp = 0,
            activeUdp = 0,
            tunnelState = ts
        )
    } catch (e: Exception) {
        null
    }

    /**
     * Parsea la respuesta a subscribe (incluye snapshot inicial con stats).
     */
    fun parseSubscribeSnapshot(json: String): Snapshot? = try {
        val root = JSONObject(json)
        val snap = root
            .getJSONObject("data")
            .getJSONObject("response")
            .getJSONObject("data")
            .getJSONObject("snapshot")

        // status.phase
        val status = snap.optJSONObject("status")
        val phase = if (status != null) status.optString("phase", "unknown") else "unknown"
        val version = if (status != null) optNull(status, "version") else null

        // lifecycle.data (tunnels, agent_id, etc.)
        val lifecycle = snap.optJSONObject("lifecycle")
        var displayAddress: String? = null
        var destination: String? = null
        var isDisabled = false
        var disabledReason: String? = null
        var agentId: String? = null
        var accountStatus: String? = null
        if (lifecycle != null) {
            val lifeData = lifecycle.getJSONObject("data")
            displayAddress = parseTunnelField(lifeData, "display_address")
            destination = parseTunnelField(lifeData, "destination")
            isDisabled = parseTunnelBool(lifeData, "is_disabled")
            disabledReason = parseTunnelField(lifeData, "disabled_reason")
            agentId = optNull(lifeData, "agent_id")
            accountStatus = optNull(lifeData, "account_status")
        }

        // stats
        val stats = snap.optJSONObject("stats")
        val bytesIn = stats?.optLong("bytes_in", 0L) ?: 0L
        val bytesOut = stats?.optLong("bytes_out", 0L) ?: 0L
        val activeTcp = stats?.optInt("active_tcp", 0) ?: 0
        val activeUdp = stats?.optInt("active_udp", 0) ?: 0

        val ts = computeTunnelState(phase, displayAddress, isDisabled)
        Snapshot(
            phase = phase,
            displayAddress = displayAddress,
            destination = destination,
            version = version,
            agentId = agentId,
            accountStatus = accountStatus,
            isDisabled = isDisabled,
            disabledReason = disabledReason,
            bytesIn = bytesIn,
            bytesOut = bytesOut,
            activeTcp = activeTcp,
            activeUdp = activeUdp,
            tunnelState = ts
        )
    } catch (e: Exception) {
        null
    }

    /**
     * Parsea un evento (stats o lifecycle) y devuelve el snapshot actualizado.
     * Devuelve null si el evento no es de tipo stats/lifecycle.
     */
    fun parseEvent(json: String, prev: Snapshot): Snapshot? {
        return try {
        val root = JSONObject(json)
        if (root.optString("message_kind") != "event") return null

        val evt = root.getJSONObject("data").getJSONObject("event")
        val evtType = evt.optString("type")
        val evtData = evt.getJSONObject("data")

        when (evtType) {
            "stats" -> prev.copy(
                bytesIn = evtData.optLong("bytes_in", prev.bytesIn),
                bytesOut = evtData.optLong("bytes_out", prev.bytesOut),
                activeTcp = evtData.optInt("active_tcp", prev.activeTcp),
                activeUdp = evtData.optInt("active_udp", prev.activeUdp)
            )
            "lifecycle" -> {
                val state = evtData.optString("state", prev.phase)
                val inner = evtData.optJSONObject("data")

                var displayAddress = prev.displayAddress
                var destination = prev.destination
                var isDisabled = prev.isDisabled
                var disabledReason = prev.disabledReason
                var agentId = prev.agentId
                var accountStatus = prev.accountStatus

                if (inner != null) {
                    displayAddress = parseTunnelField(inner, "display_address") ?: displayAddress
                    destination = parseTunnelField(inner, "destination") ?: destination
                    isDisabled = parseTunnelBool(inner, "is_disabled", isDisabled)
                    disabledReason = parseTunnelField(inner, "disabled_reason") ?: disabledReason
                    agentId = optNull(inner, "agent_id") ?: agentId
                    accountStatus = optNull(inner, "account_status") ?: accountStatus
                }

                val ts = computeTunnelState(state, displayAddress, isDisabled)
                prev.copy(
                    phase = state,
                    displayAddress = displayAddress,
                    destination = destination,
                    agentId = agentId,
                    accountStatus = accountStatus,
                    isDisabled = isDisabled,
                    disabledReason = disabledReason,
                    tunnelState = ts
                )
            }
            else -> null  // evento desconocido
        }
    } catch (e: Exception) {
        null
    }
    }

    /**
     * Extrae el puerto público del log del daemon.
     *
     * El daemon escribe líneas como:
     *   connect_addr: 147.185.221.213:34544
     * tomando el último (el más reciente).
     */
    fun parsePortFromLog(logText: String): Int? {
        val pattern = Regex("""connect_addr:\s*\d+\.\d+\.\d+\.\d+:(\d+)""")
        val matches = pattern.findAll(logText).toList()
        if (matches.isEmpty()) return null
        return matches.last().groupValues[1].toIntOrNull()
    }

    /** Extrae el puerto local de "127.0.0.1:25565" → 25565. */
    fun parseLocalPort(destination: String?): Int? {
        try {
            val d = destination ?: return null
            val idx = d.lastIndexOf(':')
            return if (idx < 0) null else d.substring(idx + 1).toInt()
        } catch (e: NumberFormatException) { return null }
    }

    /** Extrae el host local de "127.0.0.1:25565" → "127.0.0.1". */
    fun parseLocalIp(destination: String?): String? {
        try {
            val d = destination ?: return null
            val idx = d.lastIndexOf(':')
            return if (idx < 0) null else d.substring(0, idx)
        } catch (e: Exception) { return null }
    }

    // ---- helpers ---------------------------------------------------------

    private fun computeTunnelState(phase: String, displayAddress: String?, isDisabled: Boolean): String {
        return when {
            phase != "running" -> CONNECTING
            displayAddress == null || displayAddress.isEmpty() -> NO_TUNNEL
            isDisabled -> OFFLINE
            else -> ONLINE
        }
    }

    /** optString que devuelve null en vez de "" cuando el valor JSON es null. */
    private fun optNull(obj: JSONObject, key: String): String? {
        val v = obj.opt(key) ?: return null
        if (v == JSONObject.NULL) return null
        return v.toString()
    }

    /** Extrae display_address/destination del primer túnel del lifecycle. */
    private fun parseTunnelField(lifeData: JSONObject, field: String): String? {
        val tunnels = lifeData.optJSONArray("tunnels") ?: return null
        if (tunnels.length() == 0) return null
        val t = tunnels.getJSONObject(0)
        return optNull(t, field)
    }

    private fun parseTunnelBool(lifeData: JSONObject, field: String, default: Boolean = false): Boolean {
        val tunnels = lifeData.optJSONArray("tunnels") ?: return default
        if (tunnels.length() == 0) return default
        val t = tunnels.getJSONObject(0)
        return t.optBoolean(field, default)
    }
}
