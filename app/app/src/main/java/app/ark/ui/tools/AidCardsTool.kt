package app.ark.ui.tools

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** 急救步骤卡：列表 → 一步一屏的大字卡片，可前后翻；返回键先回列表。内容见 AidData。 */
@Composable
fun AidCardsTool(onBack: () -> Unit, onOpen: (String) -> Unit) {
    var cardId by rememberSaveable { mutableStateOf("") }
    var step by rememberSaveable { mutableIntStateOf(0) }
    val card = AidData.cards.firstOrNull { it.id == cardId }
    BackHandler(enabled = card != null) { cardId = "" }

    if (card == null) {
        ToolPage("急救步骤卡", onBack) {
            CallBanner()
            Note("一步一步照做，不用联网、不用 AI。内容是简化要点，不能代替培训。")
            AidData.cards.forEach { c ->
                Surface(
                    Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable { cardId = c.id; step = 0 },
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = MaterialTheme.shapes.large,
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(c.title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text(c.sub, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        return
    }

    val i = step.coerceIn(0, card.steps.size - 1)
    val s = card.steps[i]
    val view = LocalView.current
    DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
    ToolPage(card.title, { cardId = "" }) {
        CallBanner()
        Text("第 ${i + 1} / ${card.steps.size} 步", fontSize = 16.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        LinearProgressIndicator(progress = { (i + 1f) / card.steps.size }, Modifier.fillMaxWidth())
        Text(s.title, fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 34.sp)
        Text(s.body, fontSize = 22.sp, lineHeight = 32.sp)
        if (s.timerSec > 0) StepTimer(s.timerSec, s.timerLabel, key = "${card.id}$i")
        if (s.actionTool.isNotEmpty()) BigButton(s.actionLabel, { onOpen(s.actionTool) })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigButton("上一步", { step = i - 1 }, Modifier.weight(1f), outlined = true, enabled = i > 0)
            if (i < card.steps.size - 1) BigButton("下一步", { step = i + 1 }, Modifier.weight(1f))
            else BigButton("完成", { cardId = "" }, Modifier.weight(1f))
        }
        Note(card.basis)
        Note("以上为通用急救要点，不能代替专业培训和医生的判断。")
    }
}

@Composable
private fun CallBanner() = Warn("有条件，立即拨打 120（多数国家 112）呼叫急救 / 送医。下面的步骤是等待救援时做的。")

/** 计时器状态放在进程级：切步骤 / 离开页面不丢；用 elapsedRealtime 的截止时刻算剩余，后台挂起也照常走。 */
private class TimerState(val totalMs: Long) { var endAt = 0L; var leftMs = totalMs }
private val timers = HashMap<String, TimerState>()

@Composable
private fun StepTimer(totalSec: Int, label: String, key: String) {
    val st = remember(key) {
        timers.getOrPut(key) { TimerState(totalSec * 1000L) }.also { if (it.endAt == 0L && it.leftMs <= 0L) it.leftMs = it.totalMs }
    }
    var now by remember(key) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var running by remember(key) { mutableStateOf(st.endAt != 0L) }
    val tone = remember { try { ToneGenerator(AudioManager.STREAM_MUSIC, 100) } catch (_: Exception) { null } }
    DisposableEffect(Unit) { onDispose { tone?.release() } }
    LaunchedEffect(running, key) {
        while (st.endAt != 0L) {
            now = SystemClock.elapsedRealtime()
            if (st.endAt - now <= 0L) {
                st.endAt = 0L; st.leftMs = 0L; running = false
                repeat(3) { tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 300); delay(500) }
                break
            }
            delay(250)
        }
    }
    val leftMs = if (st.endAt != 0L) (st.endAt - now).coerceAtLeast(0L) else st.leftMs
    val remaining = ((leftMs + 999) / 1000).toInt()
    fun toggle() {
        if (st.endAt != 0L) { st.leftMs = (st.endAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L); st.endAt = 0L; running = false }
        else if (st.leftMs > 0L) { now = SystemClock.elapsedRealtime(); st.endAt = now + st.leftMs; running = true }
    }
    Card2 {
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(fmtDuration(remaining * 1000L), fontSize = 48.sp, fontWeight = FontWeight.Bold,
            color = if (remaining == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigButton(if (running) "暂停" else if (remaining == 0) "时间到" else "开始", { toggle() }, Modifier.weight(1f), outlined = running)
            BigButton("重置", { st.endAt = 0L; st.leftMs = st.totalMs; running = false }, Modifier.weight(1f), outlined = true)
        }
    }
}
