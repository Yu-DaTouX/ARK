package app.ark.comm

import java.util.Random
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * PC 上的声学链路仿真（不属于 App 运行逻辑，只是随源码一起放着，方便复测）：
 *   java -cp <kotlin-classes>;<kotlin-stdlib.jar> app.ark.comm.ModemSim [每组次数]
 * 信道：随机前导静音（0–1.5 s）+ 随机延迟、音量起伏（±3 % 的慢变）、白噪声（按载荷段 RMS 算 SNR）、
 *       时钟偏差（±ppm 线性重采样）、可选回声（12 ms/0.35 + 30 ms/0.2）、16 bit 量化。
 */
object ModemSim {
    fun channel(w: FloatArray, snrDb: Double, rnd: Random, ppm: Double = 0.0, echo: Boolean = false, sr: Int = 44100, ripple: Double = 0.03, burstMs: Int = 0): ShortArray {
        val lead = (rnd.nextDouble() * 1.5 * sr).toInt()
        val trail = (0.5 * sr).toInt()
        var x = FloatArray(lead + w.size + trail)
        System.arraycopy(w, 0, x, lead, w.size)
        if (echo) {
            val y = x.copyOf()
            val d1 = (0.012 * sr).toInt(); val d2 = (0.030 * sr).toInt()
            for (i in x.indices) { if (i >= d1) y[i] += 0.35f * x[i - d1]; if (i >= d2) y[i] += 0.2f * x[i - d2] }
            x = y
        }
        if (ppm != 0.0) { // 线性重采样：接收端时钟偏快/慢
            val r = 1.0 + ppm * 1e-6
            val y = FloatArray(x.size)
            for (i in y.indices) {
                val p = i * r; val k = p.toInt()
                y[i] = if (k + 1 < x.size) (x[k] * (1 - (p - k)) + x[k + 1] * (p - k)).toFloat() else 0f
            }
            x = y
        }
        val f = 0.3 + rnd.nextDouble() * 2.7; val ph = rnd.nextDouble() * 2 * PI
        var e = 0.0; var cnt = 0
        for (v in w) if (v != 0f) { e += v * v.toDouble(); cnt++ }
        val sigma = sqrt(e / cnt) / Math.pow(10.0, snrDb / 20)
        val out = ShortArray(x.size)
        val bStart = lead + (rnd.nextDouble() * (w.size - sr)).toInt() + sr / 2
        val bEnd = bStart + sr * burstMs / 1000
        for (i in x.indices) {
            val g = 1 + ripple * sin(2 * PI * f * i / sr + ph)
            val v = x[i] * g + rnd.nextGaussian() * sigma + (if (burstMs > 0 && i in bStart until bEnd) rnd.nextGaussian() * 0.4 else 0.0)
            out[i] = (v * 32767).coerceIn(-32768.0, 32767.0).toInt().toShort()
        }
        return out
    }

    private fun run(cfg: ModemConfig, msg: ByteArray, pcm: ShortArray, rnd: Random): AcousticModem.Frame? {
        var got: AcousticModem.Frame? = null
        val dec = AcousticModem.Decoder(cfg) { if (got == null) got = it }
        var p = 0
        while (p < pcm.size) {
            val n = minOf(pcm.size - p, 256 + rnd.nextInt(2048))
            dec.feed(if (p == 0 && n == pcm.size) pcm else pcm.copyOfRange(p, p + n), n)
            p += n
        }
        return got
    }

