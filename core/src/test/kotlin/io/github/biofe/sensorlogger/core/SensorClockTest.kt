package io.github.biofe.sensorlogger.core

import kotlin.test.Test
import kotlin.test.assertEquals

class SensorClockTest {

    private val boot = 1_792_000_000_000L

    @Test
    fun `timestamps on the elapsed-realtime clock are used as they are`() {
        val clock = SensorClock(boot)
        assertEquals(boot + 5_000.0, clock.utcMs(eventNs = 5_000_000_000, arrivalElapsedNs = 5_003_000_000))
        assertEquals(0.0, clock.correctionMs)
    }

    @Test
    fun `a sensor clock that lags by hours is moved onto the real time`() {
        val clock = SensorClock(boot)
        // The sensor clock stood still for 3 h of sleep: its events read 3 h early
        val lag = 3 * 3_600_000_000_000L
        val first = clock.utcMs(eventNs = 20_000_000_000, arrivalElapsedNs = 20_000_000_000 + lag)
        assertEquals(boot + 20_000.0 + lag / 1e6, first)
        // Later events keep their spacing, arriving a few ms late as usual
        assertEquals(first + 10.0, clock.utcMs(eventNs = 20_010_000_000, arrivalElapsedNs = 20_012_000_000 + lag))
        assertEquals(lag / 1e6, clock.correctionMs)
    }

    @Test
    fun `a clock that drifts again during the ride is re-anchored`() {
        val clock = SensorClock(boot)
        clock.utcMs(eventNs = 1_000_000_000, arrivalElapsedNs = 1_001_000_000)
        // Ten minutes later the sensor clock has fallen 30 s behind
        val t = clock.utcMs(eventNs = 570_000_000_000, arrivalElapsedNs = 600_001_000_000)
        assertEquals(boot + 600_001.0, t)
    }

    @Test
    fun `old rides are shifted only when the first row is far from the start`() {
        assertEquals(0.0, SensorClock.realignShiftMs(1_000_040.0, 1_000_000))
        assertEquals(-7_200_000.0, SensorClock.realignShiftMs(8_200_000.0, 1_000_000))
        assertEquals(7_200_000.0, SensorClock.realignShiftMs(1_000_000.0, 8_200_000))
    }
}
