package com.minicraft.mcserver

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * i18n de la app (es/en). Ingles es el idioma por defecto en builds nuevas:
 * `values/strings.xml` esta en ingles y `values-es/strings.xml` en espanol.
 *
 * El idioma se guarda en SharedPreferences (`app_locale`, clave `lang`, valores
 * `en`/`es`) y se aplica con [wrap] en `attachBaseContext` de cada Activity y
 * del Service. `Locale.setDefault` se fija en [init] para que las cadenas
 * construidas sin Context (nombres de archivo, notificaciones, logs) usen el
 * mismo idioma.
 */
@SuppressLint("ApplySharedPref")
object AppLocale {

    private const val PREFS = "app_locale"
    private const val KEY_LANG = "lang"

    /** Idioma por defecto de la app. */
    const val DEFAULT = "en"

    /** Preferencia de idioma ya cargada (o el default). Se actualiza en [setLang]. */
    @Volatile
    private var current: String = DEFAULT

    fun lang(): String = current

    /** true si el idioma activo es el espanol. */
    fun isEs(): Boolean = current == "es"

    /** Inicializa la preferencia a partir de disco. Llamar desde Application.onCreate. */
    fun init(ctx: Context) {
        val stored = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANG, DEFAULT) ?: DEFAULT
        current = if (stored == "es") "es" else DEFAULT
        applyDefault()
    }

    /** Cambia el idioma y lo persiste. La Activity debe llamar a recreate() despues. */
    fun setLang(ctx: Context, lang: String) {
        current = if (lang == "es") "es" else DEFAULT
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANG, current)
            .commit()
        applyDefault()
    }

    /** Envuelve [base] con la configuracion de idioma activa. */
    fun wrap(base: Context): Context {
        val locale = localeOf(current)
        Locale.setDefault(locale)
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(locale)
        cfg.setLayoutDirection(locale)
        return base.createConfigurationContext(cfg)
    }

    private fun applyDefault() {
        Locale.setDefault(localeOf(current))
    }

    private fun localeOf(lang: String): Locale = if (lang == "es") Locale("es") else Locale.ENGLISH
}

/**
 * Holder del contexto localizado, para obtener strings desde objetos sin Context
 * (ServerProvisioner, JreProvisioner, ModrinthApi, PlayitAgent...) sin cambiar
 * sus firmas.
 *
 * Se inicializa en [MinicraftApp.onCreate] y se refresca al cambiar de idioma
 * (llamado desde ServerSettingsActivity antes de recreate()).
 */
object Text {

    @Volatile
    private var appCtx: Context? = null

    private val ctx: Context
        get() = appCtx ?: error("Text.init() no llamado (MinicraftApp.onCreate)")

    fun init(base: Context) {
        appCtx = AppLocale.wrap(base.applicationContext)
    }

    /** String localizado por recurso. */
    fun s(resId: Int): String = ctx.getString(resId)

    /** String localizado por recurso con formato (mismo contrato que Context.getString). */
    fun s(resId: Int, vararg args: Any?): String = ctx.getString(resId, *args)
}
