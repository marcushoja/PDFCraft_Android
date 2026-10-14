package de.pdfcraft.android

import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.LocaleList
import android.widget.ImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SupportIntegrationTest {
    private val i=InstrumentationRegistry.getInstrumentation()
    private val context get()=i.targetContext

    @Test fun greenSupportIconOpensOptionalDialogAndOnlyConfirmationContinues() {
        val activity=i.startActivitySync(Intent(context,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val loader=ThumbnailLoader(context)
        var continued=0
        var created=false
        lateinit var dialog:AlertDialog
        try {
            i.runOnMainSync {
                assertNotNull(activity.findViewById<ImageView>(R.id.support_button))
                val library=LibraryView(activity,DocumentStore(activity),loader,false,{},{},{},{},onSupport={
                    dialog=SupportDialog.create(activity) { continued++ };created=true;dialog.show()
                })
                activity.setContentView(library)
                val icon=library.findViewById<ImageView>(R.id.support_button)
                assertEquals(activity.getString(R.string.support_title),icon.contentDescription)
                val image=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888)
                try {
                    icon.drawable.setBounds(0,0,64,64);icon.drawable.draw(Canvas(image))
                    val pixel=image.getPixel(32,28)
                    assertTrue(Color.green(pixel)>Color.red(pixel));assertTrue(Color.green(pixel)>Color.blue(pixel))
                    assertEquals(255,Color.alpha(pixel))
                } finally { image.recycle() }
                assertEquals(0,continued)
                icon.performClick();assertTrue(dialog.isShowing);assertEquals(0,continued)
                assertEquals(activity.getString(R.string.support_message),dialog.findViewById<android.widget.TextView>(android.R.id.message).text.toString())
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();assertEquals(0,continued)
                fun descendants(view:android.view.View):List<android.view.View> = listOf(view)+if(view is android.view.ViewGroup)
                    (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
                val footerLink=descendants(library).filterIsInstance<android.widget.TextView>().single { it.text.toString()=="Buy me some tokens" }
                footerLink.performClick();assertTrue(dialog.isShowing);assertEquals(0,continued)
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();assertEquals(0,continued)
            }
            i.waitForIdleSync();assertEquals(0,continued)
            i.runOnMainSync {
                activity.findViewById<ImageView>(R.id.support_button).performClick()
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            }
            i.waitForIdleSync();assertEquals(1,continued)
        } finally {
            i.runOnMainSync { if(created) dialog.dismiss();activity.finish() };i.waitForIdleSync()
            loader.close();assertTrue(loader.awaitClosed(30,TimeUnit.SECONDS))
        }
    }

    @Test fun externalSupportIntentUsesEstablishedLinkAndContainsNoDocumentData() {
        val intent=ProjectSupport.intent()
        assertEquals(Intent.ACTION_VIEW,intent.action)
        assertEquals("https",intent.data!!.scheme);assertEquals("buy.stripe.com",intent.data!!.host)
        assertEquals("/5kQ00c1yP9PQfDP6vV9bO04",intent.data!!.path)
        assertEquals("pdfcraft_android",intent.data!!.getQueryParameter("client_reference_id"))
        assertTrue(intent.categories!!.contains(Intent.CATEGORY_BROWSABLE))
        assertNull(intent.extras);assertNull(intent.clipData)
        assertEquals(0,intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    @Test fun supportNoticeIsLocalizedInAllFourLanguages() {
        val titles=mapOf("de" to "PDFCraft unterstützen","en" to "Support PDFCraft","fr" to "Soutenir PDFCraft","es" to "Apoyar PDFCraft")
        val actions=mapOf("de" to "Kaffee spendieren","en" to "Buy me a coffee","fr" to "Offrir un café","es" to "Invitar a un café")
        for((tag,title) in titles) {
            val localized=context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocales(LocaleList.forLanguageTags(tag)) })
            assertEquals(title,localized.getString(R.string.support_title))
            assertTrue(localized.getString(R.string.support_message).contains("Stripe"))
            assertEquals(actions[tag],localized.getString(R.string.support_action))
        }
    }
}
