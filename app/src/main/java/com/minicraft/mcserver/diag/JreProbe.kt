package com.minicraft.mcserver.diag

import com.minicraft.mcserver.runtime.NativeLauncher
import java.io.File

/**
 * FASE 1 — Ejecutar una JVM real bionic desde el almacenamiento de la app.
 *
 * LockProbe sondea rutas del sistema; esto va al grano. La prueba decisiva es:
 * ¿funciona /system/bin/linker64 con un binario DESCARGADO por la propia app,
 * con W^X activo y SELinux Enforcing?
 *
 * El JRE lo descarga el usuario o la app a jreRoot(); aqui solo se ejecuta.
 */
object JreProbe {

    /** Raiz donde la app guarda el JRE descargado. */
    fun jreRoot(): File = File("/data/user/0/com.minicraft.mcserver/files/jre")

    /**
     * Localiza el binario `java` dentro del arbol del JRE descargado.
     * Un .deb de Termux extraido cuelga de .../openjdk-21/bin/java.
     */
    fun findJava(root: File = jreRoot()): File? {
        if (!root.exists()) return null
        val direct = File(root, "bin/java")
        if (direct.canExecute()) return direct
        // El .deb crea un subdirectorio con la version: openjdk-21/bin/java
        return root.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory }
            ?.map { File(it, "bin/java") }
            ?.firstOrNull { it.canExecute() }
    }

    /**
     * Ejecuta `java -version` por las dos vias posibles y devuelve los resultados.
     * La via directa es la prueba de W^X (se espera que falle con Permission denied);
     * linker64 es la via documentada que deberia funcionar con binarios dinamicos (e_type=3).
     */
    fun probe(log: (String) -> Unit): String {
        val root = jreRoot()
        log("jreRoot=${root.absolutePath} existe=${root.exists()}")
        if (root.exists()) {
            log("  contenido: " + (root.list()?.take(8)?.joinToString(", ") ?: "(vacio)"))
            log("  bin/java existe=" + File(root, "bin/java").exists())
        }

        val java = findJava()
        if (java == null) {
            // Diagnostico: ¿esta el arbol pero sin permiso de ejecucion?
            val cand = File(root, "bin/java")
            if (cand.exists()) {
                log("ATENCION: ${cand.absolutePath} existe pero canExecute()=false")
                log("  puedeLeer=" + cand.canRead() + " puedeEscribir=" + cand.canWrite())
                val fixed = if (NativeLauncher.available) {
                    NativeLauncher.chmod(cand.absolutePath, 0x1FF)  // rwxr-xr-x
                } else -1
                log("  chmod result=$fixed ahora canExecute=" + cand.canExecute())
                log("  libs: jli=" + File(root, "lib/libjli.so").exists() +
                    " z=" + File(root, "lib/libz.so.1").exists() +
                    " shmem=" + File(root, "lib/libandroid-shmem.so").exists())
                return "SIN_PERMISOS (chmod=$fixed)"
            }
            log("no hay JVM descargada en ${root.absolutePath}")
            return "SIN_JRE"
        }
        log("java encontrado: ${java.absolutePath}")
        log("  existe=${java.exists()} puedeEjecutar=${java.canExecute()} tamaño=${java.length()}")

        // Via 1: ejecucion directa. Es la que W^X deberia bloquear.
        val directo = try {
            val p = ProcessBuilder(java.absolutePath, "-version")
                .redirectErrorStream(true)
                .start()
            val out = p.inputStream.bufferedReader().readText()
            val rc = p.waitFor()
            "OK rc=$rc out=${out.replace("\n", " | ").take(300)}"
        } catch (t: Throwable) {
            "FALLO ${t::class.java.simpleName}: ${t.message}"
        }
        log("via DIRECTA  -> $directo")

        // Via 2: linker64. Requiere que la JVM sea dinamica (PT_INTERP), no estatica.
        val viaLinker = try {
            val libDir = File(java.parentFile.parentFile, "lib")
            val jliDir = File(libDir, "jli")
            val pb = ProcessBuilder(
                "/system/bin/linker64", java.absolutePath, "-version"
            ).redirectErrorStream(true)
            pb.environment()["LD_LIBRARY_PATH"] =
                "${libDir.absolutePath}:${jliDir.absolutePath}"
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText()
            val rc = p.waitFor()
            "OK rc=$rc out=${out.replace("\n", " | ").take(400)}"
        } catch (t: Throwable) {
            "FALLO ${t::class.java.simpleName}: ${t.message}"
        }
        log("via LINKER64 -> $viaLinker")

        // Via 3: el launcher nativo (fork+execve). Es la via que usara la app final,
        // porque no impose la restriccion de "rutas absolutas" de linker64.
        val viaNative = try {
            if (!NativeLauncher.available) {
                "NO_DISPONIBLE (no se pudo cargar libminicraft_launcher.so)"
            } else {
                val lib = File(java.parentFile.parentFile, "lib")
                val libServer = File(lib, "server")
                log("  nativo: java ejecutable=${NativeLauncher.isExecutable(java.absolutePath)}")
                val argv = arrayOf(java.absolutePath, "-version")
                val envp = arrayOf(
                    "LD_LIBRARY_PATH=${lib.absolutePath}:${libServer.absolutePath}:/system/lib64",
                    "PATH=/system/bin:/system/xbin",
                    "HOME=${java.parentFile.parentFile.absolutePath}",
                    "TMPDIR=${java.parentFile.parentFile.absolutePath}"
                )
                val rc = NativeLauncher.execve(
                    java.absolutePath, argv.toList(), envp.toList(),
                    java.parentFile.parentFile.absolutePath
                )
                // 127 = execve fallo. La causa mas probable es una .so que el linker no
                // encuentra; el mensaje real va al log del proceso nativo (MinicraftNative).
                "rc=$rc" + if (rc == 127) " (fallo de enlazado: ver log MinicraftNative)" else ""
            }
        } catch (t: Throwable) {
            "EXCEPCION ${t::class.java.simpleName}: ${t.message}"
        }
        log("via NATIVA   -> $viaNative")

        return "directo=$directo || linker64=$viaLinker || nativo=$viaNative"
    }
}