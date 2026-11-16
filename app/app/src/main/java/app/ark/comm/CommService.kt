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

    private val tick = object : Runnable {
        override fun run() {
            getSystemService(NotificationManager::class.java).notify(ID, notif())
            h.postDelayed(this, 15_000L)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        channel(this)
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, ID, notif(), type)
        if (!started) {
            started = true
            kept.value = true
            CommEngine.start(this, fromService = true)
            if (!CommEngine.running.value && CommEngine.error.value != null && CommEngine.missingPermissions(this).isNotEmpty()) { stopSelf(); return START_NOT_STICKY }
            h.postDelayed(tick, 15_000L)
        }
        return START_STICKY
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
        const val ID = 61
        const val CH = "ark_comm"
        const val ACTION_STOP = "app.ark.COMM_STOP"
        val kept = kotlinx.coroutines.flow.MutableStateFlow(false)

        fun channel(ctx: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = ctx.getSystemService(NotificationManager::class.java)
                if (nm.getNotificationChannel(CH) == null) nm.createNotificationChannel(NotificationChannel(CH, "蓝牙消息", NotificationManager.IMPORTANCE_LOW))
            }
        }

        fun start(ctx: Context) { ContextCompat.startForegroundService(ctx, Intent(ctx, CommService::class.java)) }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, CommService::class.java)) }
    }
}
