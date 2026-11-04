package io.github.biofe.sensorlogger

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
    var keepScreenOn by mutableStateOf(false)
    /** The last poll tap, shown with Undo for a few seconds */
    var lastTap by mutableStateOf<Labels.Tap?>(null)
    var lastTapUndone by mutableStateOf(false)
    var installId by mutableStateOf("")
    var versionName by mutableStateOf("")
    var updaterEnabled by mutableStateOf(false)
    /** The bike being edited; [BikeEdit.NEW] adds one */
    var bikeEdit by mutableStateOf<BikeEdit?>(null)
    var firstRun by mutableStateOf(false)
    /** The introduction: on first launch, or opened again from Settings */
    var showIntro by mutableStateOf(false)
    var batteryUnrestricted by mutableStateOf(false)
    /** Folder names of the rides picked in the ride list */
    var selected by mutableStateOf<Set<String>>(emptySet())
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
    val openSettings: () -> Unit,
    val openRides: () -> Unit,
    val back: () -> Unit,
    /** From the main screen: the ride list with this ride picked */
    val openRide: (File) -> Unit,
    val selectRides: (Set<String>) -> Unit,
    val annotateRide: (File) -> Unit,
    val uploadRides: (List<File>) -> Unit,
    val gpxRides: (List<File>) -> Unit,
    val zipRides: (List<File>) -> Unit,
    val deleteRides: (List<File>) -> Unit,
    val addBike: () -> Unit,
    val editBike: (Bikes.Bike) -> Unit,
    val installUpdate: () -> Unit,
    val checkForUpdates: () -> Unit,
    val uploadNow: () -> Unit,
    val setAutoUpload: (Boolean) -> Unit,
    val showWhatIsSent: () -> Unit,
    val showUploadLog: () -> Unit,
    val deleteServerData: () -> Unit,
    val exportAll: () -> Unit,
    val setKeepScreenOn: (Boolean) -> Unit,
    val showIntro: () -> Unit,
    val finishIntro: () -> Unit,
    val openBatterySettings: () -> Unit,
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
        openSettings = { ui.screen = Screen.SETTINGS },
        openRides = { ui.selected = emptySet(); ui.screen = Screen.RIDES },
        back = { ui.selected = emptySet(); ui.screen = Screen.MAIN },
        openRide = { ui.selected = setOf(it.name); ui.screen = Screen.RIDES },
        selectRides = { ui.selected = it },
        annotateRide = { AnnotateActivity.open(this, it) },
        uploadRides = { uploadNow(it) },
        gpxRides = ::exportGpx,
        zipRides = { export(it) },
        deleteRides = ::confirmDelete,
        addBike = { ui.bikeEdit = BikeEdit.NEW },
        editBike = { ui.bikeEdit = BikeEdit(it) },
        installUpdate = { Updater.available(this)?.let { Updater.offer(this, it) } },
        checkForUpdates = ::checkForUpdate,
        uploadNow = { uploadNow() },
        setAutoUpload = { Upload.setAuto(this, it); renderUpload() },
        showWhatIsSent = { askConsent {} },
        showUploadLog = ::showUploadLog,
        deleteServerData = ::confirmServerDelete,
        exportAll = { export(Rides.list(this)) },
        setKeepScreenOn = {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_SCREEN_ON, it).apply()
            ui.keepScreenOn = it
            applyRideWindow()
        },
        showIntro = { ui.showIntro = true },
        finishIntro = {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_INTRO_SEEN, true).apply()
            ui.showIntro = false
        },
        openBatterySettings = ::openBatterySettings,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        ui.bikes = Bikes.list(this)
        ui.firstRun = ui.bikes.isEmpty()
        // Riders who already set up their bikes know the app; the intro is for new installs
        ui.showIntro = ui.firstRun && !getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_INTRO_SEEN, false)
        // On by default: the poll buttons need the screen, and a dimmed screen costs little
        ui.keepScreenOn = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_SCREEN_ON, true)
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
        ui.batteryUnrestricted = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
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

    /** Android's page for this app, where battery usage can be set to Unrestricted. */
    private fun openBatterySettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        runCatching { startActivity(intent) }
    }

    // --- Recording ---------------------------------------------------------------------------

    /** During a ride the screen shows over the lock screen, and stays on if the rider wants. */
    private fun applyRideWindow() {
        setShowWhenLocked(ui.recording)
        if (ui.recording && ui.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        brighten()
    }

    /**
     * With the screen kept on during a ride, it dims after a while without a touch to save
     * battery; any touch brings the normal brightness back.
     */
    private fun brighten() {
        handler.removeCallbacks(dim)
        setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        if (ui.recording && ui.keepScreenOn) handler.postDelayed(dim, DIM_AFTER_MS)
    }

    private val dim = Runnable { setBrightness(DIM_BRIGHTNESS) }

    private fun setBrightness(value: Float) {
        val attrs = window.attributes
        if (attrs.screenBrightness == value) return
        attrs.screenBrightness = value
        window.attributes = attrs
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && ui.recording && ui.keepScreenOn) brighten()
        return super.dispatchTouchEvent(event)
    }

    private fun tapLabel(choice: Labels.Choice) {
        val tap = Labels.tapLive(choice) ?: return
        Haptics.tap(this)
        showTap(tap, undone = false)
    }

    private fun undoLabel() {
        val tap = ui.lastTap ?: return
        if (ui.lastTapUndone) return
        Labels.undo(tap)
        Haptics.undo(this)
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
        ui.recordingBike = bike?.label(this)
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
            this.text = getString(R.string.preparing)
        }
        val dialog = AlertDialog.Builder(this).setTitle(R.string.exporting).setView(text).setCancelable(false).show()
        Thread {
            val result = runCatching {
                Exporter.export(this, selection) { done, total ->
                    runOnUiThread { text.text = if (done < total) getString(R.string.export_progress, done + 1, total) else getString(R.string.export_finishing) }
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
            Toast.makeText(this, getString(R.string.export_failed, it.message), Toast.LENGTH_LONG).show()
            return
        }
        if (r.uri == null) {
            Toast.makeText(this, R.string.export_nothing, Toast.LENGTH_LONG).show()
            return
        }
        val saved = if (r.skipped > 0) getString(R.string.export_saved_skipped, r.fileName, r.skipped)
        else getString(R.string.export_saved, r.fileName)
        Toast.makeText(this, saved, Toast.LENGTH_LONG).show()
        val share = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, r.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(share, getString(R.string.share_ride_data)))
    }

    // --- Upload ------------------------------------------------------------------------------

    private fun showUploadLog() {
        val log = UploadLog.text(this)
        val text = TextView(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            this.text = log
        }
        val scroll = android.widget.ScrollView(this).apply { addView(text) }
        AlertDialog.Builder(this).setTitle(R.string.upload_log).setView(scroll)
            .setPositiveButton(R.string.close, null)
            .setNeutralButton(R.string.copy) { _, _ ->
                getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText(getString(R.string.upload_log), log))
                Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun askConsent(onAgreed: () -> Unit) {
        val text = TextView(this).apply {
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            this.text = getString(R.string.consent_text)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(text) }
        if (Upload.consented(this)) {
            // Already agreed: just show what is sent; turning upload off is in the settings
            AlertDialog.Builder(this).setTitle(R.string.consent_what_is_sent).setView(scroll).setPositiveButton(R.string.close, null).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.consent_title)
            .setView(scroll)
            .setCancelable(false)
            .setPositiveButton(R.string.consent_agree) { _, _ ->
                Upload.setConsent(this, true)
                renderUpload()
                onAgreed()
            }
            .setNegativeButton(R.string.consent_not_now) { _, _ ->
                Upload.setConsent(this, false)
                renderUpload()
            }
            .show()
    }

    /** [only]: the rides picked in the ride list; null uploads everything waiting. */
    private fun uploadNow(only: List<File>? = null) {
        if (!Upload.consented(this)) {
            askConsent { uploadNow(only) }
            return
        }
        val names = only?.map { it.name }
        val waiting = Upload.pending(this, names?.toSet())
        if (only != null && waiting.isEmpty() && only.none { Upload.labelsPending(it) }) {
            Toast.makeText(this, R.string.nothing_to_upload, Toast.LENGTH_SHORT).show()
            return
        }
        val network = getSystemService(android.net.ConnectivityManager::class.java)
        if (network.activeNetwork == null) {
            Toast.makeText(this, R.string.no_internet, Toast.LENGTH_SHORT).show()
            return
        }
        if (network.isActiveNetworkMetered && waiting.isNotEmpty()) {
            // Zipped CSVs are about a third of their size on the phone
            val mb = waiting.sumOf { r -> r.walkTopDown().filter { it.isFile }.sumOf { it.length() } } / 3 / 1_000_000
            AlertDialog.Builder(this)
                .setMessage(resources.getQuantityString(R.plurals.upload_mobile_data, waiting.size, waiting.size, maxOf(mb, 1)))
                .setPositiveButton(R.string.action_upload) { _, _ -> runUpload(names) }
                .setNegativeButton(R.string.wait_for_wifi, null)
                .show()
        } else {
            runUpload(names)
        }
    }

    private fun runUpload(only: List<String>?) {
        UploadJob.uploadNow(this, only)
        Toast.makeText(this, R.string.upload_started, Toast.LENGTH_LONG).show()
    }

    private fun confirmServerDelete() {
        AlertDialog.Builder(this)
            .setMessage(R.string.server_delete_question)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                Thread {
                    val result = runCatching { Upload.deleteOnServer(this) }
                    runOnUiThread {
                        renderUpload()
                        val msg = result.fold(
                            { resources.getQuantityString(R.plurals.server_deleted, it, it) },
                            { getString(R.string.server_delete_failed, it.message) },
                        )
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    }
                }.start()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // --- Rides -------------------------------------------------------------------------------

    /** The rider's own full tracks (not privacy-trimmed), saved to Downloads and offered for sharing. */
    private fun exportGpx(rides: List<File>) {
        Thread {
            val result = runCatching { rides.mapNotNull { GpxExporter.export(this, it) } }
            runOnUiThread {
                val saved = result.getOrElse {
                    Toast.makeText(this, getString(R.string.export_failed, it.message), Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                if (saved.isEmpty()) {
                    Toast.makeText(this, R.string.no_gps_points, Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                Toast.makeText(this, resources.getQuantityString(R.plurals.gpx_saved, saved.size, saved.size), Toast.LENGTH_LONG).show()
                val share = if (saved.size == 1) {
                    Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, saved[0].uri)
                } else {
                    Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(saved.map { it.uri }))
                }
                share.setType("application/gpx+xml").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(share, getString(R.string.share_tracks)))
            }
        }.start()
    }

    private fun confirmDelete(rides: List<File>) {
        AlertDialog.Builder(this)
            .setMessage(resources.getQuantityString(R.plurals.delete_rides_question, rides.size, rides.size))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                rides.filterNot { RecorderState.recording && RecorderState.rideDir == it }.forEach { it.deleteRecursively() }
                ui.selected = emptySet()
                refreshRides()
            }
            .setNegativeButton(R.string.cancel, null)
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

        /** A kept-on ride screen dims to this (0–1) after this long without a touch */
        private const val DIM_AFTER_MS = 20_000L
        private const val DIM_BRIGHTNESS = 0.05f
        private const val KEY_INTRO_SEEN = "intro_seen"
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
    // Opened again from Settings: back closes it (on first launch there is nothing behind it)
    BackHandler(enabled = ui.showIntro && !ui.firstRun && !ui.recording) { actions.finishIntro() }
    BackHandler(enabled = ui.screen != Screen.MAIN && !ui.recording) {
        // In the ride list, back first drops the selection
        if (ui.screen == Screen.RIDES && ui.selected.isNotEmpty()) actions.selectRides(emptySet()) else actions.back()
    }
    // Surface, not just a background: it also sets the text colour for the theme, so plain Text
    // is light in dark mode
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.safeDrawingPadding()) {
            when {
                ui.recording -> RecordingScreen(ui, actions)
                ui.showIntro -> IntroScreen(ui, actions)
                ui.screen == Screen.SETTINGS -> SettingsScreen(ui, actions)
                ui.screen == Screen.RIDES -> RidesScreen(ui, actions)
                else -> MainScreen(ui, actions)
            }
        }
    }
    if (ui.firstRun && !ui.showIntro) FirstBikesDialog(saveFirstBikes)
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
