package io.github.biofe.sensorlogger

import android.app.Activity
import android.app.AlertDialog
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale

/**
 * A short poll the rider fills in after stopping somewhere, as ground truth for the analysis.
 * Answers are appended to the ride's annotations.csv with the last GPS fix and go through
 * the same privacy trim on export as the sensor data.
 */
object AnnotationPoll {

    val SURFACES = listOf(
        "asphalt", "concrete slabs", "paving stones", "cobbles", "compacted gravel", "loose gravel", "dirt",
    )
    val PATH_TYPES = listOf(
        "road with traffic", "quiet road", "painted cycle lane", "separate cycle path",
        "shared with pedestrians", "agricultural or forest track", "park path",
    )
    val WIDTHS = listOf(
        "narrow: under 1.5 m, one bike", "medium: 1.5-3 m, two bikes pass", "wide: over 3 m, car width",
    )
    private val WIDTH_CODES = listOf("narrow", "medium", "wide")
    val CONDITIONS = listOf("1 very bad", "2 bad", "3 ok", "4 good", "5 very good")
    val SOUNDS = listOf("traffic", "nature", "industry", "construction", "people", "quiet")

    private const val HEADER = "t_utc_ms,lat,lon,accuracy_m,path_type,width,surface,condition,sounds,note\n"

    fun show(activity: Activity, onSaved: () -> Unit) {
        val rideDir = RecorderState.rideDir
        val fix = RecorderState.lastFix
        if (!RecorderState.recording || rideDir == null) {
            Toast.makeText(activity, "Start a ride to annotate.", Toast.LENGTH_SHORT).show()
            return
        }
        if (fix == null) {
            Toast.makeText(activity, "Waiting for GPS; try again in a moment.", Toast.LENGTH_SHORT).show()
            return
        }
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        fun heading(text: String) = TextView(activity).apply {
            this.text = text
            setPadding(0, pad, 0, pad / 4)
            textSize = 16f
        }
        fun radios(options: List<String>) = RadioGroup(activity).apply {
            options.forEachIndexed { i, o -> addView(RadioButton(activity).apply { text = o; id = i + 1 }) }
        }
        val pathType = radios(PATH_TYPES)
        val width = radios(WIDTHS)
        val surface = radios(SURFACES)
        val condition = radios(CONDITIONS)
        val sounds = SOUNDS.map { CheckBox(activity).apply { text = it } }
        val note = EditText(activity).apply { hint = "Note (optional)" }

        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 0, pad, 0)
            addView(heading("Type of way"))
            addView(pathType)
            addView(heading("Width you can ride on"))
            addView(width)
            addView(heading("Surface"))
            addView(surface)
            addView(heading("Condition"))
            addView(condition)
            addView(heading("What do you hear?"))
            sounds.forEach { addView(it) }
            addView(heading("Note"))
            addView(note)
        }
        AlertDialog.Builder(activity)
            .setTitle("Annotate this spot")
            .setView(ScrollView(activity).apply { addView(form) })
            .setPositiveButton("Save") { _, _ ->
                val row = listOf(
                    System.currentTimeMillis().toString(),
                    String.format(Locale.US, "%.7f", fix[0]),
                    String.format(Locale.US, "%.7f", fix[1]),
                    String.format(Locale.US, "%.1f", fix[2]),
                    PATH_TYPES.getOrNull(pathType.checkedRadioButtonId - 1) ?: "",
                    WIDTH_CODES.getOrNull(width.checkedRadioButtonId - 1) ?: "",
                    SURFACES.getOrNull(surface.checkedRadioButtonId - 1) ?: "",
                    (condition.checkedRadioButtonId).takeIf { it > 0 }?.toString() ?: "",
                    sounds.filter { it.isChecked }.joinToString(";") { it.text },
                    csvQuote(note.text.toString()),
                ).joinToString(",")
                append(File(rideDir, Rides.ANNOTATIONS_FILE), row)
                RecorderState.annotations++
                Toast.makeText(activity, "Saved", Toast.LENGTH_SHORT).show()
                onSaved()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @Synchronized
    private fun append(file: File, row: String) {
        if (!file.exists()) file.writeText(HEADER)
        file.appendText(row + "\n")
    }

    private fun csvQuote(text: String): String {
        val oneLine = text.replace('\n', ' ').trim()
        return if (oneLine.isEmpty()) "" else "\"" + oneLine.replace("\"", "\"\"") + "\""
    }
}
