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
    val CONDITIONS = listOf("1 very bad", "2 bad", "3 ok", "4 good", "5 very good")
    val SOUNDS = listOf("traffic", "nature", "industry", "construction", "people", "quiet")

    private const val HEADER = "t_utc_ms,lat,lon,accuracy_m,surface,condition,sounds,note\n"

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
        val surface = radios(SURFACES)
        val condition = radios(CONDITIONS)
        val sounds = SOUNDS.map { CheckBox(activity).apply { text = it } }
        val note = EditText(activity).apply { hint = "Note (optional)" }

        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 0, pad, 0)
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
