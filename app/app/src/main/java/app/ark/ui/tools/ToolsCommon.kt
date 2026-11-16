/*
 * ToolsCommon.kt —— 各工具共用的页面骨架（ToolPage）、大按钮、提示条，以及位置缓存、时钟可信度检查。
 * 设计：工具页要在紧急、戴手套、低电量时用——按钮和字号偏大；定位只读系统缓存（lastLocation），
 * 不主动开 GPS 以省电；手机时钟可能因断电重启而错（天体/日出日落依赖时间），所以提供 ClockWarning。
 */
package app.ark.ui.tools

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ark.ui.ArkIcons

/** 工具页自己的小设置（止血带起始时间、手动经纬度、锁定方位等）。气压校准用主 prefs "ark" 的 "baro_sea_level"（和地图页共用）。 */
// 工具页专用偏好文件 "ark_tools"
fun Context.toolPrefs() = getSharedPreferences("ark_tools", Context.MODE_PRIVATE)
fun Context.arkPrefs() = getSharedPreferences("ark", Context.MODE_PRIVATE)

/** 精确或粗略定位权限有一个即可 */
fun hasLocationPermission(ctx: Context) =
    ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

/** 系统缓存的最近一次位置（不主动开 GPS，省电）。没有权限或没有缓存返回 null。 */
@SuppressLint("MissingPermission")
fun lastLocation(ctx: Context): Location? {
    if (!hasLocationPermission(ctx)) return null
    val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } }
        .maxByOrNull { it.time }
}

/** 位置状态：location 缓存位置（可能为 null）、permitted 是否已授权、request() 弹出授权请求（授权后自动刷新） */
class Place(val location: Location?, val permitted: Boolean, val request: () -> Unit)

/** 在 Composable 里获取 [Place]；授权对话框返回后重新读取缓存位置 */
@Composable
fun rememberPlace(): Place {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    val loc = remember(tick) { lastLocation(ctx) }
    val ok = remember(tick) { hasLocationPermission(ctx) }
    return Place(loc, ok) {
        launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
}

/** 带返回键和标题的可滚动工具页 */
@Composable
fun ToolPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(56.dp)) { Icon(ArkIcons.ChevronBack, "返回", Modifier.size(32.dp)) }
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

/** 大号按钮（最小高 64dp）；danger=红色，outlined=描边样式 */
@Composable
fun BigButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, danger: Boolean = false, outlined: Boolean = false, enabled: Boolean = true) {
    val m = modifier.fillMaxWidth().heightIn(min = 64.dp)
    val label: @Composable () -> Unit = { Text(text, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
    when {
        outlined -> OutlinedButton(onClick, m, enabled = enabled, content = { label() })
        else -> Button(
            onClick, m, enabled = enabled,
            colors = if (danger) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError) else ButtonDefaults.buttonColors(),
            content = { label() },
        )
    }
}

@Composable
fun Note(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) =
    Text(text, fontSize = 14.sp, lineHeight = 20.sp, color = color)

@Composable
fun Warn(text: String) = Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
    Text(text, Modifier.padding(14.dp).fillMaxWidth(), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
}

@Composable
fun Card2(content: @Composable ColumnScope.() -> Unit) = Surface(
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = MaterialTheme.shapes.large,
) { Column(Modifier.padding(14.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp), content = content) }

/** 毫秒 → "mm:ss"（≥1 小时为 "h:mm:ss"），负数按 0 */
fun fmtDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600; val m = s % 3600 / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}


/** 位置缓存的时效，例如“3 天前”。 */
fun locationAge(loc: Location): String {
    val m = ((System.currentTimeMillis() - loc.time) / 60_000L).coerceAtLeast(0)
    return when { m < 2 -> "刚刚"; m < 60 -> "$m 分钟前"; m < 48 * 60 -> "${m / 60} 小时前"; else -> "${m / 1440} 天前" }
}

/** 构建日期：手机时钟早于它说明时间设置不对（电池耗尽后重启、没有网络授时等）。 */
// 注意：这是写死的日期，发布新版本时可考虑更新；手机时钟早于它就当作“时间不对”
private val BUILD_MS = java.time.Instant.parse("2026-11-01T00:00:00Z").toEpochMilli()

fun clockSuspect(): Boolean = System.currentTimeMillis() < BUILD_MS

/** 手机时间明显不对时的醒目警告，并显示完整日期时间；时间正常时什么都不画。 */
@Composable
fun ClockWarning() {
    if (!clockSuspect()) return
    val t = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
    Warn("手机时间可能不准（现在显示 $t），天体和日出日落数据会全部错误。请先在系统设置里校对日期和时间。")
}

/** 缓存位置过旧（超过 24 小时）时的提醒。 */
@Composable
fun LocationAgeNote(loc: Location?) {
    if (loc == null) return
    val old = System.currentTimeMillis() - loc.time > 24 * 3600_000L
    if (old) Warn("这是 ${locationAge(loc)} 的位置缓存，如果你已经移动，请手动输入当前经纬度。") else Note("位置缓存：${locationAge(loc)}")
}
