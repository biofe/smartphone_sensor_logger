package io.github.biofe.sensorlogger

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** The bike's icon in a circle of its colour. */
@Composable
fun BikeBadge(type: String, color: Int?, size: Dp = 40.dp) {
    val c = LocalVelowind.current.bike(color)
    Box(Modifier.size(size).clip(CircleShape).background(c.color), contentAlignment = Alignment.Center) {
        Icon(VelowindIcons.bike(type), contentDescription = null, tint = c.on, modifier = Modifier.size(size * 0.6f))
    }
}

/** A titled card, as used on the main and settings screens. */
@Composable
fun SectionCard(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (title != null) {
                Text(
                    title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            content()
        }
    }
}

/** One ride: bike badge, date, distance · duration · size, and its upload state. */
@Composable
fun RideRow(ride: Rides.Info, onClick: () -> Unit) {
    val tint = LocalVelowind.current.bike(ride.color).tint
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(tint).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BikeBadge(ride.bikeType, ride.color)
        Column(Modifier.weight(1f)) {
            Text(
                ride.startedAt?.let { formatRideDate(it) } ?: ride.dir.name,
                style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
            )
            Text(
                rideFacts(ride), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        UploadState(ride)
    }
}

/** A dot plus a word, so the state never depends on colour alone. */
@Composable
fun UploadState(ride: Rides.Info) {
    val v = LocalVelowind.current
    val (label, color) = when {
        ride.recording -> stringResource(R.string.ride_recording) to MaterialTheme.colorScheme.primary
        ride.upload.state == Upload.State.UPLOADED -> stringResource(R.string.ride_uploaded) to v.ok
        ride.upload.state == Upload.State.REJECTED -> stringResource(R.string.ride_rejected) to MaterialTheme.colorScheme.error
        ride.upload.state == Upload.State.EMPTY -> stringResource(R.string.ride_too_short) to MaterialTheme.colorScheme.onSurfaceVariant
        else -> stringResource(R.string.ride_waiting) to v.warn
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
private fun rideFacts(ride: Rides.Info): String = listOfNotNull(
    ride.distanceM?.let { formatDistance(it) },
    ride.durationS?.let { formatDuration(it) },
    formatSize(ride.sizeBytes),
).joinToString(" · ")

/**
 * Stops the ride only after a press of [holdMs]; a short tap or bump does nothing. The fill shows
 * how long is left. Screen readers get a long-press action instead.
 */
@Composable
fun HoldToStopButton(onStop: () -> Unit, modifier: Modifier = Modifier, holdMs: Int = 1000) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val label = stringResource(R.string.hold_to_stop)
    val colors = MaterialTheme.colorScheme
    Box(
        modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(colors.errorContainer)
            .semantics {
                contentDescription = label
                onLongClick { onStop(); true }
            }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    val job = scope.launch {
                        progress.animateTo(1f, tween(holdMs, easing = LinearEasing))
                        onStop()
                    }
                    tryAwaitRelease()
                    if (progress.value < 1f) {
                        job.cancel()
                        scope.launch { progress.animateTo(0f, tween(150)) }
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(progress.value)
                .background(colors.error.copy(alpha = 0.35f)),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(VelowindIcons.Stop, contentDescription = null, tint = colors.onErrorContainer, modifier = Modifier.size(18.dp))
            Text(label, color = colors.onErrorContainer, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

/** A small rounded label with an icon: a sensor's state on the recording screen. */
@Composable
fun StatusChip(text: String, ok: Boolean, modifier: Modifier = Modifier) {
    val v = LocalVelowind.current
    val fg = if (ok) v.ok else v.warn
    Row(
        modifier.height(32.dp).clip(RoundedCornerShape(16.dp)).background(if (ok) v.okContainer else v.warnContainer)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
    ) {
        Icon(if (ok) VelowindIcons.Check else VelowindIcons.Warning, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
        Text(text, color = fg, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
fun Gap(height: Dp) = Spacer(Modifier.height(height))

@Composable
fun HGap(width: Dp) = Spacer(Modifier.width(width))

// --- Formatting -----------------------------------------------------------------------------

fun formatDistance(m: Double): String =
    if (m < 1000) String.format(Locale.getDefault(), "%.0f m", m) else String.format(Locale.getDefault(), "%.1f km", m / 1000)

fun formatDuration(s: Long): String =
    if (s < 3600) "${s / 60} min" else "${s / 3600} h ${(s % 3600) / 60} min"

fun formatSize(bytes: Long): String {
    val mb = bytes / 1_000_000.0
    return if (mb < 10) String.format(Locale.getDefault(), "%.1f MB", mb) else String.format(Locale.getDefault(), "%.0f MB", mb)
}

/** "Today, 16:27", "Yesterday, 08:05" or "Tue 27 Oct, 08:05", in the phone's time zone. */
@Composable
fun formatRideDate(date: Date): String {
    val clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
    val day = Calendar.getInstance().apply { time = date }
    val today = Calendar.getInstance()
    val daysAgo = ((startOfDay(today) - startOfDay(day)) / 86_400_000L).toInt()
    return when (daysAgo) {
        0 -> stringResource(R.string.date_today, clock)
        1 -> stringResource(R.string.date_yesterday, clock)
        else -> SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(date)
    }
}

private fun startOfDay(c: Calendar): Long = (c.clone() as Calendar).apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

@Composable
fun ridesCount(n: Int): String = pluralStringResource(R.plurals.rides, n, n)
