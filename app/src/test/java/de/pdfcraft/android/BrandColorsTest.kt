package de.pdfcraft.android

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class BrandColorsTest {
    private fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val value = ((color shr shift) and 255) / 255.0
            return if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
        }
        return .2126 * channel(16) + .7152 * channel(8) + .0722 * channel(0)
    }

    @Test fun unicornAccentsHaveAccessibleContrastOnAppSurfaces() {
        assertEquals(0xff147d96.toInt(), ZenColors.light.accent)
        assertEquals(0xff75dfeb.toInt(), ZenColors.dark.accent)
        for (theme in listOf(ZenColors.light, ZenColors.dark)) {
            for (surface in listOf(theme.paper, theme.card, theme.tint)) {
                val a = luminance(theme.accent)
                val b = luminance(surface)
                assertTrue("Accent contrast must meet WCAG AA", (maxOf(a, b) + .05) / (minOf(a, b) + .05) >= 4.5)
            }
        }
    }
}
