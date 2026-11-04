package io.github.biofe.sensorlogger

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * Line icons on a 24 grid, drawn here instead of pulling in the large material-icons library.
 * Same style as Material Symbols (2 px rounded strokes); Icon() tints them.
 */
object VelowindIcons {

    private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r},${cy}a$r,$r 0 1,0 ${2 * r},0a$r,$r 0 1,0 ${-2 * r},0"

    private fun icon(name: String, vararg paths: String): ImageVector = icon(name, 2f, *paths)

    private fun icon(name: String, stroke: Float, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (d in paths) {
            b.addPath(
                pathData = addPathNodes(d),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = stroke,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    // Bikes face right like the logo bike: diamond frame, saddle on a seat post, swept-back bar.
    // Each type adds one cue that still reads at 24 dp. Thinner stroke than the UI icons so the
    // details stay clear.
    private const val BIKE_STROKE = 1.6f
    private val wheels = circle(5.5f, 16f, 3.5f) + circle(18.5f, 16f, 3.5f)
    private const val REAR = "M5.5 16L9 10l3 6H5.5" // seat stay, seat tube, chain stay
    private const val FRAME = "M9 10h6.5M12 16l3.5-6" // top tube, down tube
    private const val SADDLE = "M9 10L8.2 8.4M6.8 8.4h3"
    private const val SWEPT_BAR = "M18.5 16L15.2 7.6h-2.3" // fork, stem and swept-back bar
    private const val DROP_BAR = "M18.5 16L15.2 7.6h2.4c1.4 0 1.4 3 0 3"
    /** Above the rear wheel, clear of saddle and handlebar. */
    private const val BOLT = "M4.5 1.5l-2.5 3.5h3l-2.5 3.5"

    /** Plain decimals: tiny values would otherwise print in E notation. */
    private fun num(v: Float) = String.format(Locale.US, "%.2f", v)

    /** Short spokes outside the tyre, for knobbly gravel tyres. */
    private fun knobs(cx: Float, cy: Float, r: Float): String = (0 until 10).joinToString("") { k ->
        val a = 2 * Math.PI * k / 10
        val c = Math.cos(a).toFloat()
        val s = Math.sin(a).toFloat()
        "M${num(cx + r * c)},${num(cy + r * s)}l${num(0.7f * c)},${num(0.7f * s)}"
    }

    private val standard = arrayOf(wheels, REAR, FRAME, SWEPT_BAR, SADDLE)
    private val trekking = standard + "M2.6 10.6h5.8M3.4 10.6L5.5 16" // rear rack
    private val city = arrayOf(wheels, REAR, "M12 16c1.6-2.8 3-4 4.9-4", "M18.5 16L14.9 6.8c-.8-.4-2-.2-2.8.3", SADDLE) // step-through, upright bar
    private val road = arrayOf(wheels, REAR, FRAME, DROP_BAR, SADDLE)
    private val knobbly = circle(5.5f, 16f, 3.2f) + circle(18.5f, 16f, 3.2f)
    private val treads = knobs(5.5f, 16f, 3.2f) + knobs(18.5f, 16f, 3.2f)
    private val gravel = arrayOf(knobbly, treads, REAR, FRAME, DROP_BAR, SADDLE)
    private val mountain = arrayOf( // knobbly tyres, low seat junction, suspension fork, flat bar
        knobbly, treads, "M5.5 16L9 11.6l3 4.4H5.5", "M9 11.6l6.7-2.4M12 16l3.7-6.8",
        "M18.5 16l-1.2-3.2M17.3 12.8l-1.9-5h3", "M9 11.6L8.2 8.4M6.8 8.4h3",
    )
    private val cargo = arrayOf( // long john: box between rider and small front wheel, bar on the box
        circle(4f, 16f, 3f) + circle(20f, 17f, 2.2f),
        "M4 16l3-6 2.5 6H4M9.5 16h10.5M7 10l-.6-2M5.2 8h2.6", "M12.5 10.5h6.5v4h-6.5z", "M12.5 10.5L12 7.8h-2.2",
    )
    private val folding = arrayOf( // small wheels, hinge, tall seat post and stem
        circle(5f, 17.5f, 2.5f) + circle(19f, 17.5f, 2.5f), "M5 17.5h6l-.6-5L5 17.5", "M10.4 12.5l7-1.5",
        circle(13.9f, 11.75f, 0.9f), "M11 17.5L10 7.2M8.6 7.2h2.8", "M19 17.5L16.6 7.6h-2.2",
    )
    private val recumbent = arrayOf( // reclined seat, pedals out front
        circle(5f, 16.5f, 3f) + circle(19.5f, 17f, 2.5f), "M5 16.5l4.5-3.3h4.5l5.5 3.8",
        "M9.5 13.2L7 7.8M9.5 13.2h3.3", "M14 13.2l5.3-3.4h1.7",
    )
    private val velomobile = arrayOf( // closed shell: pointed tail, head dome, round nose, wheels just showing
        "M1.2 12.2C3.5 10 6.5 9.2 9.5 9.2C15 9.2 20 11 22.2 13.3C23 14.2 22.5 16 21 16H5.5C3.5 16 2 13.8 1.2 12.2z",
        "M8.8 9.25a1.6 1.6 0 0 1 3.2 0", "M4.3 16a1.5 1.5 0 0 0 3 0M15.5 16a1.5 1.5 0 0 0 3 0",
    )

    /** The icon for a bike type; e-bikes get a small bolt. */
    fun bike(type: String): ImageVector {
        val base = when (type) {
            "trekking" -> trekking
            "city" -> city
            "road / racing" -> road
            "gravel" -> gravel
            "mountain", "e-mountain" -> mountain
            "cargo", "e-cargo" -> cargo
            "folding" -> folding
            "recumbent" -> recumbent
            "velomobile" -> velomobile
            else -> standard // e-bike, other
        }
        val electric = type.startsWith("e-")
        return icon("bike_$type", BIKE_STROKE, *(if (electric) base + BOLT else base))
    }

    val Settings = icon("settings", circle(12f, 12f, 3f), "M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9L7 7M17 17l2.1 2.1M4.9 19.1L7 17M17 7l2.1-2.1")
    val Back = icon("back", "M15 5l-7 7 7 7")
    val Next = icon("next", "M9 5l7 7-7 7")
    val Play = icon("play", "M8 5v14l11-7z")
    val Check = icon("check", "M5 12l5 5 9-10")
    val Warning = icon("warning", "M12 3L2 20h20z", "M12 10v4M12 17h.01")
    val Add = icon("add", "M12 5v14M5 12h14")
    val Edit = icon("edit", "M4 20h4L19 9l-4-4L4 16z")
    val Upload = icon("upload", "M7 18a5 5 0 1 1 1-9.9A6 6 0 0 1 19 10a4 4 0 0 1-1 8", "M12 12v8M9 15l3-3 3 3")
    val Map = icon("map", "M12 21s-6-5.5-6-11a6 6 0 1 1 12 0c0 5.5-6 11-6 11z", circle(12f, 10f, 2f))
    val Pin = icon("pin", "M12 21s-6-5.5-6-11a6 6 0 1 1 12 0c0 5.5-6 11-6 11z")
    val Stop = icon("stop", "M6 6h12v12H6z")
    val Delete = icon("delete", "M4 7h16M9.5 7V4h5v3M6 7l1 13h10l1-13M10 11v5M14 11v5")
    val Download = icon("download", "M12 4v11M7 10l5 5 5-5M5 20h14")
    val Route = icon("route", circle(6f, 18f, 2f) + circle(18f, 6f, 2f), "M8 18h6.5a3 3 0 0 0 0-6h-5a3 3 0 0 1 0-6H16")
    val Close = icon("close", "M6 6l12 12M18 6L6 18")
    val Undo = icon("undo", "M9 14L4 9l5-5", "M4 9h10.5a5.5 5.5 0 0 1 0 11H11")

    // Poll buttons: a wheel on the ground for surface, the way's edges for width
    private const val SPEAKER = "M3 9.5h3.5L11 5.5v13l-4.5-4H3z"
    private const val CAR = "M2.5 18v-3.5L5 10h9l3 4.5h2.5V18z"
    private val ground = circle(12f, 8f, 4.5f)
    private fun arrows(from: Float, to: Float) = "M$from 12H${to}M$from 12l2-2M$from 12l2 2M$to 12l-2-2M$to 12l-2 2"
    private val face = circle(12f, 12f, 9f)
    private const val EYES = "M9 9.5h.01M15 9.5h.01"

    private val POLL = mapOf(
        "noise/quiet" to icon("quiet", SPEAKER, "M15 10.5a2 2 0 0 1 0 3"),
        "noise/far_traffic" to icon("far_traffic", "M8 15v-2l1.5-3h5l1.5 3v2z", "M2 21l5-5M22 21l-5-5M12 21v-2"),
        "noise/loud_traffic" to icon("loud_traffic", CAR, circle(6.5f, 18f, 1.8f) + circle(15.5f, 18f, 1.8f), "M17 3.5a4 4 0 0 1 0 5M20 1.5a7 7 0 0 1 0 9"),
        "noise/wind" to icon("wind", "M3 8h10a2.5 2.5 0 1 0-2.5-2.5M3 12h15a2.5 2.5 0 1 1-2.5 2.5M3 16h7"),
        "surface/smooth" to icon("smooth", ground, "M2 15h20"),
        "surface/bumpy" to icon("bumpy", ground, "M2 16q2.5-3 5 0t5 0t5 0t5 0"),
        "surface/rough" to icon("rough", ground, "M2 17l2.5-2.5 2 2 3-3.5 2.5 3 2-2.5 3 3.5 2.5-2 2.5 2"),
        "width/narrow" to icon("narrow", "M9 3v18M15 3v18"),
        "width/medium" to icon("medium", "M6 3v18M18 3v18", arrows(9f, 15f)),
        "width/wide" to icon("wide", "M2.5 3v18M21.5 3v18", arrows(5.5f, 18.5f)),
        "comfort/nice" to icon("nice", face, EYES, "M8 14a5 5 0 0 0 8 0"),
        "comfort/unpleasant" to icon("unpleasant", face, EYES, "M8 16.5a5 5 0 0 1 8 0"),
    )

    fun poll(key: String): ImageVector = POLL[key] ?: Check
}
