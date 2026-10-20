package com.minicraft.mcserver.ui

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Memoria del ULTIMO servidor lanzado.
 *
 * La app debe recordar que version y tipo de servidor se ejecuto la ultima vez y
 * restaurar esa seleccion al volver. Se guarda en files/last-server.json:
 *   { "serverType": "fabric", "version": "1.21.1", "dir": "<ruta absoluta>" }
 *
 * [dir] permite tambien recordar servidores cuyo nombre no sigue el patron
 * fabric-<version> (p.ej. una carpeta importada).
 */
object LastServerStore {

    data class LastServer(
        val serverType: String, // "fabric" | "vanilla"
        val version: String,    // p.ej. "1.21.1"
        val dir: String         // ruta absoluta del directorio del server
    )

    private fun file(ctx: Context): File = File(ctx.filesDir, "last-server.json")

    fun save(ctx: Context, serverType: String, version: String, dir: String) {
        try {
            val j = JSONObject()
            j.put("serverType", serverType)
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
            // "flavor" es la clave que usaban las versiones anteriores.
            val serverType = j.optString("serverType", j.optString("flavor", "fabric"))
            val version = j.optString("version", "")
            if (dir.isEmpty() || !File(dir).isDirectory) null
            else LastServer(serverType, version, dir)
        } catch (_: Throwable) {
            null
        }
    }

    /** Borra el registro (al desinstalar un servidor). */
    fun clear(ctx: Context) {
        file(ctx).delete()
    }
}
