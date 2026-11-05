package de.pdfcraft.android

import org.junit.Assert.assertEquals
import org.junit.Test

class PdfTurnEdgesTest {
    @Test fun fullOuterTwentyPercentIncludesBothBoundariesOnPhonesAndWideScreens() {
        for (width in listOf(320, 1080, 1440, 2560)) {
            for (fraction in listOf(0f, .04f, .10f, .15f, .19f, .20f)) {
                assertEquals(-1, PdfTurnEdges.at(width * fraction, width))
            }
            for (fraction in listOf(.80f, .81f, .85f, .90f, .96f, 1f)) {
                assertEquals(1, PdfTurnEdges.at(width * fraction, width))
            }
        }
    }

    @Test fun middleSixtyPercentCannotStartATurn() {
        for (width in listOf(320, 1080, 1440, 2560)) {
            for (fraction in listOf(.201f, .21f, .25f, .5f, .75f, .79f, .799f)) {
                assertEquals(0, PdfTurnEdges.at(width * fraction, width))
            }
        }
    }

    @Test fun invalidAndOffscreenCoordinatesCannotStartATurn() {
        for (x in listOf(-1f, 1081f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertEquals(0, PdfTurnEdges.at(x, 1080))
        }
        assertEquals(0, PdfTurnEdges.at(0f, 0))
        assertEquals(0, PdfTurnEdges.at(0f, -1))
    }
}
