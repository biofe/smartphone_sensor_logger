package io.github.biofe.sensorlogger.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RideNameTest {

    @Test
    fun `name is the UTC minute plus the suffix`() {
        // 2026-10-21 16:27:59.999 UTC
        assertEquals("20261021-1627Z-k3f9", RideName.of(1_792_600_079_999L, "k3f9"))
    }

    @Test
    fun `suffix is four lowercase letters or digits`() {
        val random = Random(1)
        repeat(1000) {
            val s = RideName.randomSuffix(random)
            assertEquals(RideName.SUFFIX_LENGTH, s.length)
            assertTrue(s.all { it in 'a'..'z' || it in '0'..'9' }, s)
        }
    }

    @Test
    fun `suffixes differ between rides`() {
        val random = Random(2)
        assertNotEquals(RideName.randomSuffix(random), RideName.randomSuffix(random))
    }
}
