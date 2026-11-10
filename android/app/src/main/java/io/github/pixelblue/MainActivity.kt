package io.github.pixelblue

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.pixelblue.ble.ConnState
import io.github.pixelblue.ui.BluetoothOffScreen
import io.github.pixelblue.ui.ControllerScreen
import io.github.pixelblue.ui.DeviceListScreen
import io.github.pixelblue.ui.PermissionScreen
import io.github.pixelblue.ui.PixelBlueTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val permissions: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasPermissions() = permissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { PixelBlueTheme { App() } }
    }

    @Composable
    private fun App() {
        var granted by remember { mutableStateOf(hasPermissions()) }
        var btOn by remember { mutableStateOf(vm.ble.isBluetoothOn) }
        val lifecycle = LocalLifecycleOwner.current.lifecycle

        val permLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { granted = hasPermissions() }
        val enableLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { btOn = vm.ble.isBluetoothOn }

        // Re-check when coming back to the app (user may have toggled Bluetooth).
        LaunchedEffect(lifecycle) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                granted = hasPermissions()
                btOn = vm.ble.isBluetoothOn
            }
        }
        LaunchedEffect(Unit) { if (!granted) permLauncher.launch(permissions) }

        when {
            !granted -> PermissionScreen { permLauncher.launch(permissions) }
            !btOn -> BluetoothOffScreen {
                try { enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) } catch (_: Exception) {}
            }
            else -> {
                val conn by vm.ble.conn.collectAsStateWithLifecycle()
                val state by vm.ble.state.collectAsStateWithLifecycle()
                LaunchedEffect(conn) {
                    if (conn == ConnState.Disconnected) {
                        vm.onDisconnected()
                        vm.ble.startScan()
                    }
                }
                val s = state
                if (conn == ConnState.Connected && s != null) ControllerScreen(vm, s)
                else DeviceListScreen(vm.ble, conn)
            }
        }
    }
}
