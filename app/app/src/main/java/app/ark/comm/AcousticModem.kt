package app.ark.comm

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 声学调制解调器：16 频点 MFSK（每个音 = 4 bit），纯 Kotlin、不依赖 Android，PC 上可直接测。
 *
 * ── 帧格式（按时间顺序，每个符号 = 一个音，时长 symSamples/采样率 ≈ 40 ms）──
 *   前导 13 符号：Barker-13 序列（+ 发音 15，− 发音 0）：错位对齐的得分 ≤ 0.5，正确对齐接近 1，不会锁错位置
 *   头   12 符号：RS(6,2) = [长度 0..140][版本 1] + 4 校验字节，每字节高 4 bit 先发
 *   体   2×N 符号：数据 = UTF-8 载荷 + CRC16-CCITT(2 字节)，切成 ≤48 字节的块（平均分），每块加 12 字节 RS 校验
 *        （每块可纠 6 个字节错误），各块逐字节交错发送（突发噪声被摊到各块）
 * 速率：140 字节消息约 15 秒（约 9–10 字节/秒，含校验开销）；原始 12.5 字节/秒。
 *
 * 接收：每 1/8 符号（5 ms）对最近 1 个符号长度的采样做 16 点 Goertzel，得到能量行；
 * 用前导模板的“峰值占比”打分找同步（与音量无关，不需要 AGC），之后按符号中心取 argmax 判决。
 * 每 5 ms 处理 1764 样本×16 频点 ≈ 6 M 乘加/秒，不分配内存（复用数组）。
 */
class ModemConfig(
    val name: String,
    val sampleRate: Int = 44100,
    val symMs: Int = 40,
    val f0: Double = 1500.0,
    val df: Double = 200.0,
) {
    /** 一个符号的采样数（取 8 的倍数，便于 1/8 符号步进）。 */
    val symLen: Int = Math.round(sampleRate * symMs / 1000.0 / 8).toInt() * 8
    val hop: Int = symLen / 8
    fun freq(k: Int) = f0 + df * k

    companion object {
        /** 可闻模式：1.5–4.5 kHz，间隔 200 Hz。 */
        val AUDIBLE = ModemConfig("可闻 1.5–4.5 kHz")
        /** 近超声：17–19.5 kHz。大多数手机扬声器/麦克风在这里很弱，试验性。 */
        val NEAR_ULTRA = ModemConfig("近超声 17–19.5 kHz", f0 = 17000.0, df = 2500.0 / 15)
    }
}

object AcousticModem {
    const val MAX_BYTES = 140
    const val VERSION = 1
    private const val BLOCK_DATA = 48
    private const val BLOCK_PARITY = 12
    private const val HEAD_PARITY = 4
    val PRE = intArrayOf(15, 15, 15, 15, 15, 0, 0, 15, 15, 0, 15, 0, 15) // Barker-13：+→音15，−→音0
    private const val HEAD_SYMS = 12
    private const val RING = 4096 // 保存最近 4096 个 5 ms 的能量行 ≈ 20 秒
    private const val THRESH = 0.7f

    fun crc16(b: ByteArray, len: Int = b.size): Int {
        var crc = 0xFFFF
        for (i in 0 until len) {
            crc = crc xor ((b[i].toInt() and 0xFF) shl 8)
            for (k in 0 until 8) crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
            crc = crc and 0xFFFF
        }
        return crc
    }

    private fun blockSizes(total: Int): IntArray {
        val nb = (total + BLOCK_DATA - 1) / BLOCK_DATA
        return IntArray(nb) { total / nb + if (it < total % nb) 1 else 0 }
    }

