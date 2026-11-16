package app.ark.ui.tools

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(options: List<String>, selected: Int, onSelect: (Int) -> Unit) =
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, o -> FilterChip(selected == i, { onSelect(i) }, { Text(o, fontSize = 17.sp) }, Modifier.heightIn(min = 48.dp)) }
    }

private fun numFilter(s: String) = s.filter { it.isDigit() || it == '.' || it == '-' }.take(12)

@Composable
fun WaterTool(onBack: () -> Unit) {
    var bleach by rememberSaveable { mutableIntStateOf(0) }
    var gal by rememberSaveable { mutableStateOf(false) }
    var amount by rememberSaveable { mutableStateOf("1") }
    var cloudy by rememberSaveable { mutableStateOf(false) }
    ToolPage("饮水消毒（漂白剂）", onBack) {
        Text("漂白剂浓度", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Chips(WaterCalc.Bleach.entries.map { it.label }, bleach) { bleach = it }
        Text("水量", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(amount, { amount = numFilter(it) }, Modifier.fillMaxWidth(), singleLine = true, textStyle = LocalTextStyle.current.copy(fontSize = 24.sp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        Chips(listOf("升", "加仑（美）"), if (gal) 1 else 0) { gal = it == 1 }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Switch(cloudy, { cloudy = it }); Spacer(Modifier.width(10.dp)); Text("水浑浊 / 有色（先用干净布过滤，滴数加倍）", fontSize = 17.sp)
        }
        val v = amount.toDoubleOrNull()
        if (v != null && v > 0) {
            val liters = if (gal) v * WaterCalc.GAL_L else v
            val b = WaterCalc.Bleach.entries[bleach]
            val d = WaterCalc.drops(b, liters, cloudy)
            Card2 {
                Text("加 $d 滴", fontSize = 48.sp, fontWeight = FontWeight.Bold)
                Text("约 %s mL（1 滴 ≈ 0.05 mL）· 水量 %s L".format(Units.fmt(d * WaterCalc.DROP_ML), Units.fmt(liters)), fontSize = 16.sp)
            }
        }
        Card2 {
            Text("步骤", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("1. 浑浊水先静置并用干净布 / 咖啡滤纸过滤。\n2. 加漂白剂，搅匀或摇匀。\n3. 静置 30 分钟。\n4. 水应有轻微氯味；若没有，再按同样剂量加一次，再等 15 分钟。", fontSize = 17.sp, lineHeight = 26.sp)
            Note("只能用普通家用漂白剂（有效成分次氯酸钠），不要用带香味、颜色保护或增稠添加剂的。漂白剂放久会失效（开封约一年）。不能去除化学污染物。")
        }
        Note("来源：美国 CDC《Making Water Safe in an Emergency》——每加仑（3.8 L）：6% 漂白剂 8 滴，8.25% 漂白剂 6 滴；浑浊水加倍。此处按水量线性折算并四舍五入（至少 1 滴）；5–6% 的家用漂白剂按 6% 的剂量算。")
    }
}

@Composable
fun UnitsTool(onBack: () -> Unit) {
    var cat by rememberSaveable { mutableIntStateOf(0) }
    var from by rememberSaveable { mutableIntStateOf(0) }
    var text by rememberSaveable { mutableStateOf("1") }
    val c = Units.cats[cat]
    val f = c.units[from.coerceAtMost(c.units.size - 1)]
    ToolPage("单位换算", onBack) {
        Chips(Units.cats.map { it.name }, cat) { cat = it; from = 0 }
        OutlinedTextField(text, { text = numFilter(it) }, Modifier.fillMaxWidth(), singleLine = true, textStyle = LocalTextStyle.current.copy(fontSize = 28.sp),
            label = { Text("数值") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        Text("从", fontSize = 16.sp)
        Chips(c.units.map { it.name }, from) { from = it }
        val v = text.toDoubleOrNull()
        if (v != null) Card2 {
            c.units.filter { it !== f }.forEach { u ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(u.name, fontSize = 22.sp)
                    Text(Units.fmt(Units.convert(c, f, u, v)), fontSize = 26.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Note("系数：1 ft = 0.3048 m，1 mile = 1609.344 m，1 海里 = 1852 m，1 lb = 0.45359237 kg，1 psi = 6894.757 Pa，1 bar = 100 kPa，1 美制加仑 = 3.785411784 L，1 qt = ¼ gal（NIST SP 811）。")
    }
}
