package io.github.pixelblue

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** App language chosen inside the app ("" = same as the phone). */
object Lang {
    private const val PREFS = "pixelblue"
    private const val KEY = "lang"

    fun get(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "") ?: ""

    fun set(activity: Activity, code: String) {
        if (get(activity) == code) return
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, code).apply()
        activity.recreate()
    }

    fun wrap(base: Context): Context {
        val code = get(base)
        if (code.isEmpty()) return base
        val locale = Locale(code)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
