/*
 * SupplyTool.kt —— “物资计算”（人数 × 天数 → 水和热量，对照现有存量）+ 72 小时应急包清单（勾选状态存 prefs supply_checked）。
 */
package app.ark.ui.tools

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import kotlin.math.floor

/**
 * 物资计算。数值来源与口径：
 *  - 饮水：每人每天至少 4 L（约 1 美制加仑，美国 FEMA / 红十字会 / CDC 应急饮水建议，含最基本的饮用和个人卫生）。
 *  - 炎热气候或重体力活动：这里按 +2 L/人/天；哺乳期：+1 L/人/天——这两项是经验估算，不是官方定值（CDC 只说这些人需要更多水）。
 *  - 热量：成人静坐等待约 2000 kcal/人/天；寒冷或重体力 2500 kcal 作为保守值（粗略估算，个体差异大）。
 */
// 页面本身无副作用，仅读写 prefs 的勾选状态
@Composable
fun SupplyTool(onBack: () -> Unit) {
    val prefs = LocalContext.current.toolPrefs()
    var people by rememberSaveable { mutableStateOf("2") }
    var days by rememberSaveable { mutableStateOf("3") }
    var hot by rememberSaveable { mutableStateOf(false) }
    var nursing by rememberSaveable { mutableStateOf("0") }
    var heavy by rememberSaveable { mutableStateOf(false) }
    var haveWater by rememberSaveable { mutableStateOf("") }
    var haveKcal by rememberSaveable { mutableStateOf("") }
    val checked = remember { mutableStateListOf<String>().also { l -> l.addAll(prefs.getStringSet("supply_checked", emptySet()).orEmpty()) } }

    ToolPage("物资计算", onBack) {
        val n = people.toIntOrNull() ?: 0; val d = days.toIntOrNull() ?: 0; val nur = (nursing.toIntOrNull() ?: 0).coerceIn(0, maxOf(n, 0))
        val kb = KeyboardOptions(keyboardType = KeyboardType.Number)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(people, { people = it.filter(Char::isDigit).take(3) }, Modifier.weight(1f), label = { Text("人数") }, singleLine = true, keyboardOptions = kb, textStyle = LocalTextStyle.current.copy(fontSize = 22.sp))
            OutlinedTextField(days, { days = it.filter(Char::isDigit).take(3) }, Modifier.weight(1f), label = { Text("天数") }, singleLine = true, keyboardOptions = kb, textStyle = LocalTextStyle.current.copy(fontSize = 22.sp))
        }
        Row(Modifier.fillMaxWidth().clickable { hot = !hot }, verticalAlignment = Alignment.CenterVertically) { Checkbox(hot, { hot = it }); Text("炎热气候（+2 L/人/天）", fontSize = 17.sp) }
        Row(Modifier.fillMaxWidth().clickable { heavy = !heavy }, verticalAlignment = Alignment.CenterVertically) { Checkbox(heavy, { heavy = it }); Text("寒冷或重体力（热量按 2500 kcal）", fontSize = 17.sp) }
        OutlinedTextField(nursing, { nursing = it.filter(Char::isDigit).take(2) }, Modifier.fillMaxWidth(), label = { Text("其中哺乳期人数（+1 L/人/天）") }, singleLine = true, keyboardOptions = kb)

        val perWater = 4.0 + (if (hot) 2.0 else 0.0)
        val waterDay = n * perWater + nur * 1.0
        val kcalPer = if (heavy) 2500.0 else 2000.0
        val kcalDay = n * kcalPer
        if (n > 0 && d > 0) Card2 {
            Text("需要", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("饮水 %.0f L".format(ceil(waterDay * d)), fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text("每天 %.1f L · 约 %.1f 美制加仑".format(waterDay, waterDay / WaterCalc.GAL_L), fontSize = 15.sp)
            Text("热量 %,.0f kcal".format(kcalDay * d), fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Text("每天 %,.0f kcal（每人 %.0f）".format(kcalDay, kcalPer), fontSize = 15.sp)
        }
        Text("现有存量", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val dk = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            OutlinedTextField(haveWater, { haveWater = it.filter { c -> c.isDigit() || c == '.' }.take(7) }, Modifier.weight(1f), label = { Text("饮水（L）") }, singleLine = true, keyboardOptions = dk)
            OutlinedTextField(haveKcal, { haveKcal = it.filter { c -> c.isDigit() || c == '.' }.take(8) }, Modifier.weight(1f), label = { Text("食物（kcal）") }, singleLine = true, keyboardOptions = dk)
        }
        val hw = haveWater.toDoubleOrNull(); val hk = haveKcal.toDoubleOrNull()
        if (n > 0 && (hw != null || hk != null)) Card2 {
            hw?.let { Text("饮水够 %.1f 天".format(it / waterDay), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                if (d > 0 && it < waterDay * d) Note("还差约 %.0f L 才够 $d 天。".format(ceil(waterDay * d - it))) }
            hk?.let { Text("食物够 %.1f 天".format(it / kcalDay), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                if (d > 0 && it < kcalDay * d) Note("还差约 %,.0f kcal 才够 $d 天。".format(ceil(kcalDay * d - it))) }
            Note("省水办法：不洗澡、少活动、阴凉处休息；缺水时别省着不喝，也别在没水时进食干燥食物。")
        }
        Note("饮水按每人每天至少 4 L（约 1 加仑，FEMA / 红十字会 / CDC 应急建议）；炎热、哺乳的加量和热量是经验估算，仅供规划参考。")

        Text("72 小时应急包清单", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Note("点一下勾选，自动保存。已勾 ${checked.size} / ${kit.size}。")
        kit.forEach { (id, label) ->
            val on = id in checked
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable {
                if (on) checked.remove(id) else checked.add(id)
                prefs.edit().putStringSet("supply_checked", checked.toSet()).apply()
            }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(on, null); Spacer(Modifier.width(8.dp)); Text(label, fontSize = 18.sp)
            }
        }
        Note("清单参考美国 Ready.gov / FEMA 与红十字会的应急包建议，按家庭情况增减。")
    }
}

// 清单项：id 用于持久化勾选（改 id 会丢用户已勾状态），label 是显示文字
private val kit = listOf(
    "water" to "饮用水：每人每天 4 L × 3 天",
    "food" to "不用加热的耐储存食物 3 天份",
    "opener" to "开罐器 / 多功能刀",
    "light" to "手电筒 + 备用电池（或头灯）",
    "radio" to "手摇或电池收音机",
    "power" to "充电宝 + 充电线",
    "aid" to "急救包（含止血带、纱布、绷带、胶带）",
    "meds" to "常用药 + 个人处方药 7 天份",
    "whistle" to "哨子（求救）",
    "mask" to "口罩、一次性手套",
    "hygiene" to "卫生用品：湿巾、垃圾袋、纸巾、女性用品",
    "purify" to "净水片或普通漂白剂（无香型）",
    "docs" to "身份证件、保险、重要文件复印件，装防水袋",
    "cash" to "现金（小面额）",
    "blanket" to "保暖毯 / 睡袋、雨衣、备用衣物和袜子",
    "fire" to "防水火柴 / 打火机",
    "map" to "纸质地图、指南针、联系人写在纸上",
    "tape" to "强力胶带、绳子、塑料布",
    "phone" to "手机装进防水袋，备好离线资料（本 App）",
    "family" to "婴儿 / 老人 / 宠物的专用物品",
)