    private fun randomText(rnd: Random, bytes: Int): ByteArray {
        val sb = StringBuilder()
        val pool = "求救我们在山脊北侧有三人需要水和保暖 abc XYZ 0123456789,.!"
        while (true) {
            val c = pool[rnd.nextInt(pool.length)]
            if (sb.toString().toByteArray().size + c.toString().toByteArray().size > bytes) break
            sb.append(c)
        }
        return sb.toString().toByteArray()
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val trials = args.firstOrNull()?.toIntOrNull() ?: 60
        val rnd = Random(20260101)
        // 1) RS 自检
        var rsOk = 0; var rsBad = 0; var rsDet = 0
        repeat(2000) {
            val d = ByteArray(1 + rnd.nextInt(48)).also { rnd.nextBytes(it) }
            val cw = d + Rs.parity(d, 12)
            val c = cw.copyOf()
            val ne = rnd.nextInt(10)
            val pos = (0 until cw.size).shuffled(rnd).take(ne)
            for (p in pos) c[p] = (c[p].toInt() xor (1 + rnd.nextInt(255))).toByte()
            val r = Rs.correct(c, 12)
            if (ne <= 6) { if (r == ne && c.contentEquals(cw)) rsOk++ else rsBad++ } else if (r < 0) rsDet++
        }
        println("RS(≤48+12)：≤6 错 正确纠正 $rsOk 次，失败 $rsBad 次；>6 错 检出不可纠 $rsDet 次（其余为误纠，由 CRC16 兜底）")

        val full = randomText(rnd, 140)
        val dur = AcousticModem.encode(full, ModemConfig.AUDIBLE).size / 44100.0
        println("140 字节：音频 %.1f 秒（含 0.4 s 静音）→ 约 %.1f 字节/秒".format(dur, 140 / (dur - 0.4)))
        println("每组 $trials 次，随机文本长度 1–140 字节（含 140 的）；成功 = 载荷逐字节一致")
        println("%-22s %-14s %8s %8s %10s".format("模式", "条件", "成功", "失败", "平均纠错字节"))
        data class Case(val label: String, val snr: Double, val ppm: Double = 0.0, val echo: Boolean = false, val burst: Int = 0)
        val cases = listOf(
            Case("SNR 20 dB", 20.0), Case("SNR 10 dB（验收）", 10.0), Case("SNR 5 dB", 5.0), Case("SNR 0 dB", 0.0),
            Case("SNR -5 dB", -5.0), Case("SNR -10 dB", -10.0), Case("SNR -15 dB", -15.0), Case("SNR -20 dB", -20.0),
            Case("10 dB+0.4s 强噪声", 10.0, burst = 400), Case("10 dB+1s 强噪声", 10.0, burst = 1000), Case("10 dB+1000ppm", 10.0, ppm = 1000.0),
            Case("10 dB+回声", 10.0, echo = true), Case("10 dB+时钟 +300ppm", 10.0, ppm = 300.0), Case("10 dB+时钟 -300ppm", 10.0, ppm = -300.0),
            Case("0 dB+回声+150ppm", 0.0, ppm = 150.0, echo = true),
        )
        for (cfg in listOf(ModemConfig.AUDIBLE, ModemConfig.NEAR_ULTRA)) {
            for (c in cases) {
                var ok = 0; var errs = 0
                for (t in 0 until trials) {
                    val msg = if (t == 0) full else randomText(rnd, 1 + rnd.nextInt(140))
                    val pcm = channel(AcousticModem.encode(msg, cfg), c.snr, rnd, c.ppm, c.echo, burstMs = c.burst)
                    val r = run(cfg, msg, pcm, rnd)
                    if (r != null && r.payload.contentEquals(msg)) { ok++; errs += r.corrected }
                }
                println("%-22s %-14s %8d %8d %10.2f".format(cfg.name, c.label, ok, trials - ok, if (ok > 0) errs.toDouble() / ok else 0.0))
            }
        }
        // 2) 误报：纯噪声 / 纯单音 / 宽带噪声叠 3 kHz 持续音，每种 120 秒
        for ((label, gen) in listOf<Pair<String, (Int) -> Double>>(
            "白噪声 σ=0.1" to { _ -> rnd.nextGaussian() * 0.1 },
            "持续 3 kHz 单音+噪声" to { i -> 0.5 * sin(2 * PI * 3000.0 * i / 44100) + rnd.nextGaussian() * 0.05 },
            "0/15 两音交替(无 Barker 结构)" to { i -> 0.5 * sin(2 * PI * (if ((i / 1760) % 2 == 0) 1500.0 else 4500.0) * i / 44100) + rnd.nextGaussian() * 0.05 },
        )) {
            var frames = 0
            val dec = AcousticModem.Decoder(ModemConfig.AUDIBLE) { frames++ }
            val buf = ShortArray(2048); var idx = 0
            repeat(44100 * 120 / 2048) { for (j in buf.indices) buf[j] = (gen(idx++) * 32767).coerceIn(-32768.0, 32767.0).toInt().toShort(); dec.feed(buf, buf.size) }
            println("误报测试（$label，120 s）：误解出帧数 $frames")
        }
    }
}
