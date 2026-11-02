package io.github.biofe.sensorlogger

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.biofe.sensorlogger.core.GeoPoint
import io.github.biofe.sensorlogger.core.PrivacyTrim
import io.github.biofe.sensorlogger.core.RideStats
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Annotating a ride afterwards: its track on OpenStreetMap, and the same poll buttons as during
 * the ride for any spot the rider taps on the track. The taps go into the ride's labels.csv with
 * source `later` and the time the rider passed that spot. Only the part of the track that leaves
 * the phone can be annotated; the privacy-trimmed ends are drawn grey.
 * Runs on Leaflet (bundled in assets); tiles come from tile.openstreetmap.org.
 */
class AnnotateActivity : ComponentActivity() {

    /** A point of the track the rider can annotate. */
    private class Spot(val point: GeoPoint, val accuracyM: Double?, val km: Double)

    private lateinit var ride: File
    private lateinit var web: WebView
    private var spots: List<Spot> = emptyList()
    private val handler = Handler(Looper.getMainLooper())

    private var annotatable by mutableStateOf(true)
    private var selected by mutableStateOf<Spot?>(null)
    private var lastTap by mutableStateOf<Labels.Tap?>(null)
    private var lastTapUndone by mutableStateOf(false)
    private var changed = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val name = intent.getStringExtra(EXTRA_RIDE)
        ride = Rides.list(this).firstOrNull { it.name == name } ?: run {
            finish()
            return
        }
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        // OSM's tile policy asks apps to identify themselves
        web.settings.userAgentString = web.settings.userAgentString +
            " Velowind/${packageManager.getPackageInfo(packageName, 0).versionName}" +
            " (+https://github.com/biofe/smartphone_sensor_logger)"
        web.addJavascriptInterface(Bridge(), "Android")

