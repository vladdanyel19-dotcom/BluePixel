package io.github.pixelblue.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.pixelblue.MainViewModel
import io.github.pixelblue.R
import io.github.pixelblue.ble.DeviceState
import io.github.pixelblue.ble.Protocol
import io.github.pixelblue.ble.ScheduleSlot

private fun fmt(min: Int) = "%02d:%02d".format(min / 60, min % 60)

/** Suggested windows for a fresh controller: early morning and evening. */
private val DEFAULTS = listOf(
    ScheduleSlot(5 * 60, 8 * 60, 0x7F, false),
    ScheduleSlot(16 * 60, 20 * 60, 0x7F, false),
    ScheduleSlot(18 * 60, 23 * 60, 0x7F, false),
    ScheduleSlot(0, 0, 0x7F, false),
)

@Composable
fun ScheduleTab(vm: MainViewModel, s: DeviceState, modifier: Modifier) {
    val context = LocalContext.current
    val initial = remember(s.schedule) {
        if (s.schedule.all { !it.enabled && it.start == 0 && it.end == 0 }) DEFAULTS else s.schedule
    }
    var slots by remember(initial) { mutableStateOf(initial) }
    var saved by remember(initial) { mutableStateOf(true) }
    val days = stringResource(R.string.days_short).split(",")

    fun update(i: Int, f: (ScheduleSlot) -> ScheduleSlot) {
        slots = slots.toMutableList().also { it[i] = f(it[i]) }
        saved = false
    }

    fun pickTime(current: Int, onPicked: (Int) -> Unit) {
        TimePickerDialog(context, { _, hh, mm -> onPicked(hh * 60 + mm) }, current / 60, current % 60, true).show()
    }

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.schedule_intro), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!s.timeValid) Text(stringResource(R.string.time_unknown), color = MaterialTheme.colorScheme.secondary)

        slots.forEachIndexed { i, slot ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.program_n, i + 1), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Switch(slot.enabled, { on -> update(i) { it.copy(enabled = on) } })
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.turn_on_at), style = MaterialTheme.typography.bodySmall)
                            OutlinedButton({ pickTime(slot.start) { m -> update(i) { it.copy(start = m) } } }) {
                                Text(fmt(slot.start), style = MaterialTheme.typography.titleMedium)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.turn_off_at), style = MaterialTheme.typography.bodySmall)
                            OutlinedButton({ pickTime(slot.end) { m -> update(i) { it.copy(end = m) } } }) {
                                Text(fmt(slot.end), style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        days.forEachIndexed { d, label ->
                            val on = slot.days and (1 shl d) != 0
                            Surface(
                                onClick = { update(i) { it.copy(days = it.days xor (1 shl d)) } },
                                shape = CircleShape,
                                color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                modifier = Modifier.size(36.dp),
                            ) {
                                Text(label.trim(), Modifier.padding(top = 8.dp).fillMaxWidth(),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
        }

        Button(
            onClick = { vm.ble.send(Protocol.schedule(slots)); saved = true },
            enabled = !saved, modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(if (saved) R.string.saved_on_device else R.string.save_to_device)) }
        Spacer(Modifier.height(12.dp))
    }
}
