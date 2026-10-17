package com.minicraft.mcserver

import android.app.Application

/**
 * Punto de entrada de la app. Solo sirve para fijar el idioma activo antes de
 * que cualquier Activity/Service toque recursos:
 *  - [AppLocale.init] lee la preferencia `lang` (default `en`).
 *  - [Text.init] guarda el contexto localizado para los objetos sin Context.
 *
 * Registrado con `android:name=".MinicraftApp"` en AndroidManifest.
 */
class MinicraftApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppLocale.init(this)
        Text.init(this)
    }

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(AppLocale.wrap(base))
    }
}
