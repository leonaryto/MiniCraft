package com.minicraft.mcserver.server

import android.content.Context
import android.util.Log
import com.minicraft.mcserver.runtime.NativeLauncher
import java.io.File

/**
 * FASE 2 - Arranque real de un servidor de Minecraft en Android.
 *
 * Verificado en escritorio primero (Fabric 1.21.1 llego a "Done (10.881s)!") y luego
 * en el telefono con la JVM bionic.
 *
 * Puntos clave aprendidos de las pruebas:
 *  - `-Djava.awt.headless=true` es OBLIGATORIO: sin el, la clase `server` de Fabric
 *    intenta abrir Swing para el dialogo post-instalacion y falla en Android.
 *
 *  - NO se puede lanzar con `/system/bin/linker64 <java> <flags...>`. El linker de
 *    Android exige que TODOS sus argumentos sean rutas absolutas, asi que rechaza
 *    cualquier flag de la JVM:
 *        error: expected absolute path: "-Djava.awt.headless=true"
 *    (Verificado en el telefono con el servicio real. La conclusion anterior de que
 *    ProcessBuilder lo solucionaba era incorrecta: el parseo ocurre igual.)
 *
 *  - LA VIA QUE FUNCIONA es dejar que el KERNEL ejecute el stub `bin/java`: es un
 *    ELF con PT_INTERP=/system/bin/linker64, asi que el kernel carga el linker y le
 *    pasa argv intacto, sin reinterpretar. Para no chocar con W^X (execve en
 *    /data/user/0 -> Permission denied), el stub va empaquetado como jniLib del APK y
 *    Android lo extrae a `nativeLibraryDir`, que tiene otra etiqueta SELinux.
 *
 *  - El resto de la JRE (243 MB) se queda en el data dir (`filesDir/jre`), descargada
 *    en el primer arranque. `LD_LIBRARY_PATH` hace que
 *    el linker cargue `libjli.so` y `lib/server/libjvm.so` de ahi, y
 *    `GetApplicationHomeFromDll()` (dladdr sobre libjli.so) encuentra el home.
 *
 *  - `libjvm.so` necesita `libandroid-shmem.so`, `libandroid-spawn.so` y
 *    `libc++_shared.so`, que la app deja en `<jre>/lib` (ver `extralib` en JreProbe).
 *
 * Ver cpp/launcher.c.
 */
object ServerLauncher {

    private const val TAG = "MinicraftServer"

    /** Nombre del stub dentro de `nativeLibraryDir`. */
    const val STUB_NAME = "libmcjvmboot.so"

    /**
     * Linea que emite el servidor cuando ha terminado de arrancar:
     *     [xx:xx:xx INFO]: Done (11.896s)! For help, type "help"
     * Se compila una vez porque se evalua en cada linea del log del servidor.
     */
    private val DONE_RE = Regex("""Done \(([\d.]+)s\)!""")

    data class Result(
        val exitCode: Int,
        val log: String,
        val reachedDone: Boolean,
        val doneSeconds: Double?
    )

    /** Estado vivo del servidor, consultado por la UI y el servicio. */
    @Volatile var running: Boolean = false
        private set
    @Volatile var ready: Boolean = false
        private set
    @Volatile var readyAtMs: Long = 0L
        private set
    @Volatile var exitCode: Int = 0
        private set

    /**
     * Ruta del stub `java` de la JVM, empaquetado como jniLib del APK.
     *
     * Android lo extrae a `nativeLibraryDir/`, que tiene etiqueta SELinux
     * `apk_data_file` y NO esta bajo W^X. En cambio `filesDir/jre/bin/java` da
     * `Permission denied` en `execve`.
     *
     * El nombre del fichero NO importa para que la JVM encuentre su home: eso lo
     * hace `GetApplicationHomeFromDll()`, que hace `dladdr()` de `libjli.so` (la
     * carga `LD_LIBRARY_PATH` desde el data dir) y trunca en el ultimo `/lib/`.
     * Por eso el stub puede vivir fuera del arbol del JRE. Si esta nombrado
     * `lib*.so` es porque el plugin de Android solo empaqueta jniLibs con ese
     * patron (verificado: `mergeDebugNativeLibs` descarta el resto).
     */
    fun stubInNativeLibDir(ctx: Context): File {
        val dir = File(ctx.applicationInfo.nativeLibraryDir)
        val flat = File(dir, STUB_NAME)
        if (flat.exists()) return flat
        // Por si una version futura del plugin preserva los subdirectorios.
        return File(dir, "bin/java")
    }

