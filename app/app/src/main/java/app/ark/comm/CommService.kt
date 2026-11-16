// 文件职责：“保持在线”前台服务，让 CommEngine 在后台继续广播/低占空比扫描/接收，并用通知展示状态。
// 调用方：通讯界面的“保持在线”开关、通知上的“关闭”按钮；依赖 CommEngine、MainActivity。
// 前台服务类型 connectedDevice（API 29+ 才传 type）：因为是在与外部蓝牙设备交互；需要相应蓝牙权限（Android 14 起系统会校验）。
// 通知渠道与暂停通知刷新的设计：只在邻居数/新消息数变化时才重发，省电。
package app.ark.comm

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.ark.MainActivity

/**
 * “保持在线”：前台服务（connectedDevice），引擎在后台低占空比运行（每 30 秒扫 5 秒，发现新邻居时临时加密）。
 * 关掉（通知上的“关闭”或界面开关）就完全停止：广播、扫描、GATT 服务全部释放。
 */
@SuppressLint("MissingPermission")
class CommService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private var started = false

    override fun onBind(i: Intent?): IBinder? = null

    private var shown = -1L // 上次通知里的（邻居数, 新消息数），没变就不重发通知（每次 notify 都要跨进程唤醒 system_server / SystemUI）

    private val tick = object : Runnable {
        override fun run() {
            val k = (CommEngine.neighbors.value.size.toLong() shl 32) or CommEngine.incoming.value.toLong()
            if (k != shown) { shown = k; getSystemService(NotificationManager::class.java).notify(ID, notif()) }
            h.postDelayed(this, 15_000L)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        channel(this)
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        try {
            ServiceCompat.startForeground(this, ID, notif(), type)
        } catch (e: Exception) { // 缺蓝牙权限 / 系统拒绝后台启动前台服务：不要崩，直接退出
            CommEngine.error.value = "无法在后台保持在线：${e.message}"
            stopSelf(); return START_NOT_STICKY
        }
        if (!started) {
            started = true
            kept.value = true
            CommEngine.start(this, fromService = true)
            if (!CommEngine.running.value && CommEngine.error.value != null && CommEngine.missingPermissions(this).isNotEmpty()) { stopSelf(); return START_NOT_STICKY }
            h.postDelayed(tick, 15_000L)
        }
        return START_STICKY // 被系统杀掉后尝试重建（重建时 intent 为 null）
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        CommEngine.release(fromService = true)
        kept.value = false
        super.onDestroy()
    }

    private fun notif(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 7, Intent(this, CommService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = CommEngine.neighbors.value.size
        val inc = CommEngine.incoming.value
        return Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("方舟：蓝牙消息保持在线")
            .setContentText("附近 $n 台" + (if (inc > 0) "，收到 $inc 条新消息" else "") + "；每 30 秒扫描 5 秒")
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "关闭", stop).build())
            .build()
    }

    companion object {
        const val ID = 61 // 通知 ID；警报服务用 62，避免冲突
        const val CH = "ark_comm"
        const val ACTION_STOP = "app.ark.COMM_STOP"
        val kept = kotlinx.coroutines.flow.MutableStateFlow(false)

        /** 创建“蓝牙消息”通知渠道（API 26+，低重要性无声）；警报服务也复用它。幂等。 */
        fun channel(ctx: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = ctx.getSystemService(NotificationManager::class.java)
                if (nm.getNotificationChannel(CH) == null) nm.createNotificationChannel(NotificationChannel(CH, "蓝牙消息", NotificationManager.IMPORTANCE_LOW))
            }
        }

        /** 启动前台服务（开蓝牙广播/扫描，持续耗电）。Android 12+ 后台启动可能被系统拒绝，此时服务自己会退出并写 CommEngine.error。 */
        fun start(ctx: Context) { ContextCompat.startForegroundService(ctx, Intent(ctx, CommService::class.java)) }
        /** 停止服务，释放引擎在后台的那一路。 */
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, CommService::class.java)) }
    }
}
