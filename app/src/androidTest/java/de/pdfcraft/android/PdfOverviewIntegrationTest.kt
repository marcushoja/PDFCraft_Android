package de.pdfcraft.android

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PdfOverviewIntegrationTest {
    @Test fun overviewOfThreeHundredPagesBindsOnlyViewportAndStartsAtReadingPage() {
        val i=InstrumentationRegistry.getInstrumentation();val context=i.targetContext
        val prefs=context.getSharedPreferences("reader_settings",0)
        val oldLayout=prefs.getString("layout",null);val oldRtl=prefs.all["rtl"]
        prefs.edit().putString("layout","SINGLE").putBoolean("rtl",false).commit()
        val source=File.createTempFile("overview-300-",".pdf",context.cacheDir)
        val pdf=PdfDocument()
        try {
            repeat(300) { index ->
                val page=pdf.startPage(PdfDocument.PageInfo.Builder(240,320,index+1).create())
                page.canvas.drawColor(Color.WHITE)
                page.canvas.drawText("${source.name} ${index+1}",12f,40f,Paint().apply { color=Color.BLACK;textSize=12f })
                pdf.finishPage(page)
            }
            source.outputStream().use { pdf.writeTo(it) }
        } finally { pdf.close() }
        val activity=i.startActivitySync(Intent(context,MainActivity::class.java).setAction(Intent.ACTION_VIEW)
            .setDataAndType(Uri.fromFile(source),"application/pdf").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            var reader:PdfReaderView?=null
            for(attempt in 0 until 200) {
                i.runOnMainSync { reader=activity.findViewById(R.id.pdf_canvas) }
                if(reader?.pageCount==300) break
                Thread.sleep(100)
            }
            assertEquals(300,reader?.pageCount)
            lateinit var dialog:AlertDialog
            lateinit var grid:GridView
            val loaderField=MainActivity::class.java.getDeclaredField("thumbnails").apply { isAccessible=true }
            val loader=loaderField.get(activity) as ThumbnailLoader
            i.runOnMainSync {
                reader!!.goToPage(220)
                val apply=MainActivity::class.java.getDeclaredMethod("applyReaderSettings").apply { isAccessible=true }
                prefs.edit().putString("layout","SPREAD").putBoolean("rtl",true).commit();apply.invoke(activity)
                val counter=activity.findViewById<TextView>(R.id.page_indicator)
                assertEquals("221–222 / 300",counter.text.toString())
                assertEquals("RTL navigation must not reverse the numeric range",View.TEXT_DIRECTION_LTR,counter.textDirection)
                assertEquals(View.LAYOUT_DIRECTION_RTL,activity.findViewById<View>(R.id.page_navigation).layoutDirection)
                prefs.edit().putString("layout","SINGLE").putBoolean("rtl",false).commit();apply.invoke(activity)
                MainActivity::class.java.getDeclaredMethod("pagesDialog",Boolean::class.javaPrimitiveType).apply { isAccessible=true }.invoke(activity,false)
                dialog=MainActivity::class.java.getDeclaredField("pageOverviewDialog").apply { isAccessible=true }.get(activity) as AlertDialog
                grid=dialog.findViewById(R.id.page_overview_grid)
            }
            i.waitForIdleSync()
            assertTrue(loader.awaitIdle(40,TimeUnit.SECONDS));i.waitForIdleSync()
            i.runOnMainSync {
                assertEquals(300,grid.adapter.count)
                assertTrue("Do not inflate the entire PDF",grid.childCount in 1..40)
                assertTrue("Open at the saved page, not page one",grid.firstVisiblePosition in 210..220)
                val visible=(0 until grid.childCount).map { grid.getChildAt(it) as LinearLayout }
                assertTrue("Visible thumbnails must actually be displayed",visible.any {
                    ((it.getChildAt(0) as FrameLayout).getChildAt(1) as ImageView).drawable!=null
                })
                dialog.dismiss()
                assertEquals("Dismiss must cancel the grid's outstanding requests",0,loader.stats().outstandingRequests)
            }
        } finally {
            i.runOnMainSync { activity.finish() };i.waitForIdleSync()
            val store=DocumentStore(context);store.list().filter { it.name==source.name }.forEach { store.remove(it.id) }
            source.delete()
            val edit=prefs.edit()
            if(oldLayout==null) edit.remove("layout") else edit.putString("layout",oldLayout)
            if(oldRtl==null) edit.remove("rtl") else edit.putBoolean("rtl",oldRtl as Boolean)
            edit.commit()
        }
    }
}
