package io.github.biofe.sensorlogger.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StopDetectorTest {

    private val min = 60_000L

    // ~11.1 m per 0.0001 deg latitude
    private fun at(tMs: Long, metresNorth: Double) = GeoPoint(tMs, 52.0 + metresNorth / 111_195.0, 13.0)

    @Test
    fun `long wait at a traffic light does not stop the ride`() {
        val d = StopDetector(0)
        for (s in 0..120) d.onFix(at(s * 1000L, s * 5.0), 5.0) // 2 min riding
        for (s in 121..420) d.onFix(at(s * 1000L, 600.0 + (s % 3)), 8.0) // 5 min waiting, GPS jitter
        assertFalse(d.shouldStop(420_000))
        for (s in 421..480) d.onFix(at(s * 1000L, 603.0 + (s - 420) * 5.0), 5.0) // rides on
        assertFalse(d.shouldStop(480_000 + 9 * min))
    }

    @Test
    fun `parked for ten minutes stops the ride`() {
        val d = StopDetector(0)
        for (s in 0..120) d.onFix(at(s * 1000L, s * 5.0), 5.0)
        for (s in 121..1000) d.onFix(at(s * 1000L, 600.0 + (s % 7) * 3.0), 10.0)
        assertEquals(121_000L, d.stillSinceMs)
        assertFalse(d.shouldStop(121_000 + 10 * min - 1))
        assertTrue(d.shouldStop(121_000 + 10 * min))
    }

    @Test
    fun `inaccurate indoor fixes don't count as moving`() {
        val d = StopDetector(0)
        d.onFix(at(0, 0.0), 5.0)
        for (s in 1..700) d.onFix(at(s * 1000L, (s % 2) * 150.0), 80.0)
        assertTrue(d.shouldStop(700_000))
    }
}
