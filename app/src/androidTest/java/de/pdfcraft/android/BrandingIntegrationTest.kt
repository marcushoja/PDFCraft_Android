package de.pdfcraft.android

import android.content.Intent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BrandingIntegrationTest {
    @Test fun launcherLibraryAndAboutUsePDFPauliAndTheAppIcon() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals("PDFPauli", context.applicationInfo.loadLabel(context.packageManager).toString())
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val loader = ThumbnailLoader(activity)
        try {
            instrumentation.runOnMainSync {
                for (dark in listOf(false, true)) {
                    val library = LibraryView(activity, DocumentStore(activity), loader, dark, {}, {}, {}, {})
                    val header = library.getChildAt(0) as LinearLayout
                    val icon = header.getChildAt(0) as ImageView
                    assertNotNull(icon.drawable)
                    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, icon.importantForAccessibility)
                    assertEquals("PDFPauli", (header.getChildAt(1) as TextView).text.toString())
                }
                // Exercise the same dialog entry point used by the library menu.
                MainActivity::class.java.getDeclaredMethod("showAbout").apply { isAccessible = true }.invoke(activity)
            }
            instrumentation.waitForIdleSync()
            val root = instrumentation.uiAutomation.rootInActiveWindow
            assertTrue(root.findAccessibilityNodeInfosByText("PDFPauli").isNotEmpty())
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
            loader.close()
            assertTrue(loader.awaitClosed(30, TimeUnit.SECONDS))
        }
    }
}
