// 文件职责：声波传信的音频收发——麦克风录音循环喜给 AcousticModem.Decoder，发送用 AudioTrack 播放调制后的波形，另有单机自测。
// 调用方：ui/comm 的超声界面（读各 StateFlow）；依赖 AcousticModem、CommProto.truncUtf8。
// 关键决定：只用近超声（可闻模式太吵）；录音源优先 UNPROCESSED（避免系统降噪/AGC 把 17 kHz 以上的音滤掉），不支持时回退普通 MIC；
// 发送时忽略自己的声音（ignoreOwn）；录音/播放都在独立线程，优先级 URGENT_AUDIO。
// 权限：需 RECORD_AUDIO（由界面负责申请）；监听期间持续耗电，没有前台服务，退到后台系统可能切断麦克风。
package app.ark.comm

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.sqrt

/** 声音传信的音频收发（扬声器 → 麦克风）。调制解调见 [AcousticModem]；全部在后台线程，界面只读状态。 */
@SuppressLint("MissingPermission")
object AcousticLink {
    class Rx(val text: String, val time: Long, val corrected: Int, val quality: Float, val self: Boolean)

    val cfg = MutableStateFlow(ModemConfig.NEAR_ULTRA) // 可闻模式太吵，界面只留近超声（12S→Fold6 实测可用）
    val listening = MutableStateFlow(false)
    val sending = MutableStateFlow(false)
    /** 麦克风当前音量 0..1（粗略）与前导得分 0..1（>0.7 说明听到了同步信号）。 */
    val level = MutableStateFlow(0f)
    val score = MutableStateFlow(0f)
    val received = MutableStateFlow<List<Rx>>(emptyList())
    val message = MutableStateFlow<String?>(null)
    val selfTest = MutableStateFlow<String?>(null)
    val txSeconds = MutableStateFlow(0.0)

    /** 调试：接收时保留最近 30 秒原始录音，停止监听时写到这个目录的 acoustic_rx.pcm（16 bit 单声道），电脑上复盘解码。 */
    @Volatile var dumpDir: java.io.File? = null

    @Volatile private var stopFlag = false
    @Volatile private var reader: Thread? = null
    @Volatile private var ignoreOwn = false
    @Volatile private var selfPayload: ByteArray? = null
    @Volatile private var selfResult: AcousticModem.Frame? = null

    /** 估算发送时长（秒）；超过 140 字节返回 0。不含前后 200 ms 静音。 */
    fun duration(text: String, c: ModemConfig = cfg.value): Double {
        val b = text.toByteArray(Charsets.UTF_8)
        if (b.size > AcousticModem.MAX_BYTES) return 0.0
        return AcousticModem.symbols(b).size * c.symLen.toDouble() / c.sampleRate
    }

    /** 截到 140 字节（按字符边界）。 */
    fun clip(text: String) = CommProto.truncUtf8(text, AcousticModem.MAX_BYTES)

    /** 开始监听（幂等）：起 ark-acoustic-rx 线程打开麦克风持续录音（耗电）。失败原因写入 [message]，不抛异常。任意线程可调。 */
    @Synchronized
    fun startListening() {
        if (reader?.isAlive == true) return
        stopFlag = false
        val c = cfg.value
        val t = Thread({ readLoop(c) }, "ark-acoustic-rx")
        reader = t
        t.start()
    }

    /** 停止监听：置标志并中断线程，录音资源在线程退出时释放（异步）。若 [dumpDir] 非空会写 acoustic_rx.pcm。 */
    @Synchronized
    fun stopListening() {
        stopFlag = true
        reader?.interrupt()
        reader = null
    }

