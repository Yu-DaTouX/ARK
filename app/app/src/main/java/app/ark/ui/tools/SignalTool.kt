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

/** 手电筒：setTorchMode 从 API 23 起不需要 CAMERA 权限。 */
private class Torch(ctx: Context) {
    private val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val id: String? = try {
        cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
    } catch (_: Exception) { null }
    var failed = false
    fun set(on: Boolean) {
        val i = id ?: return
        try { cm.setTorchMode(i, on) } catch (_: Exception) { failed = true }
    }
}

@Composable
fun SignalTool(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val torch = remember { Torch(ctx) }
    var mode by rememberSaveable { mutableStateOf("") } // "" 停止 / "sos" / "text"
    var text by rememberSaveable { mutableStateOf("HELP") }
    var lit by remember { mutableStateOf(false) }
    var screenLight by rememberSaveable { mutableStateOf("") } // "" / "white" / "red"
    var blink by rememberSaveable { mutableStateOf(false) }

    val steps = remember(mode, text) {
        when (mode) { "sos" -> Morse.steps("SOS"); "text" -> Morse.steps(text); else -> emptyList() }
    }
    LaunchedEffect(steps) {
        if (steps.isEmpty()) return@LaunchedEffect
        try {
            while (true) for (s in steps) { torch.set(s.on); lit = s.on; delay(s.units * Morse.UNIT_MS) }
        } finally { torch.set(false); lit = false }
    }
    val keepOn = mode != "" || screenLight != ""
    DisposableEffect(keepOn) { view.keepScreenOn = keepOn; onDispose { view.keepScreenOn = false } } // 等价于 FLAG_KEEP_SCREEN_ON
    DisposableEffect(Unit) { onDispose { torch.set(false) } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { mode = ""; screenLight = "" } // 切到后台就停，省电

    ToolPage("信号", onBack) {
        if (torch.id == null) Warn("本机没有闪光灯：只能用屏幕信号灯，或看下面的方块闪烁。")
        Box(
            Modifier.fillMaxWidth().height(90.dp).background(if (lit) Color(0xFFFFEB3B) else MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.large),
            contentAlignment = Alignment.Center,
        ) { Text(if (mode == "") "未发送" else if (lit) "亮" else "灭", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.Black.copy(alpha = if (lit) 1f else 0.5f)) }

        Card2 {
            Text("闪光灯 SOS", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Note("· · ·  — — —  · · ·，循环。国际求救信号；对着飞机、远处的人或船只发。")
            if (mode == "sos") BigButton("停止", { mode = "" }, danger = true) else BigButton("开始 SOS 闪光", { mode = "sos" })
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
            if (mode == "text") BigButton("停止", { mode = "" }, danger = true)
            else BigButton("循环闪光", { mode = "text" }, enabled = Morse.steps(text).isNotEmpty())
        }
        Card2 {
            Text("屏幕信号灯", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Note("全屏纯色并把亮度调到最大，点一下屏幕退出。夜里照明、给救援者看位置。很费电，不用就关。")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(blink, { blink = it }); Spacer(Modifier.width(10.dp)); Text("闪烁", fontSize = 18.sp)
            }
            BigButton("白光", { screenLight = "white" })
            BigButton("红光（保留夜视）", { screenLight = "red" }, outlined = true)
        }
    }

    if (screenLight != "") ScreenLight(red = screenLight == "red", blink = blink, onClose = { screenLight = "" })
}

@Composable
private fun ScreenLight(red: Boolean, blink: Boolean, onClose: () -> Unit) {
    var on by remember { mutableStateOf(true) }
    LaunchedEffect(blink) { on = true; if (blink) while (true) { delay(500); on = !on } }
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
