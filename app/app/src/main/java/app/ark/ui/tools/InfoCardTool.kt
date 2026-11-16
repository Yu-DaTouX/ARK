/*
 * InfoCardTool.kt —— 应急信息卡：把血型、过敏、用药、联系人等存在本机（prefs ark_tools 的 info_*），
 * 可全屏大字（白底黑字，屏幕常亮）给救援人员看。不联网、不上传。
 */
package app.ark.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private val fields = listOf(
    "name" to "姓名", "blood" to "血型（如 A 型 Rh+）", "allergy" to "过敏（药物、食物、蜂蜇）", "meds" to "正在用的药",
    "history" to "病史（心脏病、糖尿病、癫痫、哮喘、妊娠等）", "contact" to "紧急联系人和电话", "place" to "所在位置备注（营地、地址、同行人数）",
)

/** 应急信息卡：存在 SharedPreferences（info_*），全屏大字给救援人员看。 */
@Composable
fun InfoCardTool(onBack: () -> Unit) {
    val prefs = LocalContext.current.toolPrefs()
    val values = remember { mutableStateMapOf<String, String>().also { m -> fields.forEach { (k, _) -> m[k] = prefs.getString("info_$k", "") ?: "" } } }
    var show by remember { mutableStateOf(false) }
    ToolPage("应急信息卡", onBack) {
        Note("填好后点“全屏显示”给救援人员看。内容只存在本机，不联网。")
        BigButton("全屏显示（给救援人员）", { show = true })
        fields.forEach { (k, label) ->
            OutlinedTextField(
                values[k] ?: "", { values[k] = it; prefs.edit().putString("info_$k", it).apply() },
                Modifier.fillMaxWidth(), label = { Text(label) }, minLines = if (k in listOf("meds", "history", "place")) 2 else 1,
                textStyle = LocalTextStyle.current.copy(fontSize = 18.sp),
            )
        }
    }
    if (show) Dialog(onDismissRequest = { show = false }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
        Column(Modifier.fillMaxSize().background(Color.White).clickable { show = false }.statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("应急信息 / EMERGENCY INFO", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color(0xFFB71C1C))
            val any = fields.any { (values[it.first] ?: "").isNotBlank() }
            if (!any) Text("还没有填写信息", fontSize = 24.sp, color = Color.Black)
            fields.forEach { (k, label) ->
                val v = values[k]?.trim().orEmpty()
                if (v.isNotEmpty()) Column {
                    Text(label.substringBefore('（'), fontSize = 16.sp, color = Color.DarkGray)
                    Text(v, fontSize = if (k == "name" || k == "blood" || k == "contact") 36.sp else 28.sp, fontWeight = FontWeight.Bold, color = Color.Black, lineHeight = 40.sp)
                }
            }
            Text("点一下屏幕退出", fontSize = 14.sp, color = Color.Gray)
        }
    }
}
