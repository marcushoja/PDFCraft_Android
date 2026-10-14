package de.pdfcraft.android

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class UiTextTest {
    private val base get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun localized(tag: String): Context = base.createConfigurationContext(
        Configuration(base.resources.configuration).apply { setLocales(LocaleList(Locale.forLanguageTag(tag))) }
    )

    @Test fun staticLabelsUseCallerResources() {
        assertEquals("Open", UiText.t(localized("en"), "Öffnen"))
        assertEquals("Öffnen", UiText.t(localized("de"), "Öffnen"))
        assertEquals("Ouvrir", UiText.t(localized("fr"), "Öffnen"))
        assertEquals("Abrir", UiText.t(localized("es"), "Öffnen"))
    }

    @Test fun scannerLabelsAndPageNumbersUseAllFourLanguages() {
        val titles=mapOf("de" to "PDF scannen","en" to "Scan PDF","fr" to "Numériser un PDF","es" to "Escanear PDF")
        val pages=mapOf("de" to "Seite 3","en" to "Page 3","fr" to "Page 3","es" to "Página 3")
        for(tag in titles.keys) {
            assertEquals(titles[tag],localized(tag).getString(R.string.scan_title))
            assertEquals(pages[tag],localized(tag).getString(R.string.scan_page,3))
        }
    }

    @Test fun progressAndAccessibilityTemplates() {
        val context = localized("en")
        assertEquals("Page 3 of 12", UiText.t(context, "Seite 3 von 12"))
        assertEquals("Page {0} of {1}", UiText.t(context, "Seite {0} von {1}"))
        assertEquals("24 results", UiText.t(context, "24 Treffer"))
        assertEquals("Preparing pages · 4 / 9", UiText.t(context, "Seiten vorbereiten · 4 / 9"))
        assertEquals("Local copy · 1,5 / 8,2 MB", UiText.t(context, "Lokale Kopie · 1,5 / 8,2 MB"))
        assertEquals("Reflow, page 2 of 8", UiText.t(context, "Reflow, Seite 2 von 8"))
        assertEquals("PDF document, page 2 of 8", UiText.t(context, "PDF-Dokument, Seite 2 von 8"))
        assertEquals("Search is case-insensitive.\nPage 2 of 8", UiText.t(context, "Groß-/Kleinschreibung wird ignoriert.\nSeite 2 von 8"))
    }

    @Test fun capturedPdfAndUserTextIsNotTranslated() {
        val context = localized("en")
        val raw = "Öffnen\nSeite 2 von 8 · 100% & <pdf>"
        assertEquals("Page 4\n$raw", UiText.t(context, "Seite 4\n$raw"))
        assertEquals("Page 4 · $raw", UiText.t(context, "Seite 4 · $raw"))
        assertEquals("$raw, continue reading", UiText.t(context, "$raw, weiterlesen"))
        assertEquals("Collapse subchapters of $raw", UiText.t(context, "Unterkapitel von $raw zuklappen"))
        assertEquals("Form field “$raw” was not found.", UiText.t(context, "Das Formularfeld „$raw“ wurde nicht gefunden."))
        assertEquals("Title: Öffnen\nAuthor: Deutsch\nPages: 3\nPDF version: 1.7\nEncrypted: Yes\nFile size: 42 KiB\n\nPrivate copy on this device.",
            UiText.t(context, "Titel: Öffnen\nAutor: Deutsch\nSeiten: 3\nPDF-Version: 1.7\nVerschlüsselt: Ja\nDateigröße: 42 KiB\n\nPrivate Kopie auf diesem Gerät."))
        val filename = "Öffnen:Deutsch.pdf"
        val warning = "Die Kopie ist passwortgeschützt; Textextraktion und Bearbeitung sind gesperrt."
        assertEquals("$filename\nThe original remains unchanged.\n\nThe copy is password-protected; text extraction and editing are disabled.",
            UiText.t(context, "$filename\nDas Original bleibt unverändert.\n\n$warning"))
    }

    @Test fun backendWrappersAreTranslatedRecursively() {
        val context = localized("en")
        assertEquals("Saving failed: PDF could not be edited: Invalid or unsupported PDF data.",
            UiText.t(context, "Speichern fehlgeschlagen: PDF konnte nicht bearbeitet werden: Ungültige oder nicht unterstützte PDF-Daten."))
        assertEquals("PDF could not be edited: native unknown: Öffnen.pdf",
            UiText.t(context, "PDF konnte nicht bearbeitet werden: native unknown: Öffnen.pdf"))
        assertEquals("Lesen.pdf: Öffnen", UiText.t(context, "Lesen.pdf: Öffnen"))
        assertEquals("Custom text\n100% \"quoted\"", UiText.t(context, "Custom text\n100% \"quoted\""))
    }

    @Test fun creatorBrandAndCapturedNameArePreserved() {
        val context = localized("en")
        assertEquals("By Marcus Hoja\nOffline · private copies",
            UiText.t(context, "Von Marcus Hoja\nOffline · private Kopien"))
        assertEquals("By Öffnen\nOffline · private copies",
            UiText.t(context, "Von Öffnen\nOffline · private Kopien"))
        assertEquals("About PDFCraft", UiText.t(context, "Über PDFCraft"))
        assertEquals("PDFCraft Android\nBy Marcus Hoja\nA private PDF reader. Offline and without an account.",
            UiText.t(context, "PDFCraft Android\nVon Marcus Hoja\nEin privater PDF-Reader. Offline und ohne Konto."))
    }

    @Test fun germanTemplatesRoundTripAcrossTheWholeCatalog() {
        val german = localized("de")
        val placeholder = Regex("%(\\d+)\\\$s")
        for (field in R.string::class.java.fields.filter { it.name.startsWith("ui_") }) {
            val id = field.getInt(null)
            val source = placeholder.replace(german.getString(id)) { "{${it.groupValues[1].toInt() - 1}}" }
            assertEquals(field.name, source, UiText.t(german, source))
        }
    }

    @Test fun selectedLanguageDoesNotChangeProcessLocale() {
        val prefs = base.getSharedPreferences("reader_settings", Context.MODE_PRIVATE)
        val previous = prefs.getString("language", null)
        val defaultLocale = Locale.getDefault()
        try {
            prefs.edit().remove("language").commit()
            assertEquals("de", LanguageContext.selected(base))
            assertEquals("de", LanguageContext.wrap(base).resources.configuration.locales[0].language)
            for (tag in listOf("en", "fr", "es", "de")) {
                LanguageContext.select(base, tag)
                assertEquals(tag, LanguageContext.selected(base))
                assertEquals(tag, LanguageContext.wrap(base).resources.configuration.locales[0].language)
                assertEquals(defaultLocale, Locale.getDefault())
            }
            LanguageContext.select(base, "system")
            assertSame(base, LanguageContext.wrap(base))
        } finally {
            prefs.edit().apply {
                if (previous == null) remove("language") else putString("language", previous)
            }.commit()
        }
    }

}
