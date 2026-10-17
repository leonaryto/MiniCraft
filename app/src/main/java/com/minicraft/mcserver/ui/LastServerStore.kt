package com.minicraft.mcserver.ui

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Memoria del ULTIMO servidor lanzado.
 *
 * La app debe recordar que version/flavor se ejecuto la ultima vez y restaurar
 * esa seleccion al volver. Se guarda en files/last-server.json:
 *   { "flavor": "fabric", "version": "1.21.1", "dir": "<ruta absoluta>" }
 *
 * [dir] permite tambien recordar servidores cuyo nombre no sigue el patron
 * fabric-<version> (p.ej. una carpeta importada).
 */
object LastServerStore {

    data class LastServer(
        val flavor: String,   // "fabric" | "vanilla"
        val version: String,  // p.ej. "1.21.1"
        val dir: String       // ruta absoluta del directorio del server
    )

    private fun file(ctx: Context): File = File(ctx.filesDir, "last-server.json")

    fun save(ctx: Context, flavor: String, version: String, dir: String) {
        try {
            val j = JSONObject()
            j.put("flavor", flavor)
            j.put("version", version)
            j.put("dir", dir)
            file(ctx).writeText(j.toString(2))
        } catch (_: Throwable) {}
    }

    /** Ultimo server lanzado, o null si nunca se lanzo uno (o ya no existe). */
    fun load(ctx: Context): LastServer? {
        val f = file(ctx)
        if (!f.isFile) return null
        return try {
            val j = JSONObject(f.readText())
            val dir = j.optString("dir", "")
            val flavor = j.optString("flavor", "fabric")
            val version = j.optString("version", "")
            if (dir.isEmpty() || !File(dir).isDirectory) null
            else LastServer(flavor, version, dir)
        } catch (_: Throwable) {
            null
        }
    }

    /** Borra el registro (al desinstalar un servidor). */
    fun clear(ctx: Context) {
        file(ctx).delete()
    }
}
