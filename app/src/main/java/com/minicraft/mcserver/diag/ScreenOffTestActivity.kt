package com.minicraft.mcserver.diag

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.minicraft.mcserver.AppLocale
import com.minicraft.mcserver.service.MinecraftService
import java.io.File

/**
 * FASE 2 (segunda parte) — Servidor con la pantalla apagada.
 *
 * Esta es la prueba clave del producto: ¿el servidor sigue vivo con la pantalla
 * apagada y el dispositivo en Doze?
 *
 * Uso: pulsa "Arrancar (pantalla apagada)". La Activity arranca el Foreground Service,
 * tu bloqueas el teléfono (o se apaga solo), y luego vuelves y pulsas "Estado".
 *
 * La UI final vemra despues; esto es una pantalla de diagnostico.
 */
class ScreenOffTestActivity : Activity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    private val TAG = "MINICRAFT_SCREENOFF"
    private val main = Handler(Looper.getMainLooper())
    private lateinit var tv: TextView

    private fun filesDir() = File("/data/user/0/com.minicraft.mcserver/files")
    private fun serverDir() = File(filesDir(), "servers/fabric-1.21.1")
    private fun logFile() = File(filesDir(), "diag/server-run.log")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }

        root.addView(Button(this).apply {
            text = getString(com.minicraft.mcserver.R.string.diag_btn_start_fgs)
            setOnClickListener { startService() }
        })
        root.addView(Button(this).apply {
            text = getString(com.minicraft.mcserver.R.string.diag_btn_status)
            setOnClickListener { showStatus() }
        })
        root.addView(Button(this).apply {
            text = getString(com.minicraft.mcserver.R.string.diag_btn_stop)
            setOnClickListener {
                MinecraftService.stop(this@ScreenOffTestActivity)
                log(getString(com.minicraft.mcserver.R.string.diag_service_stopped))
            }
        })
        root.addView(Button(this).apply {
            text = getString(com.minicraft.mcserver.R.string.diag_btn_read_log)
            setOnClickListener { dumpServerLog() }
        })

        tv = TextView(this).apply { textSize = 9f }
        root.addView(ScrollView(this).apply { addView(tv) },
            LinearLayout.LayoutParams(-1, -1))
        setContentView(root)
        showStatus()
    }

    private fun log(m: String) {
        Log.i(TAG, m)
        main.post { tv.append(m + "\n") }
    }

    private fun startService() {
        log("--> " + getString(com.minicraft.mcserver.R.string.diag_starting_fgs))
        MinecraftService.start(this)
        log("    " + getString(com.minicraft.mcserver.R.string.diag_order_sent))
    }

    private fun showStatus() {
        log(getString(com.minicraft.mcserver.R.string.diag_status_header))
        log("hora: ${java.time.LocalTime.now()}")
        log("wakefulness: " + readProp("dumpsys power", "mWakefulness="))
        log("keyguard bloqueado: " + readProp("dumpsys window", "mDreamingLockscreen"))
        log("--- proceso ---")
        log("app viva: " + isAppAlive())
        log("--- servicio ---")
        log("fgs activo: " + dumpsysFgs())
        log("--- servidor ---")
        log("log existe: ${logFile().exists()} size=${if (logFile().exists()) logFile().length() / 1024 else 0}KB")
        log("world existe: ${File(serverDir(), "world").exists()}")
        // Ultimas lineas del log
        if (logFile().exists()) {
            val lines = try { logFile().readLines() } catch (e: Exception) { emptyList() }
            log("lineas: ${lines.size}")
            lines.takeLast(6).forEach { log("  | $it") }
        }
    }

    private fun isAppAlive(): Boolean =
        try {
            java.io.File("/proc/self").exists()
        } catch (e: Exception) { false }

    private fun dumpsysFgs(): String = try {
        val p = ProcessBuilder("dumpsys", "activity", "services", "com.minicraft.mcserver")
            .redirectErrorStream(true).start()
        val o = p.inputStream.bufferedReader().readText()
        p.waitFor()
        val line = o.lines().firstOrNull { it.contains("isForeground") || it.contains("ServiceRecord") }
        line?.trim()?.take(200) ?: "sin registro"
    } catch (e: Exception) { "err: ${e.message}" }

    private fun readProp(cmd: String, key: String): String = try {
        val parts = cmd.split(" ")
        val p = ProcessBuilder(parts).redirectErrorStream(true).start()
        val o = p.inputStream.bufferedReader().readText()
        p.waitFor()
        o.lines().firstOrNull { it.trim().startsWith(key) }?.trim() ?: "?"
    } catch (e: Exception) { "err" }

    private fun dumpServerLog() {
        log("=== LOG DEL SERVIDOR (tail) ===")
        val f = File(filesDir(), "diag/server-run.log")
        if (!f.exists()) { log("aun no hay log en $f"); return }
        try {
            f.readLines().takeLast(25).forEach { log(it) }
        } catch (e: Exception) { log("err: ${e.message}") }
    }
}