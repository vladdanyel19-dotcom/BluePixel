package io.github.pixelblue

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import io.github.pixelblue.audio.MusicAnalyzer
import io.github.pixelblue.ble.DeviceState
import io.github.pixelblue.ble.Kind
import io.github.pixelblue.ble.PixelBle
import io.github.pixelblue.ble.Protocol
import io.github.pixelblue.scenes.Scene
import io.github.pixelblue.scenes.SceneStore

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val ble = PixelBle(app)
    val scenes = SceneStore(app)

    // ------------------------------------------------------------ drawing canvas
    // Local copy of the drawing (ARGB per logical pixel).
    var canvas: IntArray = IntArray(0)
        private set
    var canvasVersion by mutableIntStateOf(0)
        private set
    private var canvasUploaded = false

    /** Last text sent to the controller, so it can be kept in a scene. */
    var lastText: String = ""

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
        uploadCanvas()
    }

    private fun uploadCanvas() {
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

    fun setCanvas(pixels: IntArray) {
        if (pixels.size != canvas.size) return
        pixels.copyInto(canvas)
        canvasVersion++
        uploadCanvas()
    }

    /** Loads a picture, fits it into the matrix (keeping proportions) and sends it. */
    fun importImage(uri: Uri, width: Int, height: Int): Boolean {
        val ctx = getApplication<Application>()
        val src: Bitmap = try {
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) { null } ?: return false

        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.BLACK)
        val scale = minOf(width.toFloat() / src.width, height.toFloat() / src.height)
        val w = src.width * scale
        val h = src.height * scale
        val dst = RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        c.drawBitmap(src, null, dst, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        val px = IntArray(width * height)
        out.getPixels(px, 0, width, 0, 0, width, height)
        // Transparent areas become black.
        for (i in px.indices) {
            val a = (px[i] ushr 24) and 0xFF
            if (a < 255) {
                val r = ((px[i] shr 16) and 0xFF) * a / 255
                val g = ((px[i] shr 8) and 0xFF) * a / 255
                val b = (px[i] and 0xFF) * a / 255
                px[i] = Color.rgb(r, g, b)
            }
        }
        setCanvas(px)
        return true
    }

    fun onDisconnected() {
        canvasUploaded = false
        stopMusic()
    }

    // ------------------------------------------------------------ scenes
    fun saveScene(name: String, s: DeviceState) {
        val withCanvas = s.effect == Protocol.FX_CANVAS && s.kind == Kind.PIXELBLUE
        scenes.add(
            Scene(
                name = name, kind = s.kind, effect = s.effect, brightness = s.brightness, speed = s.speed,
                color = (s.r shl 16) or (s.g shl 8) or s.b, white = s.w,
                color2 = (s.r2 shl 16) or (s.g2 shl 8) or s.b2,
                flag = s.flag,
                text = if (s.effect == Protocol.FX_TEXT) lastText else "",
                width = s.config.width, height = s.config.height,
                canvas = if (withCanvas) canvas.copyOf() else null,
            )
        )
    }

    fun applyScene(sc: Scene, s: DeviceState) {
        stopMusic()
        val c = sc.color
        ble.send(Protocol.color((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF, sc.white))
        if (s.kind == Kind.PIXELBLUE) {
            val c2 = sc.color2
            ble.send(Protocol.color2((c2 shr 16) and 0xFF, (c2 shr 8) and 0xFF, c2 and 0xFF))
            ble.send(Protocol.flag(sc.flag))
        }
        ble.send(Protocol.brightness(sc.brightness))
        ble.send(Protocol.speed(sc.speed))
        val cv = sc.canvas
        when {
            sc.effect == Protocol.FX_CANVAS && cv != null && cv.size == canvas.size -> setCanvas(cv)
            sc.effect == Protocol.FX_TEXT && sc.text.isNotBlank() -> { lastText = sc.text; ble.send(Protocol.text(sc.text)) }
            else -> ble.send(Protocol.effect(sc.effect))
        }
        if (!s.power) ble.send(Protocol.power(true))
    }

    // ------------------------------------------------------------ music
    var musicOn by mutableStateOf(false)
        private set
    var musicLevel by mutableFloatStateOf(0f)
        private set
    var musicGain by mutableFloatStateOf(1f)
    private var analyzer: MusicAnalyzer? = null

    fun startMusic(bands: Int) {
        if (musicOn) return
        val a = MusicAnalyzer(bands.coerceIn(1, 64)) { values, level ->
            musicLevel = level
            ble.send(Protocol.music(values), "music")
        }
        a.gain = { musicGain }
        if (a.start()) {
            analyzer = a
            musicOn = true
        }
    }

    fun stopMusic() {
        analyzer?.stop()
        analyzer = null
        musicOn = false
        musicLevel = 0f
    }

    // ------------------------------------------------------------ firmware update
    var updateProgress by mutableFloatStateOf(-1f)   // -1 = idle
        private set
    var updateResult by mutableStateOf<Boolean?>(null)

    /** Version of the firmware bundled in the app, e.g. "0.2" (null if none). */
    val bundledFirmware: String? by lazy {
        try {
            getApplication<Application>().assets.open("fw/version.txt").bufferedReader().readText().trim()
        } catch (_: Exception) { null }
    }

    fun bundledFirmwareCode(): Int = bundledFirmware?.split(".")?.let {
        (it.getOrNull(0)?.toIntOrNull() ?: 0) * 1000 + (it.getOrNull(1)?.toIntOrNull() ?: 0)
    } ?: 0

    private fun imageFor(chip: String): ByteArray? {
        val file = when (chip) { "ESP32-C3" -> "esp32c3.bin"; "ESP32-S3" -> "esp32s3.bin"; "ESP32" -> "esp32.bin"; else -> return null }
        return try {
            getApplication<Application>().assets.open("fw/$file").use { it.readBytes() }
        } catch (_: Exception) { null }
    }

    fun hasImageFor(chip: String) = imageFor(chip) != null

    fun startUpdate(chip: String) {
        val img = imageFor(chip) ?: run { updateResult = false; return }
        stopMusic()
        updateResult = null
        updateProgress = 0f
        ble.updateFirmware(img, onProgress = { updateProgress = it }) { ok ->
            updateProgress = -1f
            updateResult = ok
        }
    }

    override fun onCleared() {
        stopMusic()
        ble.disconnect()
    }
}
