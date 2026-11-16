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

    val cfg = MutableStateFlow(ModemConfig.AUDIBLE)
    val listening = MutableStateFlow(false)
    val sending = MutableStateFlow(false)
    /** 麦克风当前音量 0..1（粗略）与前导得分 0..1（>0.7 说明听到了同步信号）。 */
    val level = MutableStateFlow(0f)
    val score = MutableStateFlow(0f)
    val received = MutableStateFlow<List<Rx>>(emptyList())
    val message = MutableStateFlow<String?>(null)
    val selfTest = MutableStateFlow<String?>(null)
    val txSeconds = MutableStateFlow(0.0)

    @Volatile private var stopFlag = false
    @Volatile private var reader: Thread? = null
    @Volatile private var ignoreOwn = false
    @Volatile private var selfPayload: ByteArray? = null
    @Volatile private var selfResult: AcousticModem.Frame? = null

    /** 估算发送时长（秒）。 */
    fun duration(text: String, c: ModemConfig = cfg.value): Double {
        val b = text.toByteArray(Charsets.UTF_8)
        if (b.size > AcousticModem.MAX_BYTES) return 0.0
        return AcousticModem.symbols(b).size * c.symLen.toDouble() / c.sampleRate
    }

    /** 截到 140 字节（按字符边界）。 */
    fun clip(text: String) = CommProto.truncUtf8(text, AcousticModem.MAX_BYTES)

    @Synchronized
    fun startListening() {
        if (reader?.isAlive == true) return
        stopFlag = false
        val c = cfg.value
        val t = Thread({ readLoop(c) }, "ark-acoustic-rx")
        reader = t
        t.start()
    }

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
        for (src in intArrayOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.MIC)) {
            val r = runCatching { AudioRecord(src, c.sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 16384)) }.getOrNull()
            if (r != null && r.state == AudioRecord.STATE_INITIALIZED) { rec = r; break }
            r?.release()
        }
        if (rec == null) { message.value = "无法打开麦克风（权限被拒或被别的程序占用）"; return }
        val dec = AcousticModem.Decoder(c) { f -> onFrame(f) }
        val buf = ShortArray(2048)
        try {
            rec.startRecording()
            listening.value = true; message.value = null
            var tick = 0
            while (!stopFlag && !Thread.currentThread().isInterrupted) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) { if (n < 0) { message.value = "麦克风读取出错"; break }; continue }
                dec.feed(buf, n)
                if (++tick % 2 == 0) {
                    var e = 0.0
                    for (i in 0 until n) e += buf[i] * buf[i].toDouble()
                    level.value = (sqrt(e / n) / 8000.0).toFloat().coerceIn(0f, 1f)
                    score.value = dec.lastScore
                }
            }
        } finally {
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

    /** 播放一条消息（后台线程，阻塞到播完）。[self]=true 用于自测：不过滤自己的声音。 */
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
        } finally {
            runCatching { track.stop() }; track.release()
        }
    }

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
            selfPayload = null
            selfTest.value = if (r != null) "通：本机扬声器→麦克风解码成功（RS 纠正 ${r.corrected} 字节，信号质量 ${(r.quality * 100).toInt()}%）"
            else "不通：没解出来。把音量调大、手机离桌面远一点或换到安静处再试；近超声模式很多手机扬声器发不出来，请先用可闻模式"
            if (!wasListening) stopListening()
        }, "ark-acoustic-selftest").start()
        return true
    }
}