    /**
     * `LD_LIBRARY_PATH` para que el linker encuentre las libs de la JRE, que viven en
     * el data dir. El linker de Android SI respeta esta variable (verificado con
     * `linker64 --list`), aunque el stub tambien traiga `$ORIGIN/../lib` en RUNPATH.
     */
    private fun ldLibraryPath(jreHome: File): String =
        listOf(
            File(jreHome, "lib").absolutePath,
            File(jreHome, "lib/server").absolutePath,
            "/system/lib64"
        ).joinToString(":")

    private fun envFor(jreHome: File, serverDir: File): Array<String> = arrayOf(
        // HOME y TMPDIR apuntan al dir del servidor porque Android no garantiza
        // /tmp escribible para una app.
        "LD_LIBRARY_PATH=${ldLibraryPath(jreHome)}",
        "PATH=/system/bin:/system/xbin",
        "HOME=${serverDir.absolutePath}",
        "TMPDIR=${serverDir.absolutePath}",
        "LANG=C.UTF-8"
    )

    /**
     * Flags de la JVM, en el orden en que deben ir.
     *
     * `java.io.tmpdir` hay que fijarlo a mano: el JDK de Termux trae
     * `/data/data/com.termux/files/usr/tmp` compilado dentro, que no existe y hace
     * que JNA avise (WARNING: IOException removing temporary files). Con esto el
     * directorio existe y es escribible por la app.
     *
     * Aikar's G1GC flags: pausas GC < 50 ms en
     * lugar de las pausas de segundos del GC por defecto. Heap fijo (-Xms=-Xmx)
     * evita resize pauses.
     */
    private fun javaFlags(
        javaBin: File, launchJarAbs: String, heapMb: Int, serverDir: File
    ): Array<String> {
        val tmp = File(serverDir, "tmp").apply { if (!exists()) mkdirs() }
        return arrayOf(
            javaBin.absolutePath,
            "-Djava.awt.headless=true",
            "-Xmx${heapMb}M",
            "-Xms${heapMb}M",
            "-Dfile.encoding=UTF-8",
            "-Djava.io.tmpdir=${tmp.absolutePath}",
            // === G1GC + Aikar's flags (reduce GC pauses a <50ms) ===
            "-XX:+UseG1GC",
            "-XX:+ParallelRefProcEnabled",
            "-XX:MaxGCPauseMillis=200",
            "-XX:+UnlockExperimentalVMOptions",
            "-XX:+DisableExplicitGC",
            "-XX:+AlwaysPreTouch",
            "-XX:G1HeapRegionSize=8M",
            "-XX:G1NewSizePercent=30",
            "-XX:G1MaxNewSizePercent=40",
            "-XX:G1ReservePercent=20",
            "-XX:G1HeapWastePercent=5",
            "-XX:G1MixedGCCountTarget=4",
            "-XX:InitiatingHeapOccupancyPercent=15",
            "-XX:G1MixedGCLiveThresholdPercent=90",
            "-XX:G1RSetUpdatingPauseTimePercent=5",
            "-XX:MaxTenuringThreshold=1",
            "-XX:SurvivorRatio=32",
            "-XX:+PerfDisableSharedMem",
            "-Dusing.aikars.flags=https://mcflags.emc.gs",
            "-Daikars.new.flags=true",
            "-jar",
            launchJarAbs,
            "nogui"
        )
    }

