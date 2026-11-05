package de.pdfcraft.android

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView

/** An inline library notice, never a dialog or an overlay over the reader. */
internal class UpdateNoticeView(
    context: Context, dark: Boolean, release: AppRelease,
    onDownload: () -> Unit, onDismiss: () -> Unit,
) : LinearLayout(context) {
    init {
        id = R.id.update_notice
        val colors = ZenColors.of(dark)
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(4), dp(4), dp(4))
        background = GradientDrawable().apply { setColor(colors.tint); cornerRadius = dp(14).toFloat() }
        addView(TextView(context).apply {
            text = context.getString(R.string.update_available, release.versionName)
            textSize = 14f
            setTextColor(colors.ink)
        }, LayoutParams(0, -2, 1f))
        addView(TextView(context).apply {
            id = R.id.update_download
            text = context.getString(R.string.update_download)
            textSize = 14f
            setTextColor(colors.accent)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            minHeight = dp(48)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            isClickable = true; isFocusable = true
            setOnClickListener { onDownload() }
        }, LayoutParams(-2, -2))
        addView(ImageButton(context).apply {
            id = R.id.update_dismiss
            contentDescription = context.getString(R.string.update_dismiss)
            tooltipText = contentDescription
            setImageDrawable(MaterialSymbols.drawable(context, "close", colors.muted))
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setOnClickListener { onDismiss() }
        }, LayoutParams(dp(48), dp(48)))
    }

    private fun dp(value: Int) = (resources.displayMetrics.density * value).toInt()
}
