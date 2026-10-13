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
class PdfLoadingIntegrationTest {
    @Test fun loadingSurfaceShowsByteAndPageProgressAndCancels() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        var cancelled = false
        instrumentation.runOnMainSync {
            val loading = PdfLoadingView(activity,false,"Large document.pdf") { cancelled=true }
            activity.setContentView(loading)
            loading.updateCopy(10L*1024*1024,60L*1024*1024)
            fun texts(view:View):List<TextView> = if(view is TextView) listOf(view) else if(view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else emptyList()
            assertTrue(texts(loading).any { it.text.toString().contains("60") })
            loading.updateProgress(125,500)
            assertTrue(texts(loading).any { it.text.toString().contains("125 / 500") })
            texts(loading).single { it.contentDescription == UiText.t(activity,"Laden abbrechen") }.performClick()
            assertTrue(cancelled)
            activity.finish()
        }
    }
}
