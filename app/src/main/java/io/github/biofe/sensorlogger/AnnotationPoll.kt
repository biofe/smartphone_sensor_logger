package io.github.biofe.sensorlogger

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
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
 * A short poll about one spot, as ground truth for the analysis. Six one-tap questions; the
 * detailed version (switched on in the app) adds type of way, exact surface, sounds and a note.
 *
 * During a ride the answers go to the ride's annotations.csv at the last GPS fix and are
 * privacy-trimmed with the ride. Spots picked on the map go to [Annotations.mapFile].
 */
object AnnotationPoll {

    class Question(val key: String, val title: String, val options: List<String>, val codes: List<String> = options)

    val SIMPLE = listOf(
        Question("overall", "How do you like cycling here?", listOf("good", "ok", "bad")),
        Question("surface", "Surface", listOf("smooth paved", "stones, cobbles, slabs", "unpaved"),
            listOf("smooth", "stones", "unpaved")),
        Question("condition", "Condition of the surface", listOf("good", "ok", "bad")),
        Question("width", "Width you can ride on", listOf("narrow (< 1.5 m)", "medium", "wide (> 3 m)"),
            listOf("narrow", "medium", "wide")),
        Question("traffic", "Motor traffic next to you", listOf("none", "some", "heavy")),
        Question("noise", "Noise", listOf("quiet", "medium", "loud")),
    )

    val PATH_TYPES = listOf(
        "road with traffic", "quiet road", "cycle street", "painted cycle lane", "separate cycle path",
        "shared footway (in town)", "agricultural or forest track", "park path",
    )
    val SURFACES = listOf(
        "asphalt", "concrete slabs", "paving stones", "cobbles", "compacted gravel", "loose gravel", "dirt",
    )
    val SOUNDS = listOf("traffic", "nature", "industry", "construction", "people", "quiet")

    const val HEADER = "t_utc_ms,lat,lon,accuracy_m,source,overall,surface,condition,width,traffic,noise," +
        "path_type,surface_detail,sounds,note\n"

    private const val PREFS = "poll"

    fun detailed(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("detailed", false)

    fun setDetailed(context: Context, on: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("detailed", on).apply()

    /** The poll during a ride, at the last GPS fix. */
    fun showForRide(activity: Activity, onSaved: () -> Unit) {
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
        show(activity, "Annotate this spot", fix[0], fix[1], fix[2], "ride", File(rideDir, Rides.ANNOTATIONS_FILE)) {
            RecorderState.annotations++
            onSaved()
        }
    }

    fun show(
        activity: Activity, title: String, lat: Double, lon: Double, accuracyM: Double, source: String,
        target: File, onSaved: () -> Unit,
    ) {
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        fun heading(text: String) = TextView(activity).apply {
            this.text = text
            setPadding(0, pad, 0, pad / 4)
            textSize = 16f
        }
        fun radios(options: List<String>, horizontal: Boolean) = RadioGroup(activity).apply {
            orientation = if (horizontal) RadioGroup.HORIZONTAL else RadioGroup.VERTICAL
            options.forEachIndexed { i, o -> addView(RadioButton(activity).apply { text = o; id = i + 1 }) }
        }
        fun picked(group: RadioGroup, codes: List<String>) = codes.getOrNull(group.checkedRadioButtonId - 1) ?: ""

        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 0, pad, 0)
        }
        val simple = SIMPLE.map { q ->
            form.addView(heading(q.title))
            // Three short options fit on one line; longer labels stack
            val group = radios(q.options, horizontal = q.options.all { it.length <= 8 })
            form.addView(group)
            q to group
        }
        val detailed = detailed(activity)
        val pathType = radios(PATH_TYPES, horizontal = false)
        val surface = radios(SURFACES, horizontal = false)
        val sounds = SOUNDS.map { CheckBox(activity).apply { text = it } }
        val note = EditText(activity).apply { hint = "Note (optional)" }
        if (detailed) {
            form.addView(heading("Type of way"))
            form.addView(pathType)
            form.addView(heading("Exact surface"))
            form.addView(surface)
            form.addView(heading("What do you hear?"))
            sounds.forEach { form.addView(it) }
            form.addView(heading("Note"))
            form.addView(note)
        }

        AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(ScrollView(activity).apply { addView(form) })
            .setPositiveButton("Save") { _, _ ->
                val row = listOf(
                    System.currentTimeMillis().toString(),
                    String.format(Locale.US, "%.7f", lat),
                    String.format(Locale.US, "%.7f", lon),
                    String.format(Locale.US, "%.1f", accuracyM),
                    source,
                ) + simple.map { (q, g) -> picked(g, q.codes) } + listOf(
                    if (detailed) picked(pathType, PATH_TYPES) else "",
                    if (detailed) picked(surface, SURFACES) else "",
                    sounds.filter { it.isChecked }.joinToString(";") { it.text },
                    csvQuote(note.text.toString()),
                )
                Annotations.append(target, row.joinToString(","))
                Toast.makeText(activity, "Saved", Toast.LENGTH_SHORT).show()
                onSaved()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun csvQuote(text: String): String {
        val oneLine = text.replace('\n', ' ').trim()
        return if (oneLine.isEmpty()) "" else "\"" + oneLine.replace("\"", "\"\"") + "\""
    }
}
