// 文件职责：轨迹记录前台服务，把 GPS 点写入 data/TrackStore。调用方：地图页的轨迹开关。
// 前台服务类型 location（API 29+ 才传 type）；调用前需要 ACCESS_FINE_LOCATION，没有则立即退出。
// 这里的通知渠道 ark_track（“后台任务”）也被 BeaconService、ShareService 复用。
package app.ark.sensors

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import app.ark.MainActivity
import app.ark.data.TrackPoint
import app.ark.data.TrackStore

/**
 * 轨迹记录（前台服务，必须带通知）。省电做法：只用 GPS，最短 45 秒、至少移动 8 米才回调一次，
 * 站着不动不会唤醒定位；精度差于 100 米的点丢掉。关掉就 removeUpdates，不留后台定位。
 */
class TrackService : Service() {
    private var lm: LocationManager? = null
    private var listener: SimpleLocationListener? = null

    override fun onBind(i: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        TrackStore.init(this)
        try {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) { stopSelf(); return START_NOT_STICKY }
            startFg(TrackStore.points.value.size)
        } catch (e: Exception) { // 后台启动受限 / 类型前提不满足
            stopSelf(); return START_NOT_STICKY
        }
        if (listener == null) {
            val l = SimpleLocationListener { loc ->
                MyFix.offer(loc)
                if (!loc.hasAccuracy() || loc.accuracy <= 100f) {
                    TrackStore.add(TrackPoint(loc.time, loc.latitude, loc.longitude, if (loc.hasAltitude()) loc.altitude else null))
                    getSystemService(NotificationManager::class.java).notify(ID, notif(TrackStore.points.value.size))
                }
            }
            val m = getSystemService(LocationManager::class.java)
            try {
                m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 45_000L, 8f, l, Looper.getMainLooper())
                lm = m; listener = l
                TrackStore.recording.value = true
            } catch (e: SecurityException) { stopSelf() } catch (e: IllegalArgumentException) { stopSelf() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        listener?.let { runCatching { lm?.removeUpdates(it) } }
        listener = null
        TrackStore.recording.value = false
        super.onDestroy()
    }

    private fun startFg(n: Int) {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, ID, notif(n), type)
    }

    private fun notif(n: Int): Notification {
        channel(this)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, TrackService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("方舟：正在记录轨迹")
            .setContentText("已记 $n 个点（约每 45 秒一次，GPS）")
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "停止", stop).build())
            .build()
    }

    companion object {
        const val ID = 41 // 通知 ID，见 BeaconService.ID 的编号说明
        const val CH = "ark_track"
        const val ACTION_STOP = "app.ark.TRACK_STOP"

        /** 创建“后台任务”通知渠道（低重要性），幂等；本服务、信标、分享服务共用。注意：没做 API 26 版本判断，靠 minSdk ≥ 26。 */
        fun channel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CH) == null) nm.createNotificationChannel(NotificationChannel(CH, "后台任务", NotificationManager.IMPORTANCE_LOW))
        }

        /** 开始记录轨迹（前台服务，持续 GPS，耗电）。 */
        fun start(ctx: Context) { androidx.core.content.ContextCompat.startForegroundService(ctx, Intent(ctx, TrackService::class.java)) }
        /** 停止记录并注销定位。 */
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, TrackService::class.java)) }
    }
}
