package com.minicraft.mcserver.server

import android.content.Context
import android.util.Log
import com.minicraft.mcserver.R
import com.minicraft.mcserver.Text
import com.minicraft.mcserver.runtime.NativeLauncher
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/**
 * Descarga e instala el servidor de Minecraft (vanilla o Fabric).
 *
 * El flujo es el MISMO que se verifico en escritorio, y por
 * eso no hay nada que inventar aqui:
 *
 *   vanilla -> version_manifest_v2.json -> <version>.json -> downloads.server.url -> server.jar
 *   fabric  -> meta.fabricmc.net/v2/versions/loader/<mc>   -> loader (el [0])
 *              meta.fabricmc.net/v2/versions/installer      -> instalador (el [0])
 *              -> java -jar fabric-installer.jar server -mcversion <mc> -loader <l> -dir <dir>
 *
 * Por que no se descargan las librerias a mano: desde 1.18 el `server.jar` de Mojang es un
 * BUNDLER que baja sus propias librerias en el primer arranque. Y el instalador de Fabric
 * baja las suyas y deja `fabric-server-launch.jar`. Ambas necesitan red, asi que la primera
 * ejecucion debe hacerse con conexion.
 *
 * Se usa `org.json` (viene con el SDK de Android, sin dependencias) en lugar de regex: el
 * manifiesto de Mojang son ~277 KB de JSON anidado y las respuestas de Fabric cambian.
 *
 * LIMITACION CONOCIDA: solo se ofrecen versiones que corran con Java 21, porque la unica
 * JRE instalada es OpenJDK 21 (el JDK de Termux). Verificado contra la API de Mojang: las
 * cuatro versiones de abajo piden `javaVersion.majorVersion = 21`. Las reglas son:
 *   >= 1.20.5   -> Java 21   <- las que funcionan aqui
 *   1.17-1.20.4 -> Java 17   <- necesitaria otra JRE
 *   <= 1.16     -> Java 8    <- idem
 */
object ServerProvisioner {

    private const val TAG = "MinicraftProvision"

    const val VERSION_MANIFEST = "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json"
    const val FABRIC_META = "https://meta.fabricmc.net/v2/versions/loader"

    /**
     * El instalador NO viene en `/v2/versions/loader/<mc>`: verificado contra la API,
     * ese endpoint solo trae `loader` e `intermediary`. Vive aqui.
     */
    const val FABRIC_INSTALLER_LIST = "https://meta.fabricmc.net/v2/versions/installer"

    /** Versiones ofrecidas: todas requieren Java 21 (verificado en la API de Mojang). */
    val SUPPORTED = listOf("1.21.1", "1.21", "1.20.6", "1.20.5")

    enum class Flavor(val label: String) { VANILLA("Vanilla"), FABRIC("Fabric") }

    data class Result(val ok: Boolean, val message: String, val launchJar: String?)

    // ---------------------------------------------------------------- rutas
    fun serversRoot(ctx: Context): File = File(ctx.filesDir, "servers")

    /**
     * Un directorio por version+flavor, p.ej. `servers/fabric-1.21.1`. Separarlos evita
     * que cambiar de flavor deje librerias y modulos de Mojang mezclados.
     */
    fun dirFor(ctx: Context, flavor: Flavor, mcVersion: String): File =
        File(serversRoot(ctx), "${flavor.name.lowercase()}-$mcVersion")

    fun isInstalled(ctx: Context, flavor: Flavor, mcVersion: String): Boolean =
        launchJarFor(ctx, flavor, mcVersion) != null

    /** Nombre del jar a ejecutar, o null si no esta instalado. */
    fun launchJarFor(ctx: Context, flavor: Flavor, mcVersion: String): String? {
        val d = dirFor(ctx, flavor, mcVersion)
        return when {
            !d.isDirectory -> null
            flavor == Flavor.FABRIC && File(d, "fabric-server-launch.jar").isFile ->
                "fabric-server-launch.jar"
            flavor == Flavor.VANILLA && File(d, "server.jar").isFile -> "server.jar"
            else -> null
        }
    }

