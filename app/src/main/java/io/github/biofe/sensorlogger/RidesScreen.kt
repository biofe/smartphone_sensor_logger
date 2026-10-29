package io.github.biofe.sensorlogger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** All rides on the phone, newest first, tinted in their bike's colour. */
@Composable
fun RidesScreen(ui: UiState, actions: AppActions) {
    Column(Modifier.fillMaxSize()) {
        ScreenTitle(
            stringResource(R.string.my_rides),
            subtitle = "${ridesCount(ui.rides.size)} · ${formatSize(ui.rides.sumOf { it.sizeBytes })}",
            onBack = actions.back,
        )
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(ui.rides, key = { it.dir.name }) { ride -> RideRow(ride) { actions.rideActions(ride.dir) } }
        }
    }
}
