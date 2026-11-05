package de.pdfcraft.android

/** Unicorn turquoise with quiet surfaces and accessible contrast in both themes. */
data class ZenTheme(val ink: Int, val muted: Int, val paper: Int, val card: Int, val accent: Int, val tint: Int, val brown: Int, val outline: Int)
object ZenColors {
    val light = ZenTheme(0xff163943.toInt(),0xff526e76.toInt(),0xfff3fafb.toInt(),0xfffcffff.toInt(),0xff147d96.toInt(),0xfff0fcff.toInt(),0xff896b4f.toInt(),0xff9cbfc7.toInt())
    val dark = ZenTheme(0xffe1f5f7.toInt(),0xffa7c4cb.toInt(),0xff12252b.toInt(),0xff20363e.toInt(),0xff75dfeb.toInt(),0xff294a55.toInt(),0xffd2b596.toInt(),0xff527680.toInt())
    fun of(dark: Boolean) = if (dark) this.dark else light
}
