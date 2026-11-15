package io.github.pixelblue.ui

import android.Manifest
import android.app.Activity
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.pixelblue.Lang
import io.github.pixelblue.MainViewModel
import io.github.pixelblue.R
import io.github.pixelblue.ble.DeviceState
import io.github.pixelblue.ble.Kind
import io.github.pixelblue.ble.NightMode
import io.github.pixelblue.ble.Protocol
import io.github.pixelblue.scenes.Scene
import io.github.pixelblue.scenes.SceneStore

private fun rgb(c: Int) = Color(0xFF000000.toInt() or c)
private fun fmtTime(min: Int) = "%02d:%02d".format(min / 60, min % 60)

private val PICK_COLORS = listOf(
    0xFF0000, 0xFF5000, 0xFFB000, 0xFFD200, 0x00FF00, 0x009246, 0x00FFB0, 0x00A0FF,
    0x0055A4, 0x0000FF, 0x8000FF, 0xFF00C0, 0xFFFFFF, 0x000000,
)

private data class FlagPreset(val name: Int, val colors: List<Int>)

private val FLAGS = listOf(
    FlagPreset(R.string.flag_ro, NightMode.RO_FLAG),
    FlagPreset(R.string.flag_md, listOf(0x0046AE, 0xFFD200, 0xCC092F)),
    FlagPreset(R.string.flag_it, listOf(0x009246, 0xFFFFFF, 0xCE2B37)),
    FlagPreset(R.string.flag_fr, listOf(0x0055A4, 0xFFFFFF, 0xEF4135)),
    FlagPreset(R.string.flag_ie, listOf(0x169B62, 0xFFFFFF, 0xFF883E)),
    FlagPreset(R.string.flag_be, listOf(0x000000, 0xFDDA24, 0xEF3340)),
)

// ------------------------------------------------------------------ flag colors
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FlagColors(vm: MainViewModel, s: DeviceState) {
    var editing by remember { mutableIntStateOf(-1) }
    SectionTitle(stringResource(R.string.flag_colors))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        s.flag.forEachIndexed { i, c ->
            Box(
                Modifier.size(56.dp, 36.dp)
                    .background(rgb(c), RoundedCornerShape(8.dp))
                    .clickable { editing = i },
            )
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FLAGS.forEach { f ->
            FilterChip(
                selected = s.flag == f.colors,
                onClick = { vm.ble.send(Protocol.flag(f.colors)) },
                label = { Text(stringResource(f.name)) },
            )
        }
    }
    if (editing >= 0) {
        AlertDialog(
            onDismissRequest = { editing = -1 },
            title = { Text(stringResource(R.string.pick_color)) },
            text = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PICK_COLORS.forEach { c ->
                        Dot(rgb(c), size = 38, onClick = {
                            val list = s.flag.toMutableList().also { it[editing] = c }
                            vm.ble.send(Protocol.flag(list))
                            editing = -1
                        })
                    }
                }
            },
            confirmButton = { TextButton({ editing = -1 }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

// ------------------------------------------------------------------ music
@Composable
fun MusicSection(vm: MainViewModel, s: DeviceState) {
    val ctx = LocalContext.current
    var denied by remember { mutableStateOf(false) }
    val bands = if (s.config.height > 1) s.config.width.coerceIn(1, 64) else 16
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        denied = !ok
        if (ok) vm.startMusic(bands)
    }

    SectionTitle(stringResource(R.string.music))
    Text(stringResource(R.string.music_hint), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (vm.musicOn) {
        LinearProgressIndicator(progress = { vm.musicLevel }, modifier = Modifier.fillMaxWidth())
        Column {
            Text(stringResource(R.string.sensitivity))
            Slider(value = vm.musicGain, onValueChange = { vm.musicGain = it }, valueRange = 0.4f..3f)
        }
        OutlinedButton(onClick = { vm.stopMusic() }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Stop, null); Text("  " + stringResource(R.string.music_stop))
        }
    } else {
        Button(
            onClick = {
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                    vm.startMusic(bands)
                else launcher.launch(Manifest.permission.RECORD_AUDIO)
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Icon(Icons.Default.Mic, null); Text("  " + stringResource(R.string.music_start)) }
        if (denied) Text(stringResource(R.string.mic_denied), color = MaterialTheme.colorScheme.error)
    }
}

// ------------------------------------------------------------------ scenes
@Composable
private fun sceneName(sc: Scene): String = if (!sc.builtIn) sc.name else when (sc.name) {
    "flag" -> stringResource(R.string.scene_flag)
    "rainbow" -> stringResource(R.string.scene_rainbow)
    "fire" -> stringResource(R.string.scene_fire)
    "night" -> stringResource(R.string.scene_night)
    "white" -> stringResource(R.string.scene_white)
    else -> sc.name
}

private fun scenePreview(sc: Scene): Brush = when {
    sc.effect == Protocol.FX_FLAG -> Brush.horizontalGradient(sc.flag.flatMap { listOf(rgb(it), rgb(it)) })
    sc.effect in listOf(2, 3, 15) -> Brush.horizontalGradient(listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta))
    sc.effect == 8 -> Brush.verticalGradient(listOf(Color(0xFFFFD000), Color(0xFFFF3000), Color(0xFF400000)))
    sc.effect == Protocol.FX_CANVAS && sc.canvas != null -> {
        val cv = sc.canvas!!
        val cols = (0 until 6).map { k -> cv[(k * cv.size / 6).coerceIn(0, cv.size - 1)] }
        Brush.horizontalGradient(cols.map { Color(it) })
    }
    else -> Brush.horizontalGradient(listOf(rgb(sc.color), rgb(sc.color)))
}

@Composable
fun ScenesTab(vm: MainViewModel, s: DeviceState, modifier: Modifier) {
    val saved by vm.scenes.items.collectAsStateWithLifecycle()
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }

    val builtIns = if (s.kind == Kind.SP110E) SceneStore.spBuiltIns() else SceneStore.builtIns()
    val mine = saved.filter { it.kind == s.kind }

    Column(
        modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.scenes_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { name = ""; naming = true }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.save_scene))
        }

        if (mine.isNotEmpty()) SectionTitle(stringResource(R.string.my_scenes))
        mine.forEach { sc -> SceneRow(vm, s, sc, onDelete = { vm.scenes.delete(sc.id) }) }

        SectionTitle(stringResource(R.string.ready_scenes))
        builtIns.forEach { sc -> SceneRow(vm, s, sc, onDelete = null) }
        Spacer(Modifier.height(16.dp))
    }

    if (naming) {
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text(stringResource(R.string.save_scene)) },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { if (it.length <= 30) name = it },
                    singleLine = true, label = { Text(stringResource(R.string.scene_name)) },
                )
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { vm.saveScene(name.trim(), s); naming = false }) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = { TextButton({ naming = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun SceneRow(vm: MainViewModel, s: DeviceState, sc: Scene, onDelete: (() -> Unit)?) {
    val fits = sc.canvas == null || (sc.width == s.config.width && sc.height == s.config.height)
    Card(
        Modifier.fillMaxWidth().clickable(enabled = fits) { vm.applyScene(sc, s) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(54.dp, 30.dp).background(scenePreview(sc), RoundedCornerShape(6.dp)))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(sceneName(sc), fontWeight = FontWeight.SemiBold)
                Text(
                    if (fits) "${(sc.brightness * 100 / 255)}%" else stringResource(R.string.scene_other_size),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onDelete != null) IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
        }
    }
}

// ------------------------------------------------------------------ night dimming
@Composable
fun NightSection(vm: MainViewModel, s: DeviceState) {
    val ctx = LocalContext.current
    var n by remember(s.night) { mutableStateOf(s.night) }
    var saved by remember(s.night) { mutableStateOf(true) }
    fun upd(x: NightMode) { n = x; saved = false }
    fun pick(cur: Int, set: (Int) -> Unit) {
        TimePickerDialog(ctx, { _, h, m -> set(h * 60 + m) }, cur / 60, cur % 60, true).show()
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.night_title), fontWeight = FontWeight.SemiBold)
                    if (s.nightActive) Text(stringResource(R.string.night_active), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary)
                }
                Switch(n.enabled, { upd(n.copy(enabled = it)) })
            }
            Text(stringResource(R.string.night_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.from), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton({ pick(n.start) { upd(n.copy(start = it)) } }) { Text(fmtTime(n.start)) }
                }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.until), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton({ pick(n.end) { upd(n.copy(end = it)) } }) { Text(fmtTime(n.end)) }
                }
            }
            LabeledSlider(stringResource(R.string.night_level), n.level.toFloat()) { upd(n.copy(level = it.toInt())) }
            Button(
                onClick = { vm.ble.send(Protocol.night(n)); saved = true },
                enabled = !saved, modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(if (saved) R.string.saved_on_device else R.string.save_to_device)) }
        }
    }
}

