package app.ark.share

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.ark.MainActivity
import app.ark.sensors.TrackService

/** 离线分享的前台服务：只在用户点“开启”时运行；点“关闭”、通知上点“关闭”、或 30 分钟无人访问时停止。 */
class ShareService : Service() {
    private var server: ShareServer? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        TrackService.channel(this)
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        try {
            ServiceCompat.startForeground(this, ID, notif(), type)
        } catch (e: Exception) { // 如 Android 15 dataSync 额度已用完：不要崩
            stopSelf(); return START_NOT_STICKY
        }
        if (server == null) {
            val s = ShareServer(this) { stopSelf() }
            try { s.start(); server = s } catch (e: Exception) { stopSelf() }
        }
        return START_NOT_STICKY
    }

    /** Android 15+：dataSync 前台服务达到系统时限，必须在几秒内停止，否则进程被异常终止。 */
    override fun onTimeout(startId: Int, fgsType: Int) {
        server?.stop(); server = null
        stopSelf()
    }

    override fun onDestroy() {
        server?.stop(); server = null
        super.onDestroy()
    }

    private fun notif(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 3, Intent(this, ShareService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, TrackService.CH)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("方舟：正在分享给附近的人")
            .setContentText("30 分钟无人访问会自动关闭")
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "关闭", stop).build())
            .build()
    }

    companion object {
        const val ID = 43
        const val ACTION_STOP = "app.ark.SHARE_STOP"
        fun start(ctx: Context) { ContextCompat.startForegroundService(ctx, Intent(ctx, ShareService::class.java)) }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, ShareService::class.java)) }
    }
}
