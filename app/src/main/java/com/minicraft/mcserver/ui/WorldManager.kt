package com.minicraft.mcserver.ui

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.minicraft.mcserver.R
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Pestana "Mundos": gestor de mundos del servidor (lista/importar/exportar/borrar).
 *
 * MISMA logica que ya existia (WorldManager original): escaneo por level.dat,
 * importacion por ACTION_OPEN_DOCUMENT, exportacion por ACTION_CREATE_DOCUMENT,
 * borrado con confirmacion. Solo cambia la presentacion (estilo prototipo worlds.html):
 * tarjeta de mundo activo + filas con icono, meta y botones por fila.
 */
class WorldManager : NavActivity() {

    override val titleRes = R.string.nav_worlds
    override val subtitleRes = R.string.subtitle_worlds
    override val tabId = R.id.tabWorlds

    private lateinit var list: ListView
    private lateinit var btnImport: Button
    private lateinit var adapter: WorldAdapter

    private val REQ_IMPORT = 101
    private val REQ_EXPORT = 102
    private var exportTarget: WorldEntry? = null

    /** Mundos: carpeta con level.dat; [active] = es el level-name del server. */
    data class WorldEntry(val name: String, val dir: File, val active: Boolean = false)

    companion object {
        const val TAG = "WorldManager"

        /**
         * Raiz del servidor ACTIVO (el ultimo lanzado; ver LastServerStore).
         * Los mundos de un servidor dedicado viven en la RAIZ del server
         * (world/, Nuevo mundo2/, ...), NO en un subdirectorio worlds/.
         */
        fun activeServerDir(ctx: android.content.Context): File {
            val last = LastServerStore.load(ctx)
            return if (last != null) {
                File(last.dir)
            } else {
                com.minicraft.mcserver.server.ServerProvisioner.dirFor(
                    ctx,
                    com.minicraft.mcserver.server.ServerProvisioner.Flavor.FABRIC,
                    com.minicraft.mcserver.server.ServerProvisioner.SUPPORTED[0]
                )
            }
        }

        /**
         * Mundo activo: el que dice `level-name` en server.properties del
         * servidor activo. Los demas directorios con level.dat son mundos
         * alternativos (inactivos).
         */
        fun activeLevelName(ctx: android.content.Context): String {
            val f = File(activeServerDir(ctx), "server.properties")
            if (f.isFile) {
                try {
                    for (line in f.readLines()) {
                        if (line.startsWith("level-name=")) {
                            return line.substringAfter('=').trim().removeSurrounding("\"")
                        }
                    }
                } catch (_: Throwable) {}
            }
            return "world"
        }

        /**
         * Escanea mundos validos en la raiz del servidor activo (carpetas con
         * level.dat). El activo es el que coincide con level-name.
         */
        fun scan(ctx: android.content.Context): List<WorldEntry> {
            val base = activeServerDir(ctx)
            if (!base.isDirectory) return emptyList()
            val active = activeLevelName(ctx)
            val out = ArrayList<WorldEntry>()
            val children = base.listFiles() ?: return out
            for (c in children) {
                if (c.isDirectory && File(c, "level.dat").isFile) {
                    out.add(WorldEntry(c.name, c, c.name == active))
                }
            }
            out.sortWith(compareByDescending<WorldEntry> { it.active }.thenBy { it.name })
            return out
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.MinicraftTheme)
        setContentView(R.layout.activity_world_manager)
        setupChrome()

        list = findViewById(R.id.lvWorlds)
        btnImport = findViewById(R.id.btnImportWorld)

        adapter = WorldAdapter(scan(this))
        list.adapter = adapter
        list.emptyView = findViewById(R.id.tvEmpty)

        // El boton "Crear" del prototipo abre el mismo selector de importacion
        // (crear un mundo de cero requiere arrancar el server: funcionalidad ya
        // existente via consola; aqui no se inventa nada nuevo).
        btnImport.setOnClickListener { doImport() }

        refreshHeader()
    }

    private fun refreshHeader() {
        val worlds = scan(this)
        adapter.data = worlds
        adapter.notifyDataSetChanged()
        findViewById<TextView>(R.id.tvWorldCount).text = getString(R.string.worlds_count, worlds.size)

        // Mundo activo: el que coincide con level-name del server activo.
        val active = worlds.firstOrNull { it.active }
        findViewById<TextView>(R.id.tvActiveWorld).text =
            active?.name ?: getString(R.string.worlds_empty_active)
        findViewById<TextView>(R.id.tvActiveMeta).text =
            active?.let { worldMeta(it) } ?: ""
    }

    private fun worldMeta(w: WorldEntry): String {
        val mb = w.dir.walkTopDown().filter { it.isFile }
            .map { it.length() }.sum() / 1048576.0
        val last = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            .format(java.util.Date(w.dir.lastModified()))
        return getString(R.string.world_meta, mb, last)
    }

