package com.minicraft.mcserver.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.minicraft.mcserver.R
import com.minicraft.mcserver.server.JreProvisioner
import com.minicraft.mcserver.server.ServerLauncher
import com.minicraft.mcserver.server.ServerProvisioner
import com.minicraft.mcserver.service.MinecraftService
import com.minicraft.mcserver.playit.PlayitAgent
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * UI principal de Minicraft (pestana Inicio).
 *
 * Permite: elegir version y sabor (Vanilla/Fabric), instalar, iniciar, detener,
 * exportar el log y ver el monitor de recursos. La consola vive en ConsoleActivity,
 * mods en ModManagerActivity, mundos en WorldManager y ajustes en ServerSettingsActivity
 * (bottom nav compartido via NavActivity).
 */
class MainActivity : NavActivity() {

    override val titleRes = R.string.app_name
    override val subtitleRes = R.string.subtitle_home
    override val tabId = R.id.tabHome

    private val ui = Handler(Looper.getMainLooper())
    private val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private lateinit var spinner: Spinner
    private lateinit var flavorGroup: RadioGroup
    private lateinit var btnVanilla: RadioButton
    private lateinit var btnFabric: RadioButton
    private lateinit var logView: TextView
    private lateinit var btnInstall: Button
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnExportLogs: Button
    private lateinit var btnResources: Button
    private lateinit var btnToggleIp: Button

    private lateinit var tvServerAddress: TextView
    private lateinit var pillStatus: TextView
    private lateinit var tvUptime: TextView
    private lateinit var tvPlayers: TextView
    private lateinit var tvVersion: TextView
    private lateinit var tvFlavor: TextView
    private lateinit var tvTunnelAddr: TextView
    private lateinit var pillTunnel: TextView
    private lateinit var tvTps: TextView
    private lateinit var tvCpuVal: TextView
    private lateinit var tvRamVal: TextView
    private lateinit var pbCpu: android.widget.ProgressBar
    private lateinit var pbRam: android.widget.ProgressBar
    private lateinit var playitStatusView: View

    @Volatile private var playitState: PlayitAgent.PlayitState? = null
    @Volatile private var busy = false
    private var ipHidden = true

    private val prefs by lazy { getSharedPreferences("minicraft_ui", MODE_PRIVATE) }

