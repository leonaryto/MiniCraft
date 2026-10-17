package com.minicraft.mcserver.ui

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Buffer global de lineas de log (server + acciones de la app).
 *
 * MinecraftService ya escribe a Logcat y a diag/server-run.log; esto anade un
 * listener en memoria para que Inicio y Consola muestren lo mismo sin tocar el
 * servicio. Capacidad: 500 lineas (las viejas se descartan).
 */
object ConsoleLog {

    private const val MAX = 500
    private val buffer = ArrayDeque<String>()
    private val listeners = ArrayList<(String) -> Unit>()
    private val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // Conteo de jugadores: el servidor imprime "X joined/left the game".
    @Volatile private var onlinePlayers = 0
    fun playerCount(): Int = onlinePlayers

    private val joinPattern = Regex("joined the game")
    private val leavePattern = Regex("left the game")

    @Synchronized
    fun add(line: String) {
        val ts = stamp.format(Date())
        val entry = "$ts  $line"
        buffer.addLast(entry)
        while (buffer.size > MAX) buffer.removeFirst()
        for (l in listeners.toList()) runCatching { l(entry) }

        if (joinPattern.containsMatchIn(line)) onlinePlayers++
        if (leavePattern.containsMatchIn(line)) onlinePlayers = maxOf(0, onlinePlayers - 1)

        // El servidor termino: nadie queda conectado. Marcador idioma-independiente
        // emitido por ServerLauncher ("--- ... exit=N ...") o log del propio MC.
        if ((line.startsWith("---") && line.contains("exit=")) || line.contains("Stopping server")) {
            onlinePlayers = 0
        }

        // Alimentar el parser de TPS existente en ServerLauncher con cada linea.
        runCatching { com.minicraft.mcserver.server.ServerLauncher.updateTpsFromLine(line) }
    }

    @Synchronized
    fun snapshot(): List<String> = buffer.toList()

    @Synchronized
    fun addListener(l: (String) -> Unit) {
        listeners.add(l)
    }

    @Synchronized
    fun removeListener(l: (String) -> Unit) {
        listeners.remove(l)
    }
}
