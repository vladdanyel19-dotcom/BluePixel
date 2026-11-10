package io.github.pixelblue.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import io.github.pixelblue.MainViewModel
import io.github.pixelblue.R
import io.github.pixelblue.ble.DeviceState
import io.github.pixelblue.ble.Protocol

private data class Tab(val label: Int, val icon: ImageVector)

private val tabs = listOf(
    Tab(R.string.tab_color, Icons.Default.Palette),
    Tab(R.string.tab_draw, Icons.Default.Brush),
    Tab(R.string.tab_schedule, Icons.Default.Schedule),
    Tab(R.string.tab_setup, Icons.Default.Settings),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControllerScreen(vm: MainViewModel, s: DeviceState) {
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(vm.ble.connectedName, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            stringResource(R.string.connected_info, s.config.count),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    FilledIconToggleButton(
                        checked = s.power,
                        onCheckedChange = { vm.ble.send(Protocol.power(it)) },
                        colors = IconButtonDefaults.filledIconToggleButtonColors(
                            checkedContainerColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) { Icon(Icons.Default.PowerSettingsNew, stringResource(R.string.power)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i },
                        icon = { Icon(t.icon, null) }, label = { Text(stringResource(t.label)) },
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        val m = Modifier.fillMaxSize().padding(pad)
        when (tab) {
            0 -> ControlTab(vm, s, m)
            1 -> DrawTab(vm, s, m)
            2 -> ScheduleTab(vm, s, m)
            else -> SetupTab(vm, s, m)
        }
    }
}

/**
 * A value the user edits locally (slider) that also follows the device state,
 * without jumping back while the finger is still moving.
 */
class SyncedFloat(initial: Float) {
    var value by mutableFloatStateOf(initial)
    var touchedAt by mutableLongStateOf(0L)
    fun local(v: Float) { value = v; touchedAt = System.currentTimeMillis() }
}

@Composable
fun rememberSynced(remote: Int): SyncedFloat {
    val s = remember { SyncedFloat(remote.toFloat()) }
    LaunchedEffect(remote) {
        if (System.currentTimeMillis() - s.touchedAt > 1000) s.value = remote.toFloat()
    }
    return s
}
