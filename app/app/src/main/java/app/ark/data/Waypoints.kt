package app.ark.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 航点类型：图标用一个字 + 颜色区分（地图上用 Canvas 画，不依赖图片资源）。 */
enum class WpType(val label: String, val glyph: String, val color: Int) {
    WATER("水源", "水", 0xFF1E88E5.toInt()),
    CAMP("营地", "营", 0xFF43A047.toInt()),
    DANGER("危险", "险", 0xFFE53935.toInt()),
    VEHICLE("车辆", "车", 0xFF8E24AA.toInt()),
    OTHER("其他", "标", 0xFF757575.toInt());

    companion object { fun of(s: String?) = entries.firstOrNull { it.name == s } ?: OTHER }
}

data class Waypoint(val id: String, val name: String, val type: WpType, val lat: Double, val lon: Double, val time: Long)

/** 航点：存在 app 内部文件 filesDir/waypoints.json，随时可增删改；不联网。 */
object WaypointStore {
    private var file: File? = null
    private val _list = MutableStateFlow<List<Waypoint>>(emptyList())
    val list: StateFlow<List<Waypoint>> = _list

    @Synchronized fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.applicationContext.filesDir, "waypoints.json")
        file = f
        if (!f.exists()) return
        runCatching {
            val a = JSONArray(f.readText())
            _list.value = (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Waypoint(o.getString("id"), o.getString("name"), WpType.of(o.optString("type")), o.getDouble("lat"), o.getDouble("lon"), o.optLong("time"))
            }
        }
    }

    @Synchronized fun add(name: String, type: WpType, lat: Double, lon: Double): Waypoint {
        val w = Waypoint(UUID.randomUUID().toString().take(8), name.ifBlank { type.label }, type, lat, lon, System.currentTimeMillis())
        save(_list.value + w)
        return w
    }

    @Synchronized fun update(id: String, name: String, type: WpType) =
        save(_list.value.map { if (it.id == id) it.copy(name = name.ifBlank { type.label }, type = type) else it })

    @Synchronized fun delete(id: String) = save(_list.value.filter { it.id != id })

    private fun save(l: List<Waypoint>) {
        _list.value = l
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("type", it.type.name).put("lat", it.lat).put("lon", it.lon).put("time", it.time)) }
        file?.let { f -> runCatching { File(f.path + ".tmp").also { t -> t.writeText(a.toString()); f.delete(); t.renameTo(f) } } }
    }
}