    // ------------------------------------------------------------ descargas
    fun getText(url: String, log: (String) -> Unit): String? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.connect()
        if (conn.responseCode !in 200..299) {
            log("  HTTP ${conn.responseCode} en $url")
            null
        } else {
            conn.inputStream.bufferedReader().use { it.readText() }
        }
    } catch (t: Throwable) {
        log("  ERROR GET $url -> ${t::class.java.simpleName}: ${t.message}")
        null
    }

    /** Descarga [url] a [dest] con progreso aproximado. */
    fun download(url: String, dest: File, log: (String) -> Unit): Boolean = try {
        dest.parentFile?.mkdirs()
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.connect()
        if (conn.responseCode !in 200..299) {
            log("  HTTP ${conn.responseCode} en $url")
            conn.disconnect()
            false
        } else {
            val total = conn.contentLengthLong
            var done = 0L
            var lastStep = -1L
            BufferedInputStream(conn.inputStream).use { inS ->
                FileOutputStream(dest).use { outS ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = inS.read(buf)
                        if (n <= 0) break
                        outS.write(buf, 0, n)
                        done += n
                        // no loguear cada chunk: solo cada 5 %
                        if (total > 0) {
                            val step = done * 100 / total / 5
                            if (step != lastStep) {
                                lastStep = step
                                log("  ${dest.name}: ${done * 100 / total}% " +
                                    "(${done / 1048576}/${total / 1048576} MB)")
                            }
                        }
                    }
                }
            }
            conn.disconnect()
            log("  descargado ${dest.name} (${done / 1048576} MB)")
            true
        }
    } catch (t: Throwable) {
        log("  ERROR descargando $url -> ${t::class.java.simpleName}: ${t.message}")
        false
    }

    /** Primer elemento de un endpoint de Fabric, que devuelve un array JSON. */
    private fun firstObject(url: String, log: (String) -> Unit): JSONObject? {
        val body = getText(url, log) ?: return null
        return try {
            val arr = JSONArray(body)
            if (arr.length() == 0) null else arr.getJSONObject(0)
        } catch (t: Throwable) {
            Log.e(TAG, "respuesta de Fabric ilegible en $url: ${t.message}")
            null
        }
    }

    // -------------------------------------------------------------- vanilla
    private fun installVanilla(ctx: Context, mcVersion: String, log: (String) -> Unit): Result {
        val dir = dirFor(ctx, Flavor.VANILLA, mcVersion)
        dir.mkdirs()

        log("Vanilla $mcVersion:")
        log("  " + Text.s(R.string.inst_read_manifest))
        val manifest = getText(VERSION_MANIFEST, log)
            ?: return Result(false, Text.s(R.string.inst_no_manifest), null)

        val versionJsonUrl = findVersionUrl(manifest, mcVersion)
            ?: return Result(false, Text.s(R.string.inst_version_not_in_manifest, mcVersion), null)
        log("  $mcVersion -> $versionJsonUrl")

        log("  " + Text.s(R.string.inst_dl_version_json))
        val versionJson = getText(versionJsonUrl, log)
            ?: return Result(false, Text.s(R.string.inst_no_version_json, mcVersion), null)

        val serverUrl = try {
            JSONObject(versionJson).getJSONObject("downloads")
                .getJSONObject("server").getString("url")
        } catch (t: Throwable) {
            null
        }
        if (serverUrl == null) {
            return Result(false, Text.s(R.string.inst_no_server_url), null)
        }

        log("  " + Text.s(R.string.inst_dl_server_jar))
        if (!download(serverUrl, File(dir, "server.jar"), log)) {
            return Result(false, Text.s(R.string.inst_fail_server_jar), null)
        }

        writeConfig(dir, log)
        return Result(true, Text.s(R.string.inst_vanilla_ok, mcVersion, dir.name), "server.jar")
    }

    /**
     * Descarga el `server.jar` vanilla de [mcVersion] en [dir] y devuelve el fichero.
     *
     * Para vanilla es el propio servidor. Para fabric es OBLIGATORIO antes de instalar:
     * el instalador de Fabric no lo baja (solo el loader y las librerias) y luego
     * arranca con "Missing game jar at .../server.jar" — verificado en el movil.
     */
    private fun downloadVanillaServerJar(
        dir: File, mcVersion: String, log: (String) -> Unit
    ): File? {
        val jar = File(dir, "server.jar")
        if (jar.isFile && jar.length() > 0) {
            log("  " + Text.s(R.string.inst_jar_already, (jar.length() / 1048576).toInt()))
            return jar
        }
        log("  SERVERJAR $mcVersion")
        val manifest = getText(VERSION_MANIFEST, log)
            ?: run { log("  " + Text.s(R.string.inst_read_manifest_short)); return null }
        val versionJsonUrl = findVersionUrl(manifest, mcVersion)
            ?: run { log("  $mcVersion no esta en el manifiesto"); return null }
        val versionJson = getText(versionJsonUrl, log)
            ?: run { log("  " + Text.s(R.string.inst_no_version_json, mcVersion)); return null }
        val serverUrl = try {
            JSONObject(versionJson).getJSONObject("downloads")
                .getJSONObject("server").getString("url")
        } catch (t: Throwable) {
            null
        }
        if (serverUrl == null) { log("  el JSON no trae server.url"); return null }
        if (!download(serverUrl, jar, log)) return null
        return jar
    }

    /** Busca la URL del JSON de una versión dentro de `version_manifest_v2.json`. */
    private fun findVersionUrl(manifest: String, mcVersion: String): String? {
        return try {
            val versions = JSONObject(manifest).getJSONArray("versions")
            for (i in 0 until versions.length()) {
                val e = versions.getJSONObject(i)
                if (e.optString("id") == mcVersion) {
                    val u = e.optString("url")
                    if (u.isNotEmpty()) return u
                }
            }
            null
        } catch (t: Throwable) {
            Log.e(TAG, "manifiesto ilegible: ${t.message}")
            null
        }
    }

    // --------------------------------------------------------------- fabric
    private fun installFabric(ctx: Context, mcVersion: String, log: (String) -> Unit): Result {
        val dir = dirFor(ctx, Flavor.FABRIC, mcVersion)
        dir.mkdirs()
        val jreHome = File(ctx.filesDir, "jre")

        log("Fabric $mcVersion:")

        // El server.jar vanilla TIENE que estar en el directorio antes de instalar: el
        // instalador de Fabric no lo descarga y luego falla al arrancar.
        log("  " + Text.s(R.string.inst_fabric_step1))
        if (downloadVanillaServerJar(dir, mcVersion, log) == null) {
            return Result(false, Text.s(R.string.inst_no_vanilla_jar, mcVersion), null)
        }

        log("  " + Text.s(R.string.inst_fabric_step2))

        // Loader: la lista va de más nueva a más vieja, así que el [0] es el bueno.
        // Para 1.21.1 da 0.19.5, el mismo loader que se verificó en escritorio.
        val loaderVersion = firstObject("$FABRIC_META/$mcVersion", log)
            ?.optJSONObject("loader")?.optString("version")?.ifEmpty { null }
        if (loaderVersion == null) {
            return Result(false, Text.s(R.string.inst_no_loader, mcVersion), null)
        }
        log("  loader $loaderVersion")

        // Instalador: endpoint aparte, verificado. También el [0].
        val installerUrl = firstObject(FABRIC_INSTALLER_LIST, log)
            ?.optString("url")?.ifEmpty { null }
        if (installerUrl == null) {
            return Result(false, Text.s(R.string.inst_no_installer), null)
        }
        log("  instalador $installerUrl")

        log("  " + Text.s(R.string.inst_fabric_step3))
        val installer = File(dir, "fabric-installer.jar")
        if (!download(installerUrl, installer, log)) {
            return Result(false, Text.s(R.string.inst_fail_installer), null)
        }

        log("  " + Text.s(R.string.inst_fabric_step4))
        val stub = ServerLauncher.stubInNativeLibDir(ctx)
        if (!stub.isFile) return Result(false, Text.s(R.string.inst_no_stub), null)

        val (out, rc) = NativeLauncher.runAndCapture(
            path = stub.absolutePath,
            argv = listOf(
                stub.absolutePath,
                "-Djava.awt.headless=true",
                "-jar", installer.absolutePath,
                "server",
                "-mcversion", mcVersion,
                "-loader", loaderVersion,
                "-dir", dir.absolutePath
            ),
            envp = ServerLauncher.envForInstaller(jreHome, dir),
            cwd = dir.absolutePath
        )
        out.lines().takeLast(8).forEach { log("    | $it") }
        if (rc != 0) return Result(false, Text.s(R.string.inst_installer_rc, rc), null)

        if (!File(dir, "fabric-server-launch.jar").isFile) {
            return Result(false, Text.s(R.string.inst_no_launch_jar), null)
        }
        installer.delete()
        writeConfig(dir, log)
        return Result(true, Text.s(R.string.inst_fabric_ok, mcVersion, dir.name), "fabric-server-launch.jar")
    }

    // -------------------------------------------------------------- comun
    fun install(ctx: Context, flavor: Flavor, mcVersion: String, log: (String) -> Unit): Result =
        when (flavor) {
            Flavor.VANILLA -> installVanilla(ctx, mcVersion, log)
            Flavor.FABRIC -> installFabric(ctx, mcVersion, log)
        }

    /**
     * Escribe `eula.txt` y `server.properties` con lo mínimo para jugar en LAN.
     *
     * - `eula=true`: sin esto el servidor se niega a arrancar. Es una decisión del
     *   USUARIO (aceptar la EULA); el fichero deja el enlace de la licencia arriba.
     * - `online-mode=false`: permite entrar sin cuenta de Mojang, que es lo habitual al
     *   jugar en LAN desde un móvil. Quiera autenticación real, cámbielo a `true`.
     */
    private fun writeConfig(dir: File, log: (String) -> Unit) {
        val eula = File(dir, "eula.txt")
        if (!eula.isFile) {
            eula.writeText(
                "# Al arrancar, el usuario debe aceptar la EULA de Minecraft:\n" +
                "# https://aka.ms/MinecraftEULA\n" +
                "# Poner eula=true declara que la has leido y aceptado.\n" +
                "eula=true\n"
            )
            log("  " + Text.s(R.string.inst_wrote_eula))
        }
        // Si el mundo ya existe no se tocan las propiedades: el usuario pudo configurarlas.
        if (!File(dir, "server.properties").isFile && !File(dir, "world").isDirectory) {
            File(dir, "server.properties").writeText(
                """
                server-ip=
                server-port=25565
                online-mode=false
                level-name=world
                max-players=10
                view-distance=6
                simulation-distance=6
                gamemode=survival
                difficulty=normal
                spawn-protection=0
                enable-command-block=false
                """.trimIndent() + "\n"
            )
            log("  " + Text.s(R.string.inst_wrote_props))
        }
    }

    /** Instalaciones terminadas, para mostrarlas en la UI. */
    fun installedServers(ctx: Context): List<String> =
        serversRoot(ctx).listFiles()?.filter { it.isDirectory }?.mapNotNull { d ->
            when {
                File(d, "fabric-server-launch.jar").isFile -> "Fabric ${d.name.substringAfter('-')}"
                File(d, "server.jar").isFile -> "Vanilla ${d.name.substringAfter('-')}"
                else -> null
            }
        } ?: emptyList()
}