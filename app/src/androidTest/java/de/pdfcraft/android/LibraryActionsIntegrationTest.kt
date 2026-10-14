package de.pdfcraft.android

import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LibraryActionsIntegrationTest {
    private fun descendants(view:View):List<View> = listOf(view)+if(view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun windows()=WindowInspector.getGlobalWindowViews().flatMap { descendants(it) }

    @Test fun renameIsEditableAndDeletingLocalCopyPreservesOriginal() {
        val i=InstrumentationRegistry.getInstrumentation();val context=i.targetContext
        val source=File.createTempFile("library-actions-",".pdf",context.cacheDir)
        val pdf=PdfDocument()
        try {
            val page=pdf.startPage(PdfDocument.PageInfo.Builder(240,320,1).create())
            page.canvas.drawText(source.name,10f,40f,Paint().apply { textSize=12f })
            pdf.finishPage(page);source.outputStream().use { pdf.writeTo(it) }
        } finally { pdf.close() }
        val original=source.readBytes();val store=DocumentStore(context)
        val doc=store.importDocument(Uri.fromFile(source));val copy=store.file(doc)
        val activity=i.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val menu=MainActivity::class.java.getDeclaredMethod("documentMenu",de.pdfcraft.android.PdfDocument::class.java).apply { isAccessible=true }
        try {
            i.runOnMainSync { menu.invoke(activity,doc) };i.waitForIdleSync()
            i.runOnMainSync {
                val list=windows().filterIsInstance<ListView>().single()
                assertEquals(UiText.t(activity,"Lokale Kopie löschen"),list.adapter.getItem(3).toString())
                list.performItemClick(null,1,list.adapter.getItemId(1))
            };i.waitForIdleSync()
            i.runOnMainSync {
                val input=windows().filterIsInstance<EditText>().single { it.text.toString()==doc.name }
                assertEquals(doc.name,input.text.toString())
                assertEquals(0,input.selectionStart);assertEquals(doc.name.length,input.selectionEnd)
                input.setText("Mein geändertes Buch.pdf")
                windows().single { it.id==android.R.id.button1 }.performClick()
            };i.waitForIdleSync()
            assertEquals("Mein geändertes Buch.pdf",store.get(doc.id)!!.name)
            assertTrue(copy.exists());assertArrayEquals(original,source.readBytes())
            i.runOnMainSync { menu.invoke(activity,store.get(doc.id)) };i.waitForIdleSync()
            i.runOnMainSync {
                val list=windows().filterIsInstance<ListView>().single()
                list.performItemClick(null,3,list.adapter.getItemId(3))
            };i.waitForIdleSync()
            i.runOnMainSync {
                assertTrue(windows().filterIsInstance<TextView>().any {
                    it.text.toString()==activity.getString(R.string.ui_remove_copy_warning)
                })
                windows().single { it.id==android.R.id.button2 }.performClick()
            };i.waitForIdleSync()
            assertTrue("Cancel must retain the private copy",copy.exists());assertNotNull(store.get(doc.id))
            i.runOnMainSync { menu.invoke(activity,store.get(doc.id)) };i.waitForIdleSync()
            i.runOnMainSync {
                val list=windows().filterIsInstance<ListView>().single()
                list.performItemClick(null,3,list.adapter.getItemId(3))
            };i.waitForIdleSync()
            i.runOnMainSync { windows().single { it.id==android.R.id.button1 }.performClick() };i.waitForIdleSync()
            assertFalse(copy.exists());assertNull(store.get(doc.id))
            assertArrayEquals("Deleting the copy must not alter the original",original,source.readBytes())
        } finally {
            i.runOnMainSync { activity.finish() };i.waitForIdleSync()
            store.get(doc.id)?.let { store.remove(it.id) };source.delete()
        }
    }
}
