package de.pdfcraft.android

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/** Quiet, cancellable loading surface, used for copying, metadata and first-page rendering. */
class PdfLoadingView(context: Context, dark: Boolean, title: String, onCancel: () -> Unit) : FrameLayout(context) {
    private val accent = ZenColors.of(dark).accent
    private val ink = ZenColors.of(dark).ink
    private val subtitle = TextView(context).apply { textSize = 14f; gravity = Gravity.CENTER; setTextColor(ZenColors.of(dark).muted) }
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        isIndeterminate = true; max = 1000; progressTintList = android.content.res.ColorStateList.valueOf(accent); indeterminateTintList = android.content.res.ColorStateList.valueOf(accent)
    }
    init {
        id = R.id.pdf_loading
        setBackgroundColor(ZenColors.of(dark).paper)
        isClickable = true
        contentDescription = UiText.t(context,"PDF wird geladen")
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(28),dp(28),dp(28),dp(28)) }
        body.addView(BookAnimation(context, accent), LinearLayout.LayoutParams(dp(96),dp(110)))
        body.addView(TextView(context).apply { text = title; textSize = 22f; gravity = Gravity.CENTER; setTextColor(ink); setTypeface(typeface, Typeface.BOLD); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = dp(12) })
        body.addView(subtitle,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = dp(24) })
        body.addView(progress,LinearLayout.LayoutParams(-1,dp(5)))
        body.addView(TextView(context).apply { text = UiText.t(context,"Abbrechen"); contentDescription = UiText.t(context,"Laden abbrechen"); textSize = 15f; setTextColor(accent); gravity = Gravity.CENTER; minHeight = dp(48); isFocusable = true; setOnClickListener { onCancel() }; background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(ZenColors.of(dark).tint) } }, LinearLayout.LayoutParams(-1,dp(48)).apply { topMargin = dp(24) })
        addView(body, LayoutParams(minOf(resources.displayMetrics.widthPixels - dp(32),dp(380)),-2,Gravity.CENTER))
        setMessage("Deine Seiten werden vorbereitet …")
    }
    fun setMessage(message: String) { subtitle.text = UiText.t(context,message) }
    fun updateProgress(loaded: Int, total: Int) {
        progress.isIndeterminate = total <= 0
        if (total > 0) progress.progress = (loaded.toLong()*1000/total).toInt().coerceIn(0,1000)
        setMessage(if (total > 0) "Seiten vorbereiten · $loaded / $total" else "Dokument wird geöffnet …")
    }
    fun updateCopy(copied: Long, total: Long) {
        progress.isIndeterminate = total <= 0
        if (total > 0) progress.progress = (copied*1000/total).toInt().coerceIn(0,1000)
        fun mb(bytes: Long) = String.format(resources.configuration.locales[0],"%.1f",bytes/1048576.0)
        setMessage(if (total > 0) "Lokale Kopie · ${mb(copied)} / ${mb(total)} MB" else "Lokale Kopie · ${mb(copied)} MB")
    }
    private fun dp(n: Int) = (n*resources.displayMetrics.density).toInt()

    private class BookAnimation(context: Context, color: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE; strokeWidth = 2f; strokeCap = Paint.Cap.ROUND }
        private var phase = 0f
        private val animator = ValueAnimator.ofFloat(0f,1f).apply { duration = 1600; repeatCount = ValueAnimator.INFINITE; addUpdateListener { phase=it.animatedValue as Float; invalidate() } }
        override fun onAttachedToWindow() { super.onAttachedToWindow(); animator.start() }
        override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }
        override fun onVisibilityChanged(changedView: View, visibility: Int) { super.onVisibilityChanged(changedView,visibility); if (visibility == VISIBLE && isAttachedToWindow) animator.start() else animator.cancel() }
        override fun onDraw(canvas: Canvas) {
            canvas.save(); canvas.scale(width/96f,height/110f)
            paint.alpha=35; canvas.drawRoundRect(22f,14f,79f,91f,8f,8f,paint)
            paint.alpha=65; canvas.drawRoundRect(16f,10f,73f,87f,8f,8f,paint)
            paint.alpha=255; canvas.drawRoundRect(10f,6f,67f,83f,8f,8f,paint)
            for(i in 0..4) { line.alpha = if (phase * 5 > i) 235 else 90; canvas.drawLine(23f,30f+i*8,54f,30f+i*8,line) }
            canvas.restore()
        }
    }
}
