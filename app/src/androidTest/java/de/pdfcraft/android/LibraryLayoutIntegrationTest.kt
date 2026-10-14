package de.pdfcraft.android

import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument as AndroidPdfDocument
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class LibraryLayoutIntegrationTest {
    private fun descendants(view:View):List<View> = listOf(view)+if(view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun listAndGridRetainActionsAndRememberNameAndDateSorting() {
        val i=InstrumentationRegistry.getInstrumentation();val context=i.targetContext
        val prefs=context.getSharedPreferences("library_settings",0)
        val oldList=prefs.all["list"];val oldSort=prefs.all["sort"]
        prefs.edit().putBoolean("list",false).putInt("sort",0).commit()
        val store=DocumentStore(context);val loader=ThumbnailLoader(context)
        val documents=mutableListOf<PdfDocument>();val sources=mutableListOf<File>()
        var activity:MainActivity?=null
        try {
            for(name in listOf("Zulu","Alpha")) {
                val source=File.createTempFile("$name-",".pdf",context.cacheDir);sources.add(source)
                val pdf=AndroidPdfDocument()
                try {
                    val page=pdf.startPage(AndroidPdfDocument.PageInfo.Builder(240,320,1).create())
                    page.canvas.drawText(source.name,10f,40f,Paint().apply { textSize=12f })
                    pdf.finishPage(page);source.outputStream().use { pdf.writeTo(it) }
                } finally { pdf.close() }
                documents.add(store.importDocument(Uri.fromFile(source)))
            }
            val host=i.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            activity=host
            i.runOnMainSync {
                var opened:PdfDocument?=null;var options:PdfDocument?=null
                fun library()=LibraryView(host,store,loader,false,{ opened=it },{ options=it },{},{})
                var view=library();host.setContentView(view)
                fun layout() {
                    view.measure(View.MeasureSpec.makeMeasureSpec(host.resources.displayMetrics.widthPixels,View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(host.resources.displayMetrics.heightPixels,View.MeasureSpec.EXACTLY))
                    view.layout(0,0,view.measuredWidth,view.measuredHeight)
                }
                fun button(text:String)=descendants(view).single { it.contentDescription?.toString()==text }
                fun grid()=descendants(view).filterIsInstance<GridView>().single()
                button(host.getString(R.string.library_list)).performClick()
                layout()
                assertEquals(1,grid().numColumns)
                assertTrue(prefs.getBoolean("list",false))
                for(order in 0..3) {
                    button(host.getString(R.string.library_sort)).performClick()
                    val choices=WindowInspector.getGlobalWindowViews().flatMap { descendants(it) }.filterIsInstance<ListView>().single()
                    choices.performItemClick(null,order,choices.adapter.getItemId(order))
                    val actual=(0 until grid().adapter.count).map { grid().adapter.getItem(it) as PdfDocument }
                    val expected=when(order) {
                        1 -> actual.sortedByDescending { it.name.lowercase() }
                        2 -> actual.sortedByDescending { it.addedAt }
                        3 -> actual.sortedBy { it.addedAt }
                        else -> actual.sortedBy { it.name.lowercase() }
                    }
                    assertEquals(expected,actual)
                    assertTrue(actual.containsAll(documents))
                    assertEquals(order,prefs.getInt("sort",-1))
                }
                val doc=grid().adapter.getItem(0) as PdfDocument
                val cell=grid().adapter.getView(0,null,grid()) as LinearLayout
                assertEquals(LinearLayout.HORIZONTAL,cell.orientation)
                cell.measure(View.MeasureSpec.makeMeasureSpec(view.measuredWidth,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
                assertTrue(cell.measuredHeight<= (80*host.resources.displayMetrics.density).toInt())
                assertTrue(button(host.getString(R.string.library_grid)) is android.widget.ImageButton)
                assertTrue(button(host.getString(R.string.library_sort)) is android.widget.ImageButton)
                cell.performClick();assertEquals(doc,opened)
                cell.getChildAt(2).performClick();assertEquals(doc,options)
                view=library();host.setContentView(view)
                layout()
                assertEquals(1,grid().numColumns)
                button(host.getString(R.string.library_grid)).performClick()
                layout()
                assertTrue(grid().numColumns>=2)
                assertEquals(LinearLayout.VERTICAL,(grid().adapter.getView(0,cell,grid()) as LinearLayout).orientation)
                assertTrue(descendants(view).filterIsInstance<TextView>().any { it.text.toString()=="Buy me some tokens" && it.isClickable })
            }
        } finally {
            activity?.let { host -> i.runOnMainSync { host.finish() };i.waitForIdleSync() }
            loader.close();assertTrue(loader.awaitClosed(30,TimeUnit.SECONDS))
            documents.forEach { store.remove(it.id) };sources.forEach { it.delete() }
            prefs.edit().apply {
                if(oldList==null) remove("list") else putBoolean("list",oldList as Boolean)
                if(oldSort==null) remove("sort") else putInt("sort",oldSort as Int)
            }.commit()
        }
    }
}
