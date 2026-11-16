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
        ServiceCompat.startForeground(this, ID, notif(pat), type)
        // 已在播放：换样式
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
        thread = Thread({ play(pat) }, "ark-alarm").apply { start() }
        getSystemService(NotificationManager::class.java).notify(ID, notif(pat))
        return START_NOT_STICKY
    }

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
            track.play()
            while (!stop && !Thread.currentThread().isInterrupted) {
                gen.fill(buf, buf.size)
                if (track.write(buf, 0, buf.size) < 0) break
            }
        } finally {
            runCatching { track.stop() }; track.release()
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
        const val ID = 62
        const val ACTION_STOP = "app.ark.ALARM_STOP"
        const val EXTRA_PATTERN = "pattern"
        val active = MutableStateFlow<AlarmPattern?>(null)
        val error = MutableStateFlow<String?>(null)
        var startedAt = 0L
        var startLevel = -1

        fun start(ctx: Context, p: AlarmPattern) {
            error.value = null
            ContextCompat.startForegroundService(ctx, Intent(ctx, AlarmService::class.java).putExtra(EXTRA_PATTERN, p.ordinal))
        }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, AlarmService::class.java)) }

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
