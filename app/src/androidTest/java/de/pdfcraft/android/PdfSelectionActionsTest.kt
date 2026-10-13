package de.pdfcraft.android

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.SystemClock
import android.view.ActionMode
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfSelectionActionsTest {
    @Test fun nativeRenderedSelectionCopiesExactTextThroughActivityMenu() {
        val i=InstrumentationRegistry.getInstrumentation();val context=i.targetContext
        val source=File.createTempFile("selection-actions-",".pdf",context.cacheDir)
        val pdf=PdfDocument()
        try {
            val page=pdf.startPage(PdfDocument.PageInfo.Builder(400,600,1).create())
            page.canvas.drawColor(Color.WHITE)
            page.canvas.drawText("Hello PDF selection",40f,100f,Paint().apply { color=Color.BLACK;textSize=24f })
            pdf.finishPage(page);source.outputStream().use { pdf.writeTo(it) }
        } finally { pdf.close() }
        val activity=i.startActivitySync(Intent(context,MainActivity::class.java).setAction(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(source),"application/pdf").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        var reader:PdfReaderView?=null
        for(attempt in 0 until 100) {
            i.runOnMainSync { reader=activity.findViewById(R.id.pdf_canvas) }
            if(reader?.pageCount==1) break
            Thread.sleep(100)
        }
        assertNotNull(reader)
        i.runOnMainSync { reader!!.setReadingMode(ReadingMode.SINGLE);reader!!.setFitPage(true);reader!!.resetZoom();reader!!.goToPage(0) }
        i.waitForIdleSync()
        val down=SystemClock.uptimeMillis()
        var x=0f;var y=0f
        i.runOnMainSync {
            val view=reader!!;val scale=minOf(view.width/400f,view.height/600f)
            x=(view.width-400*scale)/2+60*scale;y=(view.height-600*scale)/2+90*scale
            val event=MotionEvent.obtain(down,down,MotionEvent.ACTION_DOWN,x,y,0)
            view.onTouchEvent(event);event.recycle()
        }
        Thread.sleep(800)
        i.runOnMainSync { val e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,x,y,0);reader!!.onTouchEvent(e);e.recycle() }
        val field=MainActivity::class.java.getDeclaredField("selectionMode").apply { isAccessible=true }
        var mode:ActionMode?=null
        for(attempt in 0 until 100) {
            i.runOnMainSync { mode=field.get(activity) as? ActionMode }
            if(mode!=null) break
            Thread.sleep(100)
        }
        i.runOnMainSync {
            assertNotNull("Long press should show real Copy/Mark toolbar",mode)
            assertEquals("Hello",reader!!.selectedTextRange!!.text)
            val pickedField=MainActivity::class.java.getDeclaredField("directSelection").apply { isAccessible=true }
            val picked=pickedField.get(activity) as Pair<*,*>
            assertEquals("Hello",(picked.second as PdfTextSelection).text)
            assertNotNull(mode!!.menu.findItem(R.id.mark_selection))
            assertTrue(mode!!.menu.performIdentifierAction(R.id.pdf_selection_copy,0))
        }
        val clipboard=activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        var copied:String?=null
        var description:String?=null
        var label:String?=null
        // Poll off the UI thread; never substitute the selected text for the real clipboard.
        for(attempt in 0 until 50) {
            i.runOnMainSync {
                val clip=clipboard.primaryClip
                copied=clip?.getItemAt(0)?.text?.toString()
                label=clip?.description?.label?.toString()
                description="${clip?.description}; label=${clip?.description?.label}"
            }
            if(copied=="Hello" && label=="PDF") break
            Thread.sleep(100)
        }
        try {
            assertEquals("Clipboard $description; focus=${activity.hasWindowFocus()}; package=${activity.opPackageName}","Hello",copied)
            assertEquals("Copy must create the app's clip, not an emulator host clipboard clip","PDF",label)
        } finally { i.runOnMainSync { activity.finish() };source.delete() }
    }
}