        val info = runCatching { Rides.info(this, ride) }.getOrNull()
        val title = info?.startedAt?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(it) } ?: ride.name
        setContent {
            VelowindTheme {
                val bike = LocalVelowind.current.bike(info?.color).color.toArgb()
                LaunchedEffect(Unit) { load(bike) }
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
                    ScreenTitle(stringResource(R.string.annotate_title), subtitle = title, onBack = ::finish)
                    AndroidView({ web }, Modifier.weight(1f).fillMaxWidth())
                    Panel()
                }
            }
        }
    }

    @Composable
    private fun Panel() {
        val spot = selected
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                if (spot == null) {
                    Text(
                        stringResource(if (annotatable) R.string.annotate_hint else R.string.annotate_nothing_kept),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    return@Column
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.annotate_spot_at, clock(spot.point.tMs), formatDistance(spot.km * 1000)),
                        style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = ::deselect, modifier = Modifier.size(48.dp)) {
                        Icon(VelowindIcons.Close, contentDescription = stringResource(R.string.close))
                    }
                }
                PollGrid(lastTap, lastTapUndone, ::tap, Modifier.fillMaxWidth().height(260.dp))
                PollStatus(lastTap, lastTapUndone, ::undo, hint = stringResource(R.string.annotate_poll_hint))
            }
        }
    }

    private fun clock(tMs: Long) = DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault()).format(Date(tMs))

    // --- Taps ----------------------------------------------------------------------------------

    private fun tap(choice: Labels.Choice) {
        val spot = selected ?: return
        val p = spot.point
        Labels.append(ride, p.tMs, p.lat, p.lon, spot.accuracyM, "later", choice)
        val tap = Labels.Tap(ride, p.tMs, choice)
        Haptics.tap(this)
        changed = true
        web.evaluateJavascript("addLabel(${labelJson(p.tMs, p.lat, p.lon, "later", choice.key)})", null)
        show(tap, undone = false)
    }

    private fun undo() {
        val tap = lastTap ?: return
        if (lastTapUndone) return
        Labels.undo(tap)
        Haptics.undo(this)
        web.evaluateJavascript("removeLabel(${JSONObject.quote(labelId(tap.tMs, tap.choice.key))})", null)
        show(tap, undone = true)
    }

    private fun show(tap: Labels.Tap, undone: Boolean) {
        lastTap = tap
        lastTapUndone = undone
        handler.removeCallbacks(clearTap)
        handler.postDelayed(clearTap, if (undone) 2_000L else 5_000L)
    }

    private val clearTap = Runnable { lastTap = null }

    private fun deselect() {
        selected = null
        lastTap = null
        web.evaluateJavascript("select(null)", null)
    }

    override fun onDestroy() {
        handler.removeCallbacks(clearTap)
        // Send the new taps with the next upload run (on Wi-Fi, if automatic upload is on)
        if (changed) UploadJob.schedule(this)
        super.onDestroy()
    }

    // --- Map -----------------------------------------------------------------------------------

    private inner class Bridge {
        /** The rider tapped the map; picks the nearest annotatable point of the track. */
        @JavascriptInterface
        fun tapped(lat: Double, lon: Double, metresPerPixel: Double) {
            runOnUiThread {
                val tapped = GeoPoint(0, lat, lon)
                val nearest = spots.minByOrNull { PrivacyTrim.distanceM(it.point, tapped) }
                // Within about a finger's width of the track
                val reach = maxOf(MIN_REACH_M, metresPerPixel * REACH_PX)
                if (nearest == null || PrivacyTrim.distanceM(nearest.point, tapped) > reach) {
                    web.evaluateJavascript("hint(${JSONObject.quote(getString(R.string.annotate_tap_track))})", null)
                    return@runOnUiThread
                }
                selected = nearest
                lastTap = null
                web.evaluateJavascript("select([${nearest.point.lat}, ${nearest.point.lon}])", null)
            }
        }
    }

    private fun labelId(tMs: Long, key: String) = "$tMs/$key"

    private fun labelJson(tMs: Long, lat: Double, lon: Double, source: String, key: String): String {
        val choice = Labels.choice(key)
        val text = (choice?.let { getString(it.label) } ?: key) + " · " +
            getString(if (source == "live") R.string.annotate_during_ride else R.string.annotate_later)
        return JSONObject().put("id", labelId(tMs, key)).put("lat", lat).put("lon", lon)
            .put("live", source == "live").put("text", text).toString()
    }

    /** Reads the track and taps off the main thread, then draws the map. */
    private fun load(bikeColor: Int) {
        Thread {
            val window = Exporter.keptWindow(ride)
            val track = Rides.track(ride)
            val accuracy = readAccuracy()
            var metres = 0.0
            val kept = mutableListOf<Spot>()
            val lines = JSONArray()
            var current = JSONArray()
            var currentKept: Boolean? = null
            track.forEachIndexed { i, p ->
                if (i > 0) metres += RideStats.stepM(track[i - 1], p)
                val inside = window != null && p.tMs in window
                if (inside) kept += Spot(p, accuracy[p.tMs], metres / 1000)
                if (inside != currentKept) {
                    // Start a new piece, sharing the last point so the line has no gap
                    if (current.length() > 0) lines.put(JSONObject().put("kept", currentKept).put("pts", current))
                    current = JSONArray().apply { if (i > 0) put(JSONArray().put(track[i - 1].lat).put(track[i - 1].lon)) }
                    currentKept = inside
                }
                current.put(JSONArray().put(p.lat).put(p.lon))
            }
            if (current.length() > 0) lines.put(JSONObject().put("kept", currentKept).put("pts", current))
            val labels = JSONArray()
            for (l in Labels.read(ride)) {
                if (l.lat != null && l.lon != null) labels.put(JSONObject(labelJson(l.tMs, l.lat, l.lon, l.source, l.key)))
            }
            val data = JSONObject()
                .put("lines", lines).put("labels", labels)
                .put("color", String.format("#%06X", bikeColor and 0xFFFFFF))
                .toString()
            val page = page(data)
            handler.post {
                if (isFinishing) return@post
                spots = kept
                annotatable = kept.isNotEmpty()
                web.loadDataWithBaseURL("https://velowind.app/", page, "text/html", "utf-8", null)
            }
        }.start()
    }

    /** GPS accuracy per fix time, stored with each tap. */
    private fun readAccuracy(): Map<Long, Double> {
        val gps = File(ride, Rides.GPS_FILE)
        if (!gps.exists()) return emptyMap()
        return gps.useLines { lines ->
            lines.drop(1).mapNotNull { line ->
                val c = line.split(',')
                val t = c.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                val acc = c.getOrNull(7)?.toDoubleOrNull() ?: return@mapNotNull null
                t to acc
            }.toMap()
        }
    }

    private fun page(data: String): String {
        val css = assets.open("leaflet/leaflet.css").bufferedReader().readText()
        val js = assets.open("leaflet/leaflet.js").bufferedReader().readText()
        val safe = data.replace("</", "<\\/")
        return """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>$css
html, body, #map { height: 100%; margin: 0; }
#hint { position: absolute; z-index: 1000; left: 8px; right: 8px; top: 8px; padding: 8px 12px; display: none;
  background: rgba(255,255,255,.95); color: #222; font: 14px sans-serif; border-radius: 8px; text-align: center; }
</style></head><body><div id="map"></div><div id="hint"></div>
<script>$js</script>
<script>
const data = $safe;
const map = L.map('map');
L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
  maxZoom: 19, attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
}).addTo(map);
const bounds = L.latLngBounds([]);
for (const l of data.lines) {
  const line = l.kept
    ? L.polyline(l.pts, {color: data.color, weight: 6, opacity: .9})
    : L.polyline(l.pts, {color: '#8a8f8e', weight: 4, opacity: .8, dashArray: '6 8'});
  line.addTo(map);
  bounds.extend(line.getBounds());
}
const labels = {};
function addLabel(s) {
  const m = L.circleMarker([s.lat, s.lon], {radius: 7, color: '#ffffff', weight: 2,
    fillColor: s.live ? '#0A6C69' : '#E69F00', fillOpacity: 1, bubblingMouseEvents: false}).bindPopup(s.text).addTo(map);
  (labels[s.id] = labels[s.id] || []).push(m);
}
function removeLabel(id) {
  const m = (labels[id] || []).pop();
  if (m) map.removeLayer(m);
}
data.labels.forEach(addLabel);
let marker = null;
function select(p) {
  if (marker) { map.removeLayer(marker); marker = null; }
  if (p) marker = L.circleMarker(p, {radius: 12, color: '#000000', weight: 3, fill: false, interactive: false}).addTo(map);
}
let hintTimer = null;
function hint(text) {
  const h = document.getElementById('hint');
  h.textContent = text; h.style.display = 'block';
  clearTimeout(hintTimer); hintTimer = setTimeout(() => { h.style.display = 'none'; }, 2500);
}
if (bounds.isValid()) map.fitBounds(bounds, {padding: [24, 24]}); else map.setView([51.16, 10.45], 6);
map.on('click', (e) => {
  const a = map.containerPointToLatLng([0, 0]), b = map.containerPointToLatLng([100, 0]);
  Android.tapped(e.latlng.lat, e.latlng.lng, map.distance(a, b) / 100);
});
</script></body></html>"""
    }

    companion object {
        private const val EXTRA_RIDE = "ride"
        private const val MIN_REACH_M = 15.0
        private const val REACH_PX = 40.0

        fun open(context: Context, ride: File) {
            context.startActivity(Intent(context, AnnotateActivity::class.java).putExtra(EXTRA_RIDE, ride.name))
        }
    }
}
