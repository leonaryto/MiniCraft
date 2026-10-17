package com.minicraft.mcserver.server

import android.content.Context
import android.util.Log
import com.minicraft.mcserver.R
import com.minicraft.mcserver.Text
import com.minicraft.mcserver.runtime.NativeLauncher
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Auto-abastecimiento de la JRE (OpenJDK 21 de Termux) en `files/jre`.
 *
 * POR QUE EXISTE: la JRE no viene en el APK (243 MB). Antes se instalaba a
 * mano con adb, asi que borrar los datos de la app la dejaba inservible con
 * "CANNOT LINK EXECUTABLE: libjli.so not found" y no habia forma de
 * recuperarse desde el propio telefono. Ahora `install` y `start` comprueban
 * que exista y, si falta, la descargan y la instalan solos.
 *
 * COMO (verificado analizando los .deb reales en el host, 2026-10-04):
 *  - Cada .deb es un archivo `ar`: cabeceras de 60 bytes por entrada
 *    (nombre 16, tam en 48..58 decimal, relleno de 1 byte a par).
 *    Entradas: debian-binary, control.tar.xz, data.tar.xz.
 *  - `data.tar.xz` descomprime un tar USTAR (magic "ustar", typeflags solo
 *    '0' archivo, '5' dir, '2' symlink — verificado) con prefijo
 *    `./data/data/com.termux/files/usr/`. La JRE vive en
 *    `usr/lib/jvm/java-21-openjdk/` y las libs nativas en `usr/lib/`.
 *  - Los 206 symlinks del tar estan TODOS en `legal/` o `share/doc/`
 *    (licencias): se saltan sin perdida funcional. Ninguno en bin/ ni lib/.
 *  - Los permisos del tar son restrictivos (bin/java 0700, *.so 0600) y no
 *    sirven tal cual: se hace chmod 755 sobre todo el arbol al final.
 *
 * XZ: Android no trae decodificador XZ, asi que se usa org.tukaani:xz
 * (113 KB, sin dependencias) empaquetada en app/libs/xz-1.9.jar.
 */
object JreProvisioner {

    private const val TAG = "MinicraftJre"

    private const val TERMUX_MAIN = "https://packages.termux.dev/apt/termux-main/pool/main"

    /** Prefijo comun de TODO data.tar de Termux. */
    private const val TERMUX_PREFIX = "data/data/com.termux/files/usr/"

    /** Ruta de la JRE dentro del data.tar del openjdk. */
    private const val JDK_SUBDIR = "lib/jvm/java-21-openjdk/"

    /**
     * Los 5 paquetes: nombre del .deb, subruta del repo y (para las 4 libs
     * nativas) de donde -> a donde se copian dentro de files/jre.
     * URLs verificadas contra el indice Packages real (2026-10-04).
     */
    private data class Deb(val file: String, val repoPath: String, val extract: Pair<String, String>?)

    private val PACKAGES = listOf(
        Deb("openjdk-21_21.0.12_aarch64.deb", "o/openjdk-21", null),
        Deb("zlib_1.3.2_aarch64.deb", "z/zlib",
            "$TERMUX_PREFIX${"lib/libz.so.1.3.2"}" to "lib/libz.so.1"),
        Deb("libandroid-shmem_0.7_aarch64.deb", "liba/libandroid-shmem",
            "$TERMUX_PREFIX${"lib/libandroid-shmem.so"}" to "lib/libandroid-shmem.so"),
        Deb("libandroid-spawn_0.3_aarch64.deb", "liba/libandroid-spawn",
            "$TERMUX_PREFIX${"lib/libandroid-spawn.so"}" to "lib/libandroid-spawn.so"),
        Deb("libc++_30_aarch64.deb", "libc/libc++",
            "$TERMUX_PREFIX${"lib/libc++_shared.so"}" to "lib/libc++_shared.so")
    )

    /** Sin estos archivos la JVM no arranca (verificado en el movil). */
    private val JRE_REQUIRED_FILES = listOf(
        "bin/java", "lib/libjli.so", "lib/server/libjvm.so", "lib/modules",
        "lib/libz.so.1", "lib/libandroid-shmem.so",
        "lib/libandroid-spawn.so", "lib/libc++_shared.so"
    )

    // ---------------------------------------------------------------- estado
    fun jreRoot(ctx: Context): File = File(ctx.filesDir, "jre")

