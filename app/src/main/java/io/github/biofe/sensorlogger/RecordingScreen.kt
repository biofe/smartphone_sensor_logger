package io.github.biofe.sensorlogger

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * What the rider sees during a ride: time and speed large, the sensors' state, the poll buttons
 * (each tap saves time and place) and a stop button that needs a one-second press.
 */
@Composable
fun RecordingScreen(ui: UiState, actions: AppActions) {
    // ui.now changes every second, which also re-reads the recorder's counters below
    val s = RecorderState
    val secs = ((ui.now - s.startedAtMs) / 1000).coerceAtLeast(0)
    val timer = if (secs < 3600) String.format(Locale.US, "%d:%02d", secs / 60, secs % 60)
    else String.format(Locale.US, "%d:%02d:%02d", secs / 3600, (secs % 3600) / 60, secs % 60)
    val speed = if (s.lastSpeedKmh.isNaN()) "–" else String.format(Locale.getDefault(), "%.0f", s.lastSpeedKmh)
    val big = MaterialTheme.typography.displayMedium.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 44.sp)

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error))
                    HGap(8.dp)
                    Text(
                        ui.recordingBike?.let { stringResource(R.string.recording_on, it) } ?: stringResource(R.string.recording),
                        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(timer, style = big)
            }
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(speed, style = big)
                    HGap(4.dp)
                    Text("km/h", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                }
                Text(
                    formatDistance(s.distanceM), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ui.sensors.forEach { StatusChip(it.label, it.ok, Modifier.weight(1f)) }
        }
        s.error?.let {
            Text(it, color = LocalVelowind.current.warn, style = MaterialTheme.typography.bodyMedium)
        }

        PollGrid(ui, actions, Modifier.weight(1f))
        PollStatus(ui, actions)
        HoldToStopButton(onStop = actions.stopRide)
    }
}

/** The 12 poll buttons in rows of 4, 3, 3 and 2; the tapped one flashes briefly. */
@Composable
private fun PollGrid(ui: UiState, actions: AppActions, modifier: Modifier) {
    var flash by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(ui.lastTap) {
        val tap = ui.lastTap ?: return@LaunchedEffect
        if (ui.lastTapUndone) return@LaunchedEffect
        flash = tap.choice.key
        delay(FLASH_MS)
        flash = null
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Labels.ROWS.forEach { row ->
            val title = stringResource(row.title)
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.choices.forEach { choice ->
                    PollTile(
                        title, choice, ui.labelCounts[choice.key] ?: 0, flash == choice.key,
                        Modifier.weight(1f).fillMaxHeight(),
                    ) { actions.tapLabel(choice) }
                }
            }
        }
    }
}

@Composable
private fun PollTile(category: String, choice: Labels.Choice, count: Int, flashing: Boolean, modifier: Modifier, onTap: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (flashing) colors.primary else colors.surfaceContainerHigh, label = "tile")
    val fg = if (flashing) colors.onPrimary else colors.onSurface
    val label = stringResource(choice.label)
    val description = stringResource(R.string.poll_count, category, label, count)
    Surface(
        onClick = onTap,
        modifier = modifier.semantics { contentDescription = description },
        shape = RoundedCornerShape(16.dp),
        color = bg,
        contentColor = fg,
    ) {
        Box(Modifier.clearAndSetSemantics {}) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(VelowindIcons.poll(choice.key), contentDescription = null, modifier = Modifier.size(30.dp))
                Gap(4.dp)
                Text(
                    label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center,
                    maxLines = 2, lineHeight = 16.sp,
                )
            }
            if (count > 0) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).sizeIn(minWidth = 20.dp).height(20.dp)
                        .clip(CircleShape).background(colors.primary).padding(horizontal = 5.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(count.toString(), style = MaterialTheme.typography.labelSmall, color = colors.onPrimary)
                }
            }
        }
    }
}

/** What the last tap saved, with Undo for a few seconds; otherwise a one-line hint. */
@Composable
private fun PollStatus(ui: UiState, actions: AppActions) {
    val tap = ui.lastTap
    Row(
        Modifier.fillMaxWidth().height(40.dp).semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tap == null) {
            Text(
                stringResource(R.string.poll_hint), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            val label = stringResource(tap.choice.label)
            Text(
                stringResource(if (ui.lastTapUndone) R.string.poll_undone else R.string.poll_saved, label),
                style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f),
            )
            if (!ui.lastTapUndone) {
                TextButton(onClick = actions.undoLabel) {
                    Icon(VelowindIcons.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
                    HGap(6.dp)
                    Text(stringResource(R.string.undo))
                }
            }
        }
    }
}

private const val FLASH_MS = 600L

/** One sensor chip: [ok] means data arrived in the last few seconds. */
class SensorHealth(val label: String, val ok: Boolean)
