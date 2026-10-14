package de.pdfcraft.android

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.widget.LinearLayout
import android.view.View
import android.view.ViewGroup
import android.net.Uri
import android.view.inspector.WindowInspector
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.content.FileProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ScanActivityIntegrationTest {
    private fun descendants(view:View):List<View> = listOf(view)+if(view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    @Test fun selectedImageUsesSimpleControlsAndExportIncludesCurrentEdit() {
        val prefs=context.getSharedPreferences("scanner_draft",0);val old=prefs.getString("session",null)
        val settings=context.getSharedPreferences("scanner_settings",0);val oldAuto=settings.all["auto_next"]
        val oldMode=settings.getString("mode",null)
        prefs.edit().remove("session").commit();settings.edit().putBoolean("auto_next",true).commit()
        val source=File.createTempFile("picked-image-",".jpg",context.cacheDir)
        val bitmap=Bitmap.createBitmap(120,180,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,95,it) };bitmap.recycle()
        val original=source.readBytes();val activity=launch();val session=field(activity,"session") as File
        try {
            i.runOnMainSync {
                val menu=field(activity,"more") as View
                assertFalse(menu.isEnabled);assertTrue(menu.alpha<.5f)
                assertNull(activity.findViewById<View>(R.id.scan_auto_next))
                assertEquals(0,(field(activity,"actions") as LinearLayout).childCount)
                val intro=activity.findViewById<android.widget.TextView>(R.id.scan_intro)
                val styled=intro.text as android.text.Spanned
                assertEquals(4,styled.getSpans(0,styled.length,android.text.style.ImageSpan::class.java).size)
                assertFalse(intro.contentDescription.toString().contains('\uFFFC'))
            }
            i.runOnMainSync {
                ScanActivity::class.java.getDeclaredMethod("importImage",Uri::class.java).apply { isAccessible=true }.invoke(activity,Uri.fromFile(source))
            };idle(activity)
            assertEquals(false,field(activity,"cropFromCamera"))
            i.runOnMainSync {
                val labels=listOf(R.string.scan_rotate,R.string.scan_edit_page,R.string.scan_delete_page,R.string.scan_accept,R.string.scan_export_action)
                labels.forEach { label -> assertEquals(1,descendants(activity.window.decorView).count { it.contentDescription==activity.getString(label) }) }
                assertFalse(descendants(activity.window.decorView).any { it.contentDescription==activity.getString(R.string.scan_action_filter) })
                assertNull(activity.findViewById<View>(R.id.scan_next_button))
                val adjust=activity.findViewById<View>(R.id.scan_adjust_button)
                assertFalse(adjust.isEnabled);assertTrue(adjust.alpha<.5f)
                descendants(activity.window.decorView).single { it.contentDescription==activity.getString(R.string.scan_rotate) }.performClick()
            };idle(activity)
            ScanProcessing.loadPhoto(File(session,field(activity,"cropRaw") as String)).also {
                assertEquals(180,it.width);assertEquals(120,it.height)
            }.recycle()
            i.runOnMainSync {
                ScanActivity::class.java.getDeclaredField("cropMode").apply { isAccessible=true }.set(activity,ScanColorMode.COLOR)
                invoke(activity,"refresh")
                activity.findViewById<View>(R.id.scan_grayscale).performClick()
                assertEquals(ScanColorMode.GRAYSCALE,field(activity,"cropMode"))
                assertTrue(activity.findViewById<View>(R.id.scan_grayscale).isSelected)
                assertFalse(activity.findViewById<View>(R.id.scan_black_white).isSelected)
                activity.findViewById<View>(R.id.scan_black_white).performClick()
                assertTrue(activity.findViewById<View>(R.id.scan_black_white).isSelected)
                assertFalse(activity.findViewById<View>(R.id.scan_grayscale).isSelected)
                activity.findViewById<View>(R.id.scan_black_white).performClick()
                assertEquals(ScanColorMode.COLOR,field(activity,"cropMode"))
                activity.findViewById<View>(R.id.scan_grayscale).performClick()
                activity.findViewById<View>(R.id.scan_grayscale).performClick()
                assertEquals(ScanColorMode.COLOR,field(activity,"cropMode"))
                activity.findViewById<View>(R.id.scan_black_white).performClick()
                activity.findViewById<View>(R.id.scan_export_button).performClick()
            };idle(activity)
            i.runOnMainSync {
                val windows=WindowInspector.getGlobalWindowViews().flatMap { descendants(it) }
                assertTrue(windows.filterIsInstance<EditText>().any { it.hint==activity.getString(R.string.scan_name) })
                windows.last { it.id==android.R.id.button2 && it.isShown }.performClick()
            };i.waitForIdleSync()
            assertNull(field(activity,"pending"));assertNull(field(activity,"cropRaw"))
            i.runOnMainSync { assertEquals(1,activity.findViewById<LinearLayout>(R.id.scan_strip).childCount) }
            i.runOnMainSync {
                assertTrue(activity.findViewById<View>(R.id.scan_adjust_button).isEnabled)
                assertEquals(1f,activity.findViewById<View>(R.id.scan_adjust_button).alpha,.001f)
                assertNull(activity.findViewById<View>(R.id.scan_accept_button))
            }
            assertArrayEquals(original,source.readBytes())
            assertEquals("DOCUMENT",JSONObject(File(session,"session.json").readText()).getJSONArray("pages").getJSONObject(0).getString("mode"))
        } finally {
            i.runOnMainSync { activity.finish() };i.waitForIdleSync();ScanActivity.clearImportedDraft(context,session.name);source.delete()
            if(old==null) prefs.edit().remove("session").commit() else prefs.edit().putString("session",old).commit()
            if(oldAuto==null) settings.edit().remove("auto_next").commit() else settings.edit().putBoolean("auto_next",oldAuto as Boolean).commit()
            if(oldMode==null) settings.edit().remove("mode").commit() else settings.edit().putString("mode",oldMode).commit()
        }
    }
    private val i=InstrumentationRegistry.getInstrumentation()
    private val context get()=i.targetContext
    private fun field(activity:ScanActivity,name:String)=ScanActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.get(activity)
    private fun invoke(activity:ScanActivity,name:String)=ScanActivity::class.java.getDeclaredMethod(name).apply { isAccessible=true }.invoke(activity)
    private fun idle(activity:ScanActivity) {
        repeat(200) {
            i.waitForIdleSync()
            var busy=true;i.runOnMainSync { busy=field(activity,"busy") as Boolean }
            if(!busy) return
            Thread.sleep(50)
        }
        fail("Scanner did not complete its bounded background operation")
    }
    private fun launch()=i.startActivitySync(Intent(context,ScanActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ScanActivity
    private fun photograph(activity:ScanActivity,color:Int) {
        val session=field(activity,"session") as File
        val source=File(session,"test-camera-${System.nanoTime()}.jpg")
        val bitmap=Bitmap.createBitmap(400,600,Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).apply { drawColor(color);drawText(source.name,20f,80f,Paint().apply { this.color=Color.BLACK;textSize=16f }) }
            source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG,95,it)) }
        } finally { bitmap.recycle() }
        val cameraUri=FileProvider.getUriForFile(context,"${context.packageName}.files",source)
        assertArrayEquals(source.readBytes(),context.contentResolver.openInputStream(cameraUri)!!.use { it.readBytes() })
        i.runOnMainSync {
            ScanActivity::class.java.getDeclaredField("pending").apply { isAccessible=true }.set(activity,source.name)
            invoke(activity,"saveDraft");invoke(activity,"onResume")
        }
        idle(activity)
        i.runOnMainSync { activity.findViewById<View>(R.id.scan_accept_button).performClick() }
        idle(activity)
    }

    @Test fun cameraOutputRecoveryCollectsPagesReordersAndResumesDurableDraft() {
        val prefs=context.getSharedPreferences("scanner_draft",0);val old=prefs.getString("session",null)
        val settings=context.getSharedPreferences("scanner_settings",0);val oldAuto=settings.all["auto_next"]
        settings.edit().putBoolean("auto_next",false).commit()
        prefs.edit().remove("session").commit()
        var activity=launch();val session=field(activity,"session") as File
        try {
            photograph(activity,Color.CYAN);photograph(activity,Color.YELLOW)
            i.runOnMainSync {
                assertEquals(2,activity.findViewById<LinearLayout>(R.id.scan_strip).childCount)
                ScanActivity::class.java.getDeclaredMethod("move",Int::class.javaPrimitiveType).apply { isAccessible=true }.invoke(activity,-1)
            }
            val before=JSONObject(File(session,"session.json").readText()).getJSONArray("pages")
            i.runOnMainSync { activity.finish() };i.waitForIdleSync()
            activity=launch();idle(activity)
            assertEquals(session,field(activity,"session"))
            i.runOnMainSync { assertEquals(2,activity.findViewById<LinearLayout>(R.id.scan_strip).childCount) }
            val after=JSONObject(File(session,"session.json").readText()).getJSONArray("pages")
            assertEquals(before.toString(),after.toString())
            assertTrue("A cancelled import must leave photos available",session.listFiles()!!.any { it.name.startsWith("test-camera-") })
            assertFalse(ScanActivity.clearImportedDraft(context,"../scanner-drafts"))
            assertTrue(session.exists())
        } finally {
            i.runOnMainSync { activity.finish() };i.waitForIdleSync()
            assertTrue(ScanActivity.clearImportedDraft(context,session.name))
            if(old==null) prefs.edit().remove("session").commit() else prefs.edit().putString("session",old).commit()
            if(oldAuto==null) settings.edit().remove("auto_next").commit() else settings.edit().putBoolean("auto_next",oldAuto as Boolean).commit()
        }
    }
}
