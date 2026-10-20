package com.minicraft.mcserver.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.minicraft.mcserver.AppLocale
import com.minicraft.mcserver.R
import com.minicraft.mcserver.Text
import com.minicraft.mcserver.playit.PlayitAgent
import org.json.JSONObject
import java.io.File

/**
 * Pestana "Ajustes": server.properties visual (estilo settings.html del prototipo).
 *
 * Guarda en files/server-config.json las mismas claves que ya existian
 * (ServerConfigSheet.load/save): heapMb, threads, port, playitEnabled, viewDistance,
 * simulationDistance. El servicio ya lee view/sim distance y heap de ahi
 * (MinecraftService.generateServerProperties), asi que aqui NO se cambia logica:
 * solo se presenta en pantallas de filas con selects y sliders.
 */
class ServerSettingsActivity : NavActivity() {

    override val titleRes = R.string.nav_settings
    override val subtitleRes = R.string.subtitle_settings
    override val tabId = R.id.tabSettings

    private lateinit var etMotd: EditText
    private lateinit var tvPort: TextView
    private lateinit var spinnerRam: Spinner
    private lateinit var spinnerDifficulty: Spinner
    private lateinit var spinnerGamemode: Spinner
    private lateinit var seekVd: SeekBar
    private lateinit var seekSd: SeekBar
    private lateinit var tvVd: TextView
    private lateinit var tvSd: TextView
    private lateinit var tvTunnel: TextView
    private lateinit var dirtyNote: View
    private lateinit var tvPresetDesc: TextView
    private lateinit var btnPresetBattery: Button
    private lateinit var btnPresetBalanced: Button
    private lateinit var btnPresetMax: Button
    private lateinit var btnLangEs: Button
    private lateinit var btnLangEn: Button
    private lateinit var switchPlayit: Switch

    private var dirty = false

    companion object {
        private val RAM_OPTIONS = intArrayOf(1024, 2048, 3072, 4096, 6144, 8192)
        private val DIFFICULTY = listOf("peaceful", "easy", "normal", "hard")
        private val GAMEMODE = listOf("survival", "creative", "adventure")

        /** Lanza ServerSettingsActivity desde cualquier Activity. */
        fun start(from: Activity) {
            from.startActivity(Intent(from, ServerSettingsActivity::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.MinicraftTheme)
        setContentView(R.layout.activity_server_settings)
        setupChrome()

        etMotd = findViewById(R.id.etMotd)
        tvPort = findViewById(R.id.tvPort)
        spinnerRam = findViewById(R.id.spinnerRam)
        spinnerDifficulty = findViewById(R.id.spinnerDifficulty)
        spinnerGamemode = findViewById(R.id.spinnerGamemode)
        seekVd = findViewById(R.id.seekViewDistance)
        seekSd = findViewById(R.id.seekSimDistance)
        tvVd = findViewById(R.id.tvViewDistanceVal)
        tvSd = findViewById(R.id.tvSimDistanceVal)
        tvTunnel = findViewById(R.id.tvTunnelDomain)
        dirtyNote = findViewById(R.id.tvDirtyNote)
        tvPresetDesc = findViewById(R.id.tvPresetDesc)
        btnPresetBattery = findViewById(R.id.btnPresetBattery)
        btnPresetBalanced = findViewById(R.id.btnPresetBalanced)
        btnPresetMax = findViewById(R.id.btnPresetMax)
        btnLangEs = findViewById(R.id.btnLangEs)
        btnLangEn = findViewById(R.id.btnLangEn)
        switchPlayit = findViewById(R.id.switchPlayit)

        // Panel de idioma: cambia la preferencia, refresca el holder y recrea
        // la Activity para que todo el UI se pinte con el nuevo locale.
        refreshLangButtons()
        btnLangEs.setOnClickListener { switchLang("es") }
        btnLangEn.setOnClickListener { switchLang("en") }

        // Presets de rendimiento: ajustan RAM + distancias y las marcan como pendientes
        // de guardar; NO escriben el config hasta que el usuario pulse Guardar.
        btnPresetBattery.setOnClickListener { applyPreset("bateria") }
        btnPresetBalanced.setOnClickListener { applyPreset("equilibrado") }
        btnPresetMax.setOnClickListener { applyPreset("maximo") }

        // Spinners
        spinnerRam.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            RAM_OPTIONS.map { "${it / 1024} GB" }
        )
        spinnerDifficulty.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            DIFFICULTY.map { difficultyLabel(it) }
        )
        spinnerGamemode.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            GAMEMODE.map { gamemodeLabel(it) }
        )

