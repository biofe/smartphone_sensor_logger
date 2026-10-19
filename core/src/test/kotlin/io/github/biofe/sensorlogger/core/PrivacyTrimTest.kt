package io.github.biofe.sensorlogger.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PrivacyTrimTest {

    // ~11.1 m per 0.0001 deg latitude; one point per second, heading north
    private fun straightRide(seconds: Int) =
        (0..seconds).map { GeoPoint(it * 1000L, 52.0 + it * 0.0001, 13.0) }

    @Test
    fun `drops the first and last 200 m`() {
        val window = PrivacyTrim.keepWindow(straightRide(100), 200.0)!!
        // 200 m / 11.12 m per step -> leaves the start radius at step 18
        assertEquals(18_000L, window.first)
        assertEquals(82_000L, window.last)
    }

    @Test
    fun `short ride inside the radius is dropped entirely`() {
        assertNull(PrivacyTrim.keepWindow(straightRide(10), 200.0))
    }

    @Test
    fun `round trip ending at the start keeps the middle`() {
        val out = straightRide(50)
        val back = out.reversed().mapIndexed { i, p -> p.copy(tMs = 51_000L + i * 1000L) }
        val window = PrivacyTrim.keepWindow(out + back, 200.0)!!
        assertEquals(18_000L, window.first)
        assertEquals(51_000L + 32_000L, window.last)
    }
}
