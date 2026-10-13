package de.pdfcraft.android

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PdfTextIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun extractsSearchesAndReadsOutlineWithRealEngine() {
        val context = instrumentation.targetContext
        PDFBoxResourceLoader.init(context)
        val file = File.createTempFile("text-test-", ".pdf", context.cacheDir)
        PDDocument().use { document ->
            repeat(2) { index ->
                val page = PDPage(); document.addPage(page)
                PDPageContentStream(document, page).use { content ->
                    content.beginText(); content.setFont(PDType1Font.HELVETICA, 12f); content.newLineAtOffset(30f, 750f)
                    repeat(40) { line -> content.showText("Chapter ${index + 1}: Alpha selectable line $line"); content.newLineAtOffset(0f, -16f) }
                    content.endText()
                }
            }
            val outline = PDDocumentOutline()
            outline.addLast(PDOutlineItem().apply { title = "Chapter Two"; setDestination(document.getPage(1)) })
            document.documentCatalog.documentOutline = outline
            document.save(file)
        }
        val service = PdfTextService(context)
        try {
            var text = ""; var error: String? = null
            val extracted = CountDownLatch(1)
            service.extract(file, 1, { text = it; extracted.countDown() }, { error = it; extracted.countDown() })
            assertTrue(extracted.await(15, TimeUnit.SECONDS)); assertNull(error); assertTrue(text.contains("Chapter 2"))
            var hits = emptyList<SearchHit>(); val searched = CountDownLatch(1)
            service.search(file, "alpha", { hits = it; searched.countDown() }, { error = it; searched.countDown() })
            assertTrue(searched.await(15, TimeUnit.SECONDS)); assertNull(error); assertEquals(80, hits.size)
            assertTrue(hits.any { it.page == 0 }); assertTrue(hits.any { it.page == 1 })
            var entries = emptyList<OutlineEntry>(); val outlined = CountDownLatch(1)
            service.outline(file, { entries = it; outlined.countDown() }, { error = it; outlined.countDown() })
            assertTrue(outlined.await(15, TimeUnit.SECONDS)); assertNull(error)
            assertEquals("Chapter Two", entries.single().title); assertEquals(1, entries.single().page)
        } finally { service.close(); file.delete() }
    }

    @Test fun nativeReflowWrapsAndRestoresScroll() {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        lateinit var view: ReflowView
        var offset = 0f
        instrumentation.runOnMainSync {
            view = ReflowView(activity)
            view.onOffsetChanged = { offset = it }
            activity.setContentView(view)
            view.showText((1..200).joinToString("\n\n") { "Paragraph $it contains readable text that wraps to the smartphone width." }, 2, 8)
            view.setAppearance(true, 28f, true)
            view.restoreOffset(0.4f)
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertEquals(0.4f, offset, 0.015f)
            assertTrue(view.textView.isTextSelectable)
            assertTrue(view.textView.layout.lineCount > 200)
            view.setAppearance(false, 18f, false)
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertEquals("Font changes must retain relative position", 0.4f, offset, 0.02f)
            activity.finish()
        }
    }
}