    /** 载荷 → 符号序列（含前导）。 */
    fun symbols(payload: ByteArray): IntArray {
        require(payload.size <= MAX_BYTES) { "too long" }
        val head = byteArrayOf(payload.size.toByte(), VERSION.toByte())
        val headCw = head + Rs.parity(head, HEAD_PARITY)
        val data = ByteArray(payload.size + 2)
        System.arraycopy(payload, 0, data, 0, payload.size)
        val crc = crc16(payload)
        data[payload.size] = (crc shr 8).toByte(); data[payload.size + 1] = crc.toByte()
        val sizes = blockSizes(data.size)
        var off = 0
        val cws = sizes.map { sz ->
            val chunk = data.copyOfRange(off, off + sz); off += sz
            chunk + Rs.parity(chunk, BLOCK_PARITY)
        }
        val body = ArrayList<Byte>()
        val maxLen = cws.maxOf { it.size }
        for (c in 0 until maxLen) for (cw in cws) if (c < cw.size) body.add(cw[c])
        val all = headCw.toList() + body
        val out = IntArray(PRE.size + all.size * 2)
        System.arraycopy(PRE, 0, out, 0, PRE.size)
        var p = PRE.size
        for (b in all) { val v = b.toInt() and 0xFF; out[p++] = v shr 4; out[p++] = v and 15 }
        return out
    }

    /** 载荷 → 波形（单声道，-1..1），前后各加 [padMs] 毫秒静音。 */
    fun encode(payload: ByteArray, cfg: ModemConfig, amp: Float = 0.8f, padMs: Int = 200): FloatArray {
        val syms = symbols(payload)
        val pad = cfg.sampleRate * padMs / 1000
        val out = FloatArray(pad * 2 + syms.size * cfg.symLen)
        val ramp = (cfg.sampleRate * 0.0015).toInt()
        val w = 2 * PI / cfg.sampleRate
        var p = pad
        for (s in syms) {
            val wf = w * cfg.freq(s)
            for (i in 0 until cfg.symLen) {
                var a = amp
                if (i < ramp) a *= (0.5 - 0.5 * cos(PI * i / ramp)).toFloat()
                else if (i >= cfg.symLen - ramp) a *= (0.5 - 0.5 * cos(PI * (cfg.symLen - 1 - i) / ramp)).toFloat()
                out[p + i] = (a * sin(wf * i)).toFloat()
            }
            p += cfg.symLen
        }
        return out
    }

    class Frame(val payload: ByteArray, val corrected: Int, val quality: Float)

    /** 流式解码器：不断 feed 麦克风采样，解出一帧就回调。非线程安全（单线程喂入）。 */
    class Decoder(val cfg: ModemConfig, private val onFrame: (Frame) -> Unit) {
        private val symLen = cfg.symLen
        private val hop = cfg.hop
        private val win = FloatArray(symLen)
        private var hopFill = 0
        private val coef = DoubleArray(16) { 2 * cos(2 * PI * cfg.freq(it) / cfg.sampleRate) }
        private val ring = FloatArray(RING * 16)
        private val sums = FloatArray(RING)
        private var n = -1L // 最近一个步进的绝对序号
        private var scanFrom = 0L
        private var cand = false
        private var bestS = 0f
        private var bestN = 0L
        private var candStart = 0L
        private var lockN = 0L
        private var state = 0 // 0 找同步，1 等头，2 等体
        private var bodyLen = 0
        private var bodySyms = 0

        /** 最近一次的前导得分（0..1），界面可显示“听到信号”。 */
        @Volatile var lastScore = 0f
        /** 最近一步的总能量（粗略的“音量”）。 */
        @Volatile var level = 0f

        fun reset() { state = 0; cand = false; scanFrom = n + 1 }

        fun feed(pcm: ShortArray, count: Int) {
            for (i in 0 until count) {
                // 把样本直接放进窗口末尾；满一个步进就整体左移、处理
                win[symLen - hop + hopFill] = pcm[i] / 32768f
                hopFill++
                if (hopFill == hop) {
                    step()
                    System.arraycopy(win, hop, win, 0, symLen - hop)
                    hopFill = 0
                }
            }
        }

