package de.pdfcraft.android

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OutlinePanelIntegrationTest {
    @Test fun expandsSearchesAndNavigatesNestedChapterCards() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val activity=instrumentation.startActivitySync(Intent(instrumentation.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        lateinit var panel:OutlinePanel
        var destination=-1
        fun views(view:View):List<View> = listOf(view)+if(view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
        instrumentation.runOnMainSync {
            panel=OutlinePanel(activity,false,listOf(OutlineEntry("Chapter One",0,0),OutlineEntry("Child Section",1,1),OutlineEntry("Chapter Two",4,0)),0,{destination=it},{})
            activity.setContentView(panel)
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            val list=views(panel).filterIsInstance<ListView>().single()
            assertEquals(2,list.adapter.count)
            val title=views(panel).filterIsInstance<TextView>().single { it.text.toString()=="Chapter One" }
            (title.parent as View).performClick()
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertEquals(3,views(panel).filterIsInstance<ListView>().single().adapter.count)
            views(panel).filterIsInstance<EditText>().single().setText("CHILD")
        }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertEquals("Search should keep matching child and its ancestor",2,views(panel).filterIsInstance<ListView>().single().adapter.count)
            val child=views(panel).filterIsInstance<TextView>().single { it.text.toString()=="Child Section" }
            (child.parent as View).performClick()
            assertEquals(1,destination)
            activity.finish()
        }
    }
}
