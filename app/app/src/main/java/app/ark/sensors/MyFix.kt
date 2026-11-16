// 文件职责：“最近一次定位”的全局共享点（MyFix）、一个简化的 LocationListener、界面可见时的定位 Composable。
// 调用方：TrackService / BeaconService / 地图页往里报；comm/CommEngine 发带位置消息时读。
// 耗电约定：界面端定位只在页面可见（STARTED）时注册。
package app.ark.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow

/** 最近一次定位（轨迹服务、信标服务、界面都往这里报，谁先拿到都能共用）。 */
object MyFix {
    val last = MutableStateFlow<Location?>(null)

    /** 报告一个定位：不比当前最新的早超过 5 秒才采纳（允许 GPS 与网络定位交替时小幅乱序）。任意线程。 */
    fun offer(l: Location) {
        val c = last.value
        if (c == null || l.time >= c.time - 5_000) last.value = l
    }

    /** 定位已经过去多久（毫秒，按开机后单调时钟）；时间戳无效时返回 Long.MAX_VALUE */
    fun ageMs(l: Location): Long {
        val ns = l.elapsedRealtimeNanos
        if (ns <= 0L) return Long.MAX_VALUE
        return ((android.os.SystemClock.elapsedRealtimeNanos() - ns) / 1_000_000L).coerceAtLeast(0L)
    }

    /** 取各提供者的系统缓存定位中最新的一个（不触发定位）。无权限/异常返回 null。 */
    @SuppressLint("MissingPermission")
    fun lastKnown(ctx: Context): Location? = runCatching {
        val lm = ctx.getSystemService(LocationManager::class.java)
        lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
    }.getOrNull()
}

/** 不用 AndroidX 的 LocationListener：在 API 26–29 上也不缺方法。 */
// 注意：在旧版系统里 LocationListener 要求实现 onStatusChanged，所以这里保留空实现。
open class SimpleLocationListener(private val onLoc: (Location) -> Unit) : LocationListener {
    override fun onLocationChanged(location: Location) = onLoc(location)
    @Deprecated("old API") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}

/**
 * 界面可见且 enabled 时 GPS 每 3 秒一次、网络定位每 10 秒一次（给“指向航点”用），离开界面或关闭即停止。
 * 需要精确定位权限，没有则不注册；返回 MyFix.last 的可观察状态（这里不保证是刚刚的定位）。
 */
@SuppressLint("MissingPermission")
@Composable
fun rememberMyLocation(enabled: Boolean): State<Location?> {
    val ctx = LocalContext.current
    // 跟随生命周期：退到后台 / 锁屏（ON_STOP）就停 GPS，回到前台再开。
    // 原来只在离开页面时停；轨迹 / 信标前台服务在跑时应用不受后台定位限流，锁屏后 GPS 会一直按 3 秒一次跑。
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    // 权限在页面打开后才授予：把授权状态作为 key，授权后重新注册（MapScreen 在授权回调里会重组）
    val granted = Perms.granted(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION)
    DisposableEffect(enabled, owner, granted) {
        val lm = ctx.getSystemService(LocationManager::class.java)
        val l = SimpleLocationListener { MyFix.offer(it) }
        var on = false
        fun up() {
            if (on || !enabled || !Perms.granted(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION)) return
            on = true
            MyFix.lastKnown(ctx)?.let { MyFix.offer(it) }
            runCatching {
                // 不判断 isProviderEnabled：提供者当前关着也先注册，用户之后打开定位开关就会自动开始出点
                runCatching { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000L, 0f, l, Looper.getMainLooper()) }
                runCatching { lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10000L, 0f, l, Looper.getMainLooper()) }
            }
        }
        fun down() { if (on) { on = false; runCatching { lm.removeUpdates(l) } } }
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_START) up() else if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) down()
        }
        if (owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) up()
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs); down() }
    }
    return MyFix.last.collectAsState()
}
