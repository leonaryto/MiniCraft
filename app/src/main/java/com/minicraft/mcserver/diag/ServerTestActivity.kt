package com.minicraft.mcserver.diag

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.minicraft.mcserver.AppLocale
import com.minicraft.mcserver.server.ServerLauncher
import java.io.File

/**
 * FASE 2 — Prueba de arranque del servidor de Minecraft en Android.
 *
 * Usa un servidor Fabric 1.21.1 preinstalado en filesDir/servers/fabric-1.21.1/.
 * Muestra el log en pantalla para poder revisarlo, y lo escribe tambien en logcat
 * con el tag MINICRAFT_SERVER.
 *
 * NO es la UI final (esa viene despues). Es una pantalla de prueba con botones:
 *   [Arrancar]  arranca el servidor y para cuando dice "Done"
 *   [Limpiar]   limpia la pantalla
 */
class ServerTestActivity : Activity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    private val TAG = "MINICRAFT_SERVER"
    private lateinit var logView: TextView
    private lateinit var root: LinearLayout
    private val main = Handler(Looper.getMainLooper())

    private fun appDir(): File = File("/data/user/0/com.minicraft.mcserver/files")
    private fun jreHome() = File(appDir(), "jre")
    private fun javaBin() = ServerLauncher.stubInNativeLibDir(this)
    private fun serverDir() = File(appDir(), "servers/fabric-1.21.1")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
            gravity = Gravity.TOP
        }

        logView = TextView(this).apply { textSize = 9f }
        val scroll = ScrollView(this).apply { addView(logView) }

        val start = Button(this).apply {
            text = getString(com.minicraft.mcserver.R.string.diag_btn_start_server)
            setOnClickListener { runServer() }
        }
        val clear = Button(this).apply {
            text = getString(com.minicraft.mcserver.R.string.diag_btn_clear)
            setOnClickListener { logView.text = "" }
        }
        val info = Button(this).apply {
            text = getString(com.minicraft.mcserver.R.string.diag_btn_prev_info)
            setOnClickListener { showInfo() }
        }

        root.addView(start)
        root.addView(clear)
        root.addView(info)
        root.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        setContentView(root)

        showInfo()
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        main.post {
            logView.append(msg + "\n")
            (logView.parent as? ScrollView)?.post {
                (logView.parent as ScrollView).fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }

    private fun showInfo() {
        log(getString(com.minicraft.mcserver.R.string.diag_prev_info_header))
        log("device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} API ${android.os.Build.VERSION.SDK_INT}")
        log("dominio SELinux: " + (readFileTrim("/proc/self/attr/current") ?: "?"))
        log("stub java: ${javaBin().absolutePath}")
        log("  existe=${javaBin().exists()} ejecutable=${javaBin().canExecute()} size=${javaBin().length()}")
        log("nativeLibraryDir: ${applicationInfo.nativeLibraryDir}")
        log("jre home: ${jreHome().absolutePath}")
        log("  libjli=${File(jreHome(), "lib/libjli.so").exists()} " +
            "libjvm=${File(jreHome(), "lib/server/libjvm.so").exists()} " +
            "modules=${File(jreHome(), "lib/modules").exists()}")
        log("server dir: ${serverDir().absolutePath} existe=${serverDir().exists()}")
        listOf("fabric-server-launch.jar", "server.jar", "eula.txt", "server.properties",
               "libraries", "mods", "world").forEach { n ->
            val f = File(serverDir(), n)
            log("  $n: existe=${f.exists()}" + if (f.isFile) " size=${f.length()/1024}KB" else "")
        }
        val mem = Runtime.getRuntime()
        log("heap max app: ${mem.maxMemory()/1024/1024} MB")
        log("heap total: ${mem.totalMemory()/1024/1024} MB")
        log("memoria libre del sistema: " + (readMeminfo()))
        log("")
    }

    private fun readFileTrim(path: String): String? = try {
        java.io.File(path).readText().trim()
    } catch (t: Throwable) { null }

    private fun readMeminfo(): String = try {
        val f = File("/proc/meminfo")
        val lines = f.readLines()
        val total = lines.firstOrNull { it.startsWith("MemTotal") }?.split(" ")?.get(1)?.toLong() ?: 0
        val avail = lines.firstOrNull { it.startsWith("MemAvailable") }?.split(" ")?.get(1)?.toLong() ?: 0
        "total=${total/1024/1024}MB disponible=${avail/1024/1024}MB"
    } catch (t: Throwable) { "no legible: ${t.message}" }

    private fun runServer() {
        log("=== ARRANCANDO SERVIDOR ===")
        main.post {
            Thread {
                val t0 = System.currentTimeMillis()
                try {
                    val r = ServerLauncher.runUntilReady(
                        ctx = this,
                        jreHome = jreHome(),
                        serverDir = serverDir(),
                        heapMb = 1024,
                        onLine = { line -> log(line) }
                    )
                    val ms = System.currentTimeMillis() - t0
                    log("---")
                    log("RESULTADO: exitCode=${r.exitCode} listo=${r.reachedDone} en ${"%.1f".format(r.doneSeconds ?: -1.0)}s (total ${ms}ms)")
                    log("lineas de log: ${r.log.lines().size}")
                } catch (t: Throwable) {
                    log("EXCEPCION: ${t.message}")
                    Log.e(TAG, "excepcion", t)
                }
            }.start()
        }
    }
}