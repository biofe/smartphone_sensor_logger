package io.github.biofe.sensorlogger.core

import kotlin.test.Test
import kotlin.test.assertEquals

class RideStatsTest {

    // About 111 m per 0.001° of latitude
    private fun north(i: Int, metres: Double, seconds: Long) =
        GeoPoint(i * seconds * 1000, 51.5 + i * metres / 111_195.0, 9.9)

    @Test
    fun sumsSteps() {
        val points = (0..10).map { north(it, 5.0, 1) }
        assertEquals(50.0, RideStats.distanceM(points), 0.5)
    }

    @Test
    fun skipsGpsJumps() {
        val points = (0..10).map { north(it, 5.0, 1) }.toMutableList()
        // One fix 500 m off within a second, and back
        points[5] = points[5].copy(lat = points[5].lat + 500 / 111_195.0)
        assertEquals(40.0, RideStats.distanceM(points), 0.5)
    }

    @Test
    fun emptyAndSinglePoint() {
        assertEquals(0.0, RideStats.distanceM(emptyList()))
        assertEquals(0.0, RideStats.distanceM(listOf(north(0, 5.0, 1))))
    }
}