    /**
     * Envia un comando a la consola del servidor por su stdin (`stop`, `say hello`,
     * `whitelist on`). El servidor lee lineas, asi que el `\\n` lo anade el nativo.
     *
     * Requiere que [start] se haya llamado y que el pipe de entrada exista: el proceso
     * se lanza con stdin hacia un pipe, no hacia /dev/null, precisamente para esto.
     *
     * @return true si la linea se escribio.
     */
    fun sendCommand(cmd: String): Boolean {
        if (!running) {
            Log.w(TAG, "sendCommand ignorado: el servidor no esta corriendo")
            return false
        }
        val n = try {
            NativeLauncher.writeStdin(cmd)
        } catch (t: Throwable) {
            Log.e(TAG, "sendCommand fallo para '$cmd'", t)
            return false
        }
        if (n < 0) {
            Log.w(TAG, "sendCommand fallo: no se pudo escribir '$cmd' (hijo muerto?)")
            return false
        }
        Log.i(TAG, "comando enviado: $cmd ($n bytes)")
        return true
    }

    /** Cierra la consola: el servidor ve EOF y para de forma ordenada. */
    fun closeConsole() = NativeLauncher.closeStdin()

    /** TPS calculado del log del servidor (promedio de los últimos ticks). */
    @Volatile private var lastTps: Double = 0.0
    private val tpsPattern = Regex("""Tick next #([0-9]+) ticking ([a-z]+) \(avg ([0-9]+[.][0-9]+) tps""")

    fun tps(): Double = lastTps

    /**
     * Analiza una linea del log para extraer el TPS (si contiene informacion de tick).
     * Se llama desde el listener de ConsoleLog.
     */
    fun updateTpsFromLine(line: String) {
        val m = tpsPattern.find(line)
        if (m != null) {
            lastTps = m.groupValues[3].toDoubleOrNull() ?: lastTps
        }
    }

    /**
     * Entorno para un comando Java CORTO (instalador de Fabric), mismo esquema que el
     * servidor: `LD_LIBRARY_PATH` a las libs de la JRE, que viven en el data dir.
     *
     * [cwd] hace de HOME/TMPDIR porque Android no garantiza /tmp escribible para una app.
     */
    fun envForInstaller(jreHome: File, cwd: File): List<String> {
        val tmp = File(cwd, "tmp").apply { if (!exists()) mkdirs() }
        return listOf(
            "LD_LIBRARY_PATH=${ldLibraryPath(jreHome)}",
            "PATH=/system/bin:/system/xbin",
            "HOME=${cwd.absolutePath}",
            "TMPDIR=${tmp.absolutePath}",
            "LANG=C.UTF-8"
        )
    }

    /**
     * Lanza el servidor en segundo plano y devuelve de inmediato.
     * El log se entrega linea a linea a [onLine] desde el hilo nativo de lectura.
     */
    fun start(
        ctx: Context,
        jreHome: File,
        serverDir: File,
        launchJar: String = "fabric-server-launch.jar",
        heapMb: Int = 1024,
        onLine: (String) -> Unit = {}
    ) {
        val javaBin = stubInNativeLibDir(ctx)
        require(javaBin.exists()) {
            "no existe el stub en nativeLibraryDir: $javaBin " +
                "(reinstalar la APK: el stub va como jniLib y Android lo extrae)"
        }
        require(File(jreHome, "lib/libjli.so").exists()) {
            "JRE incompleta: falta libjli.so en $jreHome"
        }
        require(serverDir.exists()) { "no existe dir del servidor: $serverDir" }

        // El jar DEBE ir con ruta absoluta: CLASSPATH no se resuelve contra el cwd
        // de forma fiable en bionic, y el launcher de la JVM asi lo exige.
        val launchJarAbs = File(serverDir, launchJar).absolutePath

        // argv[0] es el propio binario: la JVM no lo usa, pero execve lo exige.
        val argv = javaFlags(javaBin, launchJarAbs, heapMb, serverDir)
        val envp = envFor(jreHome, serverDir)

        Log.i(TAG, "lanzando: ${argv.joinToString(" ")}")
        Log.i(TAG, "cwd=${serverDir.absolutePath}")
        Log.i(TAG, "LD_LIBRARY_PATH=${envp[0]}")

        running = true
        ready = false

        NativeLauncher.lineSink = { line ->
            if (!ready) {
                val m = DONE_RE.find(line)
                if (m != null) {
                    ready = true
                    readyAtMs = System.currentTimeMillis()
                    val secs = m.groupValues[1].toDoubleOrNull()
                    Log.i(TAG, ">>> SERVIDOR LISTO en ${secs}s")
                }
            }
            onLine(line)
        }

        Thread({
            val started = System.currentTimeMillis()
            val rc = try {
                NativeLauncher.execve(
                    javaBin.absolutePath,
                    argv.toList(),
                    envp.toList(),
                    serverDir.absolutePath
                )
            } catch (t: Throwable) {
                Log.e(TAG, "fallo al lanzar", t)
                onLine("ERROR INTERNO: ${t.message}")
                -1
            }
            val ms = System.currentTimeMillis() - started
            running = false
            exitCode = rc
            Log.i(TAG, "proceso terminado rc=$rc tras ${ms}ms")
            // Marcador idioma-independiente (lo detecta ConsoleLog): nunca traducir.
            onLine("--- server process finished: exit=$rc after ${"%.1f".format(ms / 1000.0)}s")
            NativeLauncher.lineSink = null
        }, "server-exec").also { it.isDaemon = true }.start()

        // El pipe lo crea execve(), y el proceso hijo muere en ~100ms si algo falla:
        // si el lector espera 700ms se pierde la salida del diagnostico. Por eso
        // esperamos a que el pipe exista de verdad, no a un tiempo fijo.
        Thread({
            var waited = 0
            while (waited < 10_000) {
                if (NativeLauncher.hasOutputPipe()) break
                try { Thread.sleep(20) } catch (_: InterruptedException) { return@Thread }
                waited += 20
            }
            NativeLauncher.startOutputReader()
        }, "server-log").also { it.isDaemon = true }.start()
    }

