// 文件职责：警报声前台服务——最大音量播放 AlarmGen 生成的求救声，并提供耗电估算。
// 调用方：通讯界面的警报卡片（AlarmService.start/stop 和各 StateFlow）；依赖 AlarmSound.kt（样式与波形）、CommService.channel。
// 前台服务类型 mediaPlayback：它就是在播放声音；USAGE_ALARM 走闹钟音量流（独立于媒体音量）。
// 规则：启动时把闹钟音量拉满并记下旧值，退出时还原；持有 PARTIAL_WAKE_LOCK（最长 12 小时）保证关屏不断；用户没停而播放线程自己结束时要停服务。
package app.ark.comm

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.ark.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 警报声前台服务：AudioTrack + USAGE_ALARM（走闹钟音量流），启动时把闹钟音量拉到最大、停止时还原；
 * 持有 PARTIAL_WAKE_LOCK，关屏不停。通知栏可停止。
 */
@SuppressLint("MissingPermission")
class AlarmService : Service() {
    private var thread: Thread? = null
    @Volatile private var stop = false
    private var wl: PowerManager.WakeLock? = null
    private var oldVol = -1

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        val pat = AlarmPattern.entries.getOrNull(intent?.getIntExtra(EXTRA_PATTERN, 0) ?: 0) ?: AlarmPattern.SOS
        CommService.channel(this)
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        try {
            ServiceCompat.startForeground(this, ID, notif(pat), type)
        } catch (e: Exception) { error.value = "无法启动警报：${e.message}"; stopSelf(); return START_NOT_STICKY }
        // 已在播放：换样式（先停掉旧线程再起新的，最多等 500 ms）
        stop = true; thread?.interrupt(); thread?.join(500)
        stop = false
        val am = getSystemService(AudioManager::class.java)
        if (oldVol < 0) oldVol = am.getStreamVolume(AudioManager.STREAM_ALARM)
        runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0) }
        if (wl == null) {
            wl = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ark:alarm").apply { setReferenceCounted(false); acquire(12 * 3600_000L) }
        }
        if (active.value == null) { startedAt = System.currentTimeMillis(); startLevel = batteryPct(this) }
        active.value = pat
        val t = Thread({
            val me = Thread.currentThread()
            try { play(pat) } catch (e: Exception) { error.value = "播放出错：${e.message}" }
            // 非用户停止而播放线程结束（初始化/写入失败）：停掉服务、释放唤醒锁，不要“没声音却耗电”。旧线程（已被新播放取代）不得停新的
            if (!stop && thread === me) android.os.Handler(android.os.Looper.getMainLooper()).post { if (thread === me) stopSelf() }
        }, "ark-alarm")
        thread = t; t.start()
        getSystemService(NotificationManager::class.java).notify(ID, notif(pat))
        return START_NOT_STICKY
    }

    // 在 ark-alarm 线程跑，阻塞到 stop 或中断。TRIPLE 走专门的省电循环。
    private fun play(pat: AlarmPattern) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
        val sr = AlarmGen.SR
        val min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        val fmt = AudioFormat.Builder().setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
        val track = try {
            AudioTrack.Builder().setAudioAttributes(attrs).setAudioFormat(fmt).setBufferSizeInBytes(maxOf(min, 8192) * 2).setTransferMode(AudioTrack.MODE_STREAM).build()
        } catch (e: Exception) { error.value = "无法播放：${e.message}"; return }
        val gen = AlarmGen(pat, sr)
        val buf = ShortArray(2048)
        try {
            if (pat == AlarmPattern.TRIPLE) { playTriple(track, sr, buf); return }
            track.play()
            while (!stop && !Thread.currentThread().isInterrupted) {
                gen.fill(buf, buf.size)
                if (track.write(buf, 0, buf.size) < 0) break
            }
        } finally {
            runCatching { track.stop() }; track.release()
        }
    }

    /**
     * 三短声：每 60 秒只响前 3 秒。原来剩下 57 秒也在持续写静音，音频通路和功放一直开着；
     * 现在响完就 stop()（已写入的部分会播完），睡到下个周期再 play()。声音和节奏不变。唤醒锁仍持有，关屏也准时。
     */
    private fun playTriple(track: AudioTrack, sr: Int, buf: ShortArray) {
        val burst = sr * 3 // 3 声在 2.5 秒内响完，多写 0.5 秒静音让包络自然收尾
        while (!stop && !Thread.currentThread().isInterrupted) {
            val t0 = android.os.SystemClock.elapsedRealtime()
            val gen = AlarmGen(AlarmPattern.TRIPLE, sr) // 从周期起点生成
            track.play()
            var left = burst
            while (left > 0 && !stop) {
                val n = minOf(buf.size, left)
                gen.fill(buf, n)
                if (track.write(buf, 0, n) < 0) return
                left -= n
            }
            track.stop() // 流模式：缓冲里剩下的播完再停
            val wait = 60_000L - (android.os.SystemClock.elapsedRealtime() - t0)
            if (wait > 0) try { Thread.sleep(wait) } catch (_: InterruptedException) { return }
        }
    }

    override fun onDestroy() {
        stop = true; thread?.interrupt()
        wl?.let { if (it.isHeld) it.release() }
        if (oldVol >= 0) runCatching { getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, oldVol, 0) }
        active.value = null
        super.onDestroy()
    }

    private fun notif(p: AlarmPattern): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stopPi = PendingIntent.getService(this, 8, Intent(this, AlarmService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CommService.CH)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle("方舟：警报声播放中（${p.label}）")
            .setContentText("闹钟音量已调到最大；点“停止”结束")
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "停止", stopPi).build())
            .build()
    }

    companion object {
        const val ID = 62 // 通知 ID（蓝牙服务用 61）
        const val ACTION_STOP = "app.ark.ALARM_STOP"
        const val EXTRA_PATTERN = "pattern"
        val active = MutableStateFlow<AlarmPattern?>(null)
        val error = MutableStateFlow<String?>(null)
        var startedAt = 0L
        var startLevel = -1

        /** 开始/切换警报样式。副作用：开前台服务、拉满闹钟音量、持有唤醒锁（耗电）。起动失败写 [error]。 */
        fun start(ctx: Context, p: AlarmPattern) {
            error.value = null
            ContextCompat.startForegroundService(ctx, Intent(ctx, AlarmService::class.java).putExtra(EXTRA_PATTERN, p.ordinal))
        }
        /** 停止警报；还原音量、释放唤醒锁在 onDestroy 里做。 */
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, AlarmService::class.java)) }

        /** 当前电量百分比，取不到返回 -1。 */
        fun batteryPct(ctx: Context): Int = ctx.getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

        /** 电池容量 mAh：用 充电计数(µAh) ÷ 电量百分比 反推；取不到就按 4000 mAh。第二个值 = 是否为实测推算。 */
        fun capacityMah(ctx: Context): Pair<Double, Boolean> {
            val bm = ctx.getSystemService(BatteryManager::class.java)
            val cc = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: 0
            val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0
            return if (cc > 0 && pct in 10..100) Pair(cc / 1000.0 / (pct / 100.0), true) else Pair(4000.0, false)
        }

        /** 粗估平均电流 mA：关屏、扬声器最大音量；基础 70 mA（CPU 保持唤醒 + 音频通路）+ 功放 ≈ 230 mA × 发声占比。各机差异可达 2 倍。 */
        fun estimateMa(p: AlarmPattern) = 70.0 + 230.0 * p.dutyOn
    }
}
