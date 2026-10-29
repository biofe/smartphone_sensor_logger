package io.github.biofe.sensorlogger

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** The screen when no ride is recording: start buttons per bike and the latest rides. */
@Composable
fun MainScreen(ui: UiState, actions: AppActions) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(VelowindIcons.Logo, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp))
            HGap(10.dp)
            Text(stringResource(R.string.brand), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            FilledTonalIconButton(onClick = actions.openSettings, modifier = Modifier.size(48.dp)) {
                Icon(VelowindIcons.Settings, contentDescription = stringResource(R.string.settings))
            }
        }

        ui.update?.let { info ->
            Button(
                onClick = actions.installUpdate,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            ) { Text(stringResource(R.string.install_update, info.versionName)) }
        }

        Text(
            stringResource(R.string.start_a_ride), style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 20.dp, top = 8.dp, bottom = 10.dp),
        )
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(ui.bikes, key = { it.id }) { bike -> BikeTile(bike) { actions.startRide(bike) } }
            item { AddBikeTile(actions.addBike) }
        }
        Text(
            stringResource(if (ui.bikes.size > 1) R.string.start_hint_swipe else R.string.start_hint),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
        )

        if (ui.autoStopped) {
            Text(
                stringResource(R.string.auto_stopped), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp),
            )
        }

        SectionCard(title = null, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.my_rides), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (ui.rides.isNotEmpty()) {
                    TextButton(onClick = actions.openRides) { Text(stringResource(R.string.see_all, ui.rides.size)) }
                }
            }
            if (ui.rides.isEmpty()) {
                Text(
                    stringResource(R.string.no_rides_yet), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ui.rides.take(3).forEach { ride -> RideRow(ride) { actions.rideActions(ride.dir) } }
            }
            Text(
                ui.uploadLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        OutlinedButton(
            onClick = actions.openMap,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp),
        ) {
            Icon(VelowindIcons.Map, contentDescription = null, modifier = Modifier.size(18.dp))
            HGap(8.dp)
            Text(stringResource(R.string.annotate_on_map))
        }
    }
}

@Composable
private fun BikeTile(bike: Bikes.Bike, onStart: () -> Unit) {
    val c = LocalVelowind.current.bike(bike.color)
    val description = stringResource(R.string.start_ride_on, bike.label)
    Surface(
        onClick = onStart,
        shape = RoundedCornerShape(24.dp),
        color = c.tint,
        modifier = Modifier.width(158.dp).height(176.dp).semantics { contentDescription = description },
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            BikeBadge(bike.type, bike.color, size = 56.dp)
            Column {
                Text(bike.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                if (bike.name.isNotBlank()) {
                    Text(bike.type, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primary) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(VelowindIcons.Play, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(14.dp))
                    HGap(6.dp)
                    Text(stringResource(R.string.start), color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun AddBikeTile(onAdd: () -> Unit) {
    Surface(
        onClick = onAdd,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.width(120.dp).height(176.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(VelowindIcons.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                Gap(8.dp)
                Text(stringResource(R.string.add_bike), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
            }
        }
    }
}
