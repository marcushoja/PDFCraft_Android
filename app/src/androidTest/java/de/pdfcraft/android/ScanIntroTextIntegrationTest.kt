package de.pdfcraft.android

import android.content.res.Configuration
import android.graphics.Color
import android.os.LocaleList
import android.text.Spanned
import android.text.style.ImageSpan
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScanIntroTextIntegrationTest {
    @Test fun allLanguagesEmbedActualIconsAndProvideReadableAccessibleText() {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        for(language in listOf("de","en","fr","es")) {
            val context=base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                setLocales(LocaleList.forLanguageTags(language))
            })
            val text=ScanIntroText.text(context,Color.BLACK) as Spanned
            val spans=text.getSpans(0,text.length,ImageSpan::class.java)
            assertEquals(language,4,spans.size)
            spans.forEach { span ->
                assertEquals('\uFFFC',text[text.getSpanStart(span)])
                assertTrue(span.drawable.bounds.width()>0);assertEquals(span.drawable.bounds.width(),span.drawable.bounds.height())
            }
            assertFalse(text.toString().contains("{"))
            val description=ScanIntroText.description(context)
            assertFalse(description.contains("{"));assertFalse(description.contains('\uFFFC'))
            for(id in listOf(R.string.scan_take_photo,R.string.scan_choose_image,R.string.scan_accept,R.string.scan_export_action)) {
                assertTrue(description.contains(context.getString(id)))
            }
        }
    }
}
