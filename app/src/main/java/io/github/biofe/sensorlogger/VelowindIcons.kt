package io.github.biofe.sensorlogger

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Line icons on a 24 grid, drawn here instead of pulling in the large material-icons library.
 * Same style as Material Symbols (2 px rounded strokes); Icon() tints them.
 */
object VelowindIcons {

    private fun circle(cx: Float, cy: Float, r: Float) = "M${cx - r},${cy}a$r,$r 0 1,0 ${2 * r},0a$r,$r 0 1,0 ${-2 * r},0"

    private fun icon(name: String, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (d in paths) {
            b.addPath(
                pathData = addPathNodes(d),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    private val wheels = circle(5.5f, 16f, 3.5f) + circle(18.5f, 16f, 3.5f)
    private const val BOLT = "M20 1.5l-2.5 4h3l-2.5 4"

    val Logo = icon("logo", wheels, "M5.5 16L9 10h6.5l3 6M9 10l3 6H5.5M15.5 10l-1-3h-2", "M2 6.5h8.5a2.2 2.2 0 1 0-2.2-2.2M13.5 4h6.5")

    private val standard = arrayOf(wheels, "M5.5 16L9 9h7l2.5 7M9 9l3 7H5.5M16 9l-1-3h-2.5M8 6.5h3")
    private val road = arrayOf(wheels, "M5.5 16L9 9h7l2.5 7M9 9l3 7H5.5M16 9l-1-3h2.5c1 0 1 2.5 0 2.5M8 6.5h3")
    private val mountain = arrayOf(circle(5.5f, 16f, 3.8f) + circle(18.5f, 16f, 3.8f), circle(5.5f, 16f, 1.5f) + circle(18.5f, 16f, 1.5f), "M5.5 16L9.5 9h6l3 7M9.5 9l2.5 7H5.5M15.5 9l-1-3.5h-2.5M8 7h3")
    private val cargo = arrayOf(circle(4f, 17f, 3f) + circle(20f, 17f, 3f), "M4 17l3-7h4l1 7h8M7 10L6 7H4M12 10h8v5h-8z")
    private val folding = arrayOf(circle(6f, 17.5f, 2.5f) + circle(18f, 17.5f, 2.5f), "M6 17.5L12 11l6 6.5M12 11V6h-2.5M12 11l4.5-1-1-4h2")
    private val recumbent = arrayOf(circle(5f, 17f, 3f) + circle(19f, 17f, 3f), "M5 17l4-3h6l4 3M9 14L7 9M15 14l2-4h2")

    /** The icon for a bike type; e-bikes get a small bolt. */
    fun bike(type: String): ImageVector {
        val base = when {
            type.contains("road") || type == "gravel" -> road
            type.contains("mountain") -> mountain
            type.contains("cargo") -> cargo
            type == "folding" -> folding
            type == "recumbent" || type == "velomobile" -> recumbent
            else -> standard
        }
        val electric = type.startsWith("e-")
        return icon("bike_$type", *(if (electric) base + BOLT else base))
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
}
