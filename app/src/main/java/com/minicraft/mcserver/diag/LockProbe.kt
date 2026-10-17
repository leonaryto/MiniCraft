// LockProbe.kt — FASE 0 del MVP de Minicraft
//
// POR QUE EXISTE ESTE FICHERO
// El almacenamiento emulado de Android (FUSE/sdcardfs) tiene soporte problematico de
// flock/FileLock. Minecraft usa `session.lock` para garantizar la integridad del mundo:
// si el lock falla, el servidor puede negarse a arrancar o corromper el mundo.
//
// Esta clase responde a una sola pregunta, de forma definitiva y en un dispositivo real:
//     ¿Funciona FileChannel.lock() en externalFilesDir, en filesDir y en sdcard?
//
// NO es parte de la app final. Se ejecuta una vez, en la Fase 0, y su resultado decide
// el modelo de almacenamiento (ver CONTEXTO.md §9.3-§9.4). Si el lock falla en externo,
// hay que mover los mundos a filesDir y avisar al usuario.
//
// COMO EJECUTARLO
// Opción A (recomendada): convertir esto en la pantalla de "Diagnóstico" de la app.
// Opción B: app de prueba de una sola actividad que solo muestra estos resultados.
//
// Que se espere de cada caso:
//   externalFilesDir -> OK esperado. Si falla, el nivel 1 del diseño se cae.
//   filesDir         -> OK esperado (ext4/f2fs, filesystem nativo). Debe funcionar siempre.
//   sdcard (FUSE)    -> FALLO esperado. Confirma por que NO se elige esa ruta.

package com.minicraft.mcserver.diag

import android.content.Context
import android.os.Build
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

data class LockProbeResult(
    val id: String,                 // clave estable ("filesDir", "externalApp"...) — NO traducir
    val label: String,
    val path: String,
    val exists: Boolean,
    val writable: Boolean,
    val lockAcquired: Boolean,
    val lockIsShared: Boolean,      // true = el SO degrado a lock compartido (no exclusivo)
    val error: String?,
    val verdict: String             // OK | DEGRADADO | FALLO (codigos estables, no traducir)
)

object LockProbe {

    fun run(context: Context): List<LockProbeResult> = listOf(
        probe("filesDir", com.minicraft.mcserver.Text.s(com.minicraft.mcserver.R.string.lock_label_filesdir),
            File(context.filesDir, "probe.lock")),
        probe("externalApp", com.minicraft.mcserver.Text.s(com.minicraft.mcserver.R.string.lock_label_external_app),
            File(context.getExternalFilesDir(null), "probe.lock")),
        probe("externalDirs", com.minicraft.mcserver.Text.s(com.minicraft.mcserver.R.string.lock_label_external_dirs),
            File(context.getExternalFilesDirs(null).firstOrNull(), "probe.lock")),
        probe("sdcard", com.minicraft.mcserver.Text.s(com.minicraft.mcserver.R.string.lock_label_sdcard),
            File("/sdcard/minicraft_probe.lock")),
    )

    private fun probe(id: String, label: String, target: File): LockProbeResult {
        val parent = target.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        // El fichero de prueba debe CREARSE, no solo comprobarse: comprobar únicamente
        // `exists()` daba un falso negativo incluso cuando el directorio era escribible.
        val created = try {
            target.createNewFile() || target.exists()
        } catch (e: Throwable) {
            false
        }
        val exists = created && target.exists()
        if (!exists) {
            return LockProbeResult(id, label, target.absolutePath, false, false, false, false,
                "no se pudo crear el fichero de prueba", "FALLO")
        }
        if (!target.canWrite()) {
            return LockProbeResult(id, label, target.absolutePath, true, false, false, false,
                "el fichero existe pero no es escribible", "FALLO")
        }

        return try {
            RandomAccessFile(target, "rw").use { raf ->
                val ch = raf.channel
                // Exclusive = true es lo que exige la integridad del mundo de Minecraft.
                val lock: FileLock? = try {
                    ch.lock(0L, Long.MAX_VALUE, /* exclusive = */ true)
                } catch (e: OverlappingFileLockException) {
                    // Ya hay un lock en este proceso: no es un fallo de la plataforma.
                    null
                }

                if (lock == null) {
                    LockProbeResult(id, label, target.absolutePath, true, true, false, false,
                        "OverlappingFileLockException: lock ya tomado en este proceso",
                        "DEGRADADO")
                } else {
                    val shared = lock.isShared
                    lock.release()
                    LockProbeResult(
                        id, label, target.absolutePath, true, true, true, shared, null,
                        if (shared) "DEGRADADO" else "OK"
                    )
                }
            }
        } catch (t: Throwable) {
            // IOException: "not supported", "Function not implemented", EOPNOTSUPP...
            LockProbeResult(id, label, target.absolutePath, true, true, false, false,
                "${t::class.java.simpleName}: ${t.message}", "FALLO")
        }
    }

    fun render(results: List<LockProbeResult>): String = buildString {
        appendLine("Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine()
        for (r in results) {
            appendLine("[${r.verdict}] ${r.label}")
            appendLine("   ruta:  ${r.path}")
            appendLine("   lock:  adquirido=${r.lockAcquired} exclusivo=${!r.lockIsShared}")
            if (r.error != null) appendLine("   error: ${r.error}")
            appendLine()
        }
        // Chequeo por id estable (NO por label: el label esta localizado).
        val bloqueante = results.filter { it.verdict == "FALLO" && it.id.startsWith("external") }
        appendLine(
            if (bloqueante.isEmpty())
                com.minicraft.mcserver.Text.s(com.minicraft.mcserver.R.string.lock_conclusion_ok)
            else
                com.minicraft.mcserver.Text.s(com.minicraft.mcserver.R.string.lock_conclusion_fail)
        )
    }
}