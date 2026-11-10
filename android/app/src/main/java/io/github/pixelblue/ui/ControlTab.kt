package io.github.pixelblue.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.pixelblue.MainViewModel
import io.github.pixelblue.R
import io.github.pixelblue.ble.DeviceState
import io.github.pixelblue.ble.Protocol

val EFFECT_NAMES = listOf(
    R.string.fx_solid, R.string.fx_canvas, R.string.fx_rainbow, R.string.fx_rainbow_wave,
    R.string.fx_breathe, R.string.fx_wipe, R.string.fx_theater, R.string.fx_twinkle,
    R.string.fx_fire, R.string.fx_comet, R.string.fx_strobe, R.string.fx_police,
    R.string.fx_sparkle, R.string.fx_gradient, R.string.fx_matrix, R.string.fx_plasma,
    R.string.fx_text,
)
private const val FX_CANVAS = 1
private const val FX_TEXT = 16

private val SWATCHES = listOf(
    0xFF0000, 0xFF5000, 0xFFB000, 0x00FF00, 0x00FFB0, 0x00A0FF,
    0x0000FF, 0x8000FF, 0xFF00C0, 0xFFFFFF,
)

private fun hsvOf(r: Int, g: Int, b: Int): FloatArray {
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV(r, g, b, hsv)
    return hsv
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ControlTab(vm: MainViewModel, s: DeviceState, modifier: Modifier) {
    val ble = vm.ble
    var target by rememberSaveable { mutableIntStateOf(0) }   // 0 = main color, 1 = background color
    val remoteRgb = if (target == 0) Triple(s.r, s.g, s.b) else Triple(s.r2, s.g2, s.b2)

    var hue by remember { mutableFloatStateOf(0f) }
    var sat by remember { mutableFloatStateOf(1f) }
    var lastTouch by remember { mutableStateOf(0L) }
    LaunchedEffect(remoteRgb, target) {
        if (System.currentTimeMillis() - lastTouch > 1000) {
            val hsv = hsvOf(remoteRgb.first, remoteRgb.second, remoteRgb.third)
            hue = hsv[0]; sat = hsv[1]
        }
    }

    fun sendColor(rgb: Int) {
        val r = (rgb shr 16) and 0xFF; val g = (rgb shr 8) and 0xFF; val b = rgb and 0xFF
        if (target == 0) ble.send(Protocol.color(r, g, b, s.w), "color")
        else ble.send(Protocol.color2(r, g, b), "color2")
    }

    val bri = rememberSynced(s.brightness)
    val speed = rememberSynced(s.speed)
    val white = rememberSynced(s.w)
    var text by rememberSaveable { mutableStateOf("") }

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(target == 0, { target = 0 }, { Text(stringResource(R.string.color_main)) },
                leadingIcon = { Dot(Color(s.r, s.g, s.b)) })
            FilterChip(target == 1, { target = 1 }, { Text(stringResource(R.string.color_background)) },
                leadingIcon = { Dot(Color(s.r2, s.g2, s.b2)) })
        }

        ColorWheel(hue, sat, Modifier.fillMaxWidth(0.78f).align(Alignment.CenterHorizontally)) { h, sa ->
            hue = h; sat = sa; lastTouch = System.currentTimeMillis()
            sendColor(android.graphics.Color.HSVToColor(floatArrayOf(h, sa, 1f)) and 0xFFFFFF)
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SWATCHES.forEach { c ->
                Dot(Color(0xFF000000 or c.toLong()), size = 34, onClick = {
                    lastTouch = 0L; sendColor(c)
                })
            }
            Dot(Color.Black, size = 34, onClick = { lastTouch = 0L; sendColor(0) })
        }

        LabeledSlider(stringResource(R.string.brightness), bri.value) {
            bri.local(it); ble.send(Protocol.brightness(it.toInt()), "bri")
        }
        if (s.config.type == 2) {
            LabeledSlider(stringResource(R.string.white_channel), white.value) {
                white.local(it); ble.send(Protocol.color(s.r, s.g, s.b, it.toInt()), "color")
            }
        }

        SectionTitle(stringResource(R.string.effects))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            EFFECT_NAMES.forEachIndexed { id, name ->
                if (id == FX_CANVAS || id == FX_TEXT || id >= s.effectCount) return@forEachIndexed
                FilterChip(
                    selected = s.effect == id,
                    onClick = { ble.send(Protocol.effect(id)) },
                    label = { Text(stringResource(name)) },
                )
            }
        }
        LabeledSlider(stringResource(R.string.speed), speed.value) {
            speed.local(it); ble.send(Protocol.speed(it.toInt()), "speed")
        }

        SectionTitle(stringResource(R.string.scrolling_text))
        OutlinedTextField(
            value = text, onValueChange = { if (it.length <= 120) text = it },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text(stringResource(R.string.text_hint)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank()) ble.send(Protocol.text(text)) }),
            trailingIcon = {
                IconButton(onClick = { if (text.isNotBlank()) ble.send(Protocol.text(text)) }) {
                    Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.send))
                }
            },
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
fun SectionTitle(t: String) {
    Text(t, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 6.dp))
}

@Composable
fun LabeledSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            Text("${(value / 255f * 100).toInt()}%", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = value, onValueChange = onChange, valueRange = 0f..255f)
    }
}

@Composable
fun Dot(color: Color, size: Int = 14, selected: Boolean = false, onClick: (() -> Unit)? = null) {
    var m = Modifier.size(size.dp).background(color, CircleShape)
        .border(if (selected) 3.dp else 1.dp, if (selected) Color.White else Color.White.copy(alpha = 0.25f), CircleShape)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Spacer(m.width(size.dp))
}