    /** Detiene el servidor de forma ordenada (SIGTERM al hijo). */
    fun stop() {
        Log.i(TAG, "peticion de parada")
        NativeLauncher.killChild()
    }

    /** Cierra el pipe de salida. Llamar al destruir el servicio. */
    fun shutdown() {
        NativeLauncher.lineSink = null
        NativeLauncher.closeOutput()
        running = false
    }

    /**
     * Lanza y corta cuando el servidor dice Done. Bloqueante: para diagnostico.
     *
     * [maxMs] corta el proceso aunque no llegue "Done", para que una prueba no se
     * quede colgada esperando un arranque que ya fallo.
     */
    fun runUntilReady(
        ctx: Context,
        jreHome: File,
        serverDir: File,
        heapMb: Int = 1024,
        maxMs: Long = 240_000,
        onLine: (String) -> Unit = {}
    ): Result {
        val javaBin = stubInNativeLibDir(ctx)
        val launchJarAbs = File(serverDir, "fabric-server-launch.jar").absolutePath
        val argv = javaFlags(javaBin, launchJarAbs, heapMb, serverDir)
        val envp = envFor(jreHome, serverDir)
        val sb = StringBuilder()
        NativeLauncher.lineSink = { line -> sb.appendLine(line); onLine(line) }

        val t0 = System.currentTimeMillis()
        // execve() es bloqueante (espera a que muera el hijo), asi que va en su propio
        // hilo; mientras tanto el hilo nativo de lectura sigue alimentando `sb`.
        val worker = Thread({
            NativeLauncher.execve(
                javaBin.absolutePath, argv.toList(), envp.toList(), serverDir.absolutePath
            )
        }, "server-exec-probe").also { it.isDaemon = true }
        worker.start()

        val done = Regex("""Done \(([\d.]+)s\)!""")
        var text = ""
        while (System.currentTimeMillis() - t0 < maxMs) {
            text = sb.toString()
            if (done.containsMatchIn(text)) break
            if (!worker.isAlive) {
                // El hijo termino (o ni arranco): drenamos el pipe antes de cortar.
                try { Thread.sleep(500) } catch (_: InterruptedException) { break }
                break
            }
            try { Thread.sleep(300) } catch (_: InterruptedException) { break }
        }
        if (worker.isAlive) {
            onLine("--- limite de ${maxMs}ms agotado; se pide la parada")
            NativeLauncher.killChild()
            worker.join(5_000)
        }
        NativeLauncher.lineSink = null
        text = sb.toString()

        val m = done.find(text)
        return Result(0, text, m != null, m?.groupValues?.get(1)?.toDoubleOrNull())
    }
}