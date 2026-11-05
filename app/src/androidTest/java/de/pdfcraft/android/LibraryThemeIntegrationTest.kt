package de.pdfcraft.android

import android.content.Intent
import android.content.res.Configuration
import android.os.LocaleList
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryThemeIntegrationTest {
    @Test fun themeSwitchIsLeftOfMenuAndPersistsBothDirections() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("reader_settings", android.content.Context.MODE_PRIVATE)
        val hadDark = prefs.contains("dark")
        val initial = prefs.getBoolean("dark", false)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            instrumentation.runOnMainSync {
                val toggle = activity.findViewById<View>(R.id.library_theme)
                val menu = activity.findViewById<View>(R.id.library_menu)
                val header = menu.parent as ViewGroup
                assertSame(header, toggle.parent)
                assertEquals(header.indexOfChild(menu) - 1, header.indexOfChild(toggle))
                assertEquals(initial, toggle.isSelected)
                toggle.performClick()
                assertEquals(!initial, prefs.getBoolean("dark", initial))
                val changed = activity.findViewById<View>(R.id.library_theme)
                assertEquals(!initial, changed.isSelected)
                changed.performClick()
                assertEquals(initial, prefs.getBoolean("dark", !initial))
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            if (hadDark) prefs.edit().putBoolean("dark", initial).commit() else prefs.edit().remove("dark").commit()
        }
    }

    @Test fun viewRotationAndThemeActionsAreLocalized() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (tag in listOf("de", "en", "fr", "es")) {
            val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                setLocales(LocaleList.forLanguageTags(tag))
            })
            assertTrue(localized.getString(R.string.reader_rotate_view).contains("90°"))
            assertNotEquals(localized.getString(R.string.library_light_mode), localized.getString(R.string.library_dark_mode))
        }
    }
}
