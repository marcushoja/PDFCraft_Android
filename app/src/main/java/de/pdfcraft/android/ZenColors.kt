package de.pdfcraft.android

/** Sage, warm paper and cedar accents: quiet surfaces with accessible contrast. */
data class ZenTheme(val ink: Int, val muted: Int, val paper: Int, val card: Int, val accent: Int, val tint: Int, val brown: Int, val outline: Int)
object ZenColors {
    val light = ZenTheme(0xff28372d.toInt(),0xff6e7567.toInt(),0xfff7f5ed.toInt(),0xfffffdf6.toInt(),0xff496650.toInt(),0xffe1eadc.toInt(),0xff896b4f.toInt(),0xffaebca5.toInt())
    val dark = ZenTheme(0xffe8eddf.toInt(),0xffb1bfaa.toInt(),0xff18211b.toInt(),0xff263229.toInt(),0xffb3cda4.toInt(),0xff344537.toInt(),0xffd2b596.toInt(),0xff5d725e.toInt())
    fun of(dark: Boolean) = if (dark) this.dark else light
}