    /** La JRE esta completa (todos los archivos criticos presentes). */
    fun isComplete(ctx: Context): Boolean {
        val root = jreRoot(ctx)
        return JRE_REQUIRED_FILES.all { File(root, it).isFile }
    }

    // --------------------------------------------------------- instalacion
    /**
     * Garantiza que la JRE exista; si falta la descarga e instala desde los
     * .deb de Termux. Reanudable por paquete: cada .deb se baja a
     * `files/tmp-debs/` y se conserva hasta el final de la extraccion.
     *
     * @return null si todo bien, o mensaje de error para mostrar al usuario.
     */
    fun ensureInstalled(ctx: Context, progress: (String) -> Unit): String? {
        if (isComplete(ctx)) return null
        val root = jreRoot(ctx)
        val debs = File(ctx.filesDir, "tmp-debs").apply { mkdirs() }

        // Extraccion a medias de una corrida anterior: limpiar y rehacer.
        if (root.exists() && !isComplete(ctx)) {
            progress("  " + Text.s(R.string.jre_incomplete))
            root.deleteRecursively()
        }
        root.mkdirs()

        // 1) descargar los 5 debs (el openjdk pesa ~100 MB)
        var downloaded = 0L
        for (deb in PACKAGES) {
            val dest = File(debs, deb.file)
            if (dest.isFile && dest.length() > 1_000) {
                progress("  " + Text.s(R.string.jre_already_downloaded, deb.file, (dest.length() / 1048576).toInt()))
            } else {
                progress("  " + Text.s(R.string.jre_downloading, deb.file))
                if (!download("$TERMUX_MAIN/${deb.repoPath}/${deb.file}", dest, progress)) {
                    return Text.s(R.string.jre_download_failed, deb.file)
                }
            }
            downloaded += dest.length()
        }
        progress("  " + Text.s(R.string.jre_download_complete, (downloaded / 1048576).toInt()))

        // 2) extraer el arbol del JDK
        progress("  " + Text.s(R.string.jre_extracting))
        var extracted = 0
        findDataTarStream(File(debs, PACKAGES[0].file))?.use { raw ->
            TarReader(raw).use { tar ->
                val prefix = "$TERMUX_PREFIX$JDK_SUBDIR"
                while (true) {
                    val e = tar.nextHeader() ?: break
                    val clean = e.name.removePrefix("./")
                    if (!clean.startsWith(prefix)) { tar.skipBody(e.size); continue }
                    val rel = clean.removePrefix(prefix)
                    if (rel.isEmpty()) { tar.skipBody(e.size); continue }
                    // legal/ y demo/ son licencias y ejemplos: ~40 MB que no
                    // sirven y contienen los unicos symlinks del tar.
                    if (rel.startsWith("legal/") || rel.startsWith("demo/")) {
                        tar.skipBody(e.size); continue
                    }
                    if (e.isDirectory || rel.endsWith("/")) {
                        File(root, rel).mkdirs(); tar.skipBody(0)
                    } else if (e.isRegularFile) {
                        tar.copyBody(e.size, File(root, rel))
                        extracted++
                        if (extracted % 100 == 0) progress("    " + Text.s(R.string.jre_extracted_files, extracted))
                    } else {
                        // symlink u otro: se salta (solo hay en legal/, verificado)
                        tar.skipBody(e.size)
                    }
                }
            }
        } ?: return Text.s(R.string.jre_no_data_tar, PACKAGES[0].file)
        progress("  " + Text.s(R.string.jre_extracted_done, extracted))

        // 3) las 4 libs nativas del linker (van a jre/lib, donde las busca
        //    LD_LIBRARY_PATH — misma disposicion que la instalacion manual)
        for (deb in PACKAGES.drop(1)) {
            val (from, to) = deb.extract ?: continue
            progress("  " + Text.s(R.string.jre_extract_lib, to.substringAfterLast('/')))
            findDataTarStream(File(debs, deb.file))?.use { raw ->
                TarReader(raw).use { tar ->
                    var found = false
                    while (true) {
                        val e = tar.nextHeader() ?: break
                        val clean = e.name.removePrefix("./")
                        if (clean == from && e.isRegularFile) {
                            tar.copyBody(e.size, File(root, to))
                            found = true
                            break
                        }
                        tar.skipBody(e.size)
                    }
                    if (!found) return Text.s(R.string.jre_no_entry, from, deb.file)
                }
            } ?: return Text.s(R.string.jre_no_data_tar, deb.file)
        }

        // 4) permisos: el tar trae 0600/0700 y asi no ejecuta ni lee
        progress("  " + Text.s(R.string.jre_applying_perms))
        var fails = 0
        root.walkTopDown().forEach { f ->
            if (NativeLauncher.chmod(f.absolutePath, 0x1FF) != 0) fails++ // rwxr-xr-x
        }
        if (fails > 0) progress("  " + Text.s(R.string.jre_chmod_failed, fails))

        // 5) verificacion final
        val missing = JRE_REQUIRED_FILES.filter { !File(root, it).isFile }
        if (missing.isNotEmpty()) {
            return Text.s(R.string.jre_incomplete_missing, missing.joinToString())
        }
        progress("  " + Text.s(R.string.jre_complete, root.walkTopDown().count()))

        // 6) los .deb ya no hacen falta (liberan ~101 MB)
        debs.deleteRecursively()
        return null
    }