        // Sliders con label mono (como el prototipo)
        seekVd.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                tvVd.text = "view-distance · " + (p + 2) + " chunks"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) { markDirty() }
        })
        seekSd.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                tvSd.text = "simulation-distance · " + (p + 2) + " chunks"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) { markDirty() }
        })

        // Dirty tracking en selects
        val dirtyListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (loaded) markDirty()
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }
        spinnerRam.onItemSelectedListener = dirtyListener
        spinnerDifficulty.onItemSelectedListener = dirtyListener
        spinnerGamemode.onItemSelectedListener = dirtyListener
        etMotd.setOnKeyListener { _, _, _ -> markDirty(); false }
        switchPlayit.setOnCheckedChangeListener { _, _ -> if (loaded) markDirty() }

        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }
        findViewById<Button>(R.id.btnUndo).setOnClickListener { load(); clearDirty() }

        // Estado del tunel playit (solo lectura)
        val st = PlayitAgent.get(this).currentState()
        tvTunnel.text = st?.publicAddress ?: st?.displayAddress ?: getString(R.string.tunnel_requires_active)

        load()
        clearDirty()
        refreshPresetButtons()
    }

    private var loaded = false

    /** Carga la config existente (ServerConfigSheet.load, misma fuente que el servicio). */
    private fun load() {
        val cfg = ServerConfigSheet.load(this)
        loaded = false
        etMotd.setText(getString(R.string.server_title))
        tvPort.text = cfg.port.toString()
        // RAM: elegir el valor guardado si existe en la lista, si no el mas cercano
        val ramIdx = RAM_OPTIONS.indices.minByOrNull {
            kotlin.math.abs(RAM_OPTIONS[it] - cfg.heapMb)
        } ?: 1
        spinnerRam.setSelection(ramIdx, false)
        spinnerDifficulty.setSelection(DIFFICULTY.indexOfFirst { it == "normal" }.coerceAtLeast(0), false)
        spinnerGamemode.setSelection(GAMEMODE.indexOfFirst { it == "survival" }.coerceAtLeast(0), false)
        // Sliders: max=28 -> valor 2..30 (SeekBar parte de 0 = 2 chunks)
        seekVd.progress = (cfg.viewDistance - 2).coerceIn(0, seekVd.max)
        seekSd.progress = (cfg.simulationDistance - 2).coerceIn(0, seekSd.max)
        tvVd.text = "view-distance · " + cfg.viewDistance + " chunks"
        tvSd.text = "simulation-distance · " + cfg.simulationDistance + " chunks"
        switchPlayit.isChecked = cfg.playitEnabled
        loaded = true
    }

    /** Guarda: mismas claves JSON que ya existian; el servicio las aplica al arrancar. */
    private fun save() {
        val cfg = ServerConfigSheet.load(this)
        val vd = seekVd.progress + 2
        val sd = seekSd.progress + 2
        val j = JSONObject()
        j.put("heapMb", RAM_OPTIONS[spinnerRam.selectedItemPosition])
        j.put("threads", cfg.threads)
        j.put("port", cfg.port)
        j.put("playitEnabled", switchPlayit.isChecked)
        j.put("viewDistance", vd)
        j.put("simulationDistance", sd)
        val f = File(filesDir, "server-config.json")
        f.writeText(j.toString(2))
        Toast.makeText(
            this,
            getString(R.string.msg_saved, RAM_OPTIONS[spinnerRam.selectedItemPosition], vd, sd),
            Toast.LENGTH_LONG
        ).show()
        ConsoleLog.add(getString(R.string.console_props_updated, vd, sd))
        clearDirty()
    }

    private fun markDirty() {
        dirty = true
        dirtyNote.visibility = View.VISIBLE
        findViewById<Button>(R.id.btnSave).text = getString(R.string.settings_save)
    }

    /**
     * Presets de rendimiento (mismas claves que ya maneja el servicio):
     *  - bateria: heap 1024, view 6, sim 4   (minimo consumo)
     *  - equilibrado: heap 2048, view 10, sim 8  (recomendado)
     *  - maximo: heap 4096, view 16, sim 12  (mejor experiencia, mas RAM)
     */
    private var activePreset: String? = null

    private fun applyPreset(which: String) {
        val ramMb: Int
        val vd: Int
        val sd: Int
        val desc: String
        when (which) {
            "bateria" -> { ramMb = 1024; vd = 6; sd = 4; desc = getString(R.string.preset_battery_desc) }
            "maximo" -> { ramMb = 4096; vd = 16; sd = 12; desc = getString(R.string.preset_max_desc) }
            else -> { ramMb = 2048; vd = 10; sd = 8; desc = getString(R.string.preset_balanced_desc) }
        }
        val ramIdx = RAM_OPTIONS.indices.firstOrNull { RAM_OPTIONS[it] == ramMb } ?: 1
        spinnerRam.setSelection(ramIdx, false)
        seekVd.progress = (vd - 2).coerceIn(0, seekVd.max)
        seekSd.progress = (sd - 2).coerceIn(0, seekSd.max)
        tvVd.text = "view-distance · " + vd + " chunks"
        tvSd.text = "simulation-distance · " + sd + " chunks"
        tvPresetDesc.text = getString(R.string.settings_preset_current, desc)
        activePreset = which
        refreshPresetButtons()
        markDirty()
        ConsoleLog.add(getString(R.string.preset_selected, which))
    }

    /** Pinta verde el preset activo; los demas quedan en estilo secundario. */
    private fun refreshPresetButtons() {
        btnPresetBattery.styleForPreset(activePreset == "bateria")
        btnPresetBalanced.styleForPreset(activePreset == "equilibrado")
        btnPresetMax.styleForPreset(activePreset == "maximo")
    }

    private fun Button.styleForPreset(active: Boolean) {
        // setBackgroundResource + textColor: mismo efecto que BtnGreen/BtnSecondary
        setBackgroundResource(if (active) R.drawable.bg_btn_green else R.drawable.bg_btn_secondary)
        setTextColor(getColor(if (active) R.color.on_primary else R.color.on_surface))
    }

    private fun clearDirty() {
        dirty = false
        dirtyNote.visibility = View.GONE
    }

    // ---------------------------------------------------------- idioma
    /** Etiqueta localizada de una dificultad (el valor guardado sigue siendo el de MC). */
    private fun difficultyLabel(value: String): String = when (value) {
        "peaceful" -> getString(R.string.diff_peaceful)
        "easy" -> getString(R.string.diff_easy)
        "hard" -> getString(R.string.diff_hard)
        else -> getString(R.string.diff_normal)
    }

    /** Etiqueta localizada de un modo de juego (el valor guardado sigue siendo el de MC). */
    private fun gamemodeLabel(value: String): String = when (value) {
        "creative" -> getString(R.string.gm_creative)
        "adventure" -> getString(R.string.gm_adventure)
        else -> getString(R.string.gm_survival)
    }

    private fun refreshLangButtons() {
        val es = AppLocale.lang() == "es"
        btnLangEs.styleForPreset(es)
        btnLangEn.styleForPreset(!es)
    }

    /** Cambia el idioma de la app y recrea la Activity para repintar todo. */
    private fun switchLang(lang: String) {
        if (AppLocale.lang() == lang) return
        AppLocale.setLang(this, lang)
        Text.init(this)
        recreate()
    }
}
