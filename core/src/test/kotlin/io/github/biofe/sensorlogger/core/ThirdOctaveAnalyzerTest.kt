package io.github.biofe.sensorlogger.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ThirdOctaveAnalyzerTest {

    private val analyzer = ThirdOctaveAnalyzer(48_000)

    private fun sine(freq: Double, amplitude: Double) =
        DoubleArray(analyzer.frameSamples) { amplitude * sin(2 * PI * freq * it / 48_000) }

    @Test
    fun `has 26 bands from 50 Hz to 16 kHz at 125 ms`() {
        assertEquals(6000, analyzer.frameSamples)
        assertEquals(26, analyzer.bandCenters.size)
        assertEquals("50", analyzer.bandLabels.first())
        assertEquals("1000", analyzer.bandLabels[13])
        assertEquals("16000", analyzer.bandLabels.last())
    }

    @Test
    fun `full-scale 1 kHz sine lands in the 1 kHz band at -3 dBFS`() {
        val result = analyzer.process(sine(1000.0, 1.0))
        val band1k = analyzer.bandLabels.indexOf("1000")
        assertEquals(-3.01, result.bandDb[band1k], 0.2)
        // A-weighting is 0 dB at 1 kHz
        assertEquals(-3.01, result.laeqDb, 0.2)
        // Neighbouring bands are far below
        result.bandDb.forEachIndexed { i, db -> if (abs(i - band1k) > 1) assertTrue(db < -40, "band $i = $db") }
    }

    @Test
    fun `level scales with amplitude and A-weighting attenuates low frequencies`() {
        val result = analyzer.process(sine(125.0, 0.1))
        val band = analyzer.bandLabels.indexOf("125")
        assertEquals(-23.01, result.bandDb[band], 0.5)
        // A-weighting at 125 Hz is about -16.1 dB
        assertEquals(-23.01 - 16.1, result.laeqDb, 0.5)
    }

    @Test
    fun `silence gives the floor value`() {
        val result = analyzer.process(ShortArray(analyzer.frameSamples))
        assertTrue(result.bandDb.all { it <= -199.0 })
    }

    @Test
    fun `44_1 kHz devices also work`() {
        val a = ThirdOctaveAnalyzer(44_100)
        assertEquals(5512, a.frameSamples)
        assertEquals(26, a.bandCenters.size)
    }
}
