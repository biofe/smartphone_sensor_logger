package io.github.biofe.sensorlogger

import androidx.annotation.StringRes
import java.io.File
import java.util.Locale

/**
 * The ride poll: what the rider notices, one row per tap. The same rows are used during the ride
 * (source `live`) and later on the map (source `later`). A tap stores only time, place and the
 * button; which stretch it describes is worked out in the analysis.
 *
 * `labels.csv` per ride: t_utc_ms,lat,lon,accuracy_m,source,category,value,undone. Position is
 * empty when there was no GPS fix yet; `undone` is 1 when the rider pressed Undo.
 */
object Labels {

    const val FILE = "labels.csv"
    const val HEADER = "t_utc_ms,lat,lon,accuracy_m,source,category,value,undone\n"

    class Choice(val category: String, val value: String, @StringRes val label: Int) {
        val key: String get() = "$category/$value"
    }

    class Row(val category: String, @StringRes val title: Int, val choices: List<Choice>)

    val ROWS = listOf(
        Row("noise", R.string.poll_noise, listOf(
            Choice("noise", "quiet", R.string.poll_quiet),
            Choice("noise", "far_traffic", R.string.poll_far_traffic),
            Choice("noise", "loud_traffic", R.string.poll_loud_traffic),
            Choice("noise", "wind", R.string.poll_wind),
        )),
        Row("surface", R.string.poll_surface, listOf(
            Choice("surface", "smooth", R.string.poll_smooth),
            Choice("surface", "bumpy", R.string.poll_bumpy),
            Choice("surface", "rough", R.string.poll_rough),
        )),
        Row("width", R.string.poll_width, listOf(
            Choice("width", "narrow", R.string.poll_narrow),
            Choice("width", "medium", R.string.poll_medium),
            Choice("width", "wide", R.string.poll_wide),
        )),
        Row("comfort", R.string.poll_comfort, listOf(
            Choice("comfort", "nice", R.string.poll_nice),
            Choice("comfort", "unpleasant", R.string.poll_unpleasant),
        )),
    )

    /** One stored tap, enough to find it again for Undo. */
    class Tap(val ride: File, val tMs: Long, val choice: Choice)

    /** Stores a tap during the ride at the last GPS fix; null if no ride is recording. */
    fun tapLive(choice: Choice): Tap? {
        val ride = RecorderState.rideDir ?: return null
        if (!RecorderState.recording) return null
        val t = System.currentTimeMillis()
        val fix = RecorderState.lastFix
        append(ride, t, fix?.get(0), fix?.get(1), fix?.get(2), "live", choice)
        return Tap(ride, t, choice)
    }

    @Synchronized
    fun append(ride: File, tMs: Long, lat: Double?, lon: Double?, accuracyM: Double?, source: String, choice: Choice) {
        val file = File(ride, FILE)
        if (!file.exists()) file.writeText(HEADER)
        val row = listOf(
            tMs.toString(),
            lat?.let { String.format(Locale.US, "%.7f", it) } ?: "",
            lon?.let { String.format(Locale.US, "%.7f", it) } ?: "",
            accuracyM?.let { String.format(Locale.US, "%.1f", it) } ?: "",
            source, choice.category, choice.value, "",
        )
        file.appendText(row.joinToString(",") + "\n")
    }

    /** Marks the tap as undone; the row stays, so the analysis can see what happened. */
    @Synchronized
    fun undo(tap: Tap) {
        val file = File(tap.ride, FILE)
        if (!file.exists()) return
        val lines = file.readLines().toMutableList()
        val prefix = "${tap.tMs},"
        val i = lines.indexOfLast { it.startsWith(prefix) && it.endsWith(",${tap.choice.category},${tap.choice.value},") }
        if (i < 0) return
        lines[i] = lines[i] + "1"
        file.writeText(lines.joinToString("\n", postfix = "\n"))
    }

    /** Taps per [Choice.key] that weren't undone, for the counters on the buttons. */
    fun counts(ride: File): Map<String, Int> {
        val file = File(ride, FILE)
        if (!file.exists()) return emptyMap()
        return file.readLines().drop(1).mapNotNull { line ->
            val c = line.split(',')
            if (c.size < 8 || c[7] == "1") null else "${c[5]}/${c[6]}"
        }.groupingBy { it }.eachCount()
    }
}
