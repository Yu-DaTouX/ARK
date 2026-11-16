package app.ark.sensors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** 大圆距离（米）和起点到终点的真北方位（度，0–360）。 */
object Geo {
    fun distance(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val p1 = Math.toRadians(la1); val p2 = Math.toRadians(la2)
        val dp = p2 - p1; val dl = Math.toRadians(lo2 - lo1)
        val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 6371000.0 * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    fun bearing(la1: Double, lo1: Double, la2: Double, lo2: Double): Float {
        val p1 = Math.toRadians(la1); val p2 = Math.toRadians(la2); val dl = Math.toRadians(lo2 - lo1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return ((Math.toDegrees(atan2(y, x)) + 360) % 360).toFloat()
    }

    fun distText(m: Double) = if (m < 1000) "${Math.round(m)} m" else "%.1f km".format(m / 1000)

    /** 相对角（-180..180，右为正）→ “往左转 30°”。 */
    fun turnText(rel: Float): String {
        val a = abs(rel)
        return when {
            a < 10f -> "正前方，直走"
            a > 150f -> "目标在身后，向后转"
            rel < 0 -> "往左转 ${Math.round(a)}°"
            else -> "往右转 ${Math.round(a)}°"
        }
    }

    fun agoText(ms: Long): String {
        val m = ms / 60_000
        return when { m < 1 -> "刚刚"; m < 60 -> "${m} 分钟前"; m < 1440 -> "${m / 60} 小时前"; else -> "${m / 1440} 天前" }
    }
}

/** 各功能要申请的运行时权限。 */
object Perms {
    private val loc = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    private val notif = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()

    fun track(): Array<String> = loc + notif
    fun beacon(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) loc + notif + arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        else loc + notif

    fun granted(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    /** 必需权限是否齐了（通知权限、粗略定位不是必需的：没有通知权限服务照常运行）。 */
    fun ready(ctx: Context, list: Array<String>) =
        list.filter { it != Manifest.permission.POST_NOTIFICATIONS && it != Manifest.permission.ACCESS_COARSE_LOCATION }.all { granted(ctx, it) }
}
