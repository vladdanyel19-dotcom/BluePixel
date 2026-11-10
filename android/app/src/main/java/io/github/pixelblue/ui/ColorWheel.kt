package io.github.pixelblue.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** Hue/saturation wheel. [hue] 0..360, [sat] 0..1. */
@Composable
fun ColorWheel(hue: Float, sat: Float, modifier: Modifier = Modifier, onChange: (hue: Float, sat: Float) -> Unit) {
    fun pick(p: Offset, w: Float, h: Float) {
        val c = Offset(w / 2, h / 2)
        val r = min(w, h) / 2
        val dx = p.x - c.x
        val dy = p.y - c.y
        var angle = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
        if (angle < 0) angle += 360f
        onChange(angle, (hypot(dx, dy) / r).coerceIn(0f, 1f))
    }

    Canvas(
        modifier
            .aspectRatio(1f)
            .pointerInput(Unit) { detectTapGestures { pick(it, size.width.toFloat(), size.height.toFloat()) } }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { pick(it, size.width.toFloat(), size.height.toFloat()) },
                ) { change, _ -> pick(change.position, size.width.toFloat(), size.height.toFloat()) }
            }
    ) {
        val r = size.minDimension / 2
        val hues = List(13) { Color.hsv((it * 30f) % 360f, 1f, 1f) }
        drawCircle(Brush.sweepGradient(hues, center), r, center)
        drawCircle(Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = 0f)), center, r), r, center)

        val a = Math.toRadians(hue.toDouble())
        val p = Offset(center.x + (cos(a) * sat * r).toFloat(), center.y + (sin(a) * sat * r).toFloat())
        drawCircle(Color.hsv(hue, sat, 1f), 14.dp2px(), p)
        drawCircle(Color.White, 14.dp2px(), p, style = Stroke(3.dp2px()))
        drawCircle(Color.Black.copy(alpha = 0.4f), 15.5f.dp2px(), p, style = Stroke(1.dp2px()))
    }
}

private fun Number.dp2px(): Float = this.toFloat() * android.content.res.Resources.getSystem().displayMetrics.density
