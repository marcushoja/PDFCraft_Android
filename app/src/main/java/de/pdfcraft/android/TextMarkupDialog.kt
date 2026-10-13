package de.pdfcraft.android

import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.*

object MarkupColors {
    val values=intArrayOf(0xfff3d95d.toInt(),0xffffb454.toInt(),0xff8bcb88.toInt(),0xff70b9ed.toInt(),0xffeb87a1.toInt(),0xffb4a0e5.toInt())
    val names=intArrayOf(R.string.ui_color_yellow,R.string.ui_color_orange,R.string.ui_color_green,R.string.ui_color_blue,R.string.ui_color_pink,R.string.ui_color_violet)
}

/** A real PDF markup choice with icon modes, accessible color swatches and live preview. */
class TextMarkupDialog(
    context:Context, private val dark:Boolean, private val page:Int,
    private val initialText:String, initialColor:Int, initialStyle:TextMarkupStyle,
    private val onApply:(String,Int,TextMarkupStyle)->Unit,
):Dialog(context) {
    private val theme=ZenColors.of(dark)
    private var color=initialColor.takeIf { it in MarkupColors.values } ?: MarkupColors.values[0]
    private var style=initialStyle
    private lateinit var input:EditText
    private lateinit var preview:Preview
    private lateinit var colorLabel:TextView
    private val swatches=mutableListOf<View>()
    private val modes=mutableListOf<Pair<TextMarkupStyle,View>>()
    private fun dp(n:Int)=(n*context.resources.displayMetrics.density).toInt()
    private fun label(value:String,size:Float=14f)=TextView(context).apply { text=value;textSize=size;setTextColor(theme.ink) }
    private fun round(fill:Int,radius:Int=14)=GradientDrawable().apply { setColor(fill);cornerRadius=dp(radius).toFloat() }
    override fun onCreate(state:Bundle?) {
        super.onCreate(state);requestWindowFeature(Window.FEATURE_NO_TITLE)
        val body=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(22),dp(20),dp(20));background=round(theme.paper,26) }
        body.addView(label(context.getString(R.string.ui_highlight_text),21f).apply { setTypeface(typeface,android.graphics.Typeface.BOLD) })
        body.addView(label(context.getString(R.string.ui_markup_matches,(page+1).toString()),12f).apply { setTextColor(theme.muted);setPadding(0,dp(8),0,dp(14)) })
        input=EditText(context).apply {
            id=R.id.markup_text;setText(initialText);hint=context.getString(R.string.ui_word_sentence)
            setSingleLine();textSize=16f;setTextColor(theme.ink);setHintTextColor(theme.muted)
            setPadding(dp(14),dp(8),dp(14),dp(8));background=round(theme.card)
        }
        body.addView(input,LinearLayout.LayoutParams(-1,dp(52)))
        val modeRow=LinearLayout(context).apply { setPadding(0,dp(16),0,dp(12)) }
        listOf(Triple(TextMarkupStyle.HIGHLIGHT,"highlight",R.string.ui_markup_highlight),Triple(TextMarkupStyle.UNDERLINE,"underline",R.string.ui_markup_underline),Triple(TextMarkupStyle.STRIKEOUT,"strikeout",R.string.ui_markup_strikeout)).forEach { (type,icon,name) ->
            val cell=LinearLayout(context).apply {
                orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;minimumHeight=dp(72)
                contentDescription=context.getString(name);isFocusable=true;setPadding(dp(3),dp(8),dp(3),dp(8))
                addView(ReaderIconView(context,icon,theme.accent),LinearLayout.LayoutParams(dp(25),dp(25)))
                addView(label(context.getString(name),12f).apply { gravity=Gravity.CENTER;maxLines=2 },LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(5) })
                setOnClickListener { style=type;refresh() }
            }
            modes.add(type to cell);modeRow.addView(cell,LinearLayout.LayoutParams(0,-2,1f).apply { setMargins(dp(2),0,dp(2),0) })
        }
        body.addView(modeRow)
        colorLabel=label("").apply { setTextColor(theme.muted) };body.addView(colorLabel)
        val colors=LinearLayout(context).apply { setPadding(0,dp(8),0,dp(8)) }
        MarkupColors.values.forEachIndexed { index,value ->
            val swatch=object:View(context) {
                private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
                override fun onDraw(canvas:Canvas) {
                    val radius=minOf(width,height)*.32f
                    paint.style=Paint.Style.FILL;paint.color=value
                    canvas.drawCircle(width/2f,height/2f,radius,paint)
                    if(isSelected) {
                        paint.style=Paint.Style.STROKE;paint.color=theme.ink;paint.strokeWidth=dp(2).toFloat()
                        canvas.drawCircle(width/2f,height/2f,radius+dp(4),paint)
                        paint.style=Paint.Style.FILL
                    }
                }
            }.apply {
                id=View.generateViewId();contentDescription=context.getString(MarkupColors.names[index]);isFocusable=true;isClickable=true
                setOnClickListener { color=value;refresh() }
            }
            swatches.add(swatch);colors.addView(swatch,LinearLayout.LayoutParams(0,dp(52),1f))
        }
        body.addView(colors)
        preview=Preview(context).apply { id=R.id.markup_preview;background=round(theme.card) }
        body.addView(preview,LinearLayout.LayoutParams(-1,dp(70)).apply { topMargin=dp(4);bottomMargin=dp(16) })
        input.addTextChangedListener(object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){preview.invalidate()}
            override fun afterTextChanged(s:Editable?){}
        })
        val buttons=LinearLayout(context).apply { gravity=Gravity.END }
        fun action(title:Int,primary:Boolean,callback:()->Unit)=label(context.getString(title)).apply {
            gravity=Gravity.CENTER;minimumHeight=dp(48);setPadding(dp(14),0,dp(14),0);isFocusable=true;isClickable=true
            setTextColor(if(primary) theme.paper else theme.accent);background=round(if(primary) theme.accent else theme.tint,24);setOnClickListener { callback() }
        }
        buttons.addView(action(R.string.ui_cancel,false){dismiss()},LinearLayout.LayoutParams(0,dp(48),1f).apply { rightMargin=dp(8) })
        buttons.addView(action(R.string.ui_markup_create,true){
            val query=input.text.toString().trim().replace(Regex("\\s+")," ")
            if(query.isEmpty()) input.error=context.getString(R.string.ui_enter_value)
            else { dismiss();onApply(query,color,style) }
        },LinearLayout.LayoutParams(0,dp(48),1f))
        body.addView(buttons)
        val scroll=ScrollView(context).apply { addView(body);isFillViewport=false;clipToOutline=true }
        setContentView(scroll);window?.setBackgroundDrawableResource(android.R.color.transparent)
        refresh()
    }
    override fun onStart() {
        super.onStart();window?.setLayout(minOf(context.resources.displayMetrics.widthPixels-dp(24),dp(480)),-2)
    }
    private fun refresh() {
        colorLabel.text=context.getString(R.string.ui_markup_color,context.getString(MarkupColors.names[MarkupColors.values.indexOf(color)]))
        swatches.forEachIndexed { index,view -> view.isSelected=MarkupColors.values[index]==color;view.invalidate() }
        modes.forEach { (type,view) -> view.isSelected=type==style;view.background=round(if(type==style) theme.tint else theme.paper) }
        preview.invalidate()
    }
    private inner class Preview(context:Context):View(context) {
        private val paint=android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize=18*resources.displayMetrics.scaledDensity }
        override fun onDraw(canvas:Canvas) {
            val sample=input.text.toString().trim().ifEmpty { context.getString(R.string.ui_markup_sample) }
            val display=TextUtils.ellipsize(sample,paint,(width-dp(32)).toFloat(),TextUtils.TruncateAt.END).toString()
            val x=dp(16).toFloat();val y=height/2f-(paint.fontMetrics.ascent+paint.fontMetrics.descent)/2f
            val extent=paint.measureText(display)
            if(style==TextMarkupStyle.HIGHLIGHT) {
                paint.color=color;canvas.drawRoundRect(x-dp(3),y+paint.fontMetrics.ascent-dp(2),x+extent+dp(3),y+paint.fontMetrics.descent+dp(2),dp(3).toFloat(),dp(3).toFloat(),paint)
            }
            paint.color=theme.ink;canvas.drawText(display,x,y,paint)
            if(style!=TextMarkupStyle.HIGHLIGHT) {
                paint.color=color;paint.strokeWidth=dp(2).toFloat()
                val lineY=if(style==TextMarkupStyle.UNDERLINE) y+dp(3) else y+paint.fontMetrics.ascent*.35f
                canvas.drawLine(x,lineY,x+extent,lineY,paint)
            }
        }
    }
}
