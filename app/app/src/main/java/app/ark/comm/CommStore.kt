package app.ark.comm

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * 消息存储：app 内部 JSON 文件 filesDir/comm_msgs.json（重启后还在），最多 [CAP] 条，最旧的先丢。
 * visible=false 的是“只帮别人转发”的消息：留着用来去重和给后来的邻居补发，不在聊天界面显示。
 */
object CommStore {
    const val CAP = 400
    const val ST_PENDING = 0
    const val ST_SENT = 1
    const val ST_FAILED = 2

    class Stored(
        val msg: CommProto.Msg, val mine: Boolean, val visible: Boolean, val status: Int, val delivered: Int,
        val recvTime: Long, val rssi: Int,
    )

    private val _list = MutableStateFlow<List<Stored>>(emptyList())
    val list: StateFlow<List<Stored>> = _list
    private val keys = HashSet<String>()
    private var file: File? = null
    private val io = Executors.newSingleThreadExecutor { Thread(it, "comm-store").apply { isDaemon = true } }
    private var loaded = false

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val f = File(ctx.applicationContext.filesDir, "comm_msgs.json"); file = f
        val out = ArrayList<Stored>()
        runCatching {
            if (f.exists()) {
                val a = JSONArray(f.readText())
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val m = CommProto.Msg(
                        o.getInt("id"), o.getInt("src"), o.getInt("dst"), o.getInt("ttl"), o.getLong("time"), o.optString("nick"), o.optString("text"),
                        if (o.has("lat")) o.getDouble("lat") else null, if (o.has("lon")) o.getDouble("lon") else null,
                    )
                    out.add(Stored(m, o.getBoolean("mine"), o.getBoolean("vis"), o.optInt("st"), o.optInt("dl"), o.optLong("rt"), o.optInt("rssi")))
                }
            }
        }
        keys.clear(); out.forEach { keys.add(it.msg.key) }
        _list.value = out
    }

    @Synchronized fun has(key: String) = key in keys

    /** 新消息入库；已有（去重）返回 false。 */
    @Synchronized
    fun add(s: Stored): Boolean {
        if (!keys.add(s.msg.key)) return false
        var l = _list.value + s
        if (l.size > CAP) { val drop = l.size - CAP; l.take(drop).forEach { keys.remove(it.msg.key) }; l = l.drop(drop) }
        _list.value = l
        save()
        return true
    }

    @Synchronized
    fun update(key: String, status: Int? = null, addDelivered: Int = 0) {
        _list.value = _list.value.map {
            if (it.msg.key != key) it else Stored(it.msg, it.mine, it.visible, status ?: it.status, it.delivered + addDelivered, it.recvTime, it.rssi)
        }
        save()
    }

    @Synchronized
    fun clear() { _list.value = emptyList(); keys.clear(); save() }

    private fun save() {
        val snapshot = _list.value
        val f = file ?: return
        io.execute {
            runCatching {
                val a = JSONArray()
                for (s in snapshot) a.put(JSONObject().apply {
                    put("id", s.msg.id); put("src", s.msg.src); put("dst", s.msg.dst); put("ttl", s.msg.ttl); put("time", s.msg.time)
                    put("nick", s.msg.nick); put("text", s.msg.text)
                    s.msg.lat?.let { put("lat", it) }; s.msg.lon?.let { put("lon", it) }
                    put("mine", s.mine); put("vis", s.visible); put("st", s.status); put("dl", s.delivered); put("rt", s.recvTime); put("rssi", s.rssi)
                })
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(a.toString())
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            }
        }
    }
}
