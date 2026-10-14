package de.pdfcraft.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.ImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class LibraryOverlayIntegrationTest {
    @Test fun documentMenuHasTransparentIdleBackgroundAndKeepsTouchTarget() {
        val i=InstrumentationRegistry.getInstrumentation();val context=i.targetContext
        val source=File.createTempFile("transparent-menu-",".pdf",context.cacheDir)
        val pdf=PdfDocument()
        try {
            val page=pdf.startPage(PdfDocument.PageInfo.Builder(200,300,1).create())
            page.canvas.drawColor(Color.CYAN);pdf.finishPage(page)
            source.outputStream().use { pdf.writeTo(it) }
        } finally { pdf.close() }
        val store=DocumentStore(context)
        val imported=if(store.list().isEmpty()) store.importDocument(Uri.fromFile(source)) else null
        val loader=ThumbnailLoader(context)
        try {
            i.runOnMainSync {
                val library=LibraryView(context,store,loader,false,{},{},{},{})
                fun descendants(view:View):List<View> = listOf(view)+if(view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
                val grid=descendants(library).filterIsInstance<GridView>().single()
                val cell=grid.adapter.getView(0,null,grid) as LinearLayout
                val cover=(cell.getChildAt(0) as FrameLayout).getChildAt(0) as ImageView
                assertEquals("Covers fill the tile without stretching",ImageView.ScaleType.CENTER_CROP,cover.scaleType)
                assertTrue("The image itself must clip to rounded corners",cover.clipToOutline)
                val outline=Outline()
                cover.background.setBounds(0,0,200,300)
                cover.background.getOutline(outline)
                assertTrue(outline.canClip())
                assertEquals(10*context.resources.displayMetrics.density,outline.radius,1f)
                assertTrue((cell.getChildAt(0) as FrameLayout).clipToOutline)
                val menu=(cell.getChildAt(0) as FrameLayout).getChildAt(1)
                assertTrue(menu.layoutParams.width>=48*context.resources.displayMetrics.density)
                assertNotNull(menu.contentDescription)
                val pixels=Bitmap.createBitmap(96,96,Bitmap.Config.ARGB_8888)
                try {
                    menu.background.setBounds(0,0,96,96);menu.background.state=intArrayOf()
                    menu.background.draw(Canvas(pixels))
                    assertEquals("The cover must remain visible behind the button",0,Color.alpha(pixels.getPixel(48,48)))
                } finally { pixels.recycle() }
            }
        } finally {
            loader.close();assertTrue(loader.awaitClosed(30,TimeUnit.SECONDS))
            imported?.let { store.remove(it.id) };source.delete()
        }
    }
}
