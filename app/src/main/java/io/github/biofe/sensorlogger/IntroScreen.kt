package io.github.biofe.sensorlogger

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Shown once before the first bikes are picked, and again from Settings: what the app records,
 * how to use it safely on the bike, and the battery setting that keeps a ride from being cut off.
 */
@Composable
fun IntroScreen(ui: UiState, actions: AppActions) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val last = PAGES - 1
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Gap(8.dp)
            when (page) {
                0 -> RecordedPage()
                1 -> SafetyPage()
                else -> BatteryPage(ui, actions)
            }
        }
        val position = stringResource(R.string.intro_page_of, page + 1, PAGES)
        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp).semantics { contentDescription = position },
            horizontalArrangement = Arrangement.Center,
        ) {
            repeat(PAGES) { i ->
                Box(
                    Modifier.padding(horizontal = 4.dp).size(8.dp).clip(CircleShape)
                        .background(if (i == page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (page > 0) TextButton(onClick = { page-- }) { Text(stringResource(R.string.back)) }
            else TextButton(onClick = actions.finishIntro) { Text(stringResource(R.string.intro_skip)) }
            Box(Modifier.weight(1f))
            Button(onClick = { if (page < last) page++ else actions.finishIntro() }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(if (page < last) R.string.intro_next else R.string.intro_done))
            }
        }
    }
}

private const val PAGES = 3

@Composable
private fun RecordedPage() {
    Box(
        Modifier.size(72.dp).clip(RoundedCornerShape(18.dp)).background(colorResource(R.color.velowind_icon_background)),
        contentAlignment = Alignment.Center,
    ) {
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.requiredSize(108.dp))
    }
    Title(stringResource(R.string.intro_recorded_title))
    Body(stringResource(R.string.intro_recorded_text))
    Point(VelowindIcons.Map, stringResource(R.string.intro_recorded_gps))
    Point(VelowindIcons.Route, stringResource(R.string.intro_recorded_motion))
    Point(VelowindIcons.poll("noise/quiet"), stringResource(R.string.intro_recorded_sound))
    Point(VelowindIcons.Check, stringResource(R.string.intro_recorded_poll))
    Body(stringResource(R.string.intro_recorded_privacy))
}

@Composable
private fun SafetyPage() {
    Title(stringResource(R.string.intro_safety_title))
    Body(stringResource(R.string.intro_safety_mount))
    Point(VelowindIcons.Warning, stringResource(R.string.intro_safety_law))
    Point(VelowindIcons.poll("surface/bumpy"), stringResource(R.string.intro_safety_tap))
    Point(VelowindIcons.Stop, stringResource(R.string.intro_safety_stop))
    Point(VelowindIcons.Pin, stringResource(R.string.intro_safety_later))
}

@Composable
private fun BatteryPage(ui: UiState, actions: AppActions) {
    Title(stringResource(R.string.intro_battery_title))
    Body(stringResource(R.string.intro_battery_text))
    StatusChip(
        stringResource(if (ui.batteryUnrestricted) R.string.battery_unrestricted else R.string.battery_restricted),
        ui.batteryUnrestricted,
    )
    if (!ui.batteryUnrestricted) {
        OutlinedButton(onClick = actions.openBatterySettings) { Text(stringResource(R.string.battery_open_settings)) }
        Body(stringResource(R.string.intro_battery_how))
    }
}

@Composable
private fun Title(text: String) =
    Text(text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

@Composable
private fun Body(text: String) =
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Point(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp).size(22.dp))
        HGap(12.dp)
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
    }
}
