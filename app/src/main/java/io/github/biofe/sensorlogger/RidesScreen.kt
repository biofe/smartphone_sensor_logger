package io.github.biofe.sensorlogger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * All rides on the phone, newest first, tinted in their bike's colour. Tapping rides selects them;
 * the bar at the bottom then acts on the selection.
 */
@Composable
fun RidesScreen(ui: UiState, actions: AppActions) {
    val selected = ui.rides.filter { it.dir.name in ui.selected }
    val all = ui.rides.isNotEmpty() && selected.size == ui.rides.size
    Column(Modifier.fillMaxSize()) {
        ScreenTitle(
            if (selected.isEmpty()) stringResource(R.string.my_rides) else pluralStringResource(R.plurals.selected_n, selected.size, selected.size),
            subtitle = "${ridesCount(ui.rides.size)} · ${formatSize(ui.rides.sumOf { it.sizeBytes })}",
            onBack = actions.back,
        ) {
            if (ui.rides.isNotEmpty()) {
                TextButton(onClick = { if (all) actions.selectRides(emptySet()) else actions.selectRides(ui.rides.map { it.dir.name }.toSet()) }) {
                    Text(stringResource(if (all) R.string.select_none else R.string.select_all))
                }
            }
        }
        if (selected.isEmpty() && ui.rides.isNotEmpty()) {
            Text(
                stringResource(R.string.tap_to_select), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(ui.rides, key = { it.dir.name }) { ride ->
                val name = ride.dir.name
                RideRow(ride, selected = name in ui.selected) {
                    actions.selectRides(if (name in ui.selected) ui.selected - name else ui.selected + name)
                }
            }
        }
        if (selected.isNotEmpty()) SelectionBar(selected, actions)
    }
}

@Composable
private fun SelectionBar(selected: List<Rides.Info>, actions: AppActions) {
    val dirs = selected.map { it.dir }
    val finished = selected.none { it.recording }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            BarAction(VelowindIcons.Pin, stringResource(R.string.action_annotate), enabled = selected.size == 1 && finished) {
                actions.annotateRide(dirs.first())
            }
            BarAction(VelowindIcons.Upload, stringResource(R.string.action_upload), enabled = finished) { actions.uploadRides(dirs) }
            BarAction(VelowindIcons.Route, stringResource(R.string.action_gpx), enabled = finished) { actions.gpxRides(dirs) }
            BarAction(VelowindIcons.Download, stringResource(R.string.action_zip), enabled = finished) { actions.zipRides(dirs) }
            BarAction(VelowindIcons.Delete, stringResource(R.string.action_delete), enabled = finished, color = MaterialTheme.colorScheme.error) {
                actions.deleteRides(dirs)
            }
        }
    }
}

@Composable
private fun BarAction(icon: ImageVector, label: String, enabled: Boolean, color: Color = Color.Unspecified, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 56.dp)) {
        // Inside the button LocalContentColor is already the enabled or disabled colour
        val tint = if (enabled && color != Color.Unspecified) color else LocalContentColor.current
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = tint)
        }
    }
}
