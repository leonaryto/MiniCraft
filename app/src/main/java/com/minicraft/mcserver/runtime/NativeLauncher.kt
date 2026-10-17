package com.minicraft.mcserver.runtime

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Binding JNI del lanzador nativo (ver cpp/launcher.c).
 *
 * Existe por una limitacion concreta y VERIFICADA en Android 13 (Xiaomi M2012K11AG,
 * SELinux Enforcing): `/system/bin/linker64` exige que TODOS sus argumentos sean
 * rutas absolutas, asi que no puede pasar flags a la JVM:
 *
 *     $ /system/bin/linker64 /data/.../jre/bin/java -Djava.awt.headless=true -jar ...
 *     error: expected absolute path: "-Djava.awt.headless=true"
 *
 * Los @argfiles de Java 9+ tampoco valen: el linker expande el fichero y vuelve a
 * exigir rutas absolutas para cada token. Y `--` tampoco sirve.
 *
 * Un .so de app tampoco tiene PT_INTERP, asi que linker64 lo rechazaria. La solucion
 * es que este codigo nativo haga fork() + execve() directamente: los argumentos
 * llegan intactos a la JVM sin pasar por el parser del linker.
 *
 * Ver CONTEXTO.md §3 (tabla de vias de ejecucion) y docs/FASE-0-ESTADO.md.
 */
object NativeLauncher {

    private const val TAG = "NativeLauncher"

    @Volatile
    private var loaded = false

    /**
     * Se invoca desde el hilo NATIVO de lectura, por cada linea del hijo.
     * No tocar UI desde aqui: hay que reenviar al hilo principal.
     */
    @Volatile
    var lineSink: ((String) -> Unit)? = null

    /**
     * Punto de entrada desde C (viene en JNI como
     * acceptLine(Ljava/lang/String;)V). Se llama desde un hilo nativo sin adjuntar,
     * asi que reenviamos al hilo principal de la JVM antes de tocar nada.
     *
     * ES ESTATICO a proposito: C lo invoca con CallStaticVoidMethod. Si fuera un
     * metodo de instancia habria que pasar el objeto, y eso es lo que hacia antes:
     * se pasaba el `jclass` como si fuera el objeto y ART abortaba el proceso con
     *     JNI DETECTED ERROR IN APPLICATION: can't call void
     *     NativeLauncher.acceptLine(java.lang.String) on instance of java.lang.Class<...>
     * (SIGABRT verificado en el dispositivo, 2026-10-04.)
     */
    @JvmStatic
    @Suppress("unused")   // invocado desde JNI
    fun acceptLine(line: String) {
        lineSink?.let { sink ->
            try {
                sink(line)
            } catch (t: Throwable) {
                // Nunca propagar: un fallo del consumidor mataria el hilo nativo.
                Log.e(TAG, "lineSink fallo en: $line", t)
            }
        }
    }

