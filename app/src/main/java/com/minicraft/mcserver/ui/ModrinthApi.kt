package com.minicraft.mcserver.ui

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Cliente minimo de la API v2 de Modrinth: busqueda con paginacion, filtros de
 * entorno (servidor/cliente/ambos), orden (relevancia/descargas/recientes) y
 * descarga del .jar / icono. Sin dependencias externas (HttpURLConnection).
 *
 * Facets verificados contra la API real:
 *   ["versions:1.21.1"], ["categories:fabric"], ["server_side:required"],
 *   ["client_side:required"], ["project_type:mod"]
 * El endpoint search acepta offset+limit para paginar (total_hits ~22k).
 */
object ModrinthApi {

    private const val BASE = "https://api.modrinth.com/v2"
    private const val UA = "MinicraftApp/1.0 (Android Minecraft server manager)"

    data class SearchResult(
        val slug: String,
        val title: String,
        val description: String,
        val iconUrl: String?,
        val downloads: Long,
        val serverSide: String,   // required | optional | unsupported
        val clientSide: String
    )

    data class VersionFile(val url: String, val filename: String, val size: Long)

    /** Ordenes soportados por /search (parametro index). */
    object Sort {
        const val RELEVANCE = "relevance"
        const val DOWNLOADS = "downloads"
        const val NEWEST = "newness"
        const val UPDATED = "updated"
    }

    /** Filtros de entorno como facets (probados: server_side/client_side required). */
    object Env {
        const val ALL = "todos"
        const val SERVER = "servidor"    // server_side != unsupported
        const val CLIENT = "cliente"     // client_side != unsupported
        const val BOTH = "ambos"         // util en server: client_side optional
    }

    private fun get(path: String): String {
        val conn = URL(BASE + path).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", UA)
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        try {
            if (conn.responseCode !in 200..299) {
                throw RuntimeException("HTTP " + conn.responseCode + " en " + path)
            }
            return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /**
     * Busqueda paginada.
     * @param env uno de [Env]; usa facets server_side/client_side.
     */
    fun search(
        query: String,
        mcVersion: String,
        env: String = Env.ALL,
        sort: String = Sort.RELEVANCE,
        limit: Int = 20,
        offset: Int = 0
    ): List<SearchResult> {
        val facets = JSONArray()
        facets.put(JSONArray().put("versions:$mcVersion"))
        facets.put(JSONArray().put("categories:fabric"))
        facets.put(JSONArray().put("project_type:mod"))
        when (env) {
            Env.SERVER -> facets.put(JSONArray().put("server_side:required"))
            Env.CLIENT -> facets.put(JSONArray().put("client_side:required"))
            Env.BOTH -> {
                // Util en servidor: funciona en ambos lados
                facets.put(JSONArray().put("server_side:required"))
                facets.put(JSONArray().put("client_side:optional"))
            }
        }
        var path = "/search?limit=$limit&offset=$offset&index=$sort&facets=${enc(facets.toString())}"
        if (query.isNotEmpty()) path += "&query=${enc(query)}"
        val arr = JSONObject(get(path)).getJSONArray("hits")
        val out = ArrayList<SearchResult>()
        for (i in 0 until arr.length()) {
            val h = arr.getJSONObject(i)
            out.add(
                SearchResult(
                    slug = h.optString("slug"),
                    title = h.optString("title", h.optString("slug")),
                    description = h.optString("description"),
                    iconUrl = if (h.isNull("icon_url")) null else h.optString("icon_url"),
                    downloads = h.optLong("downloads"),
                    serverSide = h.optString("server_side", "unknown"),
                    clientSide = h.optString("client_side", "unknown")
                )
            )
        }
        return out
    }

    /** Etiqueta legible del entorno de un resultado. */
    fun envLabel(r: SearchResult): String {
        val s = r.serverSide != "unsupported"
        val c = r.clientSide != "unsupported"
        return when {
            s && c -> com.minicraft.mcserver.Text.s(com.minicraft.mcserver.R.string.env_both)
            s -> com.minicraft.mcserver.Text.s(com.minicraft.mcserver.R.string.env_server)
            c -> com.minicraft.mcserver.Text.s(com.minicraft.mcserver.R.string.env_client)
            else -> "?"
        }
    }

    /** Ultimo .jar compatible (fabric + mcVersion); prefiere el archivo "primary". */
    fun latestFile(slug: String, mcVersion: String): VersionFile? {
        val gv = """["${mcVersion}"]"""
        val ld = """["fabric"]"""
        val arr = JSONArray(
            get("/project/${enc(slug)}/version?game_versions=${enc(gv)}&loaders=${enc(ld)}")
        )
        if (arr.length() == 0) return null
        val files = arr.getJSONObject(0).getJSONArray("files")
        for (i in 0 until files.length()) {
            val f = files.getJSONObject(i)
            if (f.optBoolean("primary")) {
                return VersionFile(f.getString("url"), f.getString("filename"), f.optLong("size"))
            }
        }
        if (files.length() > 0) {
            val f = files.getJSONObject(0)
            return VersionFile(f.getString("url"), f.getString("filename"), f.optLong("size"))
        }
        return null
    }

    /** Descarga [url] a [dest]. */
    fun download(url: String, dest: File): File {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", UA)
        conn.connectTimeout = 15000
        conn.readTimeout = 60000
        try {
            if (conn.responseCode !in 200..299) {
                throw RuntimeException("HTTP " + conn.responseCode + " al descargar")
            }
            conn.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
            return dest
        } finally {
            conn.disconnect()
        }
    }

    /** Descarga el icono a cache (si no esta) y devuelve el path o null. */
    fun downloadIcon(url: String?, cacheDir: File, key: String): String? {
        if (url.isNullOrBlank()) return null
        val safe = key.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dest = File(cacheDir, "$safe.png")
        if (dest.isFile && dest.length() > 0) return dest.absolutePath
        return try {
            download(url, dest)
            dest.absolutePath
        } catch (t: Throwable) {
            null
        }
    }
}
