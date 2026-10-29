package io.github.biofe.sensorlogger

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import java.io.File

/** Everything the screens show; the activity updates it, Compose redraws what changed. */
class UiState {
    var screen by mutableStateOf(Screen.MAIN)
    var now by mutableLongStateOf(System.currentTimeMillis())
    var bikes by mutableStateOf<List<Bikes.Bike>>(emptyList())
    var rides by mutableStateOf<List<Rides.Info>>(emptyList())
    var recording by mutableStateOf(false)
    var recordingBike by mutableStateOf<String?>(null)
    var autoStopped by mutableStateOf(false)
    var sensors by mutableStateOf<List<SensorHealth>>(emptyList())
    var update by mutableStateOf<Updater.Info?>(null)
    var uploadLine by mutableStateOf("")
    var consented by mutableStateOf(false)
    var autoUpload by mutableStateOf(false)
    var detailedPoll by mutableStateOf(false)
    var keepScreenOn by mutableStateOf(false)
    /** Non-undone taps per [Labels.Choice.key] on the running ride */
    var labelCounts by mutableStateOf<Map<String, Int>>(emptyMap())
    /** The last poll tap, shown with Undo for a few seconds */
    var lastTap by mutableStateOf<Labels.Tap?>(null)
    var lastTapUndone by mutableStateOf(false)
    var installId by mutableStateOf("")
    var versionName by mutableStateOf("")
    var updaterEnabled by mutableStateOf(false)
    /** The bike being edited; [BikeEdit.NEW] adds one */
    var bikeEdit by mutableStateOf<BikeEdit?>(null)
    var firstRun by mutableStateOf(false)
}

enum class Screen { MAIN, SETTINGS, RIDES }

class BikeEdit(val bike: Bikes.Bike?) {
    companion object {
        val NEW = BikeEdit(null)
    }
}

/** What the screens can ask for. */
class AppActions(
    val startRide: (Bikes.Bike) -> Unit,
    val stopRide: () -> Unit,
    val tapLabel: (Labels.Choice) -> Unit,
    val undoLabel: () -> Unit,
    val openMap: () -> Unit,
    val openSettings: () -> Unit,
    val openRides: () -> Unit,
    val back: () -> Unit,
    val rideActions: (File) -> Unit,
    val addBike: () -> Unit,
    val editBike: (Bikes.Bike) -> Unit,
    val installUpdate: () -> Unit,
    val checkForUpdates: () -> Unit,
    val uploadNow: () -> Unit,
    val setAutoUpload: (Boolean) -> Unit,
    val showWhatIsSent: () -> Unit,
    val deleteServerData: () -> Unit,
    val exportAll: () -> Unit,
    val setDetailedPoll: (Boolean) -> Unit,
    val setKeepScreenOn: (Boolean) -> Unit,
)

class MainActivity : ComponentActivity() {

    private val ui = UiState()
    private var pendingBike: Bikes.Bike? = null
    private var updateOffered = false
    private val handler = Handler(Looper.getMainLooper())

    /** Per sensor: the last count seen and when it last grew, for the sensor chips. */
    private val lastCounts = mutableMapOf<String, Pair<Long, Long>>()

    private val tick = object : Runnable {
        private var count = 0

        override fun run() {
            renderRecording()
            // Show rides turning "uploaded" while a background upload runs
            if (++count % 3 == 0 && Upload.isRunning) refreshRides()
            handler.postDelayed(this, 1000)
        }
    }

