package io.github.biofe.sensorlogger.core

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/**
 * Readable names for exported rides, e.g. `20261021-1627Z-k3f9`: the UTC minute of the first row
 * kept after the privacy trim, plus a random suffix so rides started in the same minute differ.
 * The time comes from the trimmed data, so the name tells nothing the CSV timestamps don't.
 */
object RideName {

    const val SUFFIX_LENGTH = 4
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
    private val MINUTE = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm'Z'").withZone(ZoneOffset.UTC)

    /** A suffix drawn once per ride and stored with it, so every export of a ride gets the same name. */
    fun randomSuffix(random: Random): String =
        (1..SUFFIX_LENGTH).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")

    fun of(firstKeptUtcMs: Long, suffix: String): String =
        "${MINUTE.format(Instant.ofEpochMilli(firstKeptUtcMs))}-$suffix"
}