        private fun step() {
            n++
            val row = (n and (RING - 1).toLong()).toInt()
            var sum = 0f
            for (k in 0 until 16) {
                val c = coef[k]
                var s1 = 0.0; var s2 = 0.0
                for (i in 0 until symLen) { val s0 = win[i] + c * s1 - s2; s2 = s1; s1 = s0 }
                val e = (s1 * s1 + s2 * s2 - c * s1 * s2).toFloat()
                ring[row * 16 + k] = e
                sum += e
            }
            sums[row] = sum
            level = sum
            if (n < PRE.size * 8 || n < scanFrom) return
            when (state) {
                0 -> scanSync()
                1 -> if (n >= lockN + 8 * HEAD_SYMS) decodeHead()
                2 -> if (n >= lockN + 8L * (HEAD_SYMS + bodySyms)) decodeBody()
            }
        }

        private fun peakness(m: Long, tone: Int): Float {
            val r = (m and (RING - 1).toLong()).toInt()
            return ring[r * 16 + tone] / (sums[r] + 1e-2f)
        }

        private fun scanSync() {
            var sc = 0f
            for (i in PRE.indices) sc += peakness(n - 8L * (PRE.size - 1 - i), PRE[i])
            sc /= PRE.size
            lastScore = sc
            if (!cand) {
                if (sc > THRESH) { cand = true; bestS = sc; bestN = n; candStart = n }
            } else {
                if (sc > bestS) { bestS = sc; bestN = n }
                if (n - candStart >= 10) { cand = false; lockN = bestN; state = 1 }
            }
        }

        private fun symAt(m: Long): Int {
            val r = (m and (RING - 1).toLong()).toInt()
            var best = 0; var be = -1f
            for (k in 0 until 16) { val e = ring[r * 16 + k]; if (e > be) { be = e; best = k } }
            return best
        }

        private fun decodeHead() {
            val cw = ByteArray(6)
            for (b in 0 until 6) cw[b] = ((symAt(lockN + 8L * (2 * b + 1)) shl 4) or symAt(lockN + 8L * (2 * b + 2))).toByte()
            val ok = Rs.correct(cw, HEAD_PARITY)
            val len = cw[0].toInt() and 0xFF
            if (ok < 0 || cw[1].toInt() != VERSION || len > MAX_BYTES) { fail(); return }
            bodyLen = len
            val total = len + 2
            val nb = blockSizes(total).size
            bodySyms = 2 * (total + nb * BLOCK_PARITY)
            state = 2
        }

        private fun decodeBody() {
            val total = bodyLen + 2
            val sizes = blockSizes(total)
            val cws = Array(sizes.size) { ByteArray(sizes[it] + BLOCK_PARITY) }
            val maxLen = cws.maxOf { it.size }
            var idx = 0
            var qsum = 0f
            for (c in 0 until maxLen) for (cw in cws) if (c < cw.size) {
                val m1 = lockN + 8L * (HEAD_SYMS + 2 * idx + 1)
                val m2 = m1 + 8
                cw[c] = ((symAt(m1) shl 4) or symAt(m2)).toByte()
                idx++
            }
            for (j in 0 until bodySyms) qsum += peakness(lockN + 8L * (HEAD_SYMS + j + 1), symAt(lockN + 8L * (HEAD_SYMS + j + 1)))
            val data = ByteArray(total)
            var off = 0; var errs = 0
            for (cw in cws) {
                val e = Rs.correct(cw, BLOCK_PARITY)
                if (e < 0) { fail(); return }
                errs += e
                System.arraycopy(cw, 0, data, off, cw.size - BLOCK_PARITY)
                off += cw.size - BLOCK_PARITY
            }
            val crc = ((data[bodyLen].toInt() and 0xFF) shl 8) or (data[bodyLen + 1].toInt() and 0xFF)
            if (crc != crc16(data, bodyLen)) { fail(); return }
            state = 0; scanFrom = n + 1
            onFrame(Frame(data.copyOf(bodyLen), errs, qsum / bodySyms))
        }

        private fun fail() {
            state = 0
            scanFrom = lockN + 8L * PRE.size
        }
    }
}
