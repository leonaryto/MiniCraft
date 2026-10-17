package com.minicraft.mcserver.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import android.widget.Switch
import com.minicraft.mcserver.R
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pestana Mods: gestion visual de los .jar del servidor Fabric activo.
 *
 * - Lista mods con portada (icono Modrinth si existe), nombre, origen
 *   (modrinth/importado), version y tamano.
 * - Switch activar/desactivar: renombra a .jar.disabled (convencion estandar
 *   que el loader ya respeta al arrancar; no cambia logica del servidor).
 * - Agregar: dialogo con dos opciones: bajar desde Modrinth (API v2) o
 *   importar del selector de archivos (SAF, seleccion multiple).
 * - Filtro por nombre y orden por nombre/fecha de modificacion.
 */
class ModManagerActivity : NavActivity() {

    override val titleRes = R.string.nav_mods
    override val subtitleRes = R.string.subtitle_mods
    override val tabId = R.id.tabMods

    private lateinit var lvMods: ListView
    private lateinit var tvCount: TextView
    private lateinit var tvLoaderInfo: TextView
    private lateinit var etFilter: EditText
    private lateinit var chipName: Button
    private lateinit var chipDate: Button
    private lateinit var chipDisabled: Button
    private lateinit var adapter: ModAdapter

    private val ui = Handler(Looper.getMainLooper())

