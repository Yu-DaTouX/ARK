/*
 * TorchService.kt —— 闪光灯摩尔斯信号的前台服务（类型 specialUse，子类型 flashlight_morse_signal，见清单；
 * Android 14+ 才必须带 FGS 类型，更低版本传 0）。由 SignalTool 通过 start()/stop() 控制，状态用静态 StateFlow 回传界面。
 * 耗电：闪光灯 + 持有 PARTIAL_WAKE_LOCK（最长 2 小时自动释放）；通知栏有“停止”。
 */
package app.ark.ui.tools

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.ark.MainActivity
import app.ark.sensors.TrackService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 带闪光灯的摄像头 id（没有闪光灯返回 null）。setTorchMode 从 API 23 起不需要 CAMERA 权限。 */
fun torchId(ctx: Context): String? = try {
    val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
} catch (_: Exception) { null }

/**
 * 闪光灯信号的前台服务：关屏、切到后台后继续按摩尔斯码闪（通知栏上有“停止”）。
 * 离开信号页（SignalTool 被销毁）时界面会主动停止它；应用被划掉时 onTaskRemoved 也会停。
 * 闪光灯被占用 / 过热 / 低电量导致 setTorchMode 失败时，failed 置 true 并自动停止，界面提示改用屏幕信号灯。
 */
class TorchService : Service() {
    // 主线程协程：CameraManager.setTorchMode 可在主线程调用，信号时序用 delay 驱动
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var cm: CameraManager? = null
    private var id: String? = null
    private val cb = object : CameraManager.TorchCallback() {
        override fun onTorchModeUnavailable(cameraId: String) { if (cameraId == id && _active.value) _failed.value = true }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        TrackService.channel(this) // 复用轨迹服务创建的通知渠道
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, ID, notif(), type)
        val text = intent?.getStringExtra("text") ?: "SOS"
        val steps = Morse.steps(text)
        if (steps.isEmpty()) { stopSelf(); return START_NOT_STICKY }
        val m = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cm = m; id = torchId(this)
        try { m.registerTorchCallback(cb, null) } catch (_: Exception) {}
        _failed.value = false; _active.value = true
        job?.cancel()
        // 关屏深睡时协程 delay 会被拖慢：信号运行期间持有带超时的部分唤醒锁，所有退出路径释放
        runCatching {
            wake?.takeIf { it.isHeld }?.release()
            wake = (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "ark:torch").also { it.acquire(2 * 60 * 60 * 1000L) }
        }
        job = scope.launch {
            try {
                while (true) for (s in steps) {
                    val ok = setTorch(s.on)
                    _lit.value = s.on
                    if (!ok) { _failed.value = true; stopSelf(); return@launch }
                    delay(s.units * Morse.UNIT_MS)
                }
            } finally { setTorch(false); _lit.value = false; runCatching { wake?.takeIf { it.isHeld }?.release() } }
        }
        return START_NOT_STICKY
    }

    private fun setTorch(on: Boolean): Boolean {
        val i = id ?: return false
        return try { cm?.setTorchMode(i, on); true } catch (_: Exception) { false }
    }

    override fun onTaskRemoved(rootIntent: Intent?) { stopSelf() }

    private var wake: android.os.PowerManager.WakeLock? = null

    override fun onDestroy() {
        job?.cancel()
        runCatching { wake?.takeIf { it.isHeld }?.release() }
        setTorch(false)
        try { cm?.unregisterTorchCallback(cb) } catch (_: Exception) {}
        scope.cancel()
        _active.value = false; _lit.value = false
        super.onDestroy()
    }

    private fun notif(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 5, Intent(this, TorchService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, TrackService.CH)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("方舟：闪光灯信号运行中")
            .setContentText("关屏也会继续闪；点“停止”结束")
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "停止", stop).build())
            .build()
    }

    companion object {
        const val ID = 47 // 通知 id，各前台服务各用不同值
        const val ACTION_STOP = "app.ark.TORCH_STOP"
        private val _active = MutableStateFlow(false)
        private val _lit = MutableStateFlow(false)
        private val _failed = MutableStateFlow(false)
        val active: StateFlow<Boolean> = _active
        val lit: StateFlow<Boolean> = _lit
        val failed: StateFlow<Boolean> = _failed
        fun clearFailed() { _failed.value = false }

        /** text：要闪的文字（"SOS" 或用户输入的字母数字）。 */
        fun start(ctx: Context, text: String) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, TorchService::class.java).putExtra("text", text))
        }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, TorchService::class.java)) }
    }
}
