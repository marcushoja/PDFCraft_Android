package de.pdfcraft.android

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TextMarkupDialogTest {
    @Test fun reflowSelectionAddsMarkupAndPreservesSelectedDocumentText() {
        val i=InstrumentationRegistry.getInstrumentation()
        val activity=i.startActivitySync(Intent(i.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        i.runOnMainSync {
            val reflow=ReflowView(activity)
            activity.setContentView(reflow)
            reflow.showText("Hello PDF world",0,1)
            var selected:String?=null
            reflow.onMarkSelection={selected=it}
            android.text.Selection.setSelection(reflow.textView.text as android.text.Spannable,6,9)
            val callback=reflow.textView.customSelectionActionModeCallback!!
            val mode=activity.startActionMode(callback)!!
            val item=mode.menu.findItem(R.id.mark_selection)
            assertNotNull(item)
            assertTrue(callback.onActionItemClicked(mode,item))
            assertEquals("PDF",selected)
            activity.finish()
        }
    }
    @Test fun colorModeAndSelectedTextReachRealMarkupCallback() {
        val i=InstrumentationRegistry.getInstrumentation()
        val activity=i.startActivitySync(Intent(i.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        i.runOnMainSync {
            var result:Triple<String,Int,TextMarkupStyle>?=null
            val dialog=TextMarkupDialog(activity,false,0,"Hello",MarkupColors.values[0],TextMarkupStyle.HIGHLIGHT) { text,color,style -> result=Triple(text,color,style) }
            dialog.show()
            fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
            val nodes=views(dialog.window!!.decorView)
            nodes.single { it.contentDescription==activity.getString(R.string.ui_color_blue) }.performClick()
            nodes.single { it.contentDescription==activity.getString(R.string.ui_markup_underline) }.performClick()
            nodes.filterIsInstance<TextView>().single { it.text==activity.getString(R.string.ui_markup_create) }.performClick()
            assertEquals(Triple("Hello",MarkupColors.values[3],TextMarkupStyle.UNDERLINE),result)
            activity.finish()
        }
    }
}
