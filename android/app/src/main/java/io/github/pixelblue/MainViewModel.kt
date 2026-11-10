package io.github.pixelblue

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import io.github.pixelblue.ble.PixelBle
import io.github.pixelblue.ble.Protocol

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val ble = PixelBle(app)

    // Local copy of the drawing canvas (ARGB per logical pixel).
    var canvas: IntArray = IntArray(0)
        private set
    var canvasVersion by mutableIntStateOf(0)
        private set
    private var canvasUploaded = false

    fun ensureCanvas(width: Int, height: Int) {
        val n = width * height
        if (canvas.size != n) {
            canvas = IntArray(n) { 0xFF000000.toInt() }
            canvasUploaded = false
            canvasVersion++
        }
    }

    /** Sends the whole local canvas once per connection, so device and app match. */
    fun syncCanvas() {
        if (canvasUploaded || canvas.isEmpty()) return
        canvasUploaded = true
        val rgb = ByteArray(canvas.size * 3)
        canvas.forEachIndexed { i, c ->
            rgb[i * 3] = (c shr 16).toByte(); rgb[i * 3 + 1] = (c shr 8).toByte(); rgb[i * 3 + 2] = c.toByte()
        }
        ble.sendPixels(0, rgb)
    }

    fun paint(index: Int, argb: Int) {
        if (index !in canvas.indices || canvas[index] == argb) return
        canvas[index] = argb
        canvasVersion++
        ble.sendPixels(index, byteArrayOf((argb shr 16).toByte(), (argb shr 8).toByte(), argb.toByte()))
    }

    fun fill(argb: Int) {
        canvas.fill(argb)
        canvasVersion++
        ble.send(Protocol.clear((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF))
    }

    fun onDisconnected() { canvasUploaded = false }

    override fun onCleared() {
        ble.disconnect()
    }
}
