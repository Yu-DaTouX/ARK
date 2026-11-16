// 文件职责：蓝牙消息（BLE 不配对、最多 3 跳接力）的线上格式——广播载荷、分片、消息帧、ACK 的编解码。
// 调用方：CommEngine（收发/接力）、CommStore（存 Msg）、AcousticLink（借 truncUtf8 截字节）。不依赖 Android，可在 PC 上测。
// 关键决定：
//  - 不配对：靠“广播服务 UUID + 扫描响应里的厂商数据”发现邻居，再临时连 GATT 写入消息，写完即断；这样陌生人之间无需用户操作。
//  - 魔数 A7 43 = 聊天广播；与求救信标（sensors/Beacon，A7 4B）共用公司 ID 0xFFFF，靠第二个魔数字节区分。
//  - 3 跳接力：消息带 TTL（默认 3），每转发一次减 1，去重键 = (消息 ID, 源)，防止广播风暴/环路。
//  - 全部多字节整数用大端；帧里不放校验和，完整性依赖 GATT 链路层。
package app.ark.comm

import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * 方舟蓝牙消息协议（纯 Kotlin，可在 PC 上测）。
 *
 * ── 发现（BLE 广播，可连接）──
 *  广播包：Flags + 128 位服务 UUID [SERVICE]（用来扫描过滤；与求救信标 Beacon 的 0xFFFF/A7 4B 厂商数据不冲突）
 *  扫描响应：厂商数据 公司 ID 0xFFFF，载荷 = 0xA7 0x43（魔数，注意与信标 A7 4B 不同）、版本 1、短 ID 4 字节（大端）、昵称 UTF-8 ≤12 字节
 *
 * ── GATT 服务 [SERVICE] ──
 *  [RX]  特征（WRITE，带响应）：对方把消息分片写进来；每个分片被协议栈确认一次
 *  [ACK] 特征（NOTIFY，带 CCCD）：整条消息重组并入库后通知 [msgId 4 字节][状态 1 字节：0 新消息 1 重复 2 格式错误]
 *
 * ── 分片（写入 RX 的每个值）──
 *   [xfer 1][idx 1][total 1][数据…]   xfer = 发送方每次传输递增的编号；每片数据 ≤ MTU−3−3，协商失败按 20 字节分片（每片数据 17 字节）
 *
 * ── 消息（重组后，全部大端）──
 *   0   1  版本 = 1
 *   1   1  flags：bit0 = 群发（1）/单发（0），bit1 = 带位置
 *   2   4  消息 ID（随机）            —— 去重键 = (消息 ID, 源)
 *   6   4  源节点短 ID
 *   10  4  目的短 ID（群发为 0）
 *   14  1  TTL（接收方还可以转发的跳数，默认 3）
 *   15  4  发送时间（UTC 秒）
 *   19  1  昵称长度 n（≤12）
 *   20  n  昵称 UTF-8
 *   ..  6  若带位置：纬度 3 字节 + 经度 3 字节（与 Beacon 同量化 ×10000，0xFFFFFF=无）
 *   ..  *  正文 UTF-8（≤ [TEXT_MAX] 字节）
 */
object CommProto {
    val SERVICE: UUID = UUID.fromString("6b5e0001-4152-4b00-8c3a-0f7a6c0d9e11")
    val RX: UUID = UUID.fromString("6b5e0002-4152-4b00-8c3a-0f7a6c0d9e11")
    val ACK: UUID = UUID.fromString("6b5e0003-4152-4b00-8c3a-0f7a6c0d9e11")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val COMPANY = 0xFFFF
    const val MAGIC0 = 0xA7
    const val MAGIC1 = 0x43
    const val ADV_VERSION = 1
    const val NICK_MAX = 12
    const val TEXT_MAX = 200
    const val DEFAULT_TTL = 3
    const val FRAG_HEAD = 3
    const val DEFAULT_CHUNK = 20 - FRAG_HEAD // MTU 23 时单次写入最多 20 字节，减分片头 3 = 17
    const val ST_NEW = 0
    const val ST_DUP = 1
    const val ST_BAD = 2

