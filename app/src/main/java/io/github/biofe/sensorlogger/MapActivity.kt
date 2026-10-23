package io.github.biofe.sensorlogger

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * OpenStreetMap with the rider's own tracks and annotations. Tapping the map and confirming
 * opens the poll for that spot, so places can be rated without stopping there.
 * Runs on Leaflet (bundled in assets); tiles come from tile.openstreetmap.org.
 */
class MapActivity : Activity() {

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Annotate on the map"
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        // OSM's tile policy asks apps to identify themselves
        web.settings.userAgentString = web.settings.userAgentString +
            " BikeSensorLogger/${packageManager.getPackageInfo(packageName, 0).versionName}" +
            " (+https://github.com/biofe/smartphone_sensor_logger)"
        web.addJavascriptInterface(Bridge(), "Android")
        setContentView(web)
        web.loadDataWithBaseURL("https://bikesensorlogger.app/", page(), "text/html", "utf-8", null)
    }

    private inner class Bridge {
        @JavascriptInterface
        fun annotate(lat: Double, lon: Double) {
            runOnUiThread {
                AnnotationPoll.show(
                    this@MapActivity, "Annotate this spot", lat, lon, 0.0, "map", Annotations.mapFile(this@MapActivity),
                ) {
                    val spot = Annotations.read(Annotations.mapFile(this@MapActivity)).lastOrNull() ?: return@show
                    web.evaluateJavascript("addSpot(${spotJson(spot)})", null)
                }
            }
        }
    }

    private fun spotJson(s: Annotations.Spot) =
        JSONObject().put("lat", s.lat).put("lon", s.lon).put("text", s.summary).toString()

    /** Tracks thinned to about one point every 5 s; they never leave the phone. */
    private fun tracks(): JSONArray {
        val out = JSONArray()
        for (ride in Rides.list(this)) {
            val gps = File(ride, Rides.GPS_FILE)
            if (!gps.exists()) continue
            val pts = JSONArray()
            gps.useLines { lines ->
                lines.drop(1).forEachIndexed { i, line ->
                    if (i % 5 != 0) return@forEachIndexed
                    val c = line.split(',')
                    val lat = c.getOrNull(2)?.toDoubleOrNull() ?: return@forEachIndexed
                    val lon = c.getOrNull(3)?.toDoubleOrNull() ?: return@forEachIndexed
                    pts.put(JSONArray().put(lat).put(lon))
                }
            }
            if (pts.length() > 1) out.put(pts)
        }
        return out
    }

    private fun page(): String {
        val css = assets.open("leaflet/leaflet.css").bufferedReader().readText()
        val js = assets.open("leaflet/leaflet.js").bufferedReader().readText()
        val spots = JSONArray(Annotations.all(this).map { JSONObject(spotJson(it)) })
        val data = JSONObject().put("tracks", tracks()).put("spots", spots).toString().replace("</", "<\\/")
        return """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>$css
html, body, #map { height: 100%; margin: 0; }
.hint { position: absolute; z-index: 1000; left: 8px; right: 8px; bottom: 16px; padding: 8px 12px;
  background: rgba(255,255,255,.92); color: #222; font: 14px sans-serif; border-radius: 6px; text-align: center; }
.leaflet-popup-content button { font: inherit; padding: 6px 12px; margin-top: 6px; }
.leaflet-popup-content { white-space: pre-line; }
</style></head><body><div id="map"></div>
<div class="hint">Tap a spot to rate it. Green circles are your earlier answers.</div>
<script>$js</script>
<script>
const data = $data;
const map = L.map('map');
L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
  maxZoom: 19, attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
}).addTo(map);
const bounds = L.latLngBounds([]);
for (const t of data.tracks) { const l = L.polyline(t, {color: '#1c7ed6', weight: 3, opacity: .7}).addTo(map); bounds.extend(l.getBounds()); }
function addSpot(s) {
  L.circleMarker([s.lat, s.lon], {radius: 8, color: '#2b8a3e', fillColor: '#40c057', fillOpacity: .8})
    .bindPopup(s.text || '(no answers)').addTo(map);
  bounds.extend([s.lat, s.lon]);
}
data.spots.forEach(addSpot);
if (bounds.isValid()) map.fitBounds(bounds, {padding: [20, 20]}); else map.setView([51.16, 10.45], 6);
map.on('click', (e) => {
  const {lat, lng} = e.latlng;
  const div = document.createElement('div');
  div.textContent = 'Rate this spot?';
  const b = document.createElement('button');
  b.textContent = 'Open poll';
  b.onclick = () => { map.closePopup(); Android.annotate(lat, lng); };
  div.appendChild(document.createElement('br'));
  div.appendChild(b);
  L.popup().setLatLng(e.latlng).setContent(div).openOn(map);
});
</script></body></html>"""
    }
}
