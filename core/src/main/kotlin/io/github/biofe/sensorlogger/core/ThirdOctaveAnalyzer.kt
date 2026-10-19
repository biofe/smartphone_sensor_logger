package io.github.biofe.sensorlogger.core

import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Turns one short audio frame (125 ms by default) into 1/3-octave band levels and an
 * A-weighted equivalent level. Only these numbers are stored; the raw audio is discarded,
 * so speech cannot be reconstructed from the output.
 *
 * Levels are in dBFS: 0 dB is the mean square of a full-scale (±1.0) signal, so a full-scale
 * sine reads about -3 dB. The microphone is not calibrated, so compare levels relative to each
 * other (and per device), not as absolute dB(A).
 */
class ThirdOctaveAnalyzer(val sampleRate: Int, val frameSeconds: Double = 0.125) {

    val frameSamples: Int = (sampleRate * frameSeconds).toInt()
    private val fftSize: Int = Integer.highestOneBit(frameSamples - 1) shl 1

    /** Exact base-10 band centre frequencies, 50 Hz to 16 kHz (26 bands), limited to below Nyquist. */
    val bandCenters: DoubleArray =
        (-13..12).map { 1000.0 * 10.0.pow(it / 10.0) }
            .filter { it * BAND_EDGE_FACTOR < sampleRate / 2.0 }
            .toDoubleArray()

    /** Nominal labels for the bands (50, 63, 80, ... 16000), used as CSV column names. */
    val bandLabels: List<String> = bandCenters.map { nominalLabel(it) }

    private val window = DoubleArray(frameSamples) { i ->
        0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (frameSamples - 1))
    }
    private val windowPower = window.sumOf { it * it }

    private val binBand = IntArray(fftSize / 2 + 1) { k ->
        val f = k.toDouble() * sampleRate / fftSize
        bandCenters.indexOfFirst { f >= it / BAND_EDGE_FACTOR && f < it * BAND_EDGE_FACTOR }
    }
    private val binAWeight = DoubleArray(fftSize / 2 + 1) { k ->
        val f = k.toDouble() * sampleRate / fftSize
        if (f < 20.0 || f > 20000.0) 0.0 else 10.0.pow(aWeightingDb(f) / 10.0)
    }

    private val re = DoubleArray(fftSize)
    private val im = DoubleArray(fftSize)

    class Result(val bandDb: DoubleArray, val laeqDb: Double)

    /** Analyses [frameSamples] 16-bit PCM samples starting at [offset]. Not thread-safe. */
    fun process(samples: ShortArray, offset: Int = 0): Result {
        require(samples.size - offset >= frameSamples) { "Need $frameSamples samples" }
        for (i in 0 until frameSamples) re[i] = samples[offset + i] / 32768.0 * window[i]
        re.fill(0.0, frameSamples, fftSize)
        im.fill(0.0)
        return analyse()
    }

    /** Same as the PCM variant, for samples already scaled to ±1.0. */
    fun process(samples: DoubleArray, offset: Int = 0): Result {
        require(samples.size - offset >= frameSamples) { "Need $frameSamples samples" }
        for (i in 0 until frameSamples) re[i] = samples[offset + i] * window[i]
        re.fill(0.0, frameSamples, fftSize)
        im.fill(0.0)
        return analyse()
    }

    private fun analyse(): Result {
        fft(re, im)
        val bandPower = DoubleArray(bandCenters.size)
        var aPower = 0.0
        val norm = fftSize * windowPower
        for (k in 0..fftSize / 2) {
            val oneSided = if (k == 0 || k == fftSize / 2) 1.0 else 2.0
            // Mean-square contribution of this bin (Parseval, corrected for the window)
            val ms = oneSided * (re[k] * re[k] + im[k] * im[k]) / norm
            val band = binBand[k]
            if (band >= 0) bandPower[band] += ms
            aPower += ms * binAWeight[k]
        }
        return Result(DoubleArray(bandPower.size) { toDb(bandPower[it]) }, toDb(aPower))
    }

    companion object {
        /** Ratio between a 1/3-octave band's centre and its edges (base-10 definition). */
        val BAND_EDGE_FACTOR: Double = 10.0.pow(1.0 / 20.0)

        private const val FLOOR_DB = -200.0

        private fun toDb(meanSquare: Double): Double =
            if (meanSquare <= 0.0) FLOOR_DB else maxOf(FLOOR_DB, 10.0 * log10(meanSquare))

        private val NOMINAL = intArrayOf(
            50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630, 800,
            1000, 1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000, 10000, 12500, 16000,
        )

        private fun nominalLabel(center: Double): String =
            NOMINAL.minByOrNull { Math.abs(log10(it.toDouble()) - log10(center)) }!!.toString()

        /** IEC 61672 A-weighting in dB. */
        fun aWeightingDb(f: Double): Double {
            val f2 = f * f
            val ra = (12194.0 * 12194.0 * f2 * f2) /
                ((f2 + 20.6 * 20.6) * sqrt((f2 + 107.7 * 107.7) * (f2 + 737.9 * 737.9)) * (f2 + 12194.0 * 12194.0))
            return 20.0 * log10(ra) + 2.0
        }
    }
}
