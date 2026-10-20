package io.github.biofe.sensorlogger

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var startStop: Button
    private lateinit var annotate: Button
    private lateinit var status: TextView
    private lateinit var ridesView: ListView
    private var rides: List<File> = emptyList()
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        startStop = Button(this).apply { setOnClickListener { toggleRecording() } }
        annotate = Button(this).apply {
            text = "Annotate this spot"
            setOnClickListener { AnnotationPoll.show(this@MainActivity) { render() } }
        }
        status = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setPadding(0, pad / 2, 0, pad)
        }
        val exportAll = Button(this).apply {
            text = "Export all rides"
            setOnClickListener { export(rides) }
        }
        val hint = TextView(this).apply {
            text = "Tap a ride to export it, long-press to delete it."
            setPadding(0, pad, 0, pad / 2)
        }
        ridesView = ListView(this).apply {
            setOnItemClickListener { _, _, pos, _ -> export(listOf(rides[pos])) }
            setOnItemLongClickListener { _, _, pos, _ -> confirmDelete(rides[pos]); true }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(startStop, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(annotate, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(status)
            addView(exportAll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(hint)
            addView(ridesView, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        // targetSdk 35 draws edge-to-edge: keep content clear of the status and navigation bars
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            root.setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                v.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom)
                insets
            }
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refreshRides()
        ui.post(tick)
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        super.onPause()
    }

    private fun render() {
        val s = RecorderState
        startStop.text = if (s.recording) "Stop ride" else "Start ride"
        annotate.visibility = if (s.recording) View.VISIBLE else View.GONE
        status.text = if (s.recording) {
            val secs = (System.currentTimeMillis() - s.startedAtMs) / 1000
            buildString {
                append(String.format(Locale.US, "Recording %d:%02d\n", secs / 60, secs % 60))
                append("Accelerometer  ${s.accelSamples} samples\n")
                append("Gyroscope      ${s.gyroSamples} samples\n")
                append("Barometer      ${s.pressureSamples} samples\n")
                append("GPS            ${s.gpsFixes} fixes")
                if (!s.lastGpsAccuracyM.isNaN()) {
                    append(String.format(Locale.US, ", ±%.0f m, %.0f km/h", s.lastGpsAccuracyM, s.lastSpeedKmh))
                }
                append("\nSound          ${s.soundFrames} frames")
                if (!s.lastLaeqDb.isNaN()) append(String.format(Locale.US, ", %.0f dBFS(A)", s.lastLaeqDb))
                if (s.annotations > 0) append("\nAnnotations    ${s.annotations}")
                s.error?.let { append("\n⚠ $it") }
            }
        } else if (s.autoStopped) {
            "Not recording. The last ride stopped by itself after 10 minutes without moving and was saved."
        } else {
            "Not recording. Put the phone in your pocket after starting; the screen can be off."
        }
    }

    private fun refreshRides() {
        rides = Rides.list(this)
        ridesView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rides.map { Rides.describe(it) })
    }

    private fun toggleRecording() {
        if (RecorderState.recording) {
            startService(Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
            ui.postDelayed({ refreshRides() }, 1500)
            return
        }
        val missing = requiredPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startRecording() else requestPermissions(missing.toTypedArray(), REQ_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISSIONS) return
        val locationOk = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (locationOk) startRecording()
        else Toast.makeText(this, "Precise location is needed to record a ride.", Toast.LENGTH_LONG).show()
    }

    private fun startRecording() {
        startForegroundService(Intent(this, RecordingService::class.java))
        ui.postDelayed({ refreshRides() }, 500)
    }

    private fun requiredPermissions(): List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun export(selection: List<File>) {
        if (RecorderState.recording) {
            Toast.makeText(this, "Stop the ride before exporting.", Toast.LENGTH_SHORT).show()
            return
        }
        if (selection.isEmpty()) return
        Toast.makeText(this, "Exporting…", Toast.LENGTH_SHORT).show()
        Thread {
            val result = runCatching { Exporter.export(this, selection) }
            runOnUiThread { onExported(result) }
        }.start()
    }

    private fun onExported(result: kotlin.Result<Exporter.Result>) {
        val r = result.getOrElse {
            Toast.makeText(this, "Export failed: ${it.message}", Toast.LENGTH_LONG).show()
            return
        }
        if (r.uri == null) {
            Toast.makeText(this, "Nothing to export: rides shorter than 400 m are removed for privacy.", Toast.LENGTH_LONG).show()
            return
        }
        val skippedNote = if (r.skipped > 0) " (${r.skipped} too short, left out)" else ""
        Toast.makeText(this, "Saved Downloads/BikeSensorLogger/${r.fileName}$skippedNote", Toast.LENGTH_LONG).show()
        val share = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, r.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(share, "Send ride data"))
    }

    private fun confirmDelete(ride: File) {
        AlertDialog.Builder(this)
            .setMessage("Delete ride ${ride.name}?")
            .setPositiveButton("Delete") { _, _ ->
                ride.deleteRecursively()
                refreshRides()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        private const val REQ_PERMISSIONS = 1
    }
}
