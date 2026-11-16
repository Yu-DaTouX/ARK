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
import kotlin.math.roundToInt

/** 校准值（海平面气压 hPa）与地图页共用：SharedPreferences "ark" 的 "baro_sea_level"。 */
private const val KEY = "baro_sea_level"

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
            val s = Baro.seaLevel(cur.hPa, alt)
            sea = s; prefs.edit().putFloat(KEY, s).apply()
        }

        if (p == null) Note("正在读取气压计……") else {
            Text("%.1f hPa".format(p.hPa), fontSize = 44.sp, fontWeight = FontWeight.Bold)
            val alt = Baro.altitude(p.hPa, sea ?: Baro.STD)
            Text("估算海拔 ${alt.roundToInt()} m", fontSize = 30.sp, fontWeight = FontWeight.Bold)
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
            BigButton("用输入的海拔校准", { input.toFloatOrNull()?.let { calibrate(it) } }, enabled = p != null && input.toFloatOrNull() != null)
            val l = place.location
            if (l != null && l.hasAltitude()) BigButton("用 GPS 最后位置的海拔（${l.altitude.roundToInt()} m）", { calibrate(l.altitude.toFloat()) }, outlined = true, enabled = p != null)
            else if (!place.permitted) BigButton("允许位置以使用 GPS 海拔", place.request, outlined = true)
            if (sea != null) BigButton("清除校准", { sea = null; prefs.edit().remove(KEY).apply() }, outlined = true)
        }

        Card2 {
            Text("气压趋势", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            val d = PressureLog.delta3h()
            if (d == null) Note("记录不足 1 小时，暂时无法判断趋势。") else {
                Text("近 3 小时 %+.1f hPa".format(d), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(Baro.trendText(d), fontSize = 17.sp)
            }
            Chart()
            Note("只在打开本页或地图页时记录（每 5 分钟一点，保留 6 小时）。想看趋势，请让手机放着打开本页几小时。")
        }
    }
}

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
