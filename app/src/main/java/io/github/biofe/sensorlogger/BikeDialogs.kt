package io.github.biofe.sensorlogger

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/**
 * Adds a bike ([bike] null) or edits one: type, an optional name that stays on the phone, and a
 * colour. [onRemove] is null when the bike can't be removed (the last one).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BikeEditor(bike: Bikes.Bike?, newColor: Int, onSave: (Bikes.Bike) -> Unit, onRemove: (() -> Unit)?, onDismiss: () -> Unit) {
    var type by remember { mutableStateOf(bike?.type ?: Bikes.TYPES.first()) }
    var name by remember { mutableStateOf(bike?.name ?: "") }
    var color by remember { mutableStateOf(bike?.color ?: newColor) }
    val palette = LocalVelowind.current.bikes

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (bike == null) R.string.add_bike else R.string.edit_bike_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text(stringResource(R.string.bike_name)) },
                    supportingText = { Text(stringResource(R.string.bike_name_note)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.bike_type), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Bikes.TYPES.forEach { t ->
                        FilterChip(selected = t == type, onClick = { type = t }, label = { Text(t) })
                    }
                }
                Text(stringResource(R.string.bike_colour), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    palette.forEachIndexed { i, c ->
                        val label = stringResource(R.string.colour_n, i + 1)
                        Box(
                            Modifier.size(44.dp).clip(CircleShape)
                                .border(BorderStroke(3.dp, if (i == color) MaterialTheme.colorScheme.onSurface else c.color), CircleShape)
                                .padding(5.dp).clip(CircleShape).background(c.color)
                                .selectable(selected = i == color, role = Role.RadioButton) { color = i }
                                .semantics { contentDescription = label },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (i == color) Icon(VelowindIcons.Check, contentDescription = null, tint = c.on, modifier = Modifier.size(20.dp))
                        }
                    }
                }
                if (bike != null && onRemove != null) {
                    TextButton(onClick = onRemove) {
                        Text(stringResource(R.string.remove_bike), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val trimmed = name.trim()
                onSave(bike?.copy(type = type, name = trimmed, color = color) ?: Bikes.Bike(type, trimmed, color))
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** First launch: pick every type of bike you ride; each becomes a bike with its own colour. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FirstBikesDialog(onDone: (List<String>) -> Unit) {
    val picked = remember { mutableStateListOf<String>() }
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.which_bikes)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.which_bikes_note), style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Bikes.TYPES.forEach { t ->
                        FilterChip(
                            selected = t in picked,
                            onClick = { if (t in picked) picked.remove(t) else picked.add(t) },
                            label = { Text(t) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDone(Bikes.TYPES.filter { it in picked }.ifEmpty { listOf("other") }) }) { Text(stringResource(R.string.ok)) }
        },
    )
}
