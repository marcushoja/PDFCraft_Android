package de.pdfcraft.android

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

/** App-local language preference; never changes the process locale or PDF content. */
object LanguageContext {
    private val supported = setOf("de", "en", "fr", "es", "system")

    fun selected(base: Context): String = base.getSharedPreferences("reader_settings", Context.MODE_PRIVATE)
        .getString("language", "de")?.takeIf { it in supported } ?: "de"

    fun select(base: Context, tag: String) {
        require(tag in supported) { "Unsupported language: $tag" }
        base.getSharedPreferences("reader_settings", Context.MODE_PRIVATE)
            .edit().putString("language", tag).apply()
    }

    fun wrap(base: Context): Context {
        val tag = selected(base)
        if (tag == "system") return base
        val configuration = Configuration(base.resources.configuration)
        val locale = Locale.forLanguageTag(tag)
        configuration.setLocales(LocaleList(locale))
        configuration.setLayoutDirection(locale)
        return base.createConfigurationContext(configuration)
    }
}
