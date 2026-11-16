package app.ark.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ark.ui.tools.*

private class ToolItem(val id: String, val title: String, val sub: String)

private val items = listOf(
    ToolItem("signal", "信号", "闪光灯 SOS · 摩尔斯闪光 · 屏幕信号灯"),
    ToolItem("compass", "指南针", "真北 / 磁北 · 锁定方位找回方向"),
    ToolItem("astro", "天体定向", "用太阳、月亮、星星校准方向，不受磁干扰"),
    ToolItem("baro", "气压 / 海拔", "气压、海拔、3 小时天气趋势"),
    ToolItem("sun", "日出日落", "日出日落、晨昏光、距天黑还有多久"),
    ToolItem("aid", "急救计时", "CPR 节拍器 · 止血带计时"),
    ToolItem("water", "饮水消毒", "漂白剂加几滴（CDC）"),
    ToolItem("units", "单位换算", "长度、重量、温度、压力、容积"),
)

/** 工具页：卡片列表 → 点进单个工具，系统返回键回到列表。全部离线。 */
@Composable
fun ToolsScreen() {
    var cur by rememberSaveable { mutableStateOf("") }
    BackHandler(enabled = cur != "") { cur = "" }
    val back = { cur = "" }
    Box(Modifier.fillMaxSize().statusBarsPadding()) {
        when (cur) {
            "signal" -> SignalTool(back)
            "compass" -> CompassTool(back)
            "astro" -> AstroTool(back)
            "baro" -> BaroTool(back)
            "sun" -> SunTool(back)
            "aid" -> FirstAidTool(back)
            "water" -> WaterTool(back)
            "units" -> UnitsTool(back)
            else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("工具", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text("全部离线，不联网", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                items.forEach { t ->
                    Surface(
                        Modifier.fillMaxWidth().heightIn(min = 76.dp).clickable { cur = t.id },
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = MaterialTheme.shapes.large,
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
