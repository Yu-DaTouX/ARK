/*
 * ToolsScreen.kt —— “工具”标签页：求生小工具的目录，点进去切换到具体工具（ui/tools 包、ui/comm/CommScreen）。
 * 导航：用 cur（工具 id，空串=目录）自己管理，不用 NavHost；系统返回键回到目录。
 * “省电模式”：把窗口亮度压到 30% + 纯黑配色（OLED 省电），并通过 LocalPowerSave 通知各工具降低刷新/动画；
 * 只在工具页内生效，离开恢复。偏好存在 SharedPreferences("ark_tools")。
 */
package app.ark.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ark.ui.tools.*

// 目录项：id 对应下面 when 里的分支，改名要两处一起改
private class ToolItem(val id: String, val title: String, val sub: String)

/** 按求生紧急程度排序：急救 → 信号 / 联络 → 方向 → 水和物资 → 其他。 */
private val items = listOf(
    ToolItem("aidcards", "急救步骤卡", "心肺复苏、出血、窒息、烧伤、蛇咬……一步一屏，零耗电"),
    ToolItem("aid", "急救计时", "CPR 节拍器 · 止血带计时"),
    ToolItem("signal", "信号", "闪光灯 SOS · 摩尔斯闪光 · 屏幕信号灯"),
    ToolItem("comm", "通讯", "蓝牙消息（不配对、可接力）· 超声传信 · 警报声"),
    ToolItem("info", "应急信息卡", "血型、过敏、用药、联系人，全屏给救援人员看"),
    ToolItem("compass", "指南针", "真北 / 磁北 · 锁定方位找回方向"),
    ToolItem("astro", "天体定向", "用太阳、月亮、星星校准方向，不受磁干扰"),
    ToolItem("water", "饮水消毒", "漂白剂加几滴（CDC）"),
    ToolItem("supply", "物资计算", "人数 × 天数 → 水和热量 · 72 小时应急包清单"),
    ToolItem("baro", "气压 / 海拔", "气压、海拔、3 小时趋势和简易天气预报"),
    ToolItem("sun", "日出日落", "日出日落、晨昏光、距天黑还有多久"),
    ToolItem("units", "单位换算", "长度、重量、温度、压力、容积"),
)

private val BlackScheme = darkColorScheme(
    background = Color.Black, surface = Color.Black, surfaceVariant = Color(0xFF1C1C1C), onSurface = Color(0xFFE0E0E0),
    onBackground = Color(0xFFE0E0E0), onSurfaceVariant = Color(0xFFB0B0B0), primary = Color(0xFF8AB4F8), onPrimary = Color.Black,
    primaryContainer = Color(0xFF1E3A5F), onPrimaryContainer = Color.White, outline = Color(0xFF707070), outlineVariant = Color(0xFF333333),
    errorContainer = Color(0xFF5C1111), onErrorContainer = Color.White,
)

// 从 Compose 的 Context 层层剥出 Activity（用来改窗口亮度）
private fun Context.activity(): Activity? = when (this) { is Activity -> this; is ContextWrapper -> baseContext.activity(); else -> null }

/** 工具页：卡片列表 → 点进单个工具，系统返回键回到列表。全部离线。顶部有省电模式开关。 */
@Composable
fun ToolsScreen() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("ark_tools", Context.MODE_PRIVATE) }
    var cur by rememberSaveable { mutableStateOf("") }
    var save by remember { mutableStateOf(prefs.getBoolean("power_save", false)) }
    BackHandler(enabled = cur != "") { cur = "" }
    val back = { cur = "" }

    // 省电模式：只在工具页内把窗口亮度压到 30%，离开页面恢复
    DisposableEffect(save) {
        val w = ctx.activity()?.window
        val before = w?.attributes?.screenBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        if (save && w != null) { val a = w.attributes; a.screenBrightness = 0.3f; w.attributes = a }
        onDispose { if (save && w != null) { val a = w.attributes; a.screenBrightness = before; w.attributes = a } }
    }

    MaterialTheme(colorScheme = if (save) BlackScheme else MaterialTheme.colorScheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
        CompositionLocalProvider(LocalPowerSave provides save) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
                when (cur) {
                    "aidcards" -> AidCardsTool(back) { cur = it }
                    "signal" -> SignalTool(back)
                    "comm" -> app.ark.ui.comm.CommScreen(back)
                    "compass" -> CompassTool(back)
                    "astro" -> AstroTool(back)
                    "baro" -> BaroTool(back)
                    "sun" -> SunTool(back)
                    "aid" -> FirstAidTool(back)
                    "water" -> WaterTool(back)
                    "supply" -> SupplyTool(back)
                    "info" -> InfoCardTool(back)
                    "units" -> UnitsTool(back)
                    else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("工具", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Text("全部离线，不联网", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        PowerSaveBar(save) { save = it; prefs.edit().putBoolean("power_save", it).apply() }
                        items.forEach { t ->
                            Surface(
                                Modifier.fillMaxWidth().heightIn(min = 76.dp).clickable { cur = t.id },
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (save) 1f else 0.5f), shape = MaterialTheme.shapes.large,
                            ) {
                                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(t.title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                        Text(t.sub, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Icon(ArkIcons.Chevron, null)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
