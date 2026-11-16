package app.ark.ui.tools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.TextButton
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ark.sensors.Heading
import app.ark.sensors.bearingName
import app.ark.sensors.rememberCompass
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.*

/** 天体定向：用太阳、月亮、亮星的已知方位校准陀螺方位，不受磁干扰。算法见 Astro.kt。 */
@Composable
fun AstroTool(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.toolPrefs() }
    val place = rememberPlace()
    var latS by rememberSaveable { mutableStateOf(prefs.getString("sun_lat", "") ?: "") } // 与日出日落页共用手动位置
    var lonS by rememberSaveable { mutableStateOf(prefs.getString("sun_lon", "") ?: "") }
    var useManual by rememberSaveable { mutableStateOf(false) }
    val gps = place.location
    val mLat = latS.toDoubleOrNull()?.takeIf { it in -90.0..90.0 }
    val mLon = lonS.toDoubleOrNull()?.takeIf { it in -180.0..180.0 }
    val pos: Pair<Double, Double>? = if (!useManual && gps != null) gps.latitude to gps.longitude
        else if (mLat != null && mLon != null) mLat to mLon else null

    val compass = rememberCompass(pos?.first, pos?.second) // 同时启动 / 停止 Heading 引擎
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val saving = LocalPowerSave.current
    LaunchedEffect(saving) { while (true) { nowMs = System.currentTimeMillis(); delay(if (saving) 20_000 else 5000) } }
    val pointing by produceState<Heading.Pointing?>(null) { while (true) { value = Heading.pointing.value; delay(150) } }

    var selected by rememberSaveable { mutableStateOf("") }
    var northUp by rememberSaveable { mutableStateOf(true) }
    var result by remember { mutableStateOf("") }

    val bodies = remember(pos, nowMs / 5000) { pos?.let { Astro.all(nowMs, it.first, it.second) } ?: emptyList() }
    val visible = bodies.filter { it.alt > 5.0 && (it.kind != Astro.Kind.STAR || it.mag <= 2.5f) }
        .sortedWith(compareBy({ if (it.id == "polaris" && (pos?.first ?: 0.0) > 0) 0 else 1 }, { it.mag }))
    val sel = bodies.firstOrNull { it.id == selected }

    ToolPage("天体定向", onBack) {
        ClockWarning()
        if (!useManual) LocationAgeNote(gps)
        Note("用太阳、月亮、星星已知的方位校准手机方向，不受磁干扰（车辆、钢筋、磁吸壳）。需要手机有陀螺仪，且时间准确。")
        if (pos == null) {
            Warn("必须先有大概位置才能算出天体在哪：允许使用位置，或在下面手动输入经纬度。")
        } else {
            Note("位置 %.2f°, %.2f°（%s）· UTC %s。位置差 1° 的纬度或经度，只带来约 1° 的方位误差。".format(
                pos.first, pos.second, if (!useManual && gps != null) "GPS 缓存" else "手动",
                DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(Instant.ofEpochMilli(nowMs).atZone(ZoneId.of("UTC")))))
        }
        // 有位置时把手动输入收起来，星图放到第一屏
        var editPos by rememberSaveable { mutableStateOf(false) }
        if (pos != null && !editPos) TextButton({ editPos = true }) { Text("位置不对？手动输入经纬度") }
        else Card2 {
            Text("手动输入位置", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            val kb = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            OutlinedTextField(latS, { latS = it.filter { c -> c.isDigit() || c == '.' || c == '-' }.take(10) }, Modifier.fillMaxWidth(), label = { Text("纬度（北正南负）") }, singleLine = true, keyboardOptions = kb)
            OutlinedTextField(lonS, { lonS = it.filter { c -> c.isDigit() || c == '.' || c == '-' }.take(11) }, Modifier.fillMaxWidth(), label = { Text("经度（东正西负）") }, singleLine = true, keyboardOptions = kb)
            BigButton("使用手动位置", { useManual = true; editPos = false; prefs.edit().putString("sun_lat", latS).putString("sun_lon", lonS).apply() }, enabled = mLat != null && mLon != null)
            if (gps != null) BigButton("改用 GPS 缓存位置", { useManual = false; editPos = false }, outlined = true)
            else if (!place.permitted) BigButton("允许使用位置", place.request, outlined = true)
        }
        if (pos == null) return@ToolPage
        val lat = pos.first

        val gyro = Heading.hasGyro
        if (!gyro) Warn("本机没有陀螺仪，不能天体校准。下面的星图和天体位置仍可参考，用肉眼对着真实的天体判断方向。")
        var camOpen by rememberSaveable { mutableStateOf(false) }
        BigButton("用相机对准", { camOpen = true }, enabled = gyro)
        Note("打开相机取景，把天体放到十字准星中心再点“对准了”，比对着星图更精准。")
        if (camOpen && gyro) AstroCameraDialog(pos.first, pos.second) { camOpen = false }

        // ---- 星图 ----
        val aimAz: Float? = pointing?.azimuth ?: compass?.heading
        val aimEl = pointing?.elevation
        val calibrated = compass?.source == Heading.Source.CELESTIAL
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(!northUp, { northUp = !it }); Spacer(Modifier.width(10.dp))
            Text(if (northUp) "星图：正北在上（点开关改为手机朝向在上）" else "星图：手机朝向在上", fontSize = 15.sp)
        }
        SkyChart(bodies, lat, if (northUp) 0f else (aimAz ?: 0f), aimAz, aimEl, calibrated, selected) { selected = it }
        Note("圆心是头顶，圆周是地平线，和面朝上方方向时所见一致（右手边在右）。点星可选中；绿色十字是手机顶边正指的位置" + (if (!calibrated) "（未校准，方向来自${if (pointing?.azimuth != null) "当前方位引擎" else "磁力计"}，可能有偏差）。" else "（已用天体校准）。"))

        // ---- 校准 ----
        Card2 {
            Text("校准", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            if (sel == null || sel.alt <= 5.0) {
                Note("先在下方列表或星图上选一个天体（太阳白天最容易；夜里选月亮或亮星，高度 10°–60° 最好）。")
            } else {
                Text(sel.name, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("方位 %.0f° %s · 高度 %.0f°".format(sel.az, bearingName(sel.az.toFloat()), sel.alt), fontSize = 18.sp)
                if (sel.kind == Astro.Kind.SUN) Warn("相机可以短时间对着太阳取景，但人眼不要直视太阳；用屏幕看。太阳请点上面的“用相机对准”，不要用下面的顶边瞄准。")
                else Text("把手机顶边对准它，像瞄准一样沿手机长边看过去（单眼，让天体正好落在顶边上方）。稳住后点“对准了”。", fontSize = 16.sp, lineHeight = 22.sp)
                val el = pointing?.elevation
                if (!gyro) Unit else if (sel.kind == Astro.Kind.SUN) Unit else if (el == null) Note("等待陀螺仪读数……（没有陀螺仪的手机不能校准）") else {
                    val dEl = el - sel.alt.toFloat()
                    Text("手机顶边仰角 %.0f°，目标高度 %.0f°，差 %+.0f°".format(el, sel.alt, dEl), fontSize = 17.sp, fontWeight = FontWeight.Medium,
                        color = if (abs(dEl) > 5f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    if (abs(dEl) > 5f) Warn(if (dEl > 0) "仰角差超过 5°：手机抬得太高，请放低一些" else "仰角差超过 5°：手机抬得太低，请抬高一些")
                    if (sel.alt > 70.0) Warn("目标太接近头顶（> 70°），方位很难对准，换一个低一点的天体。")
                    BigButton("对准了", {
                        val before = pointing?.azimuth
                        if (Heading.calibrate(sel.az.toFloat())) {
                            result = "校准成功：以 ${sel.name} 为准，方位 %.0f°。".format(sel.az) + (before?.let {
                                val d = Heading.wrapSigned(sel.az.toFloat() - it)
                                "校准前方位偏差 %.1f°（%s）。".format(abs(d), if (abs(d) < 5) "磁干扰小" else if (abs(d) < 15) "有一定偏差" else "偏差很大，之前的磁方位不可信")
                            } ?: "")
                        } else result = "校准失败：本机没有陀螺仪或还没有读数。"
                    }, enabled = abs(dEl) <= 5f && sel.alt <= 70.0)
                }
                if (result.isNotEmpty()) Text(result, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            compass?.let { c ->
                if (c.celestialMin >= 0) Note(if (c.source == Heading.Source.CELESTIAL) "当前方位来自天体校准（${c.celestialMin} 分钟前），30 分钟内有效。" else "天体校准已过 ${c.celestialMin} 分钟，建议重新校准。")
            }
        }

        // ---- 列表 ----
        Text("现在天上可用（高度 > 5°）", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (lat > 0) Note("北极星几乎就在正北，高度 ≈ 当地纬度（${"%.0f".format(lat)}°），最适合找北；用北斗七星“天璇→天枢”连线向外延长约 5 倍可找到它，或用仙后座 W 开口的中间。")
        else Note("南半球没有北极星。南十字座找南：沿十字长轴，从十字架一（Gacrux）经十字架二（Acrux）的方向延长约 4.5 倍的长度，再垂直落到地平线就是正南；也可作南门二、马腹一连线的中垂线，与长轴延长线相交处即南天极方向。")
        if (visible.isEmpty()) Note("现在没有高度超过 5° 的亮天体（天亮前后或阴天）。")
        visible.forEach { b ->
            val on = b.id == selected
            Surface(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { selected = b.id },
                color = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = MaterialTheme.shapes.medium,
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(b.name, Modifier.weight(1f), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Column(horizontalAlignment = Alignment.End) {
                        Text("%.0f° %s".format(b.az, bearingName(b.az.toFloat())), fontSize = 17.sp)
                        Text("高 %.0f°".format(b.alt), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Note("星表 J2000 坐标 + 岁差 + 大气折射；月亮 Meeus 第 47 章；太阳 NOAA。与 USNO 天文导航数据对比，恒星误差 < 0.05°。夜里请用红光或调暗屏幕。")
    }
}

/** 地平坐标圆形星图。顶部 = rot 方位；中心天顶，圆周地平线（等距投影）。 */
@Composable
private fun SkyChart(
    bodies: List<Astro.Body>, lat: Double, rot: Float, aimAz: Float?, aimEl: Float?, calibrated: Boolean,
    selected: String, onPick: (String) -> Unit,
) {
    val fg = MaterialTheme.colorScheme.onSurface
    val dim = MaterialTheme.colorScheme.outline
    val bg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    val accent = MaterialTheme.colorScheme.primary
    val aim = Color(0xFF2E7D32)
    fun pos(az: Double, alt: Double, c: Offset, r: Float): Offset {
        val rho = r * ((90.0 - alt.coerceIn(-10.0, 90.0)) / 90.0).toFloat()
        val th = Math.toRadians(az - rot)
        return Offset(c.x + rho * sin(th).toFloat(), c.y - rho * cos(th).toFloat())
    }
    Canvas(
        Modifier.fillMaxWidth().aspectRatio(1f).pointerInput(bodies, rot) {
            detectTapGestures { tap ->
                val r = minOf(size.width, size.height) / 2 * 0.9f; val c = Offset(size.width / 2f, size.height / 2f)
                bodies.filter { it.alt > 0 }.minByOrNull { (pos(it.az, it.alt, c, r) - tap).getDistance() }?.let {
                    if ((pos(it.az, it.alt, c, r) - tap).getDistance() < 48.dp.toPx()) onPick(it.id)
                }
            }
        },
    ) {
        val r = size.minDimension / 2 * 0.9f; val c = center
        drawCircle(bg, r, c)
        drawCircle(dim, r, c, style = Stroke(3f))
        drawCircle(dim.copy(alpha = 0.4f), r * 2 / 3, c, style = Stroke(1.5f)) // 高度 30°
        drawCircle(dim.copy(alpha = 0.4f), r / 3, c, style = Stroke(1.5f))     // 高度 60°
        val tp = android.graphics.Paint().apply { color = fg.toArgb(); textAlign = android.graphics.Paint.Align.CENTER; textSize = 14.dp.toPx(); isAntiAlias = true; isFakeBoldText = true }
        val sp = android.graphics.Paint(tp).apply { textAlign = android.graphics.Paint.Align.LEFT; textSize = 11.dp.toPx(); isFakeBoldText = false; color = fg.copy(alpha = 0.8f).toArgb() }
        fun text(t: String, p: Offset, paint: android.graphics.Paint) = drawIntoCanvas { it.nativeCanvas.drawText(t, p.x, p.y, paint) }
        listOf("北" to 0.0, "东" to 90.0, "南" to 180.0, "西" to 270.0).forEach { (t, a) ->
            val p = pos(a, -9.0, c, r); text(t, Offset(p.x, p.y + 5.dp.toPx()), tp)
        }
        // 天极
        val (paz, palt) = Astro.pole(lat)
        if (palt > 0) { val p = pos(paz, palt, c, r); drawCircle(accent, 4.dp.toPx(), p, style = Stroke(2f)); text(if (lat >= 0) "北天极" else "南天极", Offset(p.x + 6.dp.toPx(), p.y), sp) }
        val byId = bodies.associateBy { it.id }
        Astro.lines.forEach { (a, b) ->
            val x = byId[a]; val y = byId[b]
            if (x != null && y != null && x.alt > 0 && y.alt > 0) drawLine(dim, pos(x.az, x.alt, c, r), pos(y.az, y.alt, c, r), 2f)
        }
        val placed = mutableListOf<android.graphics.RectF>() // 已画的星名，重叠的暗星不再标名字
        bodies.filter { it.alt > 0 }.sortedBy { if (it.kind == Astro.Kind.STAR) it.mag else -10f }.forEach { b ->
            val p = pos(b.az, b.alt, c, r)
            when (b.kind) {
                Astro.Kind.SUN -> drawCircle(Color(0xFFFFC107), 9.dp.toPx(), p)
                Astro.Kind.MOON -> drawCircle(Color(0xFFCFD8DC), 8.dp.toPx(), p)
                else -> drawCircle(fg, (5.5f - b.mag * 1.1f).coerceIn(2f, 7f).dp.toPx() / 1.6f, p)
            }
            if (b.id == selected) drawCircle(accent, 14.dp.toPx(), p, style = Stroke(4f))
            if (b.kind != Astro.Kind.STAR || b.mag < 1.7f || b.id == selected) {
                val t = b.name.substringBefore(' '); val x = p.x + 8.dp.toPx(); val y = p.y - 6.dp.toPx()
                val box = android.graphics.RectF(x, y - sp.textSize, x + sp.measureText(t), y + 2f)
                if (b.id == selected || placed.none { android.graphics.RectF.intersects(it, box) }) { placed += box; text(t, Offset(x, y), sp) }
            }
        }
        if (aimAz != null && aimEl != null) { // 手机顶边指向
            val p = pos(aimAz.toDouble(), aimEl.toDouble(), c, r)
            val k = 12.dp.toPx(); val col = if (calibrated) aim else aim.copy(alpha = 0.6f)
            drawCircle(col, k, p, style = Stroke(4f)); drawLine(col, Offset(p.x - k * 1.5f, p.y), Offset(p.x + k * 1.5f, p.y), 4f); drawLine(col, Offset(p.x, p.y - k * 1.5f), Offset(p.x, p.y + k * 1.5f), 4f)
            if (!calibrated) text("未校准", Offset(p.x + k + 4.dp.toPx(), p.y + 16.dp.toPx()), sp)
        }
    }
}