// ------------------------------------------------------------------ language
@Composable
fun LanguageRow() {
    val ctx = LocalContext.current
    val current = Lang.get(ctx)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.language), Modifier.weight(1f))
        listOf("" to R.string.lang_auto, "ro" to R.string.lang_ro, "en" to R.string.lang_en).forEach { (code, label) ->
            FilterChip(
                selected = current == code,
                onClick = { (ctx as? Activity)?.let { Lang.set(it, code) } },
                label = { Text(stringResource(label)) },
            )
        }
    }
}

// ------------------------------------------------------------------ firmware update
@Composable
fun UpdateSection(vm: MainViewModel, s: DeviceState) {
    val bundled = vm.bundledFirmware ?: return
    if (vm.ble.isDemo) return
    SectionTitle(stringResource(R.string.firmware))
    when {
        !vm.ble.canUpdate -> Text(stringResource(R.string.fw_needs_cable, bundled),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        vm.bundledFirmwareCode() > s.firmwareCode && vm.hasImageFor(s.chip) -> {
            Text(stringResource(R.string.fw_available, s.firmware, bundled))
            Button(onClick = { vm.startUpdate(s.chip) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.fw_update))
            }
        }
        else -> Text(stringResource(R.string.fw_up_to_date, s.firmware),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun UpdateDialog(vm: MainViewModel) {
    val p = vm.updateProgress
    if (p >= 0f) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.fw_updating)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    Text("${(p * 100).toInt()}% · " + stringResource(R.string.fw_keep_close))
                }
            },
            confirmButton = {},
        )
    }
    vm.updateResult?.let { ok ->
        AlertDialog(
            onDismissRequest = { vm.updateResult = null },
            title = { Text(stringResource(if (ok) R.string.fw_done else R.string.fw_failed)) },
            text = { Text(stringResource(if (ok) R.string.fw_done_body else R.string.fw_failed_body)) },
            confirmButton = { TextButton({ vm.updateResult = null }) { Text(stringResource(R.string.ok)) } },
        )
    }
}
