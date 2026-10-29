package io.github.biofe.sensorlogger

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Back arrow and title, shared by the screens below the main one. */
@Composable
fun ScreenTitle(title: String, subtitle: String? = null, onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(VelowindIcons.Back, contentDescription = stringResource(R.string.back))
        }
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing()
    }
}

@Composable
fun SettingsScreen(ui: UiState, actions: AppActions) {
    Column(Modifier.fillMaxSize()) {
        ScreenTitle(stringResource(R.string.settings), onBack = actions.back)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionCard(stringResource(R.string.upload)) {
                if (ui.consented) {
                    SwitchRow(
                        stringResource(R.string.upload_auto), ui.uploadLine, ui.autoUpload, actions.setAutoUpload,
                    )
                    OutlinedButton(onClick = actions.uploadNow, modifier = Modifier.padding(vertical = 4.dp)) {
                        Text(stringResource(R.string.upload_now))
                    }
                } else {
                    Text(stringResource(R.string.upload_off), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = actions.showWhatIsSent, modifier = Modifier.padding(vertical = 4.dp)) {
                        Text(stringResource(R.string.upload_turn_on))
                    }
                }
            }

            SectionCard(stringResource(R.string.my_bikes)) {
                ui.bikes.forEach { bike ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { actions.editBike(bike) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BikeBadge(bike.type, bike.color)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(bike.label, style = MaterialTheme.typography.bodyLarge)
                            Text(bike.type, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(
                            VelowindIcons.Edit, contentDescription = stringResource(R.string.edit_bike, bike.label),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp),
                        )
                    }
                }
                OutlinedButton(onClick = actions.addBike, modifier = Modifier.padding(top = 4.dp)) {
                    Icon(VelowindIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    HGap(6.dp)
                    Text(stringResource(R.string.add_bike))
                }
            }

            SectionCard(stringResource(R.string.annotating)) {
                SwitchRow(stringResource(R.string.detailed_poll), stringResource(R.string.detailed_poll_note), ui.detailedPoll, actions.setDetailedPoll)
            }

            SectionCard(stringResource(R.string.privacy_storage)) {
                LinkRow(stringResource(R.string.what_is_sent), onClick = actions.showWhatIsSent)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ValueRow(stringResource(R.string.phone_id), stringResource(R.string.phone_id_note), ui.installId, mono = true)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ValueRow(stringResource(R.string.storage), null, "${ridesCount(ui.rides.size)} · ${formatSize(ui.rides.sumOf { it.sizeBytes })}")
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                LinkRow(stringResource(R.string.export_all), stringResource(R.string.export_all_note), onClick = actions.exportAll)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                LinkRow(stringResource(R.string.delete_server_data), color = MaterialTheme.colorScheme.error, onClick = actions.deleteServerData)
            }

            SectionCard(stringResource(R.string.app)) {
                ValueRow(stringResource(R.string.version), if (ui.updaterEnabled) null else stringResource(R.string.test_build_note), ui.versionName)
                if (ui.updaterEnabled) {
                    OutlinedButton(onClick = actions.checkForUpdates, modifier = Modifier.padding(vertical = 4.dp)) {
                        Text(stringResource(R.string.check_updates))
                    }
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, note: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!note.isNullOrEmpty()) {
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ValueRow(title: String, note: String?, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = if (mono) FontFamily.Monospace else null,
        )
    }
}

@Composable
private fun LinkRow(title: String, note: String? = null, color: Color = Color.Unspecified, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title, style = MaterialTheme.typography.bodyLarge,
                color = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color,
            )
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(VelowindIcons.Next, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}
