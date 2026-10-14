package de.pdfcraft.android

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.widget.LinearLayout
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
        i.runOnMainSync { assertTrue(activity.findViewById<android.view.View>(R.id.scan_accept_button).performClick()) }
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
