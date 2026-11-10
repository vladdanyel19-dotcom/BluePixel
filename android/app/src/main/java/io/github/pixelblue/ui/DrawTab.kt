package io.github.pixelblue.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.coerceIn
import io.github.pixelblue.MainViewModel
import io.github.pixelblue.R
import io.github.pixelblue.ble.DeviceState
import io.github.pixelblue.ble.Protocol

private val PALETTE = listOf(
    0xFFFF0000, 0xFFFF6000, 0xFFFFD000, 0xFF00FF00, 0xFF00FFC0, 0xFF00A0FF,
    0xFF0000FF, 0xFF8000FF, 0xFFFF00C0, 0xFFFFFFFF, 0xFF606060, 0xFF000000,
).map { it.toInt() }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DrawTab(vm: MainViewModel, s: DeviceState, modifier: Modifier) {
    val w = s.config.width.coerceAtLeast(1)
    val h = s.config.height.coerceAtLeast(1)
    vm.ensureCanvas(w, h)
    var brush by rememberSaveable { mutableIntStateOf(PALETTE[0]) }

    LaunchedEffect(Unit) {
        vm.syncCanvas()
        if (s.effect != 1) vm.ble.send(Protocol.effect(1))
    }

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.draw_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)

        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val cell: Dp = (maxWidth / w).coerceIn(10.dp, 40.dp)
            val gridW = cell * w
            val scroll = if (gridW > maxWidth) Modifier.horizontalScroll(rememberScrollState()) else Modifier
            val version = vm.canvasVersion   // read to recompose on changes

            Row(scroll) {
                Canvas(
                    Modifier.size(gridW, cell * h)
                        .pointerInput(w, h, brush) {
                            detectTapGestures { p -> paintAt(vm, p, size.width / w.toFloat(), w, h, brush) }
                        }
                        .pointerInput(w, h, brush) {
                            detectDragGestures(
                                onDragStart = { p -> paintAt(vm, p, size.width / w.toFloat(), w, h, brush) },
                            ) { change, _ -> paintAt(vm, change.position, size.width / w.toFloat(), w, h, brush) }
                        }
                ) {
                    if (version < 0) return@Canvas
                    val c = size.width / w
                    val gap = (c * 0.08f).coerceAtLeast(1f)
                    for (y in 0 until h) for (x in 0 until w) {
                        val argb = vm.canvas.getOrElse(y * w + x) { 0 }
                        val col = Color(argb)
                        drawRoundRect(
                            color = if ((argb and 0xFFFFFF) == 0) Color(0xFF22262E) else col,
                            topLeft = Offset(x * c + gap / 2, y * c + gap / 2),
                            size = Size(c - gap, c - gap),
                            cornerRadius = CornerRadius(c * 0.25f),
                        )
                    }
                }
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PALETTE.forEach { c -> Dot(Color(c), size = 34, selected = brush == c, onClick = { brush = c }) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { vm.fill(brush) }) {
                Icon(Icons.Default.FormatColorFill, null); Text("  " + stringResource(R.string.fill))
            }
            OutlinedButton(onClick = { vm.fill(0xFF000000.toInt()) }) {
                Icon(Icons.Default.LayersClear, null); Text("  " + stringResource(R.string.clear))
            }
        }
    }
}

private fun paintAt(vm: MainViewModel, p: Offset, cellPx: Float, w: Int, h: Int, argb: Int) {
    val x = (p.x / cellPx).toInt()
    val y = (p.y / cellPx).toInt()
    if (x in 0 until w && y in 0 until h) vm.paint(y * w + x, argb)
}