    // ------------------------------------------------------------- ar reader
    /**
     * Devuelve el stream DESCOMPRIMIDO de `data.tar.xz` (o .gz / plano) dentro
     * del .deb. Formato `ar`: cabecera de 60 bytes, tam en decimal en 48..58,
     * relleno de 1 byte si el tam es impar (verificado contra los 5 debs).
     */
    private fun findDataTarStream(deb: File): InputStream? {
        val raf = java.io.RandomAccessFile(deb, "r")
        try {
            val magic = ByteArray(8)
            raf.readFully(magic)
            if (!magic.contentEquals("!<arch>\n".toByteArray(Charsets.US_ASCII))) return null
            var offset = 8L
            while (true) {
                raf.seek(offset)
                val hdr = ByteArray(60)
                if (raf.read(hdr) < 60) return null
                val name = hdr.copyOfRange(0, 16).toString(Charsets.US_ASCII).trim()
                val size = hdr.copyOfRange(48, 58).toString(Charsets.US_ASCII).trim().toLongOrNull()
                    ?: return null
                val bodyOffset = offset + 60
                if (name.startsWith("data.tar")) {
                    val slice = object : InputStream() {
                        var pos = 0L
                        override fun read(): Int {
                            if (pos >= size) return -1
                            raf.seek(bodyOffset + pos)
                            val b = raf.read()
                            if (b >= 0) pos++
                            return b
                        }
                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            if (pos >= size) return -1
                            val n = minOf(len.toLong(), size - pos).toInt()
                            raf.seek(bodyOffset + pos)
                            val r = raf.read(b, off, n)
                            if (r > 0) pos += r
                            return r
                        }
                    }
                    return when {
                        name.startsWith("data.tar.xz") -> XZInputStream(slice)
                        name.startsWith("data.tar.gz") -> GZIPInputStream(slice)
                        else -> slice
                    }
                }
                offset = bodyOffset + size + (size % 2)
            }
        } catch (t: Throwable) {
            raf.close()
            throw t
        }
    }

    // ------------------------------------------------------------ tar reader
    /**
     * Lector tar USTAR secuencial en Kotlin puro. Cabeceras de 512 bytes:
     * nombre 0..99, tam octal 124..135, typeflag 156 (verificado: magic
     * "ustar", typeflags '0', '5' y '2' unicamente).
     *
     * Uso obligatorio: nextHeader() -> skipBody()/copyBody() segun proceda.
     */
    private class TarReader(private val input: InputStream) : Closeable {
        private val hdr = ByteArray(512)

        data class Header(val name: String, val size: Long, val typeFlag: Byte) {
            val isDirectory: Boolean get() = typeFlag == '5'.code.toByte() || name.endsWith("/")
            val isRegularFile: Boolean get() = typeFlag == '0'.code.toByte() || typeFlag == 0.toByte()
        }

        /** Siguiente cabecera o null al fin del tar (bloque de ceros o EOF).
         *  Soporta GNU LongLink: una entrada typeflag 'L' cuyo cuerpo es el
         *  nombre largo de la ENTRADA SIGUIENTE (verificado: el data.tar del
         *  openjdk trae 92, p.ej. los .policy de JCE en policy/unlimited).
         *  Sin esto los nombres quedan cortados a 100 chars y la JVM no
         *  encuentra sus propios archivos de configuracion. */
        fun nextHeader(): Header? {
            var pendingLongName: String? = null
            while (true) {
                if (!readFully(input, hdr)) return null
                if (hdr.all { it == 0.toByte() }) return null
                val typeFlag = hdr[156]
                val sizeStr = hdr.copyOfRange(124, 136)
                    .toString(Charsets.US_ASCII).trimEnd('\u0000', ' ')
                val size = if (sizeStr.isEmpty()) 0L else parseOctal(sizeStr)
                if (typeFlag == 'L'.code.toByte()) {
                    // cuerpo = nombre largo de la siguiente entrada (NUL-terminado)
                    val nb = ByteArray(size.toInt() + 1)
                    var done = 0
                    while (done < size) {
                        val n = input.read(nb, done, (size - done).toInt())
                        if (n <= 0) break
                        done += n
                    }
                    pendingLongName = nb.copyOfRange(0, done)
                        .toString(Charsets.UTF_8).trimEnd('\u0000')
                    skipPadding(size)
                    continue
                }
                val name = pendingLongName
                    ?: hdr.copyOfRange(0, 100).toString(Charsets.UTF_8).trimEnd('\u0000')
                return Header(name, size, typeFlag)
            }
        }

        private fun skipPadding(size: Long) {
            var pad = padding(size)
            val b = ByteArray(512)
            while (pad > 0) {
                val n = input.read(b, 0, minOf(pad.toInt(), b.size))
                if (n <= 0) break
                pad -= n
            }
        }

        /** Salta el cuerpo de la entrada (con su relleno a bloque de 512). */
        fun skipBody(size: Long) {
            var remaining = size + padding(size)
            val b = ByteArray(64 * 1024)
            while (remaining > 0) {
                val n = input.read(b, 0, minOf(remaining.toInt(), b.size))
                if (n <= 0) break
                remaining -= n
            }
        }

        /** Copia el cuerpo de la entrada a [dest], y luego consume el relleno.
         *  Escribe EXACTAMENTE [size] bytes: el relleno del tar son NULs de
         *  alineacion y NO deben acabar en el archivo (los .policy de JCE se
         *  leen como Properties y los NULs rompen el parseo — verificado). */
        fun copyBody(size: Long, dest: File) {
            dest.parentFile?.mkdirs()
            FileOutputStream(dest).use { out ->
                var remaining = size
                val b = ByteArray(64 * 1024)
                while (remaining > 0) {
                    val n = input.read(b, 0, minOf(remaining.toInt(), b.size))
                    if (n <= 0) break
                    out.write(b, 0, n)
                    remaining -= n
                }
            }
            // consumir el relleno para que la siguiente cabecera caa en el
            // borde de bloque correcto
            var pad = padding(size)
            while (pad > 0) {
                val n = input.read(ByteArray(minOf(pad.toInt(), 64 * 1024)))
                if (n <= 0) break
                pad -= n
            }
        }

        private fun padding(size: Long): Long = (512 - (size % 512)) % 512

        private fun parseOctal(s: String): Long = try {
            var v = 0L
            for (c in s) {
                if (c == '\u0000') break
                val d = c.code - '0'.code
                if (d < 0 || d > 7) break
                v = v * 8 + d
            }
            v
        } catch (t: Throwable) { 0L }

        override fun close() { input.close() }

        private fun readFully(input: InputStream, buf: ByteArray): Boolean {
            var done = 0
            while (done < buf.size) {
                val n = input.read(buf, done, buf.size - done)
                if (n <= 0) return false
                done += n
            }
            return true
        }
    }

    // -------------------------------------------------------------- descarga
    /** HTTP GET a archivo con progreso cada 10 %. true si se completo. */
    private fun download(url: String, dest: File, progress: (String) -> Unit): Boolean = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 120_000
        if (conn.responseCode !in 200..299) {
            Log.e(TAG, "HTTP ${conn.responseCode} en $url")
            false
        } else {
            val total = conn.contentLengthLong
            val tmp = File(dest.absolutePath + ".part")
            var lastStep = -1L
            BufferedInputStream(conn.inputStream).use { ins ->
                FileOutputStream(tmp).use { outs ->
                    val b = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = ins.read(b)
                        if (n <= 0) break
                        outs.write(b, 0, n)
                        done += n
                        if (total > 0) {
                            val step = done * 10 / total
                            if (step != lastStep) {
                                lastStep = step
                                progress("    " + Text.s(R.string.jre_downloading_pct, dest.name, (done * 100 / total).toInt(), (done / 1048576).toInt()))
                            }
                        }
                    }
                }
            }
            conn.disconnect()
            if (tmp.renameTo(dest)) true else { tmp.delete(); false }
        }
    } catch (t: Throwable) {
        Log.e(TAG, "descarga de $url fallo: ${t.message}")
        false
    }
}
