package io.github.pixelblue.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.pixelblue.MainViewModel
import io.github.pixelblue.R
import io.github.pixelblue.ble.DeviceState
import io.github.pixelblue.ble.HwConfig
import io.github.pixelblue.ble.Protocol
import io.github.pixelblue.ble.Sp110e

private val LED_TYPES = listOf(
    "WS2812B / WS2813 / WS2815", "WS2811", "SK6812 RGBW", "APA102", "SK9822",
)
private val ORDERS = listOf("RGB", "RBG", "GRB", "GBR", "BRG", "BGR")

@Composable
fun SetupTab(vm: MainViewModel, s: DeviceState, modifier: Modifier) {
    var cfg by remember(s.config) { mutableStateOf(s.config) }
    var widthText by remember(s.config) { mutableStateOf(s.config.width.toString()) }
    var heightText by remember(s.config) { mutableStateOf(s.config.height.toString()) }
    var dataText by remember(s.config) { mutableStateOf(s.config.dataPin.toString()) }
    var clockText by remember(s.config) { mutableStateOf(if (s.config.clockPin == 255) "" else s.config.clockPin.toString()) }
    var maText by remember(s.config) { mutableStateOf(s.config.maxmA.toString()) }
    var name by remember { mutableStateOf(vm.ble.connectedName) }
    var confirm by remember { mutableStateOf(false) }
    val isMatrix = (heightText.toIntOrNull() ?: 1) > 1

    fun built(): HwConfig? {
        val w = widthText.toIntOrNull() ?: return null
        val h = heightText.toIntOrNull() ?: return null
        val dp = dataText.toIntOrNull() ?: return null
        val cp = if (cfg.needsClock) clockText.toIntOrNull() ?: return null else 255
        val ma = maText.toIntOrNull() ?: 0
        if (w !in 1..2048 || h !in 1..2048 || w * h > 2048 || dp !in 0..48 || ma !in 0..60000) return null
        return cfg.copy(width = w, height = h, dataPin = dp, clockPin = cp, maxmA = ma)
    }
    val valid = built()

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionTitle(stringResource(R.string.strip_section))
        Picker(stringResource(R.string.led_type), LED_TYPES, cfg.type) { cfg = cfg.copy(type = it) }
        Picker(stringResource(R.string.color_order), ORDERS, cfg.order) { cfg = cfg.copy(order = it) }
        Text(stringResource(R.string.color_order_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NumberField(stringResource(R.string.data_pin), dataText, Modifier.weight(1f)) { dataText = it }
            if (cfg.needsClock) NumberField(stringResource(R.string.clock_pin), clockText, Modifier.weight(1f)) { clockText = it }
        }

        SectionTitle(stringResource(R.string.size_section))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            NumberField(stringResource(R.string.width), widthText, Modifier.weight(1f)) { widthText = it }
            NumberField(stringResource(R.string.height), heightText, Modifier.weight(1f)) { heightText = it }
        }
        Text(stringResource(R.string.size_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (isMatrix) {
            Toggle(stringResource(R.string.serpentine), cfg.layout and HwConfig.SERPENTINE != 0) { cfg = cfg.copy(layout = cfg.layout xor HwConfig.SERPENTINE) }
            Toggle(stringResource(R.string.vertical), cfg.layout and HwConfig.VERTICAL != 0) { cfg = cfg.copy(layout = cfg.layout xor HwConfig.VERTICAL) }
            Toggle(stringResource(R.string.start_right), cfg.layout and HwConfig.START_RIGHT != 0) { cfg = cfg.copy(layout = cfg.layout xor HwConfig.START_RIGHT) }
            Toggle(stringResource(R.string.start_bottom), cfg.layout and HwConfig.START_BOTTOM != 0) { cfg = cfg.copy(layout = cfg.layout xor HwConfig.START_BOTTOM) }
        }

        SectionTitle(stringResource(R.string.power_section))
        NumberField(stringResource(R.string.max_current), maText, Modifier.fillMaxWidth()) { maText = it }
        Text(stringResource(R.string.max_current_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        Button(onClick = { confirm = true }, enabled = valid != null, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.save_restart))
        }
        if (valid == null) Text(stringResource(R.string.invalid_values), color = MaterialTheme.colorScheme.error)

        SectionTitle(stringResource(R.string.device_section))
        OutlinedTextField(
            value = name, onValueChange = { if (it.toByteArray().size <= 20) name = it },
            label = { Text(stringResource(R.string.device_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = { if (name.isNotBlank()) { vm.ble.send(Protocol.name(name)); vm.ble.rememberName(name.trim()) } },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.rename)) }

        Text(
            stringResource(R.string.device_info, s.chip, s.firmware),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = { vm.ble.disconnect(forget = true) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.other_device))
        }
        Spacer(Modifier.height(16.dp))
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.save_restart)) },
            text = { Text(stringResource(R.string.restart_note)) },
            confirmButton = {
                TextButton({ confirm = false; valid?.let { vm.ble.send(Protocol.config(it)) } }) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = { TextButton({ confirm = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun Picker(label: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(options.getOrElse(selected) { options[0] }, Modifier.weight(1f))
                Icon(Icons.Default.ArrowDropDown, null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEachIndexed { i, o ->
                    DropdownMenuItem(text = { Text(o) }, onClick = { onSelect(i); open = false })
                }
            }
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, onValueChange = { v -> if (v.length <= 5 && v.all { it.isDigit() }) onChange(v) },
        label = { Text(label) }, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
private fun Toggle(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}

/** Settings page for an original SP110E controller. */
@Composable
fun SpSetupTab(vm: MainViewModel, s: DeviceState, modifier: Modifier) {
    var ic by remember(s.config.type) { mutableStateOf(s.config.type) }
    var order by remember(s.config.order) { mutableStateOf(s.config.order) }
    var countText by remember(s.config.width) { mutableStateOf(s.config.width.toString()) }
    val count = countText.toIntOrNull()?.takeIf { it in 1..1024 }

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.sp_intro), color = MaterialTheme.colorScheme.onSurfaceVariant)
        SectionTitle(stringResource(R.string.strip_section))
        Picker(stringResource(R.string.led_type), Sp110e.IC_MODELS, ic) { ic = it }
        Picker(stringResource(R.string.color_order), ORDERS, order) { order = it }
        Text(stringResource(R.string.color_order_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        NumberField(stringResource(R.string.led_count), countText, Modifier.fillMaxWidth()) { countText = it }
        Button(
            onClick = {
                vm.ble.send(Protocol.spIcModel(ic))
                vm.ble.send(Protocol.spOrder(order))
                count?.let { vm.ble.send(Protocol.spCount(it)) }
            },
            enabled = count != null, modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.save_to_device)) }
        if (count == null) Text(stringResource(R.string.sp_count_range), color = MaterialTheme.colorScheme.error)

        OutlinedButton(onClick = { vm.ble.disconnect(forget = true) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.other_device))
        }
        Spacer(Modifier.height(16.dp))
    }
}