    private var pendingFlavor: ServerProvisioner.Flavor? = null
    private var pendingVersion: String = ServerProvisioner.SUPPORTED[0]
    private val REQ_MODS = 7

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.MinicraftTheme)
        setContentView(R.layout.activity_main)
        setupChrome()

        spinner = findViewById(R.id.spinnerVersion)
        flavorGroup = findViewById(R.id.flavorGroup)
        btnVanilla = findViewById(R.id.rbVanilla)
        btnFabric = findViewById(R.id.rbFabric)
        logView = findViewById(R.id.logView)
        btnInstall = findViewById(R.id.btnInstall)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnExportLogs = findViewById(R.id.btnExportLogs)
        btnResources = findViewById(R.id.btnResources)
        btnToggleIp = findViewById(R.id.btnToggleIp)
        tvServerAddress = findViewById(R.id.tvServerAddress)
        pillStatus = findViewById(R.id.pillStatus)
        tvUptime = findViewById(R.id.tvUptime)
        tvPlayers = findViewById(R.id.tvPlayers)
        tvVersion = findViewById(R.id.tvVersion)
        tvFlavor = findViewById(R.id.tvFlavor)
        playitStatusView = findViewById(R.id.playitStatusView)
        tvTunnelAddr = findViewById(R.id.tvTunnelAddr)
        pillTunnel = findViewById(R.id.pillTunnel)
        tvTps = findViewById(R.id.tvTps)
        tvCpuVal = findViewById(R.id.tvCpuVal)
        tvRamVal = findViewById(R.id.tvRamVal)
        pbCpu = findViewById(R.id.pbCpu)
        pbRam = findViewById(R.id.pbRam)

        logView.movementMethod = ScrollingMovementMethod()

        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            ServerProvisioner.SUPPORTED
        ).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        ipHidden = prefs.getBoolean(KEY_HIDE_IP, true)
        restoreSelection()
        updateIpToggle()
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                saveSelection()
                refreshStatus()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        flavorGroup.setOnCheckedChangeListener { _, _ ->
            saveSelection()
            refreshStatus()
        }
        btnToggleIp.setOnClickListener {
            ipHidden = !ipHidden
            prefs.edit().putBoolean(KEY_HIDE_IP, ipHidden).apply()
            updateIpToggle()
            refreshStatus()
        }

        btnInstall.setOnClickListener { doInstall() }
        btnStart.setOnClickListener { doStart() }
        btnStop.setOnClickListener { MinecraftService.stop(this) }
        btnResources.setOnClickListener { ResourceMonitor.show(this) }
        btnExportLogs.setOnClickListener { doExportLogs() }
        findViewById<Button>(R.id.btnDeleteServer).setOnClickListener { doDeleteServer() }
        playitStatusView.setOnClickListener { copyTunnelAddress() }

        PlayitAgent.get(this).listener = { st ->
            playitState = st
            ui.post {
                tvTunnelAddr.text = st.publicAddress ?: st.displayAddress ?: getString(R.string.no_address)
                pillTunnel.text = when (st.tunnelState) {
                    "Online" -> "ONLINE"
                    "Error" -> "ERROR"
                    else -> getString(R.string.status_connecting)
                }
                pillTunnel.setTextColor(
                    when (st.tunnelState) {
                        "Online" -> getColor(R.color.success)
                        "Error" -> getColor(R.color.danger)
                        else -> getColor(R.color.warn)
                    }
                )
            }
        }

        ConsoleLog.addListener(uiListener)

        if (!jrePresent()) {
            ConsoleLog.add(getString(R.string.console_jre_missing))
        }
        refreshStatus()
    }

    private val uiListener: (String) -> Unit = { line -> ui.post { appendLine(line) } }

    override fun onResume() {
        super.onResume()
        playitState = PlayitAgent.get(this).currentState()
        refreshStatus()
        ui.post(resourceTicker)
        ui.post(uptimeTicker)
    }

    override fun onPause() {
        super.onPause()
        saveSelection()
        ui.removeCallbacks(resourceTicker)
        ui.removeCallbacks(uptimeTicker)
    }

    override fun onDestroy() {
        super.onDestroy()
        ConsoleLog.removeListener(uiListener)
        ui.removeCallbacksAndMessages(null)
    }

    // ---------------------------------------------------------- seleccion
    private fun currentVersion() = ServerProvisioner.SUPPORTED[spinner.selectedItemPosition]
    private fun currentFlavor(): ServerProvisioner.Flavor =
        if (btnFabric.isChecked) ServerProvisioner.Flavor.FABRIC
        else ServerProvisioner.Flavor.VANILLA

    /**
     * Restaura la ultima seleccion de version/sabor. Primero usa la preferencia
     * de la UI (se guarda al cambiar o al salir) y, si no existe, el ultimo
     * servidor lanzado. Nunca pisa los valores por defecto con datos invalidos.
     */
    private fun restoreSelection() {
        val last = LastServerStore.load(this)
        val savedVersion = prefs.getString(KEY_VERSION, null) ?: last?.version
        val savedFlavor = prefs.getString(KEY_FLAVOR, null) ?: last?.flavor

        val idx = savedVersion?.let { ServerProvisioner.SUPPORTED.indexOf(it) } ?: -1
        if (idx >= 0) spinner.setSelection(idx)
        when {
            savedFlavor.equals("vanilla", true) -> btnVanilla.isChecked = true
            savedFlavor.equals("fabric", true) -> btnFabric.isChecked = true
        }
    }

    private fun saveSelection() {
        prefs.edit()
            .putString(KEY_VERSION, currentVersion())
            .putString(KEY_FLAVOR, currentFlavor().name.lowercase())
            .apply()
    }

    private fun updateIpToggle() {
        btnToggleIp.text = getString(if (ipHidden) R.string.show_ip else R.string.hide_ip)
    }

    private fun activeServerDir(): File =
        ServerProvisioner.dirFor(this, currentFlavor(), currentVersion())

    // ---------------------------------------------------------- acciones
    private fun jrePresent() = File(filesDir, "jre/lib/libjli.so").isFile

    private fun doInstall() {
        if (busy) return
        val v = currentVersion()
        val f = currentFlavor()
        if (ServerProvisioner.isInstalled(this, f, v)) {
            ConsoleLog.add(getString(R.string.msg_already_installed, f, v))
            refreshStatus()
            return
        }
        setBusy(true)
        ConsoleLog.add(getString(R.string.msg_installing, f, v))
        Thread({
            // La JRE es requisito de TODO lo demas, y del instalador de Fabric en
            // particular: sin ella el stub no enlaza libjli.so y el instalador sale
            // con codigo 1. Se descarga aqui si falta (p.ej. primer arranque).
            val jreErr = JreProvisioner.ensureInstalled(this) { line ->
                ConsoleLog.add(line)
            }
            if (jreErr != null) {
                ui.post {
                    ConsoleLog.add(getString(R.string.msg_result_fail, jreErr))
                    setBusy(false)
                    refreshStatus()
                    Toast.makeText(this, jreErr, Toast.LENGTH_LONG).show()
                }
                return@Thread
            }
            val res = ServerProvisioner.install(this, f, v) { line ->
                ConsoleLog.add(line)
            }
            ui.post {
                ConsoleLog.add(
                    if (res.ok) getString(R.string.msg_result_ok, res.message)
                    else getString(R.string.msg_result_fail, res.message)
                )
                setBusy(false)
                refreshStatus()
                Toast.makeText(this, res.message, Toast.LENGTH_LONG).show()
            }
        }, "install-thread").start()
    }

    private fun doStart() {
        val v = currentVersion()
        val f = currentFlavor()
        val jar = ServerProvisioner.launchJarFor(this, f, v)
        if (jar == null) {
            // La version elegida no esta instalada: mensaje claro y sin crash.
            ConsoleLog.add(getString(R.string.msg_not_installed_console, f, v))
            Toast.makeText(
                this,
                getString(R.string.msg_not_installed_toast, v, f),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val dir = ServerProvisioner.dirFor(this, f, v)
        // Recordar la seleccion: al volver a la app quedara restaurada.
        LastServerStore.save(this, f.name.lowercase(), v, dir.absolutePath)
        MinecraftService.launch(this, dir, jar)
        ConsoleLog.add(getString(R.string.msg_starting, f, v))
        ui.postDelayed({ refreshStatus() }, 3000)
    }

    /**
     * Borra el servidor seleccionado (instalacion completa: jar, librerias,
     * mundos, mods y configs). Pide confirmacion y no permite borrar si esta
     * corriendo.
     */
    private fun doDeleteServer() {
        val v = currentVersion()
        val f = currentFlavor()
        if (ServerLauncher.running) {
            Toast.makeText(this, getString(R.string.msg_stop_before_delete), Toast.LENGTH_LONG).show()
            return
        }
        val dir = ServerProvisioner.dirFor(this, f, v)
        if (!dir.isDirectory) {
            Toast.makeText(this, getString(R.string.msg_not_installed, f, v), Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_delete_title))
            .setMessage(
                getString(R.string.dialog_delete_message, f, v, dir.name)
            )
            .setPositiveButton(getString(R.string.dialog_delete_confirm)) { _, _ ->
                if (dir.deleteRecursively()) {
                    ConsoleLog.add(getString(R.string.msg_server_deleted) + ": ${dir.name}")
                    Toast.makeText(this, getString(R.string.msg_server_deleted), Toast.LENGTH_LONG).show()
                    // Si el borrado era el ultimo lanzado, limpiar el registro.
                    val last = LastServerStore.load(this)
                    if (last != null && File(last.dir).canonicalPath == dir.canonicalPath) {
                        LastServerStore.clear(this)
                    }
                    refreshStatus()
                } else {
                    Toast.makeText(this, getString(R.string.msg_delete_failed), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.worlds_cancel), null)
            .show()
    }

    fun openModPicker(flavor: ServerProvisioner.Flavor, ver: String) {
        pendingFlavor = flavor
        pendingVersion = ver
        try {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                type = "application/java-archive"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/java-archive"))
            }
            startActivityForResult(i, REQ_MODS)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.msg_no_file_picker), Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_MODS || resultCode != RESULT_OK || data == null) return

        val flavor = pendingFlavor ?: return
        val ver = pendingVersion
        val modsDir = File(ServerProvisioner.dirFor(this, flavor, ver), "mods")
        if (!modsDir.isDirectory) modsDir.mkdirs()

        var count = 0
        val clip = data.clipData
        if (clip != null) {
            val n = clip.itemCount
            for (i in 0 until n) {
                val uri = clip.getItemAt(i).uri ?: continue
                if (copyMod(uri, modsDir)) count++
            }
        } else {
            val uri = data.data ?: return
            if (copyMod(uri, modsDir)) count++
        }

        if (count > 0) {
            ConsoleLog.add(getString(R.string.msg_mods_copied_console, count, modsDir.name))
            Toast.makeText(this, getString(R.string.msg_mods_copied_toast, count, modsDir.name), Toast.LENGTH_LONG).show()
        } else {
            ConsoleLog.add(getString(R.string.msg_no_mods))
        }
        refreshStatus()
    }

    private fun copyMod(uri: Uri, modsDir: File): Boolean = try {
        val name = queryDisplayName(uri) ?: ("mod-" + System.currentTimeMillis() + ".jar")
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val out = File(modsDir, safe)
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(out).use { input.copyTo(it) }
        }
        Log.i(TAG, "mod copiado: " + out.name)
        true
    } catch (t: Throwable) {
        Log.e(TAG, "no se pudo copiar mod $uri: ${t.message}")
        false
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        var name: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && idx >= 0) name = c.getString(idx)
        }
        name
    } catch (t: Throwable) {
        null
    }

    // ------------------------------------------------------------- estado
    private fun refreshStatus() {
        val v = currentVersion()
        val f = currentFlavor()
        val ip = lanAddress()
        val installed = ServerProvisioner.isInstalled(this, f, v)

        when {
            ServerLauncher.running && ServerLauncher.ready -> {
                pillStatus.text = getString(R.string.status_online)
                pillStatus.setTextColor(getColor(R.color.success))
            }
            ServerLauncher.running -> {
                pillStatus.text = getString(R.string.status_starting)
                pillStatus.setTextColor(getColor(R.color.warn))
            }
            else -> {
                pillStatus.text = getString(R.string.status_stopped)
                pillStatus.setTextColor(getColor(R.color.on_surface_secondary))
            }
        }

        val address = if (ip != null) "$ip:25565" else getString(R.string.no_wifi)
        tvServerAddress.text =
            if (ip != null && ipHidden) getString(R.string.ip_hidden) else address
        btnToggleIp.isEnabled = ip != null
        btnToggleIp.alpha = if (ip != null) 1f else 0.4f

        tvVersion.text = v
        tvFlavor.text = f.label
        tvPlayers.text = getString(R.string.players_count, ConsoleLog.playerCount().toString())

        btnInstall.isEnabled = !busy
        btnStart.isEnabled = !busy && installed
        btnInstall.text = if (busy) getString(R.string.installing) else getString(R.string.install)
    }

    // ------------------------------------------------------------- recursos
    private var prevCpuTicks = 0L
    private var prevCpuNs = 0L

    private val resourceTicker = object : Runnable {
        override fun run() {
            val pid = ResourceMonitor.findServerPid()

            if (pid != null) {
                val pair = ResourceMonitor.cpuPercent(pid, prevCpuTicks, prevCpuNs)
                prevCpuTicks = pair.second
                prevCpuNs = System.nanoTime()
                val cpu = pair.first
                val memMb = ResourceMonitor.memMb(pid)
                val totalMb = ResourceMonitor.totalRamMb(this@MainActivity)
                val tps = ServerLauncher.tps()

                // TPS: el vanilla no imprime ticks por segundo; solo mostramos
                // un dato real si el parser del log ya capturo alguno.
                tvTps.text = if (tps > 0.0) "TPS " + "%.1f".format(tps) else "TPS -"
                tvCpuVal.text = "%.1f%%".format(cpu)
                tvRamVal.text = "%d / %d MB".format(memMb.toInt(), totalMb)
                pbCpu.progress = cpu.toInt().coerceIn(0, 100)
                pbRam.progress = if (totalMb > 0) ((memMb / totalMb) * 100).toInt().coerceIn(0, 100) else 0
            } else {
                tvTps.text = "TPS -"
                tvCpuVal.text = "-"
                tvRamVal.text = "-"
                pbCpu.progress = 0
                pbRam.progress = 0
            }

            ui.postDelayed(this, 2000L)
        }
    }

    private val uptimeTicker = object : Runnable {
        override fun run() {
            if (ServerLauncher.running && ServerLauncher.ready) {
                val s = (System.currentTimeMillis() - ServerLauncher.readyAtMs) / 1000
                tvUptime.text = String.format(
                    Locale.getDefault(), "%02d:%02d:%02d",
                    s / 3600, (s % 3600) / 60, s % 60
                )
            } else {
                tvUptime.text = "-"
            }
            ui.postDelayed(this, 1000)
        }
    }

    private fun lanAddress(): String? = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { runCatching { it.isUp }.getOrDefault(false) }
            .filter { runCatching { !it.isLoopback }.getOrDefault(false) }
            .flatMap { nif -> nif.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it.address.size == 4 }
            ?.hostAddress
    } catch (t: Throwable) {
        Log.w(TAG, "no se pudo obtener la IP local: ${t.message}")
        null
    }

    private fun setBusy(b: Boolean) {
        busy = b
        btnInstall.isEnabled = !b
        btnStart.isEnabled = !b
        btnInstall.text = if (b) getString(R.string.installing) else getString(R.string.install)
    }

    private fun appendLine(line: String) {
        logView.append(stamp.format(Date()) + "  " + line + "\n")
    }

    private fun doExportLogs() {
        val text = logView.text?.toString() ?: ""
        if (text.isBlank()) {
            Toast.makeText(this, getString(R.string.msg_log_empty), Toast.LENGTH_SHORT).show()
            return
        }
        val stampFile = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val logFile = File(filesDir, "exported/minicraft-log-" + stampFile + ".txt")
        logFile.parentFile?.mkdirs()
        logFile.writeText(text)
        val uri = Uri.fromFile(logFile)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Log Minicraft " + stampFile)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, getString(R.string.msg_export_log)))
    }

    private fun copyTunnelAddress() {
        val s = playitState
        val addr = s?.publicAddress ?: s?.displayAddress
        if (addr.isNullOrBlank()) {
            Toast.makeText(this, getString(R.string.msg_no_tunnel_yet), Toast.LENGTH_SHORT).show()
            return
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("tunel", addr))
        Toast.makeText(this, getString(R.string.msg_url_copied, addr), Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val TAG = "MinicraftUI"
        private const val KEY_VERSION = "selected_version"
        private const val KEY_FLAVOR = "selected_flavor"
        private const val KEY_HIDE_IP = "hide_ip"
    }
}