    private val actions = AppActions(
        startRide = ::requestStart,
        stopRide = ::stopRecording,
        tapLabel = ::tapLabel,
        undoLabel = ::undoLabel,
        openMap = { startActivity(Intent(this, MapActivity::class.java)) },
        openSettings = { ui.screen = Screen.SETTINGS },
        openRides = { ui.screen = Screen.RIDES },
        back = { ui.screen = Screen.MAIN },
        rideActions = ::rideActions,
        addBike = { ui.bikeEdit = BikeEdit.NEW },
        editBike = { ui.bikeEdit = BikeEdit(it) },
        installUpdate = { Updater.available(this)?.let { Updater.offer(this, it) } },
        checkForUpdates = ::checkForUpdate,
        uploadNow = ::uploadNow,
        setAutoUpload = { Upload.setAuto(this, it); renderUpload() },
        showWhatIsSent = { askConsent {} },
        deleteServerData = ::confirmServerDelete,
        exportAll = { export(Rides.list(this)) },
        setDetailedPoll = { AnnotationPoll.setDetailed(this, it); ui.detailedPoll = it },
        setKeepScreenOn = {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_SCREEN_ON, it).apply()
            ui.keepScreenOn = it
            applyRideWindow()
        },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        ui.bikes = Bikes.list(this)
        ui.firstRun = ui.bikes.isEmpty()
        ui.detailedPoll = AnnotationPoll.detailed(this)
        ui.keepScreenOn = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_SCREEN_ON, false)
        ui.installId = Upload.shortInstallId(this)
        ui.versionName = packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
        ui.updaterEnabled = Updater.enabled(this)
        setContent { VelowindTheme { VelowindApp(ui, actions, ::saveBike, ::removeBike, ::saveFirstBikes) } }
        UploadJob.schedule(this)
        UpdateJob.schedule(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshRides()
        handler.post(tick)
        // Ask once, after the bikes are set up and not in the middle of a ride
        if (!Upload.consentAsked(this) && Bikes.list(this).isNotEmpty() && !RecorderState.recording) askConsent {}
        resumeUpdate()
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    // --- Rendering ---------------------------------------------------------------------------

    private fun renderRecording() {
        val s = RecorderState
        val now = System.currentTimeMillis()
        if (ui.recording && !s.recording) refreshRides() // the ride just ended
        val changed = ui.recording != s.recording
        ui.now = now
        ui.recording = s.recording
        if (changed) {
            applyRideWindow()
            ui.lastTap = null
            ui.labelCounts = s.rideDir?.takeIf { s.recording }?.let { Labels.counts(it) } ?: emptyMap()
        }
        ui.autoStopped = s.autoStopped
        if (!s.recording) {
            lastCounts.clear()
            return
        }
        fun fresh(key: String, count: Long): Boolean {
            val (seen, at) = lastCounts[key] ?: (-1L to s.startedAtMs)
            if (count != seen) lastCounts[key] = count to (if (count > 0) now else at)
            val since = lastCounts.getValue(key).second
            return count > 0 && now - since < SENSOR_STALE_MS
        }
        val gpsOk = fresh("gps", s.gpsFixes)
        ui.sensors = buildList {
            add(SensorHealth(if (gpsOk && !s.lastGpsAccuracyM.isNaN()) getString(R.string.sensor_gps_accuracy, s.lastGpsAccuracyM.toInt()) else getString(R.string.sensor_gps), gpsOk))
            add(SensorHealth(getString(R.string.sensor_motion), fresh("accel", s.accelSamples)))
            val soundOk = fresh("sound", s.soundFrames)
            add(SensorHealth(getString(if (soundOk) R.string.sensor_sound else R.string.sensor_no_sound), soundOk))
            if (s.hasBarometer) add(SensorHealth(getString(R.string.sensor_baro), fresh("baro", s.pressureSamples)))
        }
    }

    private fun refreshRides() {
        ui.bikes = Bikes.list(this)
        renderUpload()
        renderUpdate()
        Thread {
            val infos = Rides.list(this).mapNotNull { runCatching { Rides.info(this, it) }.getOrNull() }
            handler.post { ui.rides = infos }
        }.start()
    }

    private fun renderUpload() {
        val waiting = Upload.pending(this).size
        ui.consented = Upload.consented(this)
        ui.autoUpload = Upload.auto(this)
        ui.uploadLine = when {
            !ui.consented -> getString(R.string.upload_line_off)
            waiting == 0 -> getString(R.string.upload_line_done)
            !ui.autoUpload -> resources.getQuantityString(R.plurals.upload_line_manual, waiting, waiting)
            else -> resources.getQuantityString(R.plurals.upload_line_waiting, waiting, waiting)
        }
    }

    private fun renderUpdate() {
        ui.update = Updater.available(this)
    }

    // --- Bikes -------------------------------------------------------------------------------

    private fun saveBike(bike: Bikes.Bike) {
        val bikes = Bikes.list(this)
        val updated = if (bikes.any { it.id == bike.id }) bikes.map { if (it.id == bike.id) bike else it } else bikes + bike
        Bikes.save(this, updated)
        ui.bikeEdit = null
        refreshRides()
    }

    private fun removeBike(bike: Bikes.Bike) {
        Bikes.save(this, Bikes.list(this).filter { it.id != bike.id })
        ui.bikeEdit = null
        refreshRides()
    }

    private fun saveFirstBikes(types: List<String>) {
        Bikes.save(this, Bikes.fromTypes(types))
        ui.firstRun = false
        refreshRides()
        if (!Upload.consentAsked(this)) askConsent {}
    }

    // --- Recording ---------------------------------------------------------------------------

    /** During a ride the screen shows over the lock screen, and stays on if the rider wants. */
    private fun applyRideWindow() {
        setShowWhenLocked(ui.recording)
        if (ui.recording && ui.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun tapLabel(choice: Labels.Choice) {
        val tap = Labels.tapLive(choice) ?: return
        buzz(VibrationEffect.EFFECT_CLICK)
        ui.labelCounts = ui.labelCounts + (choice.key to (ui.labelCounts[choice.key] ?: 0) + 1)
        showTap(tap, undone = false)
    }

    private fun undoLabel() {
        val tap = ui.lastTap ?: return
        if (ui.lastTapUndone) return
        Labels.undo(tap)
        buzz(VibrationEffect.EFFECT_DOUBLE_CLICK)
        ui.labelCounts = ui.labelCounts + (tap.choice.key to ((ui.labelCounts[tap.choice.key] ?: 1) - 1).coerceAtLeast(0))
        showTap(tap, undone = true)
    }

    /** Shows the tap (with Undo) under the buttons for a few seconds. */
    private fun showTap(tap: Labels.Tap, undone: Boolean) {
        ui.lastTap = tap
        ui.lastTapUndone = undone
        handler.removeCallbacks(clearTap)
        handler.postDelayed(clearTap, if (undone) UNDONE_SHOWN_MS else UNDO_SHOWN_MS)
    }

    private val clearTap = Runnable { ui.lastTap = null }

    private fun buzz(effect: Int) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        vibrator?.vibrate(VibrationEffect.createPredefined(effect))
    }

    private fun stopRecording() {
        startService(Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
        handler.postDelayed({ refreshRides() }, 1500)
    }

    private fun requestStart(bike: Bikes.Bike) {
        if (RecorderState.recording) return
        pendingBike = bike
        val missing = requiredPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startRecording() else requestPermissions(missing.toTypedArray(), REQ_PERMISSIONS)
    }

    @Suppress("OVERRIDE_DEPRECATION") // the activity result API isn't worth it for one request
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISSIONS) return
        val locationOk = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (locationOk) startRecording()
        else Toast.makeText(this, R.string.location_needed, Toast.LENGTH_LONG).show()
    }

    private fun startRecording() {
        val bike = pendingBike ?: Bikes.list(this).firstOrNull()
        ui.recordingBike = bike?.label
        startForegroundService(
            Intent(this, RecordingService::class.java)
                .putExtra(RecordingService.EXTRA_BIKE_TYPE, bike?.type ?: "unknown")
                .putExtra(RecordingService.EXTRA_BIKE_ID, bike?.id),
        )
        handler.postDelayed({ refreshRides() }, 500)
    }

    private fun requiredPermissions(): List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    // --- Updates -----------------------------------------------------------------------------

    /** Offers a new version once per app start (or when its notification is tapped), and looks for one now and then. */
    private fun resumeUpdate() {
        val fromNotification = intent.getBooleanExtra(UpdateJob.EXTRA_SHOW_UPDATE, false)
        intent.removeExtra(UpdateJob.EXTRA_SHOW_UPDATE)
        val info = Updater.available(this)
        if (info != null && Updater.awaitingPermission && packageManager.canRequestPackageInstalls()) {
            // Back from allowing installs: carry on where the rider left off
            Updater.awaitingPermission = false
            Updater.install(this, info)
        } else if (info != null && (fromNotification || !updateOffered && !RecorderState.recording)) {
            updateOffered = true
            Updater.offer(this, info)
        }
        if (Updater.checkDue(this)) {
            Thread {
                runCatching { Updater.check(this) }
                handler.post { if (!isFinishing) renderUpdate() }
            }.start()
        }
    }

    private fun checkForUpdate() {
        Toast.makeText(this, R.string.checking_updates, Toast.LENGTH_SHORT).show()
        Thread {
            val result = runCatching { Updater.check(this) }
            handler.post {
                if (isFinishing) return@post
                renderUpdate()
                result.fold(
                    { info ->
                        if (info != null) Updater.offer(this, info)
                        else Toast.makeText(this, R.string.up_to_date, Toast.LENGTH_SHORT).show()
                    },
                    { Toast.makeText(this, R.string.update_server_unreachable, Toast.LENGTH_LONG).show() },
                )
            }
        }.start()
    }

    // --- Export ------------------------------------------------------------------------------

    private fun export(selection: List<File>) {
        if (RecorderState.recording) {
            Toast.makeText(this, R.string.stop_before_export, Toast.LENGTH_SHORT).show()
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

    // --- Upload ------------------------------------------------------------------------------

    private fun askConsent(onAgreed: () -> Unit) {
        val text = TextView(this).apply {
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            this.text = CONSENT_TEXT
        }
        val scroll = android.widget.ScrollView(this).apply { addView(text) }
        if (Upload.consented(this)) {
            // Already agreed: just show what is sent; turning upload off is in the settings
            AlertDialog.Builder(this).setTitle("What is sent").setView(scroll).setPositiveButton("Close", null).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Send your rides to the project?")
            .setView(scroll)
            .setCancelable(false)
            .setPositiveButton("Agree") { _, _ ->
                Upload.setConsent(this, true)
                renderUpload()
                onAgreed()
            }
            .setNegativeButton("Not now") { _, _ ->
                Upload.setConsent(this, false)
                renderUpload()
            }
            .show()
    }

    private fun uploadNow() {
        if (!Upload.consented(this)) {
            askConsent { uploadNow() }
            return
        }
        val waiting = Upload.pending(this)
        val network = getSystemService(android.net.ConnectivityManager::class.java)
        if (network.activeNetwork == null) {
            Toast.makeText(this, "No internet connection.", Toast.LENGTH_SHORT).show()
            return
        }
        if (network.isActiveNetworkMetered && waiting.isNotEmpty()) {
            // Zipped CSVs are about a third of their size on the phone
            val mb = waiting.sumOf { r -> r.walkTopDown().filter { it.isFile }.sumOf { it.length() } } / 3 / 1_000_000
            AlertDialog.Builder(this)
                .setMessage("You're not on Wi-Fi. Upload ${waiting.size} ${if (waiting.size == 1) "ride" else "rides"} (about ${maxOf(mb, 1)} MB) over mobile data?")
                .setPositiveButton("Upload") { _, _ -> runUpload() }
                .setNegativeButton("Wait for Wi-Fi", null)
                .show()
        } else {
            runUpload()
        }
    }

    private fun runUpload() {
        UploadJob.uploadNow(this)
        Toast.makeText(this, "Uploading in the background; the notification shows how far it got.", Toast.LENGTH_LONG).show()
    }

    private fun confirmServerDelete() {
        AlertDialog.Builder(this)
            .setMessage("Delete every ride and map annotation this phone has sent to the project server? Automatic upload will be turned off. Rides on this phone stay.")
            .setPositiveButton("Delete") { _, _ ->
                Thread {
                    val result = runCatching { Upload.deleteOnServer(this) }
                    runOnUiThread {
                        renderUpload()
                        val msg = result.fold(
                            { "Deleted $it ${if (it == 1) "ride" else "rides"} from the server. Upload is off." },
                            { "Couldn't delete: ${it.message}. Check the connection and try again." },
                        )
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    }
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- Rides -------------------------------------------------------------------------------

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
            Toast.makeText(this, R.string.stop_before_export, Toast.LENGTH_SHORT).show()
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

        /** A sensor that sent nothing for this long gets a warning on the recording screen */
        private const val SENSOR_STALE_MS = 5_000L

        /** How long the last poll tap stays on screen with Undo, and the "Removed" note after Undo */
        private const val UNDO_SHOWN_MS = 5_000L
        private const val UNDONE_SHOWN_MS = 2_000L

        private const val PREFS = "ride_screen"
        private const val KEY_SCREEN_ON = "keep_screen_on"

        private const val CONSENT_TEXT =
            "Your rides help build a map of how comfortable cycle paths are. If you agree, the app sends each " +
                "finished ride to the project server when you're on Wi-Fi.\n\n" +
                "What is sent: the motion sensor data, the GPS track, sound levels in frequency bands (never audio), " +
                "your poll answers, the bike type, the phone model and app version, and a random id for this app " +
                "install. The first and last 150 to 250 m of every ride are cut off, so your home or work isn't in the data.\n\n" +
                "What is not sent: your name, contact details, bike names or colours, or the full track you can export as GPX.\n\n" +
                "The data is used for research in this project. You can turn automatic upload off at any time, " +
                "and delete everything this phone sent, under Settings."
    }
}

/** Picks the screen; a running ride always shows the recording screen. */
@Composable
fun VelowindApp(
    ui: UiState,
    actions: AppActions,
    saveBike: (Bikes.Bike) -> Unit,
    removeBike: (Bikes.Bike) -> Unit,
    saveFirstBikes: (List<String>) -> Unit,
) {
    BackHandler(enabled = ui.screen != Screen.MAIN && !ui.recording) { actions.back() }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        when {
            ui.recording -> RecordingScreen(ui, actions)
            ui.screen == Screen.SETTINGS -> SettingsScreen(ui, actions)
            ui.screen == Screen.RIDES -> RidesScreen(ui, actions)
            else -> MainScreen(ui, actions)
        }
    }
    if (ui.firstRun) FirstBikesDialog(saveFirstBikes)
    ui.bikeEdit?.let { edit ->
        val bike = edit.bike
        BikeEditor(
            bike = bike,
            newColor = Bikes.nextColor(ui.bikes),
            onSave = saveBike,
            onRemove = if (bike != null && ui.bikes.size > 1) ({ removeBike(bike) }) else null,
            onDismiss = { ui.bikeEdit = null },
        )
    }
}
