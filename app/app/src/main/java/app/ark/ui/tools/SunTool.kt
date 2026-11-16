package app.ark.ui.tools

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SunTool(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.toolPrefs() }
    val place = rememberPlace()
    var latS by rememberSaveable { mutableStateOf(prefs.getString("sun_lat", "") ?: "") }
    var lonS by rememberSaveable { mutableStateOf(prefs.getString("sun_lon", "") ?: "") }
    var useManual by rememberSaveable { mutableStateOf(false) }
    var now by remember { mutableStateOf(Instant.now()) }
    val saving = LocalPowerSave.current
    LaunchedEffect(saving) { while (true) { now = Instant.now(); delay(if (saving) 120_000 else 30_000) } }

    val gps = place.location
    val mLat = latS.toDoubleOrNull()?.takeIf { it in -90.0..90.0 }
    val mLon = lonS.toDoubleOrNull()?.takeIf { it in -180.0..180.0 }
    val pos: Pair<Double, Double>? = if (!useManual && gps != null) gps.latitude to gps.longitude
        else if (mLat != null && mLon != null) mLat to mLon else null
    val zone = ZoneId.systemDefault()
    val fmt = DateTimeFormatter.ofPattern("HH:mm")
    fun t(i: Instant?) = i?.atZone(zone)?.format(fmt) ?: "——"

    ToolPage("日出日落", onBack) {
        ClockWarning()
        if (!useManual) LocationAgeNote(gps)
        if (pos == null) Note("需要位置。允许使用位置读取系统缓存的最后位置，或在下面手动输入经纬度。") else {
            val today = LocalDate.now(zone)
            val d = Solar.compute(today, pos.first, pos.second)
            Note("位置 %.3f°, %.3f°（%s） · %s（今天） · 时区 %s".format(pos.first, pos.second, if (!useManual && gps != null) "GPS 缓存" else "手动", today, zone.id))
            d.polar?.let { Warn(it) }
            // 距天黑：民用昏影终（太阳 −6°）；之前先看日落
            val dusk = d.dusk; val set = d.sunset
            val big = when {
                dusk != null && now.isBefore(dusk) -> "距天黑还有 " + dur(dusk.toEpochMilli() - now.toEpochMilli()) + (if (set != null && now.isBefore(set)) "\n（距日落 ${dur(set.toEpochMilli() - now.toEpochMilli())}）" else "\n（日落已过，处于黄昏光线中）")
                dusk != null -> "今天已经天黑"
                else -> null
            }
            big?.let { Card2 { Text(it, fontSize = 24.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp) } }
            Card2 {
                Row2("民用晨光始", t(d.dawn), "太阳在地平线下 6°")
                Row2("日出", t(d.sunrise))
                Row2("正午", t(d.noon), "太阳最高")
                Row2("日落", t(d.sunset))
                Row2("民用昏影终", t(d.dusk), "之后基本全黑")
            }
        }
        Card2 {
            Text("手动输入位置", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            val kb = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            OutlinedTextField(latS, { latS = it.filter { c -> c.isDigit() || c == '.' || c == '-' }.take(10) }, Modifier.fillMaxWidth(), label = { Text("纬度（北正南负，如 39.9）") }, singleLine = true, keyboardOptions = kb)
            OutlinedTextField(lonS, { lonS = it.filter { c -> c.isDigit() || c == '.' || c == '-' }.take(11) }, Modifier.fillMaxWidth(), label = { Text("经度（东正西负，如 116.4）") }, singleLine = true, keyboardOptions = kb)
            BigButton("使用手动位置", { useManual = true; prefs.edit().putString("sun_lat", latS).putString("sun_lon", lonS).apply() }, enabled = mLat != null && mLon != null)
            if (gps != null) BigButton("改用 GPS 缓存位置", { useManual = false }, outlined = true)
            else if (!place.permitted) BigButton("允许使用位置", place.request, outlined = true)
        }
        Note("算法：NOAA Solar Calculator（Meeus《Astronomical Algorithms》），完全离线，误差约 1 分钟。日出日落按太阳上缘 + 大气折射（天顶角 90.833°）。时间使用手机当前时区；手机时间不准则结果不准。")
    }
}

private fun dur(ms: Long): String { val m = ms / 60000; return if (m >= 60) "${m / 60} 小时 ${m % 60} 分" else "$m 分" }

@Composable
private fun Row2(label: String, value: String, hint: String = "") = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
    Column(Modifier.weight(1f)) { Text(label, fontSize = 18.sp); if (hint.isNotEmpty()) Note(hint) }
    Text(value, fontSize = 28.sp, fontWeight = FontWeight.Bold)
}
