package com.minicraft.mcserver.ui

import android.content.Context
import com.minicraft.mcserver.server.ServerProvisioner
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Estado de la pestana Mods.
 *
 * - Lista los .jar de mods/ del servidor Fabric activo.
 * - Activar/desactivar usa la convencion estandar de los loaders: los jars con
 *   sufijo ".jar.disabled" se ignoran al arrancar. Solo renombra archivos.
 * - Guarda metadata de origen (modrinth/importado, titulo, version, icono) en
 *   files/mods-meta.json para mostrarla en la lista.
 */
object ModStore {

    data class Mod(
        val file: File,
        val title: String,
        val fileName: String,
        val sizeMb: Double,
        val lastModifiedMs: Long,
        val enabled: Boolean,
        val source: String,      // "modrinth" | "importado"
        val slug: String,
        val version: String,
        val iconPath: String?
    )

    private fun metaFile(ctx: Context) = File(ctx.filesDir, "mods-meta.json")

    /** Directorio mods/ del servidor Fabric 1.21.1 (lo crea si falta). */
    fun modsDir(ctx: Context): File {
        val d = File(
            ServerProvisioner.dirFor(ctx, ServerProvisioner.ServerType.FABRIC, ServerProvisioner.SUPPORTED[0]),
            "mods"
        )
        if (!d.isDirectory) d.mkdirs()
        return d
    }

    private fun loadMeta(ctx: Context): JSONObject = try {
        JSONObject(metaFile(ctx).takeIf { it.isFile }?.readText() ?: "{}")
    } catch (t: Throwable) {
        JSONObject()
    }

    private fun saveMeta(ctx: Context, meta: JSONObject) {
        metaFile(ctx).writeText(meta.toString(2))
    }

    /** Lista los mods con su metadata. */
    fun list(ctx: Context): List<Mod> {
        val dir = modsDir(ctx)
        val meta = loadMeta(ctx)
        val jars = dir.listFiles { f ->
            f.isFile && (f.name.endsWith(".jar") || f.name.endsWith(".jar.disabled"))
        }?.sortedBy { it.name.lowercase(Locale.getDefault()) } ?: emptyList()
        return jars.map { f ->
            val enabled = !f.name.endsWith(".disabled")
            val base = f.name.removeSuffix(".disabled").removeSuffix(".jar")
            val m = meta.optJSONObject(f.name) ?: JSONObject()
            Mod(
                file = f,
                title = m.optString("title", base),
                fileName = f.name,
                sizeMb = f.length() / 1048576.0,
                lastModifiedMs = f.lastModified(),
                enabled = enabled,
                source = m.optString("source", "importado"),
                slug = m.optString("slug", ""),
                version = m.optString("version", ""),
                iconPath = if (m.has("iconPath") && !m.isNull("iconPath")) m.optString("iconPath") else null
            )
        }
    }

    /** Activa/desactiva renombrando a .disabled. Devuelve true si se logro. */
    fun setEnabled(ctx: Context, mod: Mod, enabled: Boolean): Boolean {
        val target = if (enabled) {
            File(mod.file.parentFile, mod.file.name.removeSuffix(".disabled"))
        } else {
            File(mod.file.parentFile, mod.file.name + ".disabled")
        }
        if (target.exists()) return false
        val ok = mod.file.renameTo(target)
        if (ok) {
            // Mover la metadata al nuevo nombre de archivo
            val meta = loadMeta(ctx)
            val old = meta.optJSONObject(mod.file.name)
            if (old != null) {
                meta.remove(mod.file.name)
                meta.put(target.name, old)
                saveMeta(ctx, meta)
            }
        }
        return ok
    }

    /** Guarda/actualiza la metadata de un mod. */
    fun rememberMeta(
        ctx: Context, fileName: String, source: String,
        slug: String, title: String, version: String, iconPath: String?
    ) {
        val meta = loadMeta(ctx)
        val o = JSONObject()
        o.put("source", source)
        o.put("slug", slug)
        o.put("title", title)
        o.put("version", version)
        if (iconPath != null) o.put("iconPath", iconPath)
        meta.put(fileName, o)
        saveMeta(ctx, meta)
    }

    /** Borra la metadata al eliminar un mod. */
    fun forgetMeta(ctx: Context, fileName: String) {
        val meta = loadMeta(ctx)
        meta.remove(fileName)
        saveMeta(ctx, meta)
    }

    fun iconCacheDir(ctx: Context): File =
        File(ctx.filesDir, "modicons").apply { if (!isDirectory) mkdirs() }
}
