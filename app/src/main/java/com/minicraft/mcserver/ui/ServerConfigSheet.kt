package com.minicraft.mcserver.ui

import android.app.AlertDialog
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.minicraft.mcserver.R
import org.json.JSONObject
import java.io.File

/**
 * Diálogo de configuración del servidor (ServerConfigSheet).
 *
 * Temas clave:
 * - El slider de RAM va de 512 MB a ~70% de la RAM total del device, leído
 *   via ActivityManager.MemoryInfo.totalMem (API 17+, reflexivo por compat).
 * - Los valores se guardan a files/server-config.json con org.json (no Gson).
 * - Es un `object` con un método show(Activity): construye un AlertDialog nativo
 *   (NO DialogFragment de AndroidX, NO Activity nueva) → no requiere manifest.
 *
 * Layout: res/layout/dialog_server_config.xml (XML puro, sin AndroidX).
 */
object ServerConfigSheet {

    const val TAG = "ServerConfigSheet"

    /** Configuración del server. */
    data class Config(
        val heapMb: Int,
        val threads: Int,
        val port: Int,
        val playitEnabled: Boolean,
        val viewDistance: Int = 8,
        val simulationDistance: Int = 6
    )

    /** Lee la configuración guardada (si no existe, valores por defecto). */
    fun load(ctx: Context): Config {
        val f = File(ctx.filesDir, "server-config.json")
        if (!f.isFile) return Config(1024, 0, 25565, true)
        return try {
            val j = JSONObject(f.readText())
            Config(
                heapMb = j.optInt("heapMb", 1024),
                threads = j.optInt("threads", 0),
                port = j.optInt("port", 25565),
                playitEnabled = j.optBoolean("playitEnabled", true),
                viewDistance = j.optInt("viewDistance", 8),
                simulationDistance = j.optInt("simulationDistance", 6)
            )
        } catch (e: Exception) {
            Config(1024, 0, 25565, true)
        }
    }

    /** Muestra el diálogo de configuración. */
    fun show(parent: Activity) {
        val ctx = parent
        val cfg = load(ctx)

        val inf = ctx.layoutInflater
        val v = inf.inflate(R.layout.dialog_server_config, null)

        val seekRam = v.findViewById<SeekBar>(R.id.seekRam)
        val tvRamValue = v.findViewById<TextView>(R.id.tvRamValue)
        val etThreads = v.findViewById<EditText>(R.id.etThreads)
        val etPort = v.findViewById<EditText>(R.id.etPort)
        val cbPlayit = v.findViewById<CheckBox>(R.id.cbPlayit)
        val rgPreset = v.findViewById<RadioGroup>(R.id.rgPreset)
        val rbLowLatency = v.findViewById<RadioButton>(R.id.rbPresetLowLatency)
        val rbBalanced = v.findViewById<RadioButton>(R.id.rbPresetBalanced)
        val rbHighView = v.findViewById<RadioButton>(R.id.rbPresetHighView)
        val rbCustom = v.findViewById<RadioButton>(R.id.rbPresetCustom)
        val llCustom = v.findViewById<LinearLayout>(R.id.llCustomDistance)
        val etViewDistance = v.findViewById<EditText>(R.id.etViewDistance)
        val etSimDistance = v.findViewById<EditText>(R.id.etSimDistance)

        // Presets de distancia de chunks: visión vs latencia.
        val presets = listOf(
            Triple(6, 4, rbLowLatency),   // Baja latencia
            Triple(8, 6, rbBalanced),     // Equilibrado (default)
            Triple(10, 8, rbHighView),    // Alta visión
            Triple(cfg.viewDistance, cfg.simulationDistance, rbCustom) // Personalizado
        )

        // Seleccionar preset guardado o equilibrado por defecto.
        val savedPreset = when {
            cfg.viewDistance <= 6 && cfg.simulationDistance <= 4 -> rbLowLatency
            cfg.viewDistance <= 8 && cfg.simulationDistance <= 6 -> rbBalanced
            cfg.viewDistance >= 10 -> rbHighView
            else -> rbCustom
        }
        savedPreset.isChecked = true
        llCustom.visibility = if (savedPreset === rbCustom) View.VISIBLE else View.GONE

        rgPreset.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.rbPresetLowLatency, R.id.rbPresetBalanced, R.id.rbPresetHighView ->
                    llCustom.visibility = View.GONE
                R.id.rbPresetCustom -> llCustom.visibility = View.VISIBLE
            }
        }

        // RAM: max = ~70% de la RAM total del device (leído via ActivityManager).
        val memInfo = ActivityManager.MemoryInfo()
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            am.getMemoryInfo(memInfo)
            // totalMem (API 17) no está expuesto en Kotlin puro → reflexivo.
            val totalBytes = memInfo.javaClass.getDeclaredField("totalMem").get(memInfo) as Long
            val totalMb = (totalBytes / (1024 * 1024)).toInt()
            val maxMb = (totalMb * 0.7).toInt().coerceAtMost(4096)
            seekRam.max = maxMb
            seekRam.progress = cfg.heapMb.coerceAtMost(maxMb)
            tvRamValue.text = "${seekRam.progress} MB"
            seekRam.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seek: SeekBar?, progress: Int, fromUser: Boolean) {
                    val valMb = progress.coerceAtLeast(512)
                    seekRam.progress = valMb
                    tvRamValue.text = "$valMb MB"
                }
                override fun onStartTrackingTouch(seek: SeekBar?) {}
                override fun onStopTrackingTouch(seek: SeekBar?) {}
            })
        }

        etThreads.setText(cfg.threads.toString())
        etPort.setText(cfg.port.toString())
        cbPlayit.isChecked = cfg.playitEnabled

        AlertDialog.Builder(ctx)
            .setTitle(R.string.server_config_title)
            .setView(v)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val ram = seekRam.progress.coerceAtLeast(512)
                val threadsStr = etThreads.text.toString().trim()
                val threads = threadsStr.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
                val portStr = etPort.text.toString().trim()
                val port = portStr.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 25565
                val playit = cbPlayit.isChecked

                if (port !in 1..65535) {
                    Toast.makeText(ctx, ctx.getString(R.string.cfg_invalid_port, port), Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                // Resolver viewDistance y simulationDistance según el preset seleccionado.
                val (viewDist, simDist) = when (rgPreset.checkedRadioButtonId) {
                    R.id.rbPresetLowLatency -> Pair(6, 4)
                    R.id.rbPresetBalanced -> Pair(8, 6)
                    R.id.rbPresetHighView -> Pair(10, 8)
                    else -> { // Personalizado
                        val vd = etViewDistance.text.toString().trim().toIntOrNull() ?: 8
                        val sd = etSimDistance.text.toString().trim().toIntOrNull() ?: 6
                        Pair(vd.coerceIn(4..32), sd.coerceIn(1..32))
                    }
                }

                val j = JSONObject()
                j.put("heapMb", ram)
                j.put("threads", threads)
                j.put("port", port)
                j.put("playitEnabled", playit)
                j.put("viewDistance", viewDist)
                j.put("simulationDistance", simDist)

                val f = File(ctx.filesDir, "server-config.json")
                f.writeText(j.toString(2))
                Toast.makeText(ctx, "Guardado: $ram MB, $threads hilos, puerto $port, view=$viewDist, sim=$simDist", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
