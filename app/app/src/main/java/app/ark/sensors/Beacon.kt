package app.ark.sensors

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import kotlin.math.pow

/**
 * 方舟蓝牙求救信标：BLE 广播（不建立连接、不配对），任何装了方舟的手机扫描到就能在地图上看到。
 *
 * ── 广播包格式（Manufacturer Specific Data，公司 ID 0xFFFF = 蓝牙联盟保留的“测试/内部使用”，Android 会按小端写在最前）──
 * 广播为不可连接（non-connectable）、不带设备名，整个 AD 结构 = 长度1 + 类型1(0xFF) + 公司ID 2 + 载荷 24 字节 = 28 字节，
 * 即使系统多加 3 字节 Flags 也不超过 31 字节上限。载荷（全部大端）：
 *
 *   偏移  长度  内容
 *   0     1     0xA7   方舟标识（魔数 1）
 *   1     1     0x4B   方舟标识（魔数 2，'K'）
 *   2     1     高 4 位 = 协议版本（现为 1）；bit0 = 状态：1 求救 / 0 平安；其余位保留 0
 *   3     2     节点 ID：安装时随机生成的 16 位，用来让接收方把同一个人的多次广播合并成一个点（不是真实身份）
 *   5     3     纬度：round((lat + 90) × 10000)，步长 0.0001° ≈ 11 m；0xFFFFFF = 没有定位
 *   8     3     经度：round((lon + 180) × 10000)，步长 0.0001°（赤道 ≈ 11 m，纬度越高越短）；0xFFFFFF = 没有定位
 *   11    1     位置已经过去的分钟数（0–255，封顶 255），接收方据此提示“位置可能过时”
 *   12    0–12  短消息，UTF-8，按字符边界截断到 12 字节（约 4 个汉字 / 12 个英文字母）；长度 = 广播剩余字节数
 *
 * 接收方收到后用自己的时间作为“收到时间”，用 RSSI 估算距离：d ≈ 10^((−65 − RSSI) / 25)（1 米处 −65 dBm、衰减指数 2.5，
 * 手机之间差别很大，只能当“很近 / 几十米 / 更远”的参考，真实位置以包里的经纬度为准）。
 *
 * ── 省电 ──
 * 广播：ADVERTISE_MODE_LOW_POWER（约每秒 1 个包），每分钟刷新一次内容（位置、已过分钟数）。
 * 扫描：每 60 秒扫 10 秒（带过滤器，只收方舟包），其余时间完全不扫。
 */
object Beacon {
    const val COMPANY = 0xFFFF
    const val MAGIC0 = 0xA7
    const val MAGIC1 = 0x4B
    const val VERSION = 1
    const val MSG_MAX = 12
    const val SCAN_MS = 10_000L
    const val PERIOD_MS = 60_000L
    private const val NONE = 0xFFFFFF

    data class Packet(val id: Int, val sos: Boolean, val lat: Double?, val lon: Double?, val ageMin: Int, val msg: String)

    /** 收到的信标：time = 本机收到的时间。 */
    data class Seen(val id: Int, val sos: Boolean, val lat: Double?, val lon: Double?, val ageMin: Int, val msg: String, val rssi: Int, val time: Long)

    fun truncate(msg: String, maxBytes: Int = MSG_MAX): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < msg.length) {
            val cp = msg.codePointAt(i)
            val b = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)
            if (out.size() + b.size > maxBytes) break
            out.write(b)
            i += Character.charCount(cp)
        }
        return out.toByteArray()
    }

    private fun q(v: Double, off: Double): Int = Math.round((v + off) * 10000).toInt().coerceIn(0, NONE - 1)

    fun encode(p: Packet): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(MAGIC0); o.write(MAGIC1)
        o.write((VERSION shl 4) or (if (p.sos) 1 else 0))
        o.write(p.id shr 8 and 0xFF); o.write(p.id and 0xFF)
        val la = if (p.lat == null || p.lon == null) NONE else q(p.lat, 90.0)
        val lo = if (p.lat == null || p.lon == null) NONE else q(p.lon, 180.0)
        for (v in intArrayOf(la, lo)) { o.write(v shr 16 and 0xFF); o.write(v shr 8 and 0xFF); o.write(v and 0xFF) }
        o.write(p.ageMin.coerceIn(0, 255))
        o.write(truncate(p.msg))
        return o.toByteArray()
    }

    fun decode(b: ByteArray?): Packet? {
        if (b == null || b.size < 12) return null
        fun u(i: Int) = b[i].toInt() and 0xFF
        if (u(0) != MAGIC0 || u(1) != MAGIC1 || (u(2) shr 4) != VERSION) return null
        val la = (u(5) shl 16) or (u(6) shl 8) or u(7)
        val lo = (u(8) shl 16) or (u(9) shl 8) or u(10)
        val has = la != NONE && lo != NONE
        return Packet(
            (u(3) shl 8) or u(4), (u(2) and 1) == 1,
            if (has) la / 10000.0 - 90 else null, if (has) lo / 10000.0 - 180 else null,
            u(11), String(b, 12, b.size - 12, Charsets.UTF_8).trim('\uFFFD', ' '),
        )
    }

    /** 粗略距离（米）。 */
    fun estimateDistance(rssi: Int): Double = 10.0.pow((-65.0 - rssi) / 25.0)

    // ---------------- 运行状态（BeaconService 写，界面读） ----------------
    val running = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    private val _seen = MutableStateFlow<List<Seen>>(emptyList())
    val seen: StateFlow<List<Seen>> = _seen

    fun onSeen(s: Seen) {
        _seen.value = (_seen.value.filter { it.id != s.id } + s).sortedByDescending { it.time }.take(60)
    }

    fun clearSeen() { _seen.value = emptyList() }

    /** 本机设置：状态、短消息。 */
    var sos = false
    var message = ""

    fun loadConfig(ctx: Context) {
        val p = ctx.applicationContext.getSharedPreferences("ark", Context.MODE_PRIVATE)
        sos = p.getBoolean("beacon_sos", false); message = p.getString("beacon_msg", "") ?: ""
    }

    fun saveConfig(ctx: Context, sos: Boolean, msg: String) {
        this.sos = sos; message = msg
        ctx.applicationContext.getSharedPreferences("ark", Context.MODE_PRIVATE).edit().putBoolean("beacon_sos", sos).putString("beacon_msg", msg).apply()
    }

    fun nodeId(ctx: Context): Int {
        val p = ctx.applicationContext.getSharedPreferences("ark", Context.MODE_PRIVATE)
        var id = p.getInt("beacon_id", -1)
        if (id < 0) { id = (Math.random() * 65536).toInt(); p.edit().putInt("beacon_id", id).apply() }
        return id
    }
}
