package io.github.pixelblue.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.pixelblue.R
import io.github.pixelblue.ble.ConnState
import io.github.pixelblue.ble.FoundDevice
import io.github.pixelblue.ble.Kind
import io.github.pixelblue.ble.PixelBle

@Composable
private fun CenterMessage(icon: ImageVector, title: String, body: String, button: String, onClick: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(icon, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(20.dp))
            Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onClick) { Text(button) }
        }
    }
}

@Composable
fun PermissionScreen(onRequest: () -> Unit) = CenterMessage(
    Icons.Default.Bluetooth,
    stringResource(R.string.perm_title), stringResource(R.string.perm_body),
    stringResource(R.string.perm_button), onRequest,
)

@Composable
fun BluetoothOffScreen(onEnable: () -> Unit) = CenterMessage(
    Icons.Default.BluetoothDisabled,
    stringResource(R.string.bt_off_title), stringResource(R.string.bt_off_body),
    stringResource(R.string.bt_off_button), onEnable,
)

@Composable
fun DeviceListScreen(ble: PixelBle, conn: ConnState) {
    val devices by ble.devices.collectAsStateWithLifecycle()
    val scanning by ble.scanning.collectAsStateWithLifecycle()
    val notSupported by ble.notSupported.collectAsStateWithLifecycle()
    val known = devices.filter { it.kind != null }
    val others = devices.filter { it.kind == null }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp)) {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
            )
            Text(stringResource(R.string.devices_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(20.dp))

            if (conn == ConnState.Connecting) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.connecting, ble.connectedName))
                }
                Spacer(Modifier.height(16.dp))
            }
            notSupported?.let {
                Text(stringResource(R.string.not_supported, it), color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp))
            }

            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(known, key = { it.address }) { d -> DeviceRow(ble, d, conn) }
                if (others.isNotEmpty()) item {
                    Text(
                        stringResource(R.string.other_devices),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                items(others, key = { it.address }) { d -> DeviceRow(ble, d, conn) }
                if (known.isEmpty() && others.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                            Text(
                                if (scanning) stringResource(R.string.searching) else stringResource(R.string.none_found),
                                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }

            TextButton(onClick = { ble.startDemo() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.try_demo))
            }
            OutlinedButton(
                onClick = { ble.startScan() }, enabled = !scanning,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (scanning) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.searching_short))
                } else Text(stringResource(R.string.search_again))
            }
        }
    }
}

@Composable
private fun DeviceRow(ble: PixelBle, d: FoundDevice, conn: ConnState) {
        Card(
            Modifier.fillMaxWidth().clickable(enabled = conn != ConnState.Connecting) {
                ble.connect(d.address, d.name)
            },
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lightbulb, null, tint = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(d.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        (when (d.kind) { Kind.SP110E -> "SP110E · "; Kind.PIXELBLUE -> "PixelBlue · "; null -> "" }) +
                            (if (d.address == ble.lastAddress) stringResource(R.string.last_used) else d.address),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.Default.ChevronRight, null)
            }
        }
}