    private var all: List<ModStore.Mod> = emptyList()
    private var shown: List<ModStore.Mod> = emptyList()
    private var sortByName = true
    private var showDisabled = true
    private val REQ_IMPORT = 41

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.MinicraftTheme)
        setContentView(R.layout.activity_mod_manager)
        setupChrome()

        lvMods = findViewById(R.id.lvMods)
        tvCount = findViewById(R.id.tvModsCount)
        tvLoaderInfo = findViewById(R.id.tvLoaderInfo)
        etFilter = findViewById(R.id.etModFilter)
        chipName = findViewById(R.id.chipSortName)
        chipDate = findViewById(R.id.chipSortDate)
        chipDisabled = findViewById(R.id.chipShowDisabled)

        adapter = ModAdapter()
        lvMods.adapter = adapter

        etFilter.setOnEditorActionListener { _, _, _ ->
            applyFilter(); false
        }
        chipName.setOnClickListener { sortByName = true; refreshChips(); applyFilter() }
        chipDate.setOnClickListener { sortByName = false; refreshChips(); applyFilter() }
        chipDisabled.setOnClickListener { showDisabled = !showDisabled; refreshChips(); applyFilter() }

        findViewById<Button>(R.id.fabAddMods).setOnClickListener { showAddDialog() }

        refreshChips()
        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun refreshChips() {
        // Chip activo: fondo verde + texto blanco (mismo lenguaje que los presets)
        styleChip(chipName, sortByName)
        styleChip(chipDate, !sortByName)
        // "Ver off" es un toggle aparte: azul cuando muestra los desactivados
        chipDisabled.setTextColor(getColor(if (showDisabled) R.color.accent else R.color.on_surface_secondary))
    }

    /** Estilo Modrinth para chips de filtro: activo = fondo blanco + borde azul + texto negrita, inactivo = fondo gris claro + borde gris. */
    private fun styleChip(c: Button, active: Boolean) {
        if (active) {
            c.setBackgroundResource(R.drawable.bg_chip_modrinth_active)
            c.setTextColor(getColor(R.color.chip_text_active))
            c.typeface = android.graphics.Typeface.DEFAULT_BOLD
        } else {
            c.setBackgroundResource(R.drawable.bg_chip_modrinth_inactive)
            c.setTextColor(getColor(R.color.chip_text_inactive))
            c.typeface = android.graphics.Typeface.DEFAULT
        }
    }

    /** Relee mods/ y refresca. */
    private fun load() {
        all = ModStore.list(this)
        applyFilter()

        val dir = ModStore.modsDir(this)
        val fabricJar = File(dir.parentFile, "fabric-server-launch.jar")
        val on = all.count { it.enabled }

        // Verificacion real: Fabric lista en logs/latest.log los mods cargados
        // ("Loading N mods:" + lineas "- modid version"). Comparamos los modids
        // del log contra los de nuestros jars (fabric.mod.json -> id) para contar
        // SOLO mods de usuario, no fabricloader/minecraft/java.
        val loadedIds = loadedModIdsFromLog(dir.parentFile)
        tvLoaderInfo.text = if (fabricJar.isFile) {
            if (loadedIds != null) {
                val ours = all.filter { it.enabled }.count { modIdOf(it.file) in loadedIds }
                getString(R.string.mods_loader_info, on, all.size, ours)
            } else {
                getString(R.string.mods_loader_info_noboot, on, all.size)
            }
        } else {
            getString(R.string.mods_fabric_missing)
        }
    }

    /**
     * Modids que Fabric reporta cargados en el ultimo arranque.
     * Devuelve null si no hay log. El bloque se ve asi:
     *   [main/INFO]: Loading 4 mods:
     *   \t- fabricloader 0.19.5
     *   \t   \-- mixinextras 0.5.5
     *   \t- java 21
     *   \t- minecraft 1.21.1
     *   \t- fabric-api 0.105.0+1.21.1   (mods de usuario, uno por linea)
     */
    private fun loadedModIdsFromLog(serverDir: File?): Set<String>? {
        if (serverDir == null) return null
        val log = File(serverDir, "logs/latest.log")
        if (!log.isFile) return null
        return try {
            val ids = HashSet<String>()
            var inBlock = false
            for (raw in log.readLines()) {
                val line = raw.trim()
                if (line.contains("Loading ") && line.endsWith(" mods:")) {
                    inBlock = true
                    continue
                }
                if (inBlock) {
                    if (line.startsWith("- ")) {
                        // "- modid version" -> id = segunda columna
                        val id = line.removePrefix("- ").split(" ").firstOrNull()
                        if (!id.isNullOrBlank()) ids.add(id)
                    } else if (!line.startsWith("\\--") && line.isNotEmpty()) {
                        break  // fin del bloque
                    }
                }
            }
            ids
        } catch (t: Throwable) {
            null
        }
    }

    /** Lee el id del mod desde fabric.mod.json dentro del jar (cache en memoria). */
    private val modIdCache = HashMap<String, String>()
    private fun modIdOf(jar: File): String {
        jar.absolutePath.let { p -> modIdCache[p] }?.let { return it }
        val id = try {
            java.util.zip.ZipFile(jar).use { zf ->
                val entry = zf.getEntry("fabric.mod.json") ?: return@use ""
                val json = JSONObject(zf.getInputStream(entry).readBytes().toString(Charsets.UTF_8))
                json.optString("id", "")
            }
        } catch (t: Throwable) {
            ""
        }
        // Fallback: primer segmento del nombre de archivo
        val fallback = jar.name.removeSuffix(".disabled").removeSuffix(".jar")
            .split("-").firstOrNull()?.lowercase(Locale.getDefault()) ?: ""
        val final = if (id.isNotEmpty()) id else fallback
        modIdCache[jar.absolutePath] = final
        return final
    }

    /** Aplica filtro de texto + orden + visibilidad de desactivados. */
    private fun applyFilter() {
        val q = etFilter.text?.toString()?.trim()?.lowercase(Locale.getDefault()) ?: ""
        var list = all
        if (q.isNotEmpty()) list = list.filter { it.title.lowercase(Locale.getDefault()).contains(q) || it.fileName.lowercase(Locale.getDefault()).contains(q) }
        if (!showDisabled) list = list.filter { it.enabled }
        shown = if (sortByName) {
            list.sortedBy { it.title.lowercase(Locale.getDefault()) }
        } else {
            list.sortedByDescending { it.lastModifiedMs }
        }
        adapter.data = shown
        adapter.notifyDataSetChanged()
        val on = shown.count { it.enabled }
        tvCount.text = getString(R.string.mods_count, shown.size, on)
    }

    // ---------------------------------------------------------- agregar
    private fun showAddDialog() {
        val options = arrayOf(
            getString(R.string.mods_add_modrinth),
            getString(R.string.mods_add_import)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.mods_add_title))
            .setItems(options) { _, which ->
                if (which == 0) showModrinthSheet() else openFilePicker()
            }
            .show()
    }

    private fun openFilePicker() {
        try {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                type = "application/java-archive"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/java-archive", "application/octet-stream"))
            }
            startActivityForResult(i, REQ_IMPORT)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.msg_no_file_picker), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK || data == null) return

        val dir = ModStore.modsDir(this)
        var count = 0
        val clip = data.clipData
        fun copyOne(uri: Uri): Boolean = try {
            val name = queryDisplayName(uri) ?: ("mod-" + System.currentTimeMillis() + ".jar")
            var safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            if (!safe.endsWith(".jar")) safe += ".jar"
            val out = File(dir, safe)
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(out).use { input.copyTo(it) }
            }
            ModStore.rememberMeta(this, out.name, "importado", "", out.name.removeSuffix(".jar"), "", null)
            true
        } catch (t: Throwable) {
            false
        }

        if (clip != null) {
            for (i in 0 until clip.itemCount) {
                val uri = clip.getItemAt(i).uri ?: continue
                if (copyOne(uri)) count++
            }
        } else {
            val uri = data.data
            if (uri != null && copyOne(uri)) count++
        }

        if (count > 0) {
            Toast.makeText(this, getString(R.string.mods_imported_toast, count), Toast.LENGTH_LONG).show()
            ConsoleLog.add(getString(R.string.mods_imported_console, count))
        } else {
            Toast.makeText(this, getString(R.string.mods_import_none), Toast.LENGTH_SHORT).show()
        }
        load()
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

    // ---------------------------------------------------------- Modrinth
    /** Hoja Modrinth tipo launcher: filtros + scroll infinito + descarga. */
    private fun showModrinthSheet() {
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_modrinth, null)
        val etSearch = v.findViewById<EditText>(R.id.etSearch)
        val tvStatus = v.findViewById<TextView>(R.id.tvSearchStatus)
        val lvResults = v.findViewById<ListView>(R.id.lvResults)

        val btnSearch = v.findViewById<Button>(R.id.btnSearch)
        val chipEnvAll = v.findViewById<Button>(R.id.chipEnvAll)
        val chipEnvServer = v.findViewById<Button>(R.id.chipEnvServer)
        val chipEnvClient = v.findViewById<Button>(R.id.chipEnvClient)
        val chipEnvBoth = v.findViewById<Button>(R.id.chipEnvBoth)
        val chipSortRelevance = v.findViewById<Button>(R.id.chipSortRelevance)
        val chipSortDownloads = v.findViewById<Button>(R.id.chipSortDownloads)
        val chipSortNew = v.findViewById<Button>(R.id.chipSortNew)
        val chipSortUpdated = v.findViewById<Button>(R.id.chipSortUpdated)

        val dialog = AlertDialog.Builder(this).setView(v).create()

        // Estado de la busqueda
        val results = ArrayList<ModrinthApi.SearchResult>()
        val downloading = HashSet<String>()
        val resAdapter = ResultAdapter(results, downloading)
        lvResults.adapter = resAdapter
        tvStatusGlobal = tvStatus

        var env = ModrinthApi.Env.ALL
        var sort = ModrinthApi.Sort.RELEVANCE
        var loading = false
        var exhausted = false   // true cuando ya no hay mas paginas

        fun styleEnv() {
            styleChip(chipEnvAll, env == ModrinthApi.Env.ALL)
            styleChip(chipEnvServer, env == ModrinthApi.Env.SERVER)
            styleChip(chipEnvClient, env == ModrinthApi.Env.CLIENT)
            styleChip(chipEnvBoth, env == ModrinthApi.Env.BOTH)
        }
        fun styleSort() {
            styleChip(chipSortRelevance, sort == ModrinthApi.Sort.RELEVANCE)
            styleChip(chipSortDownloads, sort == ModrinthApi.Sort.DOWNLOADS)
            styleChip(chipSortNew, sort == ModrinthApi.Sort.NEWEST)
            styleChip(chipSortUpdated, sort == ModrinthApi.Sort.UPDATED)
        }

        /** Carga una pagina y la anade a la lista (o reinicia si reset=true). */
        fun loadPage(reset: Boolean) {
            if (loading) return
            if (reset) {
                exhausted = false
                if (results.isNotEmpty()) { results.clear(); resAdapter.notifyDataSetChanged() }
            }
            if (exhausted) return
            loading = true
            val q = etSearch.text?.toString()?.trim() ?: ""
            val offset = if (reset) 0 else results.size
            tvStatus.text = if (q.isEmpty()) getString(R.string.modrinth_loading_mods)
                else getString(R.string.modrinth_searching, q)
            Thread({
                try {
                    val r = ModrinthApi.search(q, "1.21.1", env, sort, 20, offset)
                    ui.post {
                        loading = false
                        if (r.size < 20) exhausted = true
                        // Quitar duplicados (por si Modrinth devuelve solapes)
                        val known = results.map { it.slug }.toHashSet()
                        for (hit in r) if (known.add(hit.slug)) results.add(hit)
                        resAdapter.notifyDataSetChanged()
                        tvStatus.text = when {
                            results.isEmpty() -> getString(R.string.modrinth_no_results)
                            exhausted -> getString(R.string.modrinth_results_end, results.size)
                            else -> getString(R.string.modrinth_results_more, results.size)
                        }
                    }
                } catch (t: Throwable) {
                    loading = false
                    ui.post { tvStatus.text = getString(R.string.modrinth_network_error, t.message) }
                }
            }, "modrinth-search").start()
        }

        // Scroll infinito: al llegar al final, pedir la siguiente pagina
        lvResults.setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
            override fun onScroll(view: android.widget.AbsListView?, firstVisible: Int, visibleCount: Int, total: Int) {
                if (!loading && !exhausted && total > 0 && firstVisible + visibleCount >= total - 3) {
                    loadPage(false)
                }
            }
            override fun onScrollStateChanged(view: android.widget.AbsListView?, state: Int) {}
        })

        chipEnvAll.setOnClickListener { env = ModrinthApi.Env.ALL; styleEnv(); loadPage(true) }
        chipEnvServer.setOnClickListener { env = ModrinthApi.Env.SERVER; styleEnv(); loadPage(true) }
        chipEnvClient.setOnClickListener { env = ModrinthApi.Env.CLIENT; styleEnv(); loadPage(true) }
        chipEnvBoth.setOnClickListener { env = ModrinthApi.Env.BOTH; styleEnv(); loadPage(true) }
        chipSortRelevance.setOnClickListener { sort = ModrinthApi.Sort.RELEVANCE; styleSort(); loadPage(true) }
        chipSortDownloads.setOnClickListener { sort = ModrinthApi.Sort.DOWNLOADS; styleSort(); loadPage(true) }
        chipSortNew.setOnClickListener { sort = ModrinthApi.Sort.NEWEST; styleSort(); loadPage(true) }
        chipSortUpdated.setOnClickListener { sort = ModrinthApi.Sort.UPDATED; styleSort(); loadPage(true) }

        fun doSearch() = loadPage(true)
        btnSearch.setOnClickListener { doSearch() }
        etSearch.setOnEditorActionListener { _, _, _ -> doSearch(); true }

        lvResults.setOnItemClickListener { _, _, pos, _ ->
            val hit = results.getOrNull(pos) ?: return@setOnItemClickListener
            downloadFromModrinth(hit, downloading, resAdapter, tvStatus)
        }

        styleEnv()
        styleSort()
        loadPage(true)
        dialog.show()
    }

    /**
     * Descarga el jar del mod a mods/. [downloading] evita doble descarga y
     * permite al adapter mostrar "..." en el boton mientras baja.
     */
    private fun downloadFromModrinth(
        hit: ModrinthApi.SearchResult,
        downloading: MutableSet<String>,
        resAdapter: ResultAdapter,
        tvStatus: TextView
    ) {
        if (!downloading.add(hit.slug)) return
        resAdapter.notifyDataSetChanged()
        tvStatus.text = getString(R.string.modrinth_downloading, hit.title)
        Thread({
            try {
                val vf = ModrinthApi.latestFile(hit.slug, "1.21.1")
                    ?: throw RuntimeException(getString(R.string.cfg_no_version))
                val dir = ModStore.modsDir(this)
                val dest = File(dir, vf.filename)
                ModrinthApi.download(vf.url, dest)
                val iconPath = ModrinthApi.downloadIcon(hit.iconUrl, ModStore.iconCacheDir(this), hit.slug)
                ModStore.rememberMeta(
                    this, dest.name, "modrinth", hit.slug,
                    hit.title, vf.filename.removeSuffix(".jar"), iconPath
                )
                ui.post {
                    downloading.remove(hit.slug)
                    resAdapter.notifyDataSetChanged()
                    tvStatus.text = getString(R.string.modrinth_installed_status, vf.filename)
                    Toast.makeText(this, getString(R.string.modrinth_installed_toast, hit.title), Toast.LENGTH_SHORT).show()
                    ConsoleLog.add(
                        getString(
                            R.string.modrinth_dl_ok_console,
                            vf.filename,
                            "%.1f".format(vf.size / 1048576.0)
                        )
                    )
                    load()
                }
            } catch (t: Throwable) {
                ui.post {
                    downloading.remove(hit.slug)
                    resAdapter.notifyDataSetChanged()
                    tvStatus.text = getString(R.string.modrinth_error, t.message)
                    Toast.makeText(this, getString(R.string.modrinth_fail_toast, t.message), Toast.LENGTH_LONG).show()
                }
            }
        }, "modrinth-dl").start()
    }

    private inner class ResultAdapter(
        private val data: List<ModrinthApi.SearchResult>,
        private val downloading: Set<String>
    ) : BaseAdapter() {
        override fun getCount() = data.size
        override fun getItem(pos: Int) = data[pos]
        override fun getItemId(pos: Int) = pos.toLong()
        override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView
                ?: LayoutInflater.from(parent.context).inflate(R.layout.item_modrinth_result, parent, false)
            val hit = getItem(pos)
            v.findViewById<TextView>(R.id.tvResultTitle).text = hit.title
            val envTag = ModrinthApi.envLabel(hit)
            v.findViewById<TextView>(R.id.tvResultMeta).text =
                getString(R.string.modrinth_result_meta, hit.downloads / 1_000_000.0, envTag, hit.description)
            val iv = v.findViewById<ImageView>(R.id.ivResultIcon)
            loadIcon(hit, iv)
            // Click explicito en el boton (el click en la fila lo maneja el ListView)
            val btn = v.findViewById<Button>(R.id.btnDownload)
            val busy = downloading.contains(hit.slug)
            btn.text = if (busy) "..." else getString(R.string.modrinth_download)
            btn.isEnabled = !busy
            btn.setOnClickListener {
                val lv = parent as? ListView
                val pos2 = lv?.getPositionForView(v) ?: pos
                val hit2 = data.getOrNull(pos2) ?: hit
                downloadFromModrinth(hit2, downloading as MutableSet<String>, this@ResultAdapter, tvStatusGlobal ?: return@setOnClickListener)
            }
            return v
        }
    }

    /** Estado temporal para que el boton Bajar muestre progreso desde el adapter. */
    private var tvStatusGlobal: TextView? = null

    /** Carga el icono desde cache (o lo baja) sin bloquear el UI. */
    private fun loadIcon(hit: ModrinthApi.SearchResult, iv: ImageView) {
        iv.setImageResource(R.drawable.bg_icon_box)
        Thread({
            val p = try {
                ModrinthApi.downloadIcon(hit.iconUrl, ModStore.iconCacheDir(this), hit.slug)
            } catch (t: Throwable) { null }
            if (p != null) {
                val bmp = BitmapFactory.decodeFile(p)
                if (bmp != null) ui.post { iv.setImageBitmap(bmp) }
            }
        }, "icon-dl").start()
    }

    // ---------------------------------------------------------- adapter
    private inner class ModAdapter : BaseAdapter() {
        var data: List<ModStore.Mod> = emptyList()
        override fun getCount() = data.size
        override fun getItem(pos: Int) = data[pos]
        override fun getItemId(pos: Int) = pos.toLong()

        override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView
                ?: LayoutInflater.from(parent.context).inflate(R.layout.item_mod, parent, false)
            val m = getItem(pos)

            val iv = v.findViewById<ImageView>(R.id.ivModIcon)
            if (m.iconPath != null) {
                val bmp = BitmapFactory.decodeFile(m.iconPath)
                if (bmp != null) iv.setImageBitmap(bmp) else iv.setImageResource(R.drawable.bg_icon_box)
            } else {
                iv.setImageResource(R.drawable.bg_icon_box)
            }

            v.findViewById<TextView>(R.id.tvModName).text = m.title

            val df = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
            val src = if (m.source == "modrinth") "Modrinth" else getString(R.string.mod_source_imported)
            val ver = if (m.version.isNotEmpty()) " - ${m.version}" else ""
            val state = if (m.enabled) "" else " - OFF"
            v.findViewById<TextView>(R.id.tvModMeta).text =
                "$src$ver - %.1f MB - ${df.format(Date(m.lastModifiedMs))}$state".format(m.sizeMb)

            val sw = v.findViewById<Switch>(R.id.swModEnabled)
            sw.setOnCheckedChangeListener(null)
            sw.isChecked = m.enabled
            sw.setOnCheckedChangeListener { _, checked ->
                val ok = ModStore.setEnabled(this@ModManagerActivity, m, checked)
                if (!ok) {
                    sw.isChecked = !checked
                    Toast.makeText(this@ModManagerActivity, getString(R.string.mods_toggle_failed), Toast.LENGTH_SHORT).show()
                } else {
                    ConsoleLog.add(
                        if (checked) getString(R.string.mod_enabled_console, m.title)
                        else getString(R.string.mod_disabled_console, m.title)
                    )
                    load()
                }
            }
            return v
        }
    }
}
