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

    fun offer(l: Location) {
        val c = last.value
        if (c == null || l.time >= c.time - 5_000) last.value = l
    }

    @SuppressLint("MissingPermission")
    fun lastKnown(ctx: Context): Location? = runCatching {
        val lm = ctx.getSystemService(LocationManager::class.java)
        lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
    }.getOrNull()
}

/** 不用 AndroidX 的 LocationListener：在 API 26–29 上也不缺方法。 */
open class SimpleLocationListener(private val onLoc: (Location) -> Unit) : LocationListener {
    override fun onLocationChanged(location: Location) = onLoc(location)
    @Deprecated("old API") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}

/** 界面可见且 enabled 时每 3 秒一次定位（给“指向航点”用），离开界面或关闭即停止。 */
@SuppressLint("MissingPermission")
@Composable
fun rememberMyLocation(enabled: Boolean): State<Location?> {
    val ctx = LocalContext.current
    DisposableEffect(enabled) {
        val lm = ctx.getSystemService(LocationManager::class.java)
        val l = SimpleLocationListener { MyFix.offer(it) }
        if (enabled && Perms.granted(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION)) {
            MyFix.lastKnown(ctx)?.let { MyFix.offer(it) }
            runCatching {
                if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000L, 0f, l, Looper.getMainLooper())
                if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10000L, 0f, l, Looper.getMainLooper())
            }
        }
        onDispose { runCatching { lm.removeUpdates(l) } }
    }
    return MyFix.last.collectAsState()
}
