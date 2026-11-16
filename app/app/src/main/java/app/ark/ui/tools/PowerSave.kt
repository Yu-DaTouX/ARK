/*
 * PowerSave.kt —— 工具页“省电模式”的开关条和电量/剩余时间估算。
 * 开关状态由 ToolsScreen 持有并通过 LocalPowerSave 下发（纯黑配色、亮度 30% 在 ToolsScreen 里实现）。
 */
package app.ark.ui.tools

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs

/** 省电模式开关（工具页内有效）：纯黑背景、降低刷新、亮度调低。各工具用 LocalPowerSave 降低自己的刷新率。 */
val LocalPowerSave = compositionLocalOf { false }

/**
 * 剩余电量能用多久：BatteryManager 的 CHARGE_COUNTER（剩余电荷，µAh）÷ 当前电流 CURRENT_NOW（µA，符号各机型不同取绝对值）。
 * 部分机型用 mAh / mA 为单位：数值明显偏小时（电流 < 10000、电荷 < 100000）按 mA、mAh 理解。读不到（0 或不支持）返回 null。
 */
// 注意：CURRENT_NOW 的符号和单位各厂商不一致，这里只是粗估
fun batteryRuntimeHours(ctx: Context): Double? {
    val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    var cur = abs(bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW).toDouble())
    var cap = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER).toDouble()
    if (cur <= 0 || cap <= 0 || cur == Long.MIN_VALUE.toDouble() || cap == Long.MIN_VALUE.toDouble()) return null
    if (cur < 10_000) cur *= 1000
    if (cap < 100_000) cap *= 1000
    val h = cap / cur
    return if (h.isFinite() && h > 0 && h < 2000) h else null
}

/** 省电开关卡片 + 电量与估算续航（开省电后每 60 秒刷新，否则 20 秒） */
@Composable
fun PowerSaveBar(on: Boolean, onChange: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    var hours by remember { mutableStateOf<Double?>(null) }
    var level by remember { mutableIntStateOf(-1) }
    var charging by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            val i: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (i != null) {
                val l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                level = if (l >= 0) l * 100 / sc else -1
                val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
            }
            hours = try { batteryRuntimeHours(ctx) } catch (_: Exception) { null }
            delay(if (on) 60_000 else 20_000)
        }
    }
    Card2 {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("省电模式", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("纯黑背景、降低刷新、屏幕调暗", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(on, onChange)
        }
        if (level >= 0) Text(
            "电量 $level%" + when {
                charging -> "（充电中）"
                hours != null -> "，按当前耗电约还能用 %s（息屏会久得多，仅供估算）".format(hoursText(hours!!))
                else -> ""
            }, fontSize = 16.sp,
        )
        if (on) Note(
            "还可以：\n· 不需要通话和网络时开飞行模式（GPS 和指南针照常可用）\n· 系统亮度调到最低，不用时锁屏\n" +
                "· 关蓝牙、Wi-Fi、NFC，关闭自动同步和后台刷新\n· 只在需要时打开传感器页（指南针、天体定向用完就退出）\n· 开系统自带的省电模式，电量低于 20% 时只留急救和信号工具",
        )
    }
}

private fun hoursText(h: Double): String = if (h >= 24) "%.1f 天".format(h / 24) else if (h >= 1) "%.1f 小时".format(h) else "%d 分钟".format((h * 60).toInt())
