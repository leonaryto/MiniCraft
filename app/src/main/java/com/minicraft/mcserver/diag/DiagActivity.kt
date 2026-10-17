package com.minicraft.mcserver.diag

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import com.minicraft.mcserver.AppLocale
import java.io.File

/**
 * FASE 0 + FASE 1 — Diagnóstico de arranque.
 *
 * Comprueba en un dispositivo real las dos incógnitas que el escritorio no puede resolver:
 *   1. ¿Soporta FileLock el almacenamiento emulado? (§9.4 de CONTEXTO.md)
 *   2. ¿Existe y es ejecutable /system/bin/linker64, y puede ejecutar un binario
 *      descarga? Es la vía legal para ejecutar la JVM bionic (targetSdk >= 29).
 *
 * El resultado se imprime con tag MINICRAFT_DIAG y se muestra en pantalla.
 */
class DiagActivity : Activity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val report = StringBuilder()
        fun log(msg: String) {
            Log.i(TAG, msg)
            report.appendLine(msg)
        }

        log("=== MINICRAFT FASE 0/1 ===")

        // ---------- FASE 0: file locking ----------
        val results = LockProbe.run(this)
        log("--- FASE 0: file lock ---")
        results.forEach {
            log("[${it.verdict}] ${it.label}")
            log("    ruta=${it.path}")
            log("    lockAdquirido=${it.lockAcquired} exclusivo=${!it.lockIsShared}")
            it.error?.let { e -> log("    error=$e") }
        }

        val externoFalla = results.any {
            // Chequeo por id estable, NO por label (el label esta localizado).
            it.verdict == "FALLO" && it.id.startsWith("external")
        }
        log("FASE0_CONCLUSION=" + if (externoFalla) "EXTERNO_FALLA" else "EXTERNO_OK")

        // ---------- FASE 1: linker64 ----------
        log("--- FASE 1: linker64 ---")
        log("linker64 existe=" + File("/system/bin/linker64").exists())
        log("linker64 legible=" + File("/system/bin/linker64").canRead())

        // Intento real: ejecutar /system/bin/linker64 --help (o sin args).
        // Si el linker arranca, imprimira algo o devolvera codigo != 0, pero NO
        // "Permission denied"/"not found": eso seria un fallo de SELinux/W^X.
        val r = try {
            val p = ProcessBuilder("/system/bin/linker64")
                .redirectErrorStream(true)
                .start()
            val out = p.inputStream.bufferedReader().readText().take(400)
            p.waitFor()
            "linker64 OK rc=${p.exitValue()} out=${out.replace("\n", " | ").take(300)}"
        } catch (t: Throwable) {
            "linker64 FALLO: ${t::class.java.simpleName}: ${t.message}"
        }
        log("linker64_exec=$r")

        // ¿Existe algun JRE ya instalado en el dispositivo (p.ej. Termux)?
        val termuxJre = arrayOf(
            "/data/data/com.termux/files/usr/bin/java",
            "/data/data/com.termux/files/usr/lib/jvm"
        )
        termuxJre.forEach { log("termux $it existe=" + File(it).exists()) }

        // ---------- FASE 1: JRE real (si ya hay uno descargado) ----------
        log("--- FASE 1: JRE bionic real ---")
        val jreResult = JreProbe.probe { msg -> log(msg) }
        log("FASE1=$jreResult")

        // Espacio disponible: los mundos necesitan GB.
        val ext = getExternalFilesDir(null)
        log("externalFilesDir=$ext")
        log("espacio_libre=" + (ext?.usableSpace?.div(1024 * 1024)) + " MB")

        // SELinux
        log("selinux=" + runCmd("getenforce"))

        val tv = TextView(this)
        tv.text = report.toString()
        tv.textSize = 10f
        tv.setPadding(24, 24, 24, 24)
        setContentView(tv)

        Log.i(TAG, "REPORT_COMPLETE")
    }

    private fun runCmd(cmd: String): String = try {
        val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        val o = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        o.ifEmpty { "vacio(rc=${p.exitValue()})" }
    } catch (t: Throwable) {
        "err: ${t.message}"
    }

    companion object {
        const val TAG = "MINICRAFT_DIAG"
    }
}