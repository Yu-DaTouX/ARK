/*
 * SignalTool.kt —— “信号”工具：闪光灯 SOS / 文字摩尔斯闪光（由前台服务 TorchService 执行，关屏也继续）+ 全屏屏幕信号灯。
 * 设计：没有闪光灯或被占用（TorchService.failed）时退而用屏幕按同样的摩尔斯时序闪；离开页面即停止，避免忘记关而耗电。
 */
package app.ark.ui.tools

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.delay

private fun Context.activity(): Activity? = when (this) { is Activity -> this; is ContextWrapper -> baseContext.activity(); else -> null }

/** 信号页 */
@Composable
fun SignalTool(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val hasTorch = remember { torchId(ctx) != null }
    val active by TorchService.active.collectAsState()
    val lit by TorchService.lit.collectAsState()
    val failed by TorchService.failed.collectAsState()
    var mode by rememberSaveable { mutableStateOf("") } // "" / "sos" / "text"，只用来决定显示哪个按钮
    var text by rememberSaveable { mutableStateOf("HELP") }
    var screenLight by rememberSaveable { mutableStateOf("") } // "" / "white" / "red"
    var screenSos by rememberSaveable { mutableStateOf(false) } // 屏幕信号灯按摩尔斯闪（闪光灯不可用时的替代）
    var blink by rememberSaveable { mutableStateOf(false) }

    val running = active && mode != ""
    val steps = remember(mode, text) {
        when (mode) { "sos" -> Morse.steps("SOS"); "text" -> Morse.steps(text); else -> emptyList() }
    }
    DisposableEffect(Unit) {
        TorchService.clearFailed()
        onDispose { TorchService.stop(ctx) } // 离开信号页就停
    }
    DisposableEffect(running || screenLight != "") { view.keepScreenOn = running || screenLight != ""; onDispose { view.keepScreenOn = false } } // 等价于 FLAG_KEEP_SCREEN_ON
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { screenLight = "" } // 屏幕信号灯切到后台就关；闪光灯由前台服务继续

    fun start(m: String) {
        val t = if (m == "sos") "SOS" else text
        mode = m
        if (hasTorch) { TorchService.clearFailed(); TorchService.start(ctx, t) }
        else { screenSos = true; screenLight = "white" } // 没有闪光灯：直接用屏幕
    }
    fun stop() { mode = ""; TorchService.stop(ctx); screenLight = ""; screenSos = false }

    ToolPage("信号", onBack) {
        if (!hasTorch) Warn("本机没有闪光灯：下面的按钮会改用屏幕信号灯。")
        if (failed) {
            Warn("闪光灯不可用（可能被相机占用、过热或电量过低）。")
            BigButton("改用屏幕信号灯（按摩尔斯闪）", { screenSos = true; screenLight = "white" })
        }
        Box(
            Modifier.fillMaxWidth().height(90.dp).background(if (lit) Color(0xFFFFEB3B) else MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.large),
            contentAlignment = Alignment.Center,
        ) { Text(if (!running) "未发送" else if (lit) "亮" else "灭", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.Black.copy(alpha = if (lit) 1f else 0.5f)) }
        if (running) Note("闪光灯信号在后台服务里运行：关屏、切到别的应用也会继续闪，通知栏有“停止”按钮。离开本页会自动停止。")

        Card2 {
            Text("闪光灯 SOS", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Note("· · ·  — — —  · · ·，循环。国际求救信号；对着飞机、远处的人或船只发。")
            if (mode == "sos") BigButton("停止", { stop() }, danger = true) else BigButton("开始 SOS 闪光", { start("sos") })
        }
        Card2 {
            Text("文字转摩尔斯闪光", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            OutlinedTextField(
                text, { text = it.uppercase().filter { c -> c.isLetterOrDigit() && c.code < 128 || c == ' ' }.take(40) },
                Modifier.fillMaxWidth(), label = { Text("英文字母和数字") }, singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontSize = 20.sp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            )
            val dots = Morse.toDots(text)
            if (dots.isNotEmpty()) Text(dots.replace('.', '·').replace('-', '—'), fontSize = 20.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium)
            Note("点 1 单位、划 3 单位，字内间隔 1、字母间 3、单词间 7；1 单位 = 250 ms。")
            if (mode == "text") BigButton("停止", { stop() }, danger = true)
            else BigButton("循环闪光", { start("text") }, enabled = Morse.steps(text).isNotEmpty())
        }
        Card2 {
            Text("屏幕信号灯", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Note("全屏纯色并把亮度调到最大，点一下屏幕退出。夜里照明、给救援者看位置。很费电，不用就关。")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(blink, { blink = it }); Spacer(Modifier.width(10.dp)); Text("闪烁", fontSize = 18.sp)
            }
            BigButton("白光", { screenSos = false; screenLight = "white" })
            BigButton("红光（保留夜视）", { screenSos = false; screenLight = "red" }, outlined = true)
        }
    }

    if (screenLight != "") ScreenLight(red = screenLight == "red", blink = blink, steps = if (screenSos) steps.ifEmpty { Morse.steps("SOS") } else null, onClose = { screenLight = ""; screenSos = false })
}

// 全屏纯色对话框：亮度拉到最大并保持亮屏；steps 非空时按摩尔斯闪，否则 blink 决定是否每 0.5 秒闪
@Composable
private fun ScreenLight(red: Boolean, blink: Boolean, steps: List<Morse.Step>?, onClose: () -> Unit) {
    var on by remember { mutableStateOf(true) }
    LaunchedEffect(blink, steps) {
        on = true
        if (steps != null) while (true) for (s in steps) { on = s.on; delay(s.units * Morse.UNIT_MS) } // 按摩尔斯时序闪
        else if (blink) while (true) { delay(500); on = !on }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val act = LocalContext.current.activity()
        val dw = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(Unit) {
            val before = act?.window?.attributes?.screenBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            fun bright(w: android.view.Window?, v: Float) { w?.let { val a = it.attributes; a.screenBrightness = v; it.attributes = a } }
            bright(act?.window, 1f); bright(dw, 1f)
            dw?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose { bright(act?.window, before) }
        }
        Box(
            Modifier.fillMaxSize().background(if (!on) Color.Black else if (red) Color(0xFFFF0000) else Color.White).clickable(onClick = onClose),
            contentAlignment = Alignment.BottomCenter,
        ) { Text("点一下屏幕退出", Modifier.padding(bottom = 48.dp), color = if (on) Color.Gray else Color.DarkGray, fontSize = 14.sp) }
    }
}
