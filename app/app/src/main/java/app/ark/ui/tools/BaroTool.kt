/*
 * BaroTool.kt —— “气压 / 海拔”工具页：气压计读数、用已知海拔或 GPS 海拔校准海平面气压、3 小时趋势和 Zambretti 简易预报。
 * 气压数据和历史记录来自 sensors（rememberBarometer、PressureLog、Baro）。校准值写 prefs "ark" 的 baro_sea_level，与地图页共用；
 * 趋势判断前先用 checkMoved 排除“人在移动”（移动会把气压变化误当天气）。
 */
package app.ark.ui.tools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ark.sensors.Baro
import app.ark.sensors.PressureLog
import app.ark.sensors.hasBarometer
import app.ark.sensors.rememberBarometer
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/** 校准值（海平面气压 hPa）与地图页共用：SharedPreferences "ark" 的 "baro_sea_level"。 */
private const val KEY = "baro_sea_level"

/** 气压 / 海拔页（没有气压计的手机只显示提示） */
@Composable
fun BaroTool(onBack: () -> Unit) {
    val ctx = LocalContext.current
    ToolPage("气压 / 海拔", onBack) {
        if (!hasBarometer(ctx)) { Warn("本机没有气压计，无法测气压和海拔。"); return@ToolPage }
        val prefs = remember { ctx.arkPrefs() }
        val p = rememberBarometer()
        val place = rememberPlace()
        var sea by remember { mutableStateOf(prefs.getFloat(KEY, 0f).takeIf { it > 800f && it < 1200f }) }
        var input by remember { mutableStateOf("") }
        fun calibrate(alt: Float) {
            val cur = p ?: return
            if (!alt.isFinite() || alt < -500f || alt > 10000f) return // 合理海拔范围
            val s = Baro.seaLevel(cur.hPa, alt)
            if (!s.isFinite() || s < 800f || s > 1200f) return
            sea = s; prefs.edit().putFloat(KEY, s).apply()
            // 记下校准时的海拔，用来把之后的气压换算成海平面气压（Zambretti 要用）；地图页改了校准值则这里失效
            ctx.toolPrefs().edit().putFloat("baro_cal_alt", alt).putFloat("baro_cal_sea", s).putLong("baro_cal_at", System.currentTimeMillis()).apply()
        }

        if (p == null) Note("正在读取气压计……") else {
            Text("%.1f hPa".format(p.hPa), fontSize = 44.sp, fontWeight = FontWeight.Bold)
            val alt = Baro.altitude(p.hPa, sea ?: Baro.STD)
            Text(if (alt.isFinite()) "估算海拔 ${alt.roundToInt()} m" else "估算海拔 --", fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Note(if (sea == null) "未校准（按标准大气压 1013.25 hPa 估算，天气不同可能差上百米）" else "已校准：海平面气压 %.1f hPa。天气变化后需重新校准（约 1 hPa ≈ 8 m）".format(sea))
        }

        Card2 {
            Text("用已知海拔校准", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                input, { input = it.filter { c -> c.isDigit() || c == '.' || c == '-' }.take(7) }, Modifier.fillMaxWidth(),
                label = { Text("当前所在海拔（米）") }, singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontSize = 20.sp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            BigButton("用输入的海拔校准", { input.toFloatOrNull()?.let { calibrate(it) } }, enabled = p != null && input.toFloatOrNull()?.let { it.isFinite() && it >= -500f && it <= 10000f } == true)
            val l = place.location
            if (l != null && l.hasAltitude() && l.altitude.isFinite() && l.altitude in -500.0..10000.0) BigButton("用 GPS 最后位置的海拔（${l.altitude.roundToInt()} m）", { calibrate(l.altitude.toFloat()) }, outlined = true, enabled = p != null)
            else if (!place.permitted) BigButton("允许位置以使用 GPS 海拔", place.request, outlined = true)
            if (sea != null) BigButton("清除校准", { sea = null; prefs.edit().remove(KEY).apply() }, outlined = true)
        }

        Card2 {
            Text("气压趋势", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            val moved = remember(place.location) { checkMoved(ctx, place.location) }
            Note("气压趋势和预报只在原地不动时有效：走动、上下坡、乘车会让气压变化被误当成天气。")
            if (moved) Warn("检测到你的位置或海拔有明显变化（移动超过约 200 m 或海拔变化超过 30 m），近 3 小时的气压趋势不可信，暂不给天气结论。原地待满 3 小时后再看。")
            else {
                val d = PressureLog.delta3h()
                if (d == null) Note("记录不足 1 小时，暂时无法判断趋势。") else {
                    Text("近 3 小时 %+.1f hPa".format(d), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(Baro.trendText(d), fontSize = 17.sp)
                }
                ZambrettiCard(p?.hPa, d, sea, place.location?.latitude)
                if (place.location == null) Note("没有位置信息，无法确认你有没有移动；请确保这段时间人在原地。")
            }
            Chart()
            Note("只在打开本页或地图页时记录（每 5 分钟一点，保留 6 小时）。想看趋势，请让手机放着打开本页几小时。")
        }
    }
}

// 6 小时气压折线图：纵轴自动取范围（至少 2 hPa 跨度，避免微小抖动被放大成大起伏）
@Composable
private fun Chart() {
    val pts = PressureLog.all()
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    if (pts.size < 2) return
    val lo = pts.minOf { it.hPa }; val hi = pts.maxOf { it.hPa }
    val mid = (lo + hi) / 2; val span = maxOf(hi - lo, 2f)
    val t0 = pts.first().time; val t1 = pts.last().time
    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        for (i in 0..2) drawLine(grid, Offset(0f, size.height * i / 2), Offset(size.width, size.height * i / 2), 1f)
        val path = Path()
        pts.forEachIndexed { i, q ->
            val x = size.width * (q.time - t0) / (t1 - t0).toFloat()
            val y = size.height * (0.5f - (q.hPa - mid) / span * 0.9f)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, line, style = Stroke(5f))
    }
    Note("纵向范围约 %.1f – %.1f hPa，横向 %d 分钟".format(mid - span / 2, mid + span / 2, (t1 - t0) / 60000))
}

/** Zambretti 式简易预报，见 Zambretti.kt。 */
@Composable
private fun ZambrettiCard(stationHPa: Float?, delta3h: Float?, sea: Float?, lat: Double?) {
    val ctx = LocalContext.current
    if (delta3h == null) return
    val tp = ctx.toolPrefs()
    val calAlt = tp.getFloat("baro_cal_alt", Float.NaN); val calSea = tp.getFloat("baro_cal_sea", Float.NaN)
    // 只有校准值就是本页写入的，才知道当前海拔，才能换算海平面气压
    val slp = if (stationHPa != null && sea != null && !calAlt.isNaN() && abs(calSea - sea) < 0.01f)
        (stationHPa / (1 - calAlt / 44330.0).pow(5.255)).toFloat() else null
    val month = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH) + 1
    val r = Zambretti.forecast(slp, delta3h, month, lat != null && lat < 0)
    Text("简易预报：" + r.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    Note(r.season)
    Note((if (slp == null) "没有可用的海拔校准，只按气压趋势判断；用已知海拔校准后会加上气压高低。" else "海平面气压约 %.0f hPa。".format(slp)) +
        (if (lat == null) "位置未知，按北半球季节。" else "") + "采用 Zambretti 预报器的思路做的简化版，仅供参考，局地天气可能完全不同。")
}

/**
 * 用系统缓存的位置判断记录期间有没有移动：锚点（首次或 6 小时后重置）与之后更新的定位比较，
 * 距离 > 200 m 或 GPS 海拔变化 > 30 m 就记为“移动”，之后 3 小时内不给天气结论。
 */
private fun checkMoved(ctx: android.content.Context, loc: android.location.Location?): Boolean {
    val tp = ctx.toolPrefs()
    val now = System.currentTimeMillis()
    val anchorT = tp.getLong("baro_anchor_t", 0L)
    fun setAnchor(l: android.location.Location) = tp.edit().putLong("baro_anchor_t", now)
        .putFloat("baro_anchor_lat", l.latitude.toFloat()).putFloat("baro_anchor_lon", l.longitude.toFloat())
        .putFloat("baro_anchor_alt", if (l.hasAltitude()) l.altitude.toFloat() else Float.NaN).apply()
    if (loc != null) {
        if (anchorT == 0L || now - anchorT > 6 * 3600_000L) setAnchor(loc)
        else if (loc.time > anchorT) {
            val a = android.location.Location("anchor").apply { latitude = tp.getFloat("baro_anchor_lat", 0f).toDouble(); longitude = tp.getFloat("baro_anchor_lon", 0f).toDouble() }
            val altA = tp.getFloat("baro_anchor_alt", Float.NaN)
            val dAlt = if (loc.hasAltitude() && !altA.isNaN()) abs(loc.altitude.toFloat() - altA) else 0f
            if (loc.distanceTo(a) > 200f || dAlt > 30f) { tp.edit().putLong("baro_moved_t", loc.time).apply(); setAnchor(loc) }
        }
    }
    val movedT = tp.getLong("baro_moved_t", 0L)
    return movedT > 0 && now - movedT < 3 * 3600_000L
}
