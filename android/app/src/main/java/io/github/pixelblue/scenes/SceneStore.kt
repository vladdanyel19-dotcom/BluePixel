package io.github.pixelblue.scenes

import android.content.Context
import android.util.Base64
import io.github.pixelblue.ble.Kind
import io.github.pixelblue.ble.NightMode
import io.github.pixelblue.ble.Protocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.util.UUID

/** A saved look: effect, colors, brightness and (for drawings) the pixels. */
data class Scene(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val kind: Kind = Kind.PIXELBLUE,
    val effect: Int,
    val brightness: Int,
    val speed: Int = 128,
    val color: Int,
    val white: Int = 0,
    val color2: Int = 0,
    val flag: List<Int> = NightMode.RO_FLAG,
    val text: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val canvas: IntArray? = null,
    val builtIn: Boolean = false,
)

/** Scenes kept on the phone (SharedPreferences, JSON). */
class SceneStore(context: Context) {
    private val prefs = context.getSharedPreferences("scenes", Context.MODE_PRIVATE)
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<Scene>> = _items.asStateFlow()

    fun add(s: Scene) {
        _items.value = _items.value + s
        save()
    }

    fun delete(id: String) {
        _items.value = _items.value.filter { it.id != id }
        save()
    }

    private fun save() {
        val arr = JSONArray()
        _items.value.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id); put("name", s.name); put("kind", s.kind.name)
                put("effect", s.effect); put("brightness", s.brightness); put("speed", s.speed)
                put("color", s.color); put("white", s.white); put("color2", s.color2)
                put("flag", JSONArray(s.flag)); put("text", s.text)
                put("w", s.width); put("h", s.height)
                s.canvas?.let { cv ->
                    val bb = ByteBuffer.allocate(cv.size * 4)
                    cv.forEach { bb.putInt(it) }
                    put("canvas", Base64.encodeToString(bb.array(), Base64.NO_WRAP))
                }
            })
        }
        prefs.edit().putString("list", arr.toString()).apply()
    }

    private fun load(): List<Scene> {
        val raw = prefs.getString("list", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val fl = o.optJSONArray("flag")
                Scene(
                    id = o.getString("id"), name = o.getString("name"),
                    kind = runCatching { Kind.valueOf(o.optString("kind", "PIXELBLUE")) }.getOrDefault(Kind.PIXELBLUE),
                    effect = o.getInt("effect"), brightness = o.getInt("brightness"), speed = o.optInt("speed", 128),
                    color = o.getInt("color"), white = o.optInt("white"), color2 = o.optInt("color2"),
                    flag = if (fl != null && fl.length() == 3) (0 until 3).map { fl.getInt(it) } else NightMode.RO_FLAG,
                    text = o.optString("text", ""),
                    width = o.optInt("w"), height = o.optInt("h"),
                    canvas = o.optString("canvas", "").takeIf { it.isNotEmpty() }?.let {
                        val bb = ByteBuffer.wrap(Base64.decode(it, Base64.NO_WRAP))
                        IntArray(bb.remaining() / 4) { bb.int }
                    },
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        /** Ready-made scenes shown for every PixelBlue controller. */
        fun builtIns(): List<Scene> = listOf(
            Scene("b-flag", "flag", effect = Protocol.FX_FLAG, brightness = 140, speed = 110, color = 0xFFFFFF, builtIn = true),
            Scene("b-rainbow", "rainbow", effect = 3, brightness = 140, speed = 128, color = 0xFF5000, builtIn = true),
            Scene("b-fire", "fire", effect = 8, brightness = 160, speed = 150, color = 0xFF5000, builtIn = true),
            Scene("b-night", "night", effect = 0, brightness = 25, color = 0xFF7A20, builtIn = true),
            Scene("b-white", "white", effect = 0, brightness = 180, color = 0xFFFFFF, builtIn = true),
        )

        fun spBuiltIns(): List<Scene> = listOf(
            Scene("s-night", "night", kind = Kind.SP110E, effect = 121, brightness = 25, color = 0xFF7A20, builtIn = true),
            Scene("s-white", "white", kind = Kind.SP110E, effect = 121, brightness = 180, color = 0xFFFFFF, builtIn = true),
        )
    }
}