    val available: Boolean
        get() {
            ensureLoaded()
            return loaded
        }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loaded = try {
                System.loadLibrary("minicraft_launcher")
                true
            } catch (t: Throwable) {
                Log.e(TAG, "no se pudo cargar libminicraft_launcher.so", t)
                false
            }
        }
    }

    /**
     * Carga la libreria nativa de forma explicita.
     *
     * IMPORTANTE: hay que llamarlo ANTES de cualquier native. Si no, ART intenta
     * resolver el metodo por su cuenta y falla con `UnsatisfiedLinkError` aunque el
     * simbolo este exportado en el .so — que es exactamente lo que pasaba con
     * `runAndCaptureNative` en la ruta de instalacion (verificado: el simbolo estaba
     * en .dynsym y el md5 del .so en el movil coincidia con el del APK, y aun asi
     * ART no lo encontraba).
     */
    fun preload() {
        ensureLoaded()
    }

    /**
     * Lanza un proceso hijo y devuelve su codigo de salida.
     * BLOQUEANTE: espera a que el hijo termine.
     *
     * La salida del hijo (stdout+stderr) queda disponible para [startOutputReader],
     * que debe arrancarse ANTES en un hilo aparte para no perder lineas.
     */
    fun execve(
        path: String,
        argv: List<String>,
        envp: List<String>,
        cwd: String?
    ): Int {
        check(available) { "libreria nativa no disponible" }
        return execveNative(path, argv.toTypedArray(), envp.toTypedArray(), cwd)
    }

    /** ¿Existe ya el pipe de salida? El lector lo espera antes de arrancar. */
    fun hasOutputPipe(): Boolean {
        if (!available) return false
        return hasOutputPipeNative()
    }

    /** Lanza un hilo nativo demonio que lee la salida del hijo linea a linea y la
     *  entrega a [lineSink]. Devuelve de inmediato. */
    fun startOutputReader() {
        if (!available) return
        startOutputReaderNative()
    }

    /** Cierra el pipe de salida. Llamar al destruir el servicio. */
    fun closeOutput() {
        if (!available) return
        closeOutputNative()
    }

    /** Envia SIGTERM al hijo. Parada de emergencia; la limpia es por consola. */
    fun killChild() {
        if (!available) return
        killChildNative()
    }

    // ---- playitd daemon (proceso hermano del servidor JVM) ----

    /**
     * Nombre del daemon playitd dentro de nativeLibraryDir.
     * Android lo extrae de jniLibs/arm64-v8a/libplayitd.so a nativeLibraryDir
     * (etiqueta apk_data_file, NO bajo W^X). idéntico al stub libmcjvmboot.so.
     */
    const val PLAYITD_LIB = "libplayitd.so"

    /** Ruta del binario playitd en nativeLibraryDir. */
    fun playitdBinary(ctx: Context): File {
        val dir = File(ctx.applicationInfo.nativeLibraryDir)
        val flat = File(dir, PLAYITD_LIB)
        if (flat.exists()) return flat
        return File(dir, "libplayitd.so")
    }

    /**
     * Lanza el daemon playitd como proceso hermano (fork+execve).
     * NO bloquea: el daemon corre en segundo plano mientras el servidor
     * JVM también corre. Usa g_playit_pid, separado de g_child_pid.
     *
     * Ver CONTEXTO.md §3: nativeLibraryDir tiene etiqueta apk_data_file
     * (no W^X), asi que execve funciona. Desde files/ fallaria con EACCES.
     */
    fun startPlayitDaemon(
        path: String,
        argv: List<String>,
        envp: List<String>,
        cwd: String?
    ): Int {
        check(available) { "libreria nativa no disponible" }
        return startPlayitDaemonNative(path, argv.toTypedArray(), envp.toTypedArray(), cwd)
    }

    /** Envía SIGTERM al daemon playitd y espera a que termine. */
    fun stopPlayitDaemon(): Int {
        if (!available) return -1
        return stopPlayitDaemonNative()
    }

    /** ¿el daemon playitd sigue vivo? */
    fun isPlayitRunning(): Boolean {
        if (!available) return false
        return isPlayitRunningNative()
    }

    fun chmod(path: String, mode: Int): Int {
        if (!available) return -1
        return chmodNative(path, mode)
    }

    fun isExecutable(path: String): Boolean {
        if (!available) return false
        return isExecutableNative(path)
    }

    fun which(name: String, searchPaths: Array<String>): String? {
        if (!available) return null
        return whichNative(name, searchPaths)
    }

    // ---- natives ----------------------------------------------------------

    private external fun execveNative(
        path: String,
        argv: Array<String>,
        envp: Array<String>,
        cwd: String?
    ): Int

    private external fun hasOutputPipeNative(): Boolean
    private external fun startOutputReaderNative()
    private external fun closeOutputNative()
    private external fun killChildNative()

    /**
     * Escribe una linea en la consola del servidor (`stop`, `say`, `whitelist ...`).
     * Devuelve los bytes escritos, o -1 si el hijo ya no esta.
     * El `\\n` final lo anade el codigo nativo.
     */
    fun writeStdin(line: String): Int = writeStdinNative(line)

    /** Cierra la entrada para que el servidor vea EOF (equivale a Ctrl+D). */
    fun closeStdin() = closeStdinNative()

    /** ¿Hay consola disponible? Falso si el pipe de entrada no se pudo crear. */
    fun hasStdin(): Boolean = hasStdinNative()

    private external fun writeStdinNative(line: String): Int
    private external fun closeStdinNative()
    private external fun hasStdinNative(): Boolean

    /**
     * Ejecuta un comando Java CORTO y devuelve su salida combinada y su codigo de
     * salida: `[salida, codigo]`.
     *
     * Es distinto de [execve] a proposito: [execve] se queda en `waitpid` sin drenar
     * el pipe, asi que se atasca si el proceso escribe mas de 64 KB (que es justo lo
     * que hace el instalador de Fabric). Este drena primero y despues espera.
     *
     * NO se puede llamar mientras el servidor corre: ambos comparten el PID del hijo.
     */
    fun runAndCapture(
        path: String,
        argv: List<String>,
        envp: List<String>,
        cwd: String
    ): Pair<String, Int> {
        // Sin esto ART no resuelve el native por su cuenta -> UnsatisfiedLinkError.
        preload()
        val r = runAndCaptureNative(path, argv.toTypedArray(), envp.toTypedArray(), cwd)
            ?: return Pair("(sin resultado nativo)", -1)
        return (r[0] ?: "") to (r[1]?.toIntOrNull() ?: -1)
    }

    private external fun runAndCaptureNative(
        path: String,
        argv: Array<String>,
        envp: Array<String>,
        cwd: String
    ): Array<String>?
    private external fun chmodNative(path: String, mode: Int): Int
    private external fun isExecutableNative(path: String): Boolean
    private external fun whichNative(name: String, searchPaths: Array<String>): String?

    // playitd daemon (proceso hermano)
    private external fun startPlayitDaemonNative(
        path: String,
        argv: Array<String>,
        envp: Array<String>,
        cwd: String?
    ): Int

    private external fun stopPlayitDaemonNative(): Int
    private external fun isPlayitRunningNative(): Boolean

    // ---- Cliente IPC nativo (bypassa LocalSocket de Java) ----
    // Wrapper públicos: los métodos external son private, siguiendo el
    // patrón del resto de NativeLauncher. TunnelIpcClient los llama.
    fun connectIpc(socketName: String): Int {
        if (!available) return -1
        return connectIpcNative(socketName)
    }

    fun readLineIpc(fd: Int): String? {
        if (!available) return null
        return readLineIpcNative(fd)
    }

    fun writeLineIpc(fd: Int, data: String): Int {
        if (!available) return -1
        return writeLineIpcNative(fd, data)
    }

    fun closeIpc(fd: Int) {
        if (!available) return
        closeIpcNative(fd)
    }

    private external fun connectIpcNative(name: String): Int
    private external fun readLineIpcNative(fd: Int): String?
    private external fun writeLineIpcNative(fd: Int, data: String): Int
    private external fun closeIpcNative(fd: Int)
}