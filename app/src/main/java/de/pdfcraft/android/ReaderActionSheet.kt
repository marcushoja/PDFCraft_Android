package de.pdfcraft.android

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

data class ReaderAction(val id: String, val label: String, val icon: String, val destructive: Boolean = false)

/** The caller supplies only actions that are available for the current document. */
class ReaderActionSheet(
    context: Context,
    private val dark: Boolean,
    private val actions: List<ReaderAction>,
    private val title: String? = null,
    private val subtitle: String? = null,
    private val onAction: (String) -> Unit
) : Dialog(context) {
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val foreground = ZenColors.of(dark).ink
        val surface = ZenColors.of(dark).tint
        val grid = LinearLayout(context).apply {
            id = R.id.reader_actions
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(20), dp(10), dp(20))
        }
        title?.let { value ->
            grid.addView(TextView(context).apply { text=UiText.t(context,value);textSize=20f;setTextColor(foreground);setTypeface(typeface,android.graphics.Typeface.BOLD);setPadding(dp(14),0,dp(14),dp(4)) })
            subtitle?.let { message -> grid.addView(TextView(context).apply { text=UiText.t(context,message);textSize=12f;setTextColor(ZenColors.of(dark).muted);setPadding(dp(14),0,dp(14),dp(14)) }) }
        }
        actions.chunked(4).forEach { group ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            group.forEach { action ->
                val color = if(action.destructive) if(dark) 0xffe8b4a6.toInt() else 0xff95584a.toInt() else foreground
                val cell = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    minimumHeight = dp(if(title==null) 96 else 106)
                    setPadding(dp(3), dp(12), dp(3), dp(10))
                    contentDescription = UiText.t(context,action.label)
                    tooltipText = contentDescription
                    isFocusable = true
                    background = RippleDrawable(ColorStateList.valueOf(if (dark) 0x33FFFFFF else 0x22000000),
                        null, GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(16).toFloat() })
                    setOnClickListener { dismiss(); onAction(action.id) }
                }
                cell.addView(ReaderIconView(context, action.icon, color).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(27), dp(27)))
                cell.addView(TextView(context).apply {
                    text = UiText.t(context,action.label)
                    textSize = 14f
                    setTextColor(color)
                    gravity = Gravity.CENTER
                    maxLines = if(title==null) 2 else 3
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(9) })
                row.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            repeat(4 - group.size) { row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f)) }
            grid.addView(row)
        }
        val scroll = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val cap = (context.resources.displayMetrics.heightPixels * .82f).toInt()
                val available = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) cap
                    else minOf(cap, MeasureSpec.getSize(heightMeasureSpec))
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST))
            }
        }.apply { isFillViewport = false; isVerticalScrollBarEnabled = false; clipToOutline = true }
        scroll.background = GradientDrawable().apply { setColor(surface); cornerRadius = dp(28).toFloat() }
        scroll.addView(grid)
        setContentView(scroll)
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        setCanceledOnTouchOutside(true)
    }

    override fun onStart() {
        super.onStart()
        window?.apply {
            setGravity(Gravity.CENTER)
            setLayout(minOf((context.resources.displayMetrics.widthPixels * .9f).toInt(), dp(600)), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }
}
