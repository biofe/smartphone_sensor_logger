package io.github.biofe.sensorlogger

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * The rider's bikes, kept on the phone. Only the type goes into ride data; the name and colour
 * stay here. Each bike has one of the [BikeColors] so its rides can be told apart in the list.
 */
object Bikes {

    val TYPES = listOf(
        "trekking", "city", "road / racing", "gravel", "mountain", "e-mountain", "e-bike",
        "cargo", "e-cargo", "folding", "recumbent", "velomobile", "other",
    )

    /** [color] indexes the bike palette; [id] stays the same when the bike is edited. */
    data class Bike(val type: String, val name: String, val color: Int, val id: String = newId()) {
        val label: String get() = name.ifBlank { type.replaceFirstChar { it.uppercase() } }
    }

    const val COLOR_COUNT = 8

    private const val PREFS = "bikes"
    private const val KEY = "list"

    private fun newId() = UUID.randomUUID().toString().take(8)

    fun list(context: Context): List<Bike> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        val arr = JSONArray(raw)
        // Bikes saved before 0.10.0 have no colour or id: give them colours in list order
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Bike(
                o.getString("type"), o.optString("name"),
                o.optInt("color", i % COLOR_COUNT).mod(COLOR_COUNT),
                o.optString("id").ifEmpty { "legacy$i" },
            )
        }
    }

    fun save(context: Context, bikes: List<Bike>) {
        val arr = JSONArray(bikes.map {
            JSONObject().put("type", it.type).put("name", it.name).put("color", it.color).put("id", it.id)
        })
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    /** The first colour no bike uses yet, so a new bike looks different from the others. */
    fun nextColor(bikes: List<Bike>): Int =
        (0 until COLOR_COUNT).firstOrNull { c -> bikes.none { it.color == c } } ?: (bikes.size % COLOR_COUNT)

    /** New bikes for the types picked on first launch, each in its own colour. */
    fun fromTypes(types: List<String>): List<Bike> =
        types.mapIndexed { i, t -> Bike(t, "", i % COLOR_COUNT) }
}