    private fun readLoop(c: ModemConfig) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val min = AudioRecord.getMinBufferSize(c.sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) { message.value = "这台手机不支持 ${c.sampleRate} Hz 录音"; return }
        var rec: AudioRecord? = null
        // 先试未处理音频源，失败再用普通 MIC
        for (src in intArrayOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.MIC)) {
            val r = runCatching { AudioRecord(src, c.sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 16384)) }.getOrNull()
            if (r != null && r.state == AudioRecord.STATE_INITIALIZED) { rec = r; break }
            r?.release()
        }
        if (rec == null) { message.value = "无法打开麦克风（权限被拒或被别的程序占用）"; return }
        val dec = AcousticModem.Decoder(c) { f -> android.util.Log.i("ark", "acoustic frame ${f.payload.size} B"); onFrame(f) }
        dec.log = { android.util.Log.i("ark", "acoustic $it") }
        val buf = ShortArray(2048)
        val keep = if (dumpDir != null) ShortArray(c.sampleRate * 30) else null
        var kpos = 0L
        try {
            rec.startRecording()
            listening.value = true; message.value = null
            var tick = 0
            while (!stopFlag && !Thread.currentThread().isInterrupted) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) { if (n < 0) { message.value = "麦克风读取出错"; break }; continue }
                dec.feed(buf, n)
                if (keep != null) for (i in 0 until n) { keep[(kpos % keep.size).toInt()] = buf[i]; kpos++ }
                if (++tick % 2 == 0) { // 每两块（2048 样本）更新一次界面状态，减少 StateFlow 刷新
                    // 经验系数：RMS/8000 约对应“很响”，仅供显示
                    var e = 0.0
                    for (i in 0 until n) e += buf[i] * buf[i].toDouble()
                    level.value = (sqrt(e / n) / 8000.0).toFloat().coerceIn(0f, 1f)
                    score.value = dec.lastScore
                }
            }
        } catch (e: Exception) { // startRecording/read 可能抛 IllegalStateException（麦克风被抢、路由变化），不能逃出裸线程
            message.value = "麦克风出错：${e.message}"
        } finally {
            if (keep != null) runCatching {
                val len = minOf(kpos, keep.size.toLong()).toInt()
                val bb = java.nio.ByteBuffer.allocate(len * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                for (j in 0 until len) bb.putShort(keep[((kpos - len + j) % keep.size).toInt()])
                java.io.File(dumpDir, "acoustic_rx.pcm").writeBytes(bb.array())
            }
            runCatching { rec.stop() }; rec.release()
            listening.value = false; level.value = 0f; score.value = 0f
        }
    }

    private fun onFrame(f: AcousticModem.Frame) {
        val sp = selfPayload
        if (sp != null && f.payload.contentEquals(sp)) { selfResult = f; return }
        if (ignoreOwn) return
        val text = String(f.payload, Charsets.UTF_8)
        received.value = (listOf(Rx(text, System.currentTimeMillis(), f.corrected, f.quality, false)) + received.value).take(50)
    }

    /** 播放一条消息（后台线程，阻塞到播完）。成功返回 true；被中断或出错返回 false（出错写 [message]）。用媒体音量播放，音量由用户调。 */
    private fun play(payload: ByteArray, c: ModemConfig): Boolean {
        val wave = AcousticModem.encode(payload, c)
        val pcm = ShortArray(wave.size) { (wave[it] * 32767f).toInt().coerceIn(-32768, 32767).toShort() }
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        val fmt = AudioFormat.Builder().setSampleRate(c.sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
        val track = try {
            AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(fmt).setBufferSizeInBytes(pcm.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
        } catch (e: Exception) { message.value = "无法播放：${e.message}"; return false }
        try {
            track.write(pcm, 0, pcm.size)
            track.play()
            val end = System.currentTimeMillis() + pcm.size * 1000L / c.sampleRate + 3000
            while (track.playbackHeadPosition < pcm.size && System.currentTimeMillis() < end) Thread.sleep(40)
            Thread.sleep(150)
            return true
        } catch (_: InterruptedException) {
            return false
        } catch (e: Exception) {
            message.value = "播放出错：${e.message}"; return false
        } finally {
            runCatching { track.stop() }; runCatching { track.release() }
        }
    }

    /**
     * 发送文本（先按 140 字节截断）：异步播放，立即返回。空文本或正在发送返回 false。
     * 播放期间 [ignoreOwn] 为真，避免本机麦克风把自己的声音当成收到的消息。
     */
    @Synchronized
    fun send(text: String): Boolean {
        val b = clip(text).toByteArray(Charsets.UTF_8)
        if (b.isEmpty() || sending.value) return false
        val c = cfg.value
        txSeconds.value = duration(String(b, Charsets.UTF_8), c)
        sending.value = true
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            ignoreOwn = true
            try { play(b, c) } finally { ignoreOwn = false; sending.value = false }
        }, "ark-acoustic-tx").start()
        return true
    }

    /** 单机自测：边播边录，看本机麦克风能不能解出自己的声音。需要已有录音权限。 */
    @Synchronized
    fun runSelfTest(): Boolean {
        if (sending.value || selfTest.value == "测试中…") return false
        val c = cfg.value
        selfTest.value = "测试中…"
        val payload = ("ARK测试" + (1000 + java.util.Random().nextInt(9000))).toByteArray(Charsets.UTF_8)
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val wasListening = listening.value
            try {
                if (!wasListening) startListening()
                var waited = 0
                while (!listening.value && waited < 2000) { Thread.sleep(50); waited += 50 }
                if (!listening.value) { selfTest.value = "失败：麦克风没打开（检查录音权限）"; return@Thread }
                selfResult = null; selfPayload = payload
                sending.value = true
                val ok = try { play(payload, c) } finally { sending.value = false }
                var w = 0
                while (selfResult == null && w < 2500 && ok) { Thread.sleep(50); w += 50 }
                val r = selfResult
                selfTest.value = if (r != null) "通：本机扬声器→麦克风解码成功（RS 纠正 ${r.corrected} 字节，信号质量 ${(r.quality * 100).toInt()}%）"
                else "不通：没解出来。把媒体音量调大、换到安静处再试；有些手机扬声器发不出 17 kHz 以上的声音，这种手机只能接收，不能发送"
            } catch (e: Exception) {
                selfTest.value = "自测出错：${e.message}"
            } finally {
                selfPayload = null; sending.value = false
                if (!wasListening) stopListening()
            }
        }, "ark-acoustic-selftest").start()
        return true
    }
}
