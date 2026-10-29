package io.github.biofe.sensorlogger

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/**
 * What the rider sees during a ride: time and speed large, the sensors' state, and a stop button
 * that needs a one-second press. The live poll buttons take the middle of this screen next.
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

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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

        FilledTonalButton(
            onClick = actions.annotateSpot,
            modifier = Modifier.fillMaxWidth().height(72.dp),
        ) {
            Icon(VelowindIcons.Pin, contentDescription = null)
            HGap(8.dp)
            Text(stringResource(R.string.annotate_spot), style = MaterialTheme.typography.titleMedium)
        }
        if (s.annotations > 0) {
            Text(
                stringResource(R.string.annotations_count, s.annotations), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Box(Modifier.weight(1f))
        HoldToStopButton(onStop = actions.stopRide)
    }
}

/** One sensor chip: [ok] means data arrived in the last few seconds. */
class SensorHealth(val label: String, val ok: Boolean)
