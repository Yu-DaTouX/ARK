package app.ark.ui.tools

import android.graphics.Paint
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ark.sensors.Heading
import app.ark.sensors.bearingName
import app.ark.sensors.rememberCompass
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun CompassTool(onBack: () -> Unit) {
    val place = rememberPlace()
    val loc = place.location
    val c = rememberCompass(loc?.latitude, loc?.longitude)
    var locked by rememberSaveable { mutableStateOf<Float?>(null) }
    val fg = MaterialTheme.colorScheme.onSurface
    val dim = MaterialTheme.colorScheme.outline
    val primary = MaterialTheme.colorScheme.primary
    val north = Color(0xFFE53935)

    ToolPage("指南针", onBack) {
        if (c == null) Note("正在读取传感器……（本机可能没有磁力计；磁场不干净又没校准时也没有读数，请用「天体定向」校准）")
        val h = c?.heading ?: 0f
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
            val r = size.minDimension / 2 * 0.86f
            val ctr = center
            drawCircle(dim, r, ctr, style = Stroke(3f))
            val tp = Paint().apply { color = fg.toArgb(); textAlign = Paint.Align.CENTER; textSize = r * 0.16f; isFakeBoldText = true; isAntiAlias = true }
            val np = Paint(tp).apply { color = north.toArgb() }
            rotate(-h, ctr) { // 表盘随手机转动
                for (a in 0 until 360 step 5) rotate(a.toFloat(), ctr) {
                    val len = if (a % 90 == 0) r * 0.12f else if (a % 45 == 0) r * 0.09f else if (a % 15 == 0) r * 0.06f else r * 0.03f
                    drawLine(if (a == 0) north else dim, Offset(ctr.x, ctr.y - r), Offset(ctr.x, ctr.y - r + len), if (a % 45 == 0) 6f else 3f)
                }
                listOf("北" to 0, "东" to 90, "南" to 180, "西" to 270).forEach { (t, a) ->
                    rotate(a.toFloat(), ctr) { drawIntoCanvas { it.nativeCanvas.drawText(t, ctr.x, ctr.y - r + r * 0.34f, if (a == 0) np else tp) } }
                }
                // 指北针：红头指北
                val needle = Path().apply { moveTo(ctr.x, ctr.y - r * 0.55f); lineTo(ctr.x - r * 0.07f, ctr.y); lineTo(ctr.x + r * 0.07f, ctr.y); close() }
                drawPath(needle, north)
                val tail = Path().apply { moveTo(ctr.x, ctr.y + r * 0.55f); lineTo(ctr.x - r * 0.07f, ctr.y); lineTo(ctr.x + r * 0.07f, ctr.y); close() }
                drawPath(tail, dim)
                locked?.let { l -> rotate(l, ctr) { // 锁定方位的标记
                    val m = Path().apply { moveTo(ctr.x, ctr.y - r - 4f); lineTo(ctr.x - r * 0.06f, ctr.y - r - r * 0.11f); lineTo(ctr.x + r * 0.06f, ctr.y - r - r * 0.11f); close() }
                    drawPath(m, primary)
                } }
            }
            // 固定的“手机朝向”标线
            val tri = Path().apply { moveTo(ctr.x, ctr.y - r - 2f); lineTo(ctr.x - r * 0.07f, ctr.y - r - r * 0.13f); lineTo(ctr.x + r * 0.07f, ctr.y - r - r * 0.13f); close() }
            drawPath(tri, fg)
            drawLine(fg, Offset(ctr.x, ctr.y - r), Offset(ctr.x, ctr.y - r * 0.8f), 5f)
        }
        if (c != null) {
            val deg = h.roundToInt() % 360
            Text("$deg°  ${bearingName(h)}", Modifier.fillMaxWidth(), fontSize = 40.sp, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text(
                if (c.magnetic) "磁北（未知位置，未修正磁偏角）" else "真北（已按位置修正磁偏角）",
                Modifier.fillMaxWidth(), fontSize = 16.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                color = if (c.magnetic) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val src = when (c.source) {
                Heading.Source.MAG -> "磁力计"
                Heading.Source.HOLD -> "磁干扰·陀螺保持"
                Heading.Source.CELESTIAL -> "天体校准（${c.celestialMin} 分钟前）"
                Heading.Source.NONE -> "未校准"
            }
            Text("方位来源：$src", Modifier.fillMaxWidth(), fontSize = 16.sp, fontWeight = FontWeight.Medium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            if (c.fieldUT > 0f) Text(
                "磁场强度 实测 %.0f µT / 当地理论 %s".format(c.fieldUT, if (c.expectedUT > 0f) "%.0f µT".format(c.expectedUT) else "未知（无位置）"),
                Modifier.fillMaxWidth(), fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (c.interference) Warn("附近有磁干扰（车辆、钢筋、磁吸壳），" + (if (c.source == Heading.Source.HOLD || c.source == Heading.Source.CELESTIAL) "方位由陀螺保持，" else "") + "可用「天体定向」重新校准。")
            else if (c.accuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW) Warn("地磁精度低：把手机在空中画 8 字校准，远离铁器和磁铁。")
            if (c.celestialMin >= Heading.CELESTIAL_HOLD_MS / 60_000L) Warn("天体校准已过 ${c.celestialMin} 分钟，陀螺会漂移，请重新用「天体定向」校准。")
            if (c.celestialMin >= 0) BigButton("清除天体校准", { Heading.clearCalibration() }, outlined = true)
        }
        if (c?.magnetic != false && !place.permitted) {
            Card2 {
                Note("允许使用位置后，可按所在地修正磁偏角（只读系统缓存的最后位置，不开 GPS）。")
                BigButton("允许使用位置", place.request, outlined = true)
            }
        } else if (c?.magnetic != false && loc == null) Note("系统里没有缓存的位置：先在地图页点一次“我的位置”，或到空旷处开一会儿 GPS。")

        Card2 {
            val l = locked
            if (l == null) {
                Note("锁定方位：先把手机朝着要走的方向，点下面按钮；走偏后这里会告诉你偏了多少度。")
                BigButton("锁定当前方位", { if (c != null) locked = c.heading }, enabled = c != null)
            } else {
                Text("已锁定 ${l.roundToInt() % 360}°（${bearingName(l)}）", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                var d = h - l; if (d > 180) d -= 360f; if (d < -180) d += 360f
                val dd = d.roundToInt()
                Text(
                    when { abs(dd) <= 3 -> "方向正确 ✔"; dd > 0 -> "向右偏了 $dd°，向左转 $dd°"; else -> "向左偏了 ${-dd}°，向右转 ${-dd}°" },
                    fontSize = 22.sp, fontWeight = FontWeight.Bold, color = if (abs(dd) <= 3) primary else MaterialTheme.colorScheme.error,
                )
                BigButton("取消锁定", { locked = null }, outlined = true)
            }
        }
        Note("手机请平放测量。附近有手机壳磁吸、车辆、钢筋会严重偏。")
    }
}
