package com.minicraft.mcserver.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.minicraft.mcserver.AppLocale
import com.minicraft.mcserver.R

/**
 * Base para las 5 pantallas con bottom nav (Inicio/Consola/Mods/Mundos/Ajustes).
 *
 * Solo frontend: centraliza el header (icono + titulo + subtitulo) y la barra de
 * navegacion. Las subclases llaman [setupChrome] tras setContentView con:
 *  - [titleRes]/[subtitleRes]: strings del header
 *  - [tabId]: R.id.tab* de la pestana activa (se marca en azul y no navega)
 *
 * La navegacion reutiliza las Activities que ya existen; no agrega funcionalidad.
 */
abstract class NavActivity : Activity() {

    /** Titulo del header (p.ej. R.string.app_name). */
    abstract val titleRes: Int

    /** Subtitulo mono del header (p.ej. R.string.subtitle_home). */
    abstract val subtitleRes: Int

    /** R.id.tab* de la pestana activa. */
    abstract val tabId: Int

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    /**
     * Conecta header + bottom nav. Debe llamarse DESPUES de setContentView.
     * Los layouts incluyen <include android:id="@+id/header" layout="@layout/view_header"/>
     * y <include android:id="@+id/nav" layout="@layout/view_bottom_nav"/>.
     */
    protected fun setupChrome() {
        findViewById<TextView>(R.id.tvHeaderTitle)?.setText(titleRes)
        findViewById<TextView>(R.id.tvHeaderSubtitle)?.setText(subtitleRes)
        wireNav()
        markActiveTab()
    }

    private fun wireNav() {
        findViewById<View>(R.id.tabHome)?.setOnClickListener { go(MainActivity::class.java) }
        findViewById<View>(R.id.tabConsole)?.setOnClickListener { go(ConsoleActivity::class.java) }
        findViewById<View>(R.id.tabMods)?.setOnClickListener { go(ModManagerActivity::class.java) }
        findViewById<View>(R.id.tabWorlds)?.setOnClickListener { go(WorldManager::class.java) }
        findViewById<View>(R.id.tabSettings)?.setOnClickListener { go(ServerSettingsActivity::class.java) }
    }

    private fun markActiveTab() {
        val tab = findViewById<View>(tabId) ?: return
        val img = tab.findViewById<ImageView>(when (tabId) {
            R.id.tabHome -> R.id.ivHome
            R.id.tabConsole -> R.id.ivConsole
            R.id.tabMods -> R.id.ivMods
            R.id.tabWorlds -> R.id.ivWorlds
            R.id.tabSettings -> R.id.ivSettings
            else -> 0
        })
        val lbl = tab.findViewById<TextView>(when (tabId) {
            R.id.tabHome -> R.id.lblHome
            R.id.tabConsole -> R.id.lblConsole
            R.id.tabMods -> R.id.lblMods
            R.id.tabWorlds -> R.id.lblWorlds
            R.id.tabSettings -> R.id.lblSettings
            else -> 0
        })
        // Pestaña activa: icono y texto en azul GitHub.
        img?.setColorFilter(getColor(R.color.accent))
        lbl?.setTextColor(getColor(R.color.accent))
    }

    private fun go(target: Class<out Activity>) {
        if (this::class.java == target) return
        startActivity(Intent(this, target))
        overridePendingTransition(0, 0)
        finish()
    }
}
