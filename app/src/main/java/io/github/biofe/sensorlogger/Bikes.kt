package io.github.biofe.sensorlogger

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** The rider's bikes, kept on the phone. Only the type goes into ride data; the name stays here. */
object Bikes {

    val TYPES = listOf(
        "trekking", "city", "road / racing", "gravel", "mountain", "e-mountain", "e-bike",
        "cargo", "e-cargo", "folding", "recumbent", "velomobile", "other",
    )

    data class Bike(val type: String, val name: String) {
        val label: String get() = if (name.isBlank()) type else "$name ($type)"
    }

    private const val PREFS = "bikes"
    private const val KEY = "list"

    fun list(context: Context): List<Bike> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        val arr = JSONArray(raw)
        return (0 until arr.length()).map { arr.getJSONObject(it).let { o -> Bike(o.getString("type"), o.optString("name")) } }
    }

    fun save(context: Context, bikes: List<Bike>) {
        val arr = JSONArray(bikes.map { JSONObject().put("type", it.type).put("name", it.name) })
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }
}