    /** 一条消息（内存形态）。[dst]==0 表示群发；[ttl] 是“接收方还能转发的跳数”；[time] 为发送方 UTC 秒；[lat]/[lon] 为空表示不带位置。 */
    class Msg(
        val id: Int, val src: Int, val dst: Int, val ttl: Int, val time: Long,
        val nick: String, val text: String, val lat: Double? = null, val lon: Double? = null,
    ) {
        val broadcast get() = dst == 0
        fun withTtl(t: Int) = Msg(id, src, dst, t, time, nick, text, lat, lon)
        val key get() = "%08x:%08x".format(id, src)
    }

    /** 按码点截断到不超过 [maxBytes] 个 UTF-8 字节，不会把一个字符（含代理对）截成半个。 */
    fun truncUtf8(s: String, maxBytes: Int): String {
        val sb = StringBuilder(); var n = 0; var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val str = String(Character.toChars(cp))
            val l = str.toByteArray(Charsets.UTF_8).size
            if (n + l > maxBytes) break
            sb.append(str); n += l; i += Character.charCount(cp)
        }
        return sb.toString()
    }

    // 经纬度量化：(值+偏移)×10000 取整（约 11 米精度）放进 3 字节；上限 0xFFFFFE，把 0xFFFFFF 留作“无位置”。
    private fun q(v: Double, off: Double) = Math.round((v + off) * 10000).toInt().coerceIn(0, 0xFFFFFE)

    /** 把消息编码成上面注释里的字节布局。昵称/正文超长会被静默截断；TTL 限制在 0..255；时间只取低 32 位。 */
    fun encode(m: Msg): ByteArray {
        val o = ByteArrayOutputStream()
        val nick = truncUtf8(m.nick, NICK_MAX).toByteArray(Charsets.UTF_8)
        val hasLoc = m.lat != null && m.lon != null
        o.write(1)
        o.write((if (m.broadcast) 1 else 0) or (if (hasLoc) 2 else 0))
        w32(o, m.id); w32(o, m.src); w32(o, m.dst)
        o.write(m.ttl.coerceIn(0, 255))
        w32(o, m.time.toInt())
        o.write(nick.size); o.write(nick)
        if (hasLoc) { for (v in intArrayOf(q(m.lat!!, 90.0), q(m.lon!!, 180.0))) { o.write(v shr 16 and 255); o.write(v shr 8 and 255); o.write(v and 255) } }
        o.write(truncUtf8(m.text, TEXT_MAX).toByteArray(Charsets.UTF_8))
        return o.toByteArray()
    }

    private fun w32(o: ByteArrayOutputStream, v: Int) { o.write(v ushr 24 and 255); o.write(v ushr 16 and 255); o.write(v ushr 8 and 255); o.write(v and 255) }

    /** 解码；版本不是 1、长度不足、昵称超长、群发标志与目的 ID 不一致时返回 null（不抛异常）。 */
    fun decode(b: ByteArray): Msg? {
        if (b.size < 20 || b[0].toInt() != 1) return null
        fun u(i: Int) = b[i].toInt() and 255
        fun r32(i: Int) = (u(i) shl 24) or (u(i + 1) shl 16) or (u(i + 2) shl 8) or u(i + 3)
        val flags = u(1)
        val nl = u(19)
        if (nl > NICK_MAX || 20 + nl > b.size) return null
        var p = 20 + nl
        val nick = String(b, 20, nl, Charsets.UTF_8)
        var lat: Double? = null; var lon: Double? = null
        if (flags and 2 != 0) {
            if (p + 6 > b.size) return null
            val la = (u(p) shl 16) or (u(p + 1) shl 8) or u(p + 2)
            val lo = (u(p + 3) shl 16) or (u(p + 4) shl 8) or u(p + 5)
            // 0xFFFFFF = 无位置（注意 encode 只会在 hasLoc 时写入位置，这里仅作对端兼容）
            if (la != 0xFFFFFF && lo != 0xFFFFFF) { lat = la / 10000.0 - 90; lon = lo / 10000.0 - 180 }
            p += 6
        }
        val text = String(b, p, b.size - p, Charsets.UTF_8)
        val bc = flags and 1 != 0 // 群发标志必须与 dst==0 一致，否则视为损坏
        val dst = r32(10)
        if (bc != (dst == 0)) return null
        return Msg(r32(2), r32(6), dst, u(14), r32(15).toLong() and 0xFFFFFFFFL, nick, text, lat, lon)
    }

    /** 分片：chunk = 每片数据字节数（不含 3 字节分片头）。总片数必须在 1..255（头里只有 1 字节），否则抛 IllegalArgumentException。 */
    fun fragment(data: ByteArray, xfer: Int, chunk: Int): List<ByteArray> {
        val total = (data.size + chunk - 1) / chunk
        require(total in 1..255) { "too many fragments" }
        return List(total) { i ->
            val from = i * chunk; val to = minOf(data.size, from + chunk)
            ByteArray(FRAG_HEAD + to - from).also {
                it[0] = xfer.toByte(); it[1] = i.toByte(); it[2] = total.toByte()
                System.arraycopy(data, from, it, FRAG_HEAD, to - from)
            }
        }
    }

    /** 每个对端一个重组器（服务端用）。分片必须按序到达（GATT 写入本来就按序）。 */
    class Reassembler {
        private var xfer = -1
        private var total = 0
        private var next = 0
        private val buf = ByteArrayOutputStream()

        /** 收到一片；整条完成时返回完整字节，否则 null。 */
        fun add(v: ByteArray): ByteArray? {
            if (v.size < FRAG_HEAD) return null
            val x = v[0].toInt() and 255; val i = v[1].toInt() and 255; val t = v[2].toInt() and 255
            // 第 0 片或传输号变化 = 新传输，丢弃之前没收完的半条；中途插入的非 0 片直接忽略
            if (i == 0 || x != xfer) { // 新传输
                if (i != 0) return null
                xfer = x; total = t; next = 0; buf.reset()
            }
            if (i != next || t != total) { xfer = -1; return null }
            buf.write(v, FRAG_HEAD, v.size - FRAG_HEAD); next++
            if (next == total) { xfer = -1; return buf.toByteArray() }
            return null
        }
    }

    /** 扫描响应里的厂商数据载荷：A7 43、版本、4 字节短 ID、昵称。整条 ≤ 3+4+12 = 19 字节，适合 31 字节广播限制。 */
    fun adv(id: Int, nick: String): ByteArray {
        val n = truncUtf8(nick, NICK_MAX).toByteArray(Charsets.UTF_8)
        val o = ByteArrayOutputStream()
        o.write(MAGIC0); o.write(MAGIC1); o.write(ADV_VERSION); w32(o, id); o.write(n)
        return o.toByteArray()
    }

    class AdvInfo(val id: Int, val nick: String)

    /** 解析 [adv] 的载荷；魔数/版本不符或太短返回 null。昵称末尾的 U+FFFD（被截断的半个字符）会被去掉。 */
    fun parseAdv(b: ByteArray?): AdvInfo? {
        if (b == null || b.size < 7) return null
        if ((b[0].toInt() and 255) != MAGIC0 || (b[1].toInt() and 255) != MAGIC1 || b[2].toInt() != ADV_VERSION) return null
        val id = ((b[3].toInt() and 255) shl 24) or ((b[4].toInt() and 255) shl 16) or ((b[5].toInt() and 255) shl 8) or (b[6].toInt() and 255)
        return AdvInfo(id, String(b, 7, b.size - 7, Charsets.UTF_8).trim('\uFFFD'))
    }

    /** ACK 通知载荷：消息 ID 4 字节大端 + 状态 1 字节（[ST_NEW]/[ST_DUP]/[ST_BAD]）。 */
    fun ack(id: Int, status: Int): ByteArray = ByteArray(5).also {
        it[0] = (id ushr 24).toByte(); it[1] = (id ushr 16).toByte(); it[2] = (id ushr 8).toByte(); it[3] = id.toByte(); it[4] = status.toByte()
    }

    /** 界面用的 4 位十六进制短 ID（只取低 16 位，仅用于显示，不唯一）。 */
    fun shortId(id: Int) = "%04X".format(id and 0xFFFF)
}
