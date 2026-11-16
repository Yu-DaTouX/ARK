/*
 * SafetyNotice.kt —— 首次启动 / 每次升级后的安全提醒弹窗（5 秒倒计时后才能点“我已了解”）。
 * 在 MainActivity 里叠在 ArkRoot 之上。“已读”按 versionCode 记在 prefs(safety_ack)，所以升级后会再弹一次；
 * 文案要与仓库根目录 DISCLAIMER.md、README 顶部声明保持一致。
 */
package app.ark.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

private const val WAIT_SEC = 5 // 强制阅读的秒数

/**
 * 公开版（release）首次启动和每次升级后弹一次安全提醒，倒计时 5 秒后才能关闭；读完后按版本号记住，不再打扰。
 * debug 包默认不弹（自用调试）；调试时 --ez ark_safety true 强制弹出。
 */
@Composable
fun SafetyNotice() {
    val ctx = LocalContext.current
    val debuggable = remember { ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 }
    val prefs = remember { ctx.getSharedPreferences("ark", Context.MODE_PRIVATE) }
    val version = remember {
        runCatching {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            // longVersionCode 从 API 28 起提供，更早只有 int 的 versionCode
            if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
        }.getOrDefault(0L)
    }
    var show by remember { mutableStateOf((!debuggable || prefs.getBoolean("debug_safety", false)) && prefs.getLong("safety_ack", -1L) != version) }
    if (!show) return
    var left by remember { mutableIntStateOf(WAIT_SEC) }
    LaunchedEffect(Unit) { while (left > 0) { delay(1000); left-- } }

    AlertDialog(
        onDismissRequest = {}, // 不能点外面或返回键关掉
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text("使用前必读：资料来源与安全风险", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Text(
                    """
                    【本软件仅为个人爱好项目，请勿相信其中内容。】它没有经过任何医疗、救援或航海机构审核，所有内容只能作为参考线索，不能作为决定依据。

                    资料来源
                    · 美军和美国政府公开手册（FM 21-76 / FM 3-05.70 求生手册、FEMA、和平队手册等），很多编写于几十年前，部分做法已过时。
                    · Hesperian《Where There Is No Doctor》等书籍（允许非营利免费分发）、维基百科和 Stack Exchange 问答、古登堡计划公有领域书籍。
                    · 中文内容由 AI 从英文机器翻译，可能有误译；剂量、数字请点「原文」核对英文。
                    · 地图：Protomaps / OpenStreetMap；海图：GEBCO 2026 水深、OpenSeaMap 航标（ODbL）；卫星图为低分辨率公开影像。

                    安全风险
                    · 有条件时，第一时间呼叫急救或送医（中国 120，多数国家 112，美加 911，澳洲 000）。方舟不能代替医生、专业救援和正式培训。
                    · AI 回答由手机上的小模型生成，可能编造、遗漏或给出危险建议。用药剂量、急救步骤一定要核对原文；资料中已发现的过时做法标有“现行指南提醒”，以提醒为准，但未发现的仍可能存在。
                    · 「工具 → 急救步骤卡」不用 AI，紧急时优先看它，但它同样只是简化要点。
                    · 地图、海图不能用于正式导航；指南针、天体定向、气压海拔、天气预报都只是估算。
                    · 采集野生食物、处理野生动物、使用工具和武器都有受伤和违法风险，请量力而行、遵守当地法律。

                    使用本软件即表示你理解并自行承担全部风险。本软件按“现状”提供，开发者不对任何使用后果负责。
                    """.trimIndent(),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { prefs.edit().putLong("safety_ack", version).apply(); show = false },
                enabled = left == 0,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (left > 0) "请阅读（$left 秒）" else "我已了解") }
        },
    )
}
