package app.ark.ui.tools

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** CPR 按压频率：AHA / ERC 指南成人 100–120 次/分，取 110。按压:通气 = 30:2（成人，单人施救）。 */
private const val CPR_BPM = 110

@Composable
fun FirstAidTool(onBack: () -> Unit) {
    ToolPage("急救计时", onBack) {
        Cpr()
        Tourniquet()
    }
}

@Composable
private fun Cpr() {
    val ctx = LocalContext.current
    val view = LocalView.current
    var running by rememberSaveable { mutableStateOf(false) }
    var count by remember { mutableIntStateOf(0) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var flash by remember { mutableStateOf(false) }
    var sound by rememberSaveable { mutableStateOf(true) }
    var vib by rememberSaveable { mutableStateOf(true) }
    val tone = remember { try { ToneGenerator(AudioManager.STREAM_MUSIC, 100) } catch (_: Exception) { null } }
    val vibrator = remember { ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator }
    DisposableEffect(Unit) { onDispose { tone?.release(); view.keepScreenOn = false } }
    DisposableEffect(running) { view.keepScreenOn = running; onDispose { view.keepScreenOn = false } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { running = false }

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        count = 0; elapsed = 0
        val t0 = SystemClock.elapsedRealtime()
        var n = 0L
        while (true) {
            val due = t0 + n * 60_000L / CPR_BPM
            delay((due - SystemClock.elapsedRealtime()).coerceAtLeast(0))
            n++
            count = n.toInt(); elapsed = SystemClock.elapsedRealtime() - t0
            if (sound) tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 60)
            if (vib) vibrator?.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
            flash = true
            launch { delay(150); flash = false }
        }
    }

    Card2 {
        Text("CPR 节拍器（$CPR_BPM 次/分）", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Box(
            Modifier.fillMaxWidth().height(150.dp).background(if (flash) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface, MaterialTheme.shapes.large),
            contentAlignment = Alignment.Center,
        ) {
            val c = if (flash) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val k = if (count == 0) 0 else (count - 1) % 30 + 1
                Text(if (running) "$k / 30" else "—", fontSize = 52.sp, fontWeight = FontWeight.Bold, color = c)
                Text("已进行 ${fmtDuration(elapsed)} · 共 $count 次", fontSize = 16.sp, color = c)
            }
        }
        if (running && count > 0 && count % 30 == 0) Warn("第 30 次：停，给 2 次人工呼吸，然后立刻继续按压")
        Note("成人：每 30 次按压给 2 次人工呼吸。位置在胸骨下半部，按压深度约 5–6 cm，让胸廓完全回弹。节拍器不会自动暂停，吹气后继续跟着节拍按。")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(sound, { sound = it }); Spacer(Modifier.width(8.dp)); Text("声音", fontSize = 17.sp)
            Spacer(Modifier.width(24.dp))
            Switch(vib, { vib = it }); Spacer(Modifier.width(8.dp)); Text("振动", fontSize = 17.sp)
        }
        if (running) BigButton("停止", { running = false }, danger = true) else BigButton("开始按压节拍", { running = true })
    }
}

@Composable
private fun Tourniquet() {
    val prefs = LocalContext.current.toolPrefs()
    var start by remember { mutableLongStateOf(prefs.getLong("tq_start", 0L)) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(start) { while (start > 0) { now = System.currentTimeMillis(); delay(1000) } }
    val el = now - start
    val over = start > 0 && el >= 2 * 3600_000L

    Card2 {
        Text("止血带计时", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (start == 0L) {
            Note("上好止血带的那一刻点下面按钮。时间会保存，App 关闭或重启后仍在。")
            BigButton("已上止血带", { start = System.currentTimeMillis(); now = start; prefs.edit().putLong("tq_start", start).apply() }, danger = true)
        } else {
            Text(fmtDuration(el), Modifier.fillMaxWidth(), fontSize = 56.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            Text("上止血带时间 " + SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(start)), Modifier.fillMaxWidth(), textAlign = TextAlign.Center, fontSize = 16.sp)
            if (over) Warn("已超过 2 小时：尽快送医，不要自行松开！")
            Note("把上带时间告诉救援人员（也可写在伤者额头或胶带上）。")
            BigButton("清除记录（已处理 / 误点）", { confirm = true }, outlined = true)
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("清除止血带计时？") },
        text = { Text("清除后无法恢复已过时间。止血带仍在伤者身上时请不要清除。") },
        confirmButton = { TextButton({ start = 0; prefs.edit().remove("tq_start").apply(); confirm = false }) { Text("清除") } },
        dismissButton = { TextButton({ confirm = false }) { Text("取消") } },
    )
}
