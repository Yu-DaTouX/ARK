package app.ark.comm

import kotlin.math.PI
import kotlin.math.sin

/** 警报声样式。dutyOn = 声音实际发出的时间占比（估耗电用）。 */
enum class AlarmPattern(val label: String, val desc: String, val dutyOn: Double) {
    SOS("SOS 摩尔斯", "··· ——— ··· 约 3 kHz，穿透力强，每个循环约 7 秒", 0.44),
    WHISTLE("连续哨音", "3 kHz 上下快速颤动的连续尖音，不停顿，最容易被听到", 1.0),
    TRIPLE("三短声（国际求救）", "三声短音，每分钟重复一次；省电，适合长时间等待", 0.025),
}

/** 逐样本生成警报音（无分配，可在音频线程里反复调用 [fill]）。纯 Kotlin。 */
class AlarmGen(private val pattern: AlarmPattern, private val sr: Int = SR) {
    companion object {
        const val SR = 44100
        /** SOS：一个单位 = 0.2 s；点 1、划 3、符内间隔 1、字母间隔 3、单词间隔 7。 */
        private val SOS_UNITS: BooleanArray = run {
            val l = ArrayList<Boolean>()
            fun sym(n: Int) { repeat(n) { l.add(true) }; l.add(false) }
            for (i in 0 until 3) sym(1); repeat(2) { l.add(false) } // S + 字母间隔(共 3 个静音单位)
            for (i in 0 until 3) sym(3); repeat(2) { l.add(false) } // O
            for (i in 0 until 3) sym(1); repeat(6) { l.add(false) } // S + 单词间隔(7)
            l.toBooleanArray()
        }
        private const val SOS_UNIT_S = 0.2
        private const val TRIPLE_PERIOD_S = 60.0
    }

    private var pos = 0L
    private var phase = 0.0
    private var env = 0.0

    fun fill(buf: ShortArray, n: Int) {
        for (i in 0 until n) {
            val t = pos.toDouble() / sr
            pos++
            var f = 3000.0
            val gate: Double
            when (pattern) {
                AlarmPattern.SOS -> gate = if (SOS_UNITS[((t / SOS_UNIT_S).toLong() % SOS_UNITS.size).toInt()]) 1.0 else 0.0
                AlarmPattern.WHISTLE -> { gate = 1.0; f = 3000.0 + 350.0 * sin(2 * PI * 5.0 * t) }
                AlarmPattern.TRIPLE -> {
                    val c = t % TRIPLE_PERIOD_S
                    gate = if (c < 3.0 && (c % 1.0) < 0.5) 1.0 else 0.0 // 3 声：每声 0.5 s，间隔 0.5 s
                }
            }
            env += (gate - env) * 0.02 // ≈1 ms 过渡，避免爆音
            phase += 2 * PI * f / sr
            if (phase > 2 * PI) phase -= 2 * PI
            // 过驱削波的正弦：比纯正弦响约 3 dB，仍集中在 3 kHz 及其奇次谐波
            val v = (1.6 * sin(phase)).coerceIn(-1.0, 1.0) * env * 0.98
            buf[i] = (v * 32767).toInt().toShort()
        }
    }
}
