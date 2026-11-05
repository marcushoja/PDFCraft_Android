package de.pdfcraft.android

import android.content.Intent
import android.content.res.Configuration
import android.os.LocaleList
import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppUpdatesIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun json(code: Int = 42): JSONObject = JSONObject().apply {
        put("tag_name", "build-$code"); put("draft", false); put("prerelease", false)
        put("assets", JSONArray().put(JSONObject().apply {
            put("name", "PDFCraft.apk"); put("size", 1024)
            put("browser_download_url", AppRelease.downloadUrl(code))
        }))
    }

    @Test fun publishedReleaseUsesNumericBuildAndExactOfficialApk() {
        assertEquals(AppRelease(42, AppRelease.downloadUrl(42)), AppUpdates.parseRelease(json().toString()))
        assertNull(AppUpdates.parseRelease(json().put("draft", true).toString()))
        assertNull(AppUpdates.parseRelease(json().put("prerelease", true).toString()))
        val missing = json().put("assets", JSONArray())
        val foreign = json().apply {
            getJSONArray("assets").getJSONObject(0).put("browser_download_url", "https://example.com/PDFCraft.apk")
        }
        val empty = json().apply { getJSONArray("assets").getJSONObject(0).put("size", 0) }
        for (invalid in listOf(missing.toString(), foreign.toString(), empty.toString(),
            json().put("tag_name", "build-2147483648").toString(),
            json().put("tag_name", "v42").toString(), "{broken")) {
            assertTrue("Malformed or incomplete releases must allow a retry",
                runCatching { AppUpdates.parseRelease(invalid) }.isFailure)
        }
    }

    @Test fun browserDownloadIntentContainsOnlyTheOfficialApkUrl() {
        val intent = AppUpdates.downloadIntent(AppRelease(42, AppRelease.downloadUrl(42)))
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(AppRelease.downloadUrl(42), intent.data.toString())
        assertTrue(intent.categories!!.contains(Intent.CATEGORY_BROWSABLE))
        assertNull(intent.extras); assertNull(intent.clipData)
        assertEquals(0, intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertTrue(runCatching { AppUpdates.downloadIntent(AppRelease(42, "http://example.com/app.apk")) }.isFailure)
    }

    @Test fun inlineNoticeOnlyDownloadsAfterClickAndCanBeClosedInBothThemes() {
        instrumentation.runOnMainSync {
            for (dark in listOf(false, true)) {
                var downloads = 0
                var dismissals = 0
                val notice = UpdateNoticeView(context, dark, AppRelease(42, AppRelease.downloadUrl(42)),
                    { downloads++ }, { dismissals++ })
                notice.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                assertEquals(0, downloads); assertEquals(0, dismissals)
                assertNotNull(notice.findViewById<View>(R.id.update_notice))
                val download = notice.findViewById<TextView>(R.id.update_download)
                assertEquals(context.getString(R.string.update_download), download.text.toString())
                download.performClick(); assertEquals(1, downloads); assertEquals(0, dismissals)
                notice.findViewById<View>(R.id.update_dismiss).performClick()
                assertEquals(1, downloads); assertEquals(1, dismissals)
            }
        }
    }

    @Test fun noticeActionsAreLocalizedInAllFourLanguages() {
        for ((tag, label) in mapOf("de" to "Herunterladen", "en" to "Download", "fr" to "Télécharger", "es" to "Descargar")) {
            val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                setLocales(LocaleList.forLanguageTags(tag))
            })
            assertEquals(label, localized.getString(R.string.update_download))
            assertTrue(localized.getString(R.string.update_available, "0.1.41").contains("0.1.41"))
            assertTrue(localized.getString(R.string.update_dismiss).isNotEmpty())
        }
    }
}