    // ---------------------------------------------------------- acciones (idas y venidas ya existentes)
    private fun doImport() {
        try {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed"))
            }
            startActivityForResult(i, REQ_IMPORT)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.msg_no_picker, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    private fun doExport(w: WorldEntry) {
        exportTarget = w
        try {
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_TITLE, "${w.name}.zip")
            }
            startActivityForResult(i, REQ_EXPORT)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.msg_no_picker, e.message), Toast.LENGTH_SHORT).show()
        }
    }

    private fun doDelete(w: WorldEntry) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_delete_world_title))
            .setMessage(getString(R.string.dialog_delete_world_message, w.name))
            .setPositiveButton(getString(R.string.dialog_delete_confirm)) { _, _ ->
                if (w.dir.deleteRecursively()) {
                    Toast.makeText(this, getString(R.string.msg_world_deleted, w.name), Toast.LENGTH_SHORT).show()
                    ConsoleLog.add(getString(R.string.msg_world_deleted, w.name))
                    refreshHeader()
                } else {
                    Toast.makeText(this, getString(R.string.msg_delete_failed), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(getString(R.string.worlds_cancel), null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_IMPORT -> {
                if (resultCode == RESULT_OK && data != null) {
                    val uri = data.data ?: return
                    val name = queryName(uri) ?: "world_${System.currentTimeMillis()}"
                    // Importar a la RAIZ del servidor activo: es donde el server
                    // busca los mundos (level-name en server.properties).
                    val dest = File(activeServerDir(this), name)
                    importZip(uri, dest)
                    if (File(dest, "level.dat").isFile) {
                        Toast.makeText(this, getString(R.string.msg_world_imported, name), Toast.LENGTH_LONG).show()
                        ConsoleLog.add(getString(R.string.msg_world_imported, name))
                        refreshHeader()
                    }
                }
            }
            REQ_EXPORT -> {
                if (resultCode == RESULT_OK && data != null) {
                    val uri = data.data ?: return
                    val sel = exportTarget ?: return
                    exportZip(sel, uri)
                    Toast.makeText(this, getString(R.string.msg_world_exported, sel.name), Toast.LENGTH_LONG).show()
                    ConsoleLog.add(getString(R.string.msg_world_exported, sel.name))
                }
            }
        }
    }

    private fun queryName(uri: Uri): String? = try {
        var name: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && idx >= 0) name = c.getString(idx)
        }
        name?.removeSuffix(".zip")
    } catch (e: Exception) {
        null
    }

    /**
     * Descomprime un ZIP a [dest]. El ZIP puede tener un prefijo de carpeta
     * (p.ej. "world/level.dat") que se ajusta a la raiz de dest.
     */
    private fun importZip(uri: Uri, dest: File) {
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { z ->
                    while (true) {
                        val e = z.nextEntry ?: break
                        val parts = e.name.split("/")
                        if (parts.size < 2) { z.closeEntry(); continue }
                        val relPath = parts.drop(1).joinToString("/")
                        val out = File(dest, relPath)
                        if (e.isDirectory) {
                            out.mkdirs()
                        } else {
                            out.parentFile?.mkdirs()
                            out.outputStream().use { z.copyTo(it) }
                        }
                        z.closeEntry()
                    }
                    if (!File(dest, "level.dat").isFile) {
                        dest.deleteRecursively()
                        Toast.makeText(this, getString(R.string.msg_zip_invalid), Toast.LENGTH_LONG).show()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "importZip falló: ${e.message}")
            dest.deleteRecursively()
        }
    }

    /** Comprime [world] a ZIP en [destUri]. */
    private fun exportZip(world: WorldEntry, destUri: Uri) {
        try {
            contentResolver.openOutputStream(destUri)?.use { output ->
                ZipOutputStream(output).use { z ->
                    val files = ArrayList<File>()
                    collectFiles(world.dir, files)
                    for (f in files) {
                        val entry = world.name + "/" + f.relativeTo(world.dir).path
                        z.putNextEntry(ZipEntry(entry))
                        f.inputStream().use { z.write(it.readBytes()) }
                        z.closeEntry()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "exportZip falló: ${e.message}")
        }
    }

    private fun collectFiles(dir: File, out: ArrayList<File>) {
        val children = dir.listFiles() ?: return
        for (c in children) {
            if (c.isDirectory) collectFiles(c, out)
            else out.add(c)
        }
    }

    // ------------------------------------------------------------------ adapter
    inner class WorldAdapter(var data: List<WorldEntry>) : BaseAdapter() {
        override fun getCount() = data.size
        override fun getItem(pos: Int): WorldEntry = data[pos]
        override fun getItemId(pos: Int) = pos.toLong()

        override fun getView(pos: Int, convertView: View?, parent: ViewGroup): View {
            val w = getItem(pos)
            val v = convertView
                ?: LayoutInflater.from(parent.context).inflate(R.layout.item_world, parent, false)
            v.findViewById<TextView>(R.id.tvWorldName).text = w.name
            v.findViewById<TextView>(R.id.tvWorldMeta).text =
                (if (w.active) getString(R.string.world_active_prefix) else "") + worldMeta(w)
            v.findViewById<Button>(R.id.btnWorldExport)
                .setText(android.R.drawable.ic_menu_save)  // placeholder icono exportar
            v.findViewById<Button>(R.id.btnWorldExport).setOnClickListener { doExport(w) }
            v.findViewById<Button>(R.id.btnWorldDelete).setOnClickListener { doDelete(w) }
            v.setOnClickListener {
                Toast.makeText(parent.context, w.name, Toast.LENGTH_SHORT).show()
            }
            return v
        }
    }
}
