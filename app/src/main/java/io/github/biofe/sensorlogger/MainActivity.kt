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

    private lateinit var startButtons: LinearLayout
    private lateinit var stopButton: Button
    private var shownBikes: List<Bikes.Bike>? = null
    private var pendingBike: Bikes.Bike? = null
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

        startButtons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        stopButton = Button(this).apply {
            text = "Stop ride"
            setOnClickListener { stopRecording() }
        }
        val manageBikes = Button(this).apply {
            text = "My bikes"
            setOnClickListener { manageBikes() }
        }
        annotate = Button(this).apply {
            text = "Annotate this spot"
            setOnClickListener { AnnotationPoll.showForRide(this@MainActivity) { render() } }
        }
        val mapButton = Button(this).apply {
            text = "Annotate on the map"
            setOnClickListener { startActivity(Intent(this@MainActivity, MapActivity::class.java)) }
        }
        val detailedPoll = android.widget.CheckBox(this).apply {
            text = "Detailed poll (type of way, exact surface, sounds, note)"
            isChecked = AnnotationPoll.detailed(this@MainActivity)
            setOnCheckedChangeListener { _, on -> AnnotationPoll.setDetailed(this@MainActivity, on) }
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
            text = "Tap a ride to export or delete it."
            setPadding(0, pad, 0, pad / 2)
        }
        ridesView = ListView(this).apply {
            setOnItemClickListener { _, _, pos, _ -> rideActions(rides[pos]) }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(startButtons, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(stopButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(annotate, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(status)
            addView(exportAll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(manageBikes, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(mapButton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(detailedPoll)
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
        if (Bikes.list(this).isEmpty()) addBike(firstRun = true)
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
        stopButton.visibility = if (s.recording) View.VISIBLE else View.GONE
        startButtons.visibility = if (s.recording) View.GONE else View.VISIBLE
        val bikes = Bikes.list(this)
        if (bikes != shownBikes) {
            shownBikes = bikes
            startButtons.removeAllViews()
            for (bike in bikes) {
                startButtons.addView(Button(this).apply {
                    text = "Start ride · ${bike.label}"
                    setOnClickListener { requestStart(bike) }
                }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            }
        }
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

    private fun stopRecording() {
        startService(Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
        ui.postDelayed({ refreshRides() }, 1500)
    }

    private fun requestStart(bike: Bikes.Bike) {
        if (RecorderState.recording) return
        pendingBike = bike
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
        val bike = pendingBike ?: Bikes.list(this).firstOrNull()
        startForegroundService(
            Intent(this, RecordingService::class.java).putExtra(RecordingService.EXTRA_BIKE_TYPE, bike?.type ?: "unknown")
        )
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
        // Long rides take a while to pack; keep a visible dialog until the file is really there
        val text = TextView(this).apply {
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            this.text = "Preparing…"
        }
        val dialog = AlertDialog.Builder(this).setTitle("Exporting").setView(text).setCancelable(false).show()
        Thread {
            val result = runCatching {
                Exporter.export(this, selection) { done, total ->
                    runOnUiThread { text.text = if (done < total) "Ride ${done + 1} of $total…" else "Finishing…" }
                }
            }
            runOnUiThread {
                dialog.dismiss()
                onExported(result)
            }
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
        val skippedNote = if (r.skipped > 0) " (${r.skipped} left out: too short or unreadable)" else ""
        Toast.makeText(this, "Saved Downloads/BikeSensorLogger/${r.fileName}$skippedNote", Toast.LENGTH_LONG).show()
        val share = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, r.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(share, "Send ride data"))
    }

    // --- Bikes -----------------------------------------------------------------------------------

    private fun manageBikes() {
        val bikes = Bikes.list(this)
        val labels = bikes.map { "Remove ${it.label}" } + "Add a bike"
        AlertDialog.Builder(this)
            .setTitle("My bikes")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == bikes.size) addBike(firstRun = false)
                else if (bikes.size == 1) Toast.makeText(this, "Keep at least one bike; add another first.", Toast.LENGTH_LONG).show()
                else {
                    Bikes.save(this, bikes.filterIndexed { i, _ -> i != which })
                    render()
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    /** On first launch several types can be picked at once; later one bike is added with an optional name. */
    private fun addBike(firstRun: Boolean) {
        val types = Bikes.TYPES.toTypedArray()
        if (firstRun) {
            val checked = BooleanArray(types.size)
            AlertDialog.Builder(this)
                .setTitle("Which bikes do you ride?")
                .setMultiChoiceItems(types, checked) { _, i, on -> checked[i] = on }
                .setCancelable(false)
                .setPositiveButton("OK") { _, _ ->
                    val picked = types.filterIndexed { i, _ -> checked[i] }.ifEmpty { listOf("other") }
                    Bikes.save(this, picked.map { Bikes.Bike(it, "") })
                    render()
                }
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Type of bike")
            .setItems(types) { _, i ->
                val name = android.widget.EditText(this).apply { hint = "Name (optional, stays on this phone)" }
                AlertDialog.Builder(this)
                    .setTitle(types[i])
                    .setView(name)
                    .setPositiveButton("Add") { _, _ ->
                        Bikes.save(this, Bikes.list(this) + Bikes.Bike(types[i], name.text.toString().trim()))
                        render()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- Rides ----------------------------------------------------------------------------------

    private fun rideActions(ride: File) {
        val actions = arrayOf(
            "Send to the project (privacy-trimmed)",
            "Export my track as GPX (full, for my own use)",
            "Delete",
        )
        AlertDialog.Builder(this)
            .setTitle(ride.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> export(listOf(ride))
                    1 -> exportGpx(ride)
                    else -> confirmDelete(ride)
                }
            }
            .show()
    }

    private fun exportGpx(ride: File) {
        if (RecorderState.recording && RecorderState.rideDir == ride) {
            Toast.makeText(this, "Stop the ride before exporting.", Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            val result = runCatching { GpxExporter.export(this, ride) }
            runOnUiThread {
                val r = result.getOrElse {
                    Toast.makeText(this, "Export failed: ${it.message}", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                if (r == null) {
                    Toast.makeText(this, "This ride has no GPS points.", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                Toast.makeText(this, "Saved Downloads/BikeSensorLogger/${r.fileName}", Toast.LENGTH_LONG).show()
                val share = Intent(Intent.ACTION_SEND)
                    .setType("application/gpx+xml")
                    .putExtra(Intent.EXTRA_STREAM, r.uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(share, "Open or send track"))
            }
        }.start()
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
