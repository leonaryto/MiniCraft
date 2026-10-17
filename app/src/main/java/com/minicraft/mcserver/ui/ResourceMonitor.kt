package com.minicraft.mcserver.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.minicraft.mcserver.R
import java.io.File
import java.util.Locale

/**
 * Monitor de recursos del servidor Minecraft en tiempo real.
 *
 * - CPU del proceso: /proc/<pid>/stat (utime+stime), delta entre muestreos.
 * - Memoria: VmRSS de /proc/<pid>/status.
 * - Temperatura: /sys/class/thermal/thermal_zoneN/temp (millicelsius).
 * - Sparkline con Canvas nativo (sin librerias).
 */
class ResourceMonitor : Activity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.minicraft.mcserver.AppLocale.wrap(newBase))
    }

    private lateinit var tvPid: TextView
    private lateinit var tvCpu: TextView
    private lateinit var tvMem: TextView
    private lateinit var tvTemp: TextView
    private lateinit var progress: ProgressBar
    private lateinit var sparkContainer: FrameLayout
    private var spark: TempSparkView? = null

    private val handler = Handler(Looper.getMainLooper())
    private val temps = ArrayList<Float>()
    private var poll: Runnable? = null

    companion object {
        private const val POLL_MS = 2000L
        private const val CLK_TCK = 100  // bionic: USER_HZ = 100

        fun show(parent: Activity) {
            parent.startActivity(Intent(parent, ResourceMonitor::class.java))
        }

        /** PID del JVM del server: proceso cuyo cmdline menciona el launch jar. */
        fun findServerPid(): Int? {
            val dirs = File("/proc").listFiles() ?: return null
            for (d in dirs) {
                if (!d.name.all { ch -> ch.isDigit() }) continue
                val cmdline = File(d, "cmdline")
                if (!cmdline.canRead()) continue
                val parts = try {
                    String(cmdline.readBytes(), Charsets.UTF_8).split("\u0000")
                } catch (t: Throwable) {
                    continue
                }
                if (parts.any { it.contains("server-launch.jar") || it.contains("server.jar") }) {
                    return d.name.toIntOrNull()
                }
            }
            return null
        }

        /** CPU % del proceso, normalizado al total de cores (0-100). */
        fun cpuPercent(pid: Int, prevTicks: Long, prevNs: Long): Pair<Double, Long> {
            val stat = File("/proc/$pid/stat")
            if (!stat.canRead()) return 0.0 to prevTicks
            val text = try { stat.readText() } catch (t: Throwable) { return 0.0 to prevTicks }
            // El nombre del proceso (entre parentesis) puede tener espacios:
            // partir desde el ultimo ')' y reindexar (state=0, utime=11, stime=12).
            val rparen = text.lastIndexOf(')')
            if (rparen < 0) return 0.0 to prevTicks
            val after = text.substring(rparen + 1).trim().split(" ")
            val utime = after.getOrNull(11)?.toLongOrNull() ?: 0L
            val stime = after.getOrNull(12)?.toLongOrNull() ?: 0L
            val ticks = utime + stime
            val nowNs = System.nanoTime()
            val dtNs = nowNs - prevNs
            if (prevNs == 0L || dtNs < 1_000_000) return 0.0 to ticks
            val dTicks = ticks - prevTicks
            if (dTicks < 0) return 0.0 to ticks
            val cpuMs = dTicks * 1000.0 / CLK_TCK
            // Normalizar al total de la maquina: un proceso JVM puede usar varios
            // cores a la vez (utime+stime suma todos); sin esto muestra 600%+.
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            val pct = cpuMs / (dtNs / 1_000_000.0) * 100.0 / cores
            return pct.coerceIn(0.0, 100.0) to ticks
        }

        /** Memoria RSS del proceso en MB. */
        fun memMb(pid: Int): Double {
            val f = File("/proc/$pid/status")
            if (!f.canRead()) return 0.0
            return try {
                var kb = 0L
                f.forEachLine { line ->
                    if (line.startsWith("VmRSS:")) {
                        kb = line.split(":").getOrNull(1)?.trim()
                            ?.split(" ")?.firstOrNull()?.toLongOrNull() ?: 0L
                    }
                }
                kb / 1024.0
            } catch (t: Throwable) {
                0.0
            }
        }

        /**
         * Temperatura "de CPU": prioriza zonas tipo CPU/cpu (los telefonos exponen
         * decenas; la primera suele ser batería/PMIC y da lecturas falsas).
         * Solo devuelve zonas cuya lectura sea plausible (15-110C).
         */
        fun readTemp(): Float? {
            val zones = File("/sys/class/thermal")
            if (!zones.isDirectory) return null
            val list = zones.listFiles() ?: return null
            // 1a pasada: zonas explicitamente de CPU
            val cpuZone = list.firstOrNull { z ->
                z.name.startsWith("thermal_zone") && isCpuZone(z)
            }
            if (cpuZone != null) return readZone(cpuZone)
            // 2a pasada: la zona con la lectura mas alta plausible (la CPU suele
            // ser la mas caliente; baterias y ambientales estan por debajo)
            var best: Float? = null
            for (z in list) {
                if (!z.name.startsWith("thermal_zone")) continue
                val t = readZone(z) ?: continue
                if (best == null || t > best) best = t
            }
            return best
        }

        private fun isCpuZone(z: File): Boolean = try {
            val type = File(z, "type").readText().trim().lowercase(Locale.getDefault())
            type.contains("cpu") || type.contains("gpu") == false && type == "cpu"
        } catch (t: Throwable) {
            false
        }

        private fun readZone(z: File): Float? {
            val tempFile = File(z, "temp")
            if (!tempFile.canRead()) return null
            val mc = try {
                tempFile.readText().trim().toIntOrNull()
            } catch (t: Throwable) {
                return null
            } ?: return null
            val c = mc / 1000.0f
            // Filtrar lecturas absurdas (sensores que reportan 0 o miles)
            return if (c in 15f..110f) c else null
        }

        /** RAM total del device en MB. */
        fun totalRamMb(ctx: Context): Int {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            return (mi.totalMem / (1024L * 1024L)).toInt()
        }
    }

    private var prevTicks = 0L
    private var prevNs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.MinicraftTheme)
        setContentView(R.layout.activity_resource_monitor)

        tvPid = findViewById(R.id.tvPid)
        tvCpu = findViewById(R.id.tvCpu)
        tvMem = findViewById(R.id.tvMem)
        tvTemp = findViewById(R.id.tvTemp)
        progress = findViewById(R.id.progress)
        sparkContainer = findViewById(R.id.sparkContainer)

        spark = TempSparkView(this)
        sparkContainer.addView(
            spark,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        startPoll()
    }

    private fun startPoll() {
        poll = object : Runnable {
            override fun run() {
                val pid = findServerPid()
                if (pid == null) {
                    tvPid.text = getString(R.string.monitor_pid_not_running)
                    tvCpu.text = getString(R.string.monitor_cpu_off)
                    tvMem.text = getString(R.string.monitor_mem_off)
                    progress.progress = 0
                } else {
                    tvPid.text = getString(R.string.monitor_pid_value, pid)

                    val pair = ResourceMonitor.cpuPercent(pid, prevTicks, prevNs)
                    prevTicks = pair.second
                    prevNs = System.nanoTime()
                    val cpu = pair.first
                    tvCpu.text = "CPU: " + "%.1f".format(cpu) + "%"
                    progress.progress = cpu.toInt().coerceIn(0, 100)

                    tvMem.text = getString(R.string.monitor_mem_value, memMb(pid), totalRamMb(this@ResourceMonitor))
                }

                val t = readTemp()
                if (t != null) {
                    tvTemp.text = "Temperatura: " + "%.1f".format(t) + "C"
                    temps.add(t)
                    if (temps.size > 40) temps.removeAt(0)
                    spark?.invalidate()
                } else {
                    tvTemp.text = "Temperatura: (no disponible)"
                }

                handler.postDelayed(this, POLL_MS)
            }
        }
        handler.post(poll!!)
    }

    override fun onDestroy() {
        super.onDestroy()
        poll?.let { handler.removeCallbacks(it) }
    }

    /**
     * Sparkline de temperatura mejorado: area rellena con linea superior,
     * escala fija 20-90C (zonas de referencia), suavizado y punto actual.
     */
    inner class TempSparkView(ctx: Context) : View(ctx) {
        private val linePaint = Paint().apply {
            color = Color.argb(255, 230, 130, 20)
            strokeWidth = 3f
            style = Paint.Style.STROKE
            isAntiAlias = true
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val fillPaint = Paint().apply {
            color = Color.argb(50, 230, 130, 20)
            style = Paint.Style.FILL
        }
        private val dotPaint = Paint().apply {
            color = Color.argb(255, 200, 80, 0)
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        private val gridPaint = Paint().apply {
            color = Color.argb(30, 0, 0, 0)
            strokeWidth = 1f
        }
        private val labelPaint = Paint().apply {
            color = Color.argb(160, 0, 0, 0)
            textSize = 22f
            isAntiAlias = true
        }
        private val path = android.graphics.Path()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 1 || h <= 1) return

            // Escala fija: 20C abajo, 90C arriba (rango util de un telefono)
            val minT = 20f
            val maxT = 90f
            val range = maxT - minT
            val hPad = 14f
            val usableH = (h - hPad * 2).coerceAtLeast(1f)

            fun yOf(t: Float): Float = h - hPad - ((t - minT) / range).coerceIn(0f, 1f) * usableH

            // Rejilla con etiquetas: 30/50/70C
            for (t in intArrayOf(30, 50, 70)) {
                val y = yOf(t.toFloat())
                canvas.drawLine(0f, y, w, y, gridPaint)
                canvas.drawText("${t}C", 6f, y - 6f, labelPaint)
            }

            if (temps.size < 2) return
            val n = temps.size.coerceAtMost(40)
            val wStep = w / (n - 1)

            path.reset()
            var firstX = 0f
            var firstY = 0f
            var lastX = 0f
            var lastY = 0f
            var j = 0
            for (i in temps.size - n until temps.size) {
                val x = j * wStep
                val y = yOf(temps[i])
                if (j == 0) {
                    firstX = x; firstY = y
                    path.moveTo(x, y)
                } else {
                    // Suavizado quadratic con punto medio
                    val prevX = (j - 1) * wStep
                    val prevY = yOf(temps[i - 1])
                    val midX = (prevX + x) / 2f
                    val midY = (prevY + y) / 2f
                    path.quadTo(prevX, prevY, midX, midY)
                }
                lastX = x; lastY = y
                j++
            }
            // Area rellena bajo la curva
            val fill = android.graphics.Path(path)
            fill.lineTo(lastX, h - hPad)
            fill.lineTo(firstX, h - hPad)
            fill.close()
            canvas.drawPath(fill, fillPaint)
            canvas.drawPath(path, linePaint)

            // Punto del valor actual
            canvas.drawCircle(lastX, lastY, 5f, dotPaint)
        }
    }
}
