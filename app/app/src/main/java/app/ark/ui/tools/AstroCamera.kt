/*
 * AstroCamera.kt —— “天体定向”的相机取景：Camera2 预览 + 天体标记叠加（CamProj 投影），
 * 准星 = 相机光轴，用户把天体放到准星上点“对准了”，以其真方位校准陀螺方位（比对着星图更准）。
 * 约束：锁竖屏（投影按竖屏设备坐标算）、退到后台即关相机；对焦固定无穷远；预览尺寸取宽 ≤ 1280 的最接近 16:9，省算力。
 * 方位来源 Heading.camera：陀螺方位未校准（azimuth 为 null）时必须由用户按名字手选天体，只用仰角校准。
 * 权限：CAMERA（运行时申请）；摄像头只在此对话框打开期间占用。
 */
package app.ark.ui.tools

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import app.ark.sensors.Heading
import app.ark.sensors.bearingName
import app.ark.sensors.rememberCompass
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max

private fun Context.act(): Activity? = when (this) { is Activity -> this; is ContextWrapper -> baseContext.act(); else -> null }

/** 选好的摄像头和预览尺寸（横向缓冲区，宽 > 高），以及算焦距用的光学参数。 */
private class CamSetup(val id: String, val size: Size, val fMm: Float, val sw: Float, val sh: Float, val minFocus: Float)

/** 后摄；预览尺寸取宽 ≤ 1280 里最接近 16:9 的（低端机省算力）。没有后摄返回 null。 */
private fun pickCamera(ctx: Context): CamSetup? = try {
    val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
    id?.let {
        val ch = cm.getCameraCharacteristics(it)
        val sizes = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(SurfaceTexture::class.java).orEmpty()
        val size = sizes.filter { s -> s.width in 640..1280 && s.width > s.height }
            .sortedWith(compareBy({ s -> abs(s.width.toFloat() / s.height - 16f / 9f) }, { s -> -s.width.toLong() * s.height }))
            .firstOrNull() ?: Size(1280, 720)
        val f = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 4.3f
        val ps = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        CamSetup(it, size, f, ps?.width ?: 6.4f, ps?.height ?: 4.8f, ch.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f)
    }
} catch (_: Exception) { null }

/**
 * Camera2 预览（不引入 CameraX：不加依赖、包体不增加）。close() 可重复调用、可在任何时刻调用：
 * 若 close() 时设备还没打开完（openCamera 在途），不立刻退出线程，等 onOpened 里发现已关闭就 d.close()，
 * 再由 onClosed 退出线程，避免 CameraDevice 泄漏。状态切换用 lock 保护，避免 onOpened 与 close() 交错。
 */
private class CamRunner(private val ctx: Context, private val setup: CamSetup) {
    private val thread = HandlerThread("ark-cam").also { it.start() }
    private val handler = Handler(thread.looper)
    private val lock = Any()
    @Volatile private var closed = false
    @Volatile private var opening = false
    @Volatile private var dev: CameraDevice? = null
    @Volatile private var sess: CameraCaptureSession? = null
    private var quit = false

    private fun quitThread() { synchronized(lock) { if (!quit) { quit = true; thread.quitSafely() } } }

    @SuppressLint("MissingPermission")
    fun open(st: SurfaceTexture) {
        synchronized(lock) { if (closed || opening) return; opening = true }
        st.setDefaultBufferSize(setup.size.width, setup.size.height)
        val surface = Surface(st)
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            cm.openCamera(setup.id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) {
                    val shut = synchronized(lock) { opening = false; if (closed) true else { dev = d; false } }
                    if (shut) { d.close(); return } // onClosed 会退出线程
                    try {
                        @Suppress("DEPRECATION")
                        d.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                val shut2 = synchronized(lock) { if (closed) true else { sess = s; false } }
                                if (shut2) { s.close(); return }
                                try {
                                    val rb = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                    rb.addTarget(surface)
                                    if (setup.minFocus > 0f) { // 对焦无穷远：看星看月亮都清楚
                                        rb.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                                        rb.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
                                    }
                                    s.setRepeatingRequest(rb.build(), null, handler)
                                } catch (_: Exception) {}
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) {}
                        }, handler)
                    } catch (_: Exception) {}
                }
                override fun onDisconnected(d: CameraDevice) { opening = false; d.close() }
                override fun onError(d: CameraDevice, e: Int) { opening = false; d.close() }
                override fun onClosed(d: CameraDevice) { quitThread() }
            }, handler)
        } catch (_: Exception) {
            synchronized(lock) { opening = false }
            if (closed) quitThread()
        }
    }

    fun close() {
        val d: CameraDevice?; val s: CameraCaptureSession?
        synchronized(lock) {
            if (closed) return
            closed = true
            d = dev; s = sess; dev = null; sess = null
        }
        try { s?.close() } catch (_: Exception) {}
        if (d != null) { try { d.close() } catch (_: Exception) {} } // onClosed 退出线程
        else if (!opening) quitThread()                               // 没有设备也没有在打开：直接退出
        // else：openCamera 在途，onOpened / onError 里会关掉并退出
    }
}

/** 相机对准：全屏相机取景，叠加天体标记，十字准星 = 光轴方向，对准后校准方位。 */
// 全屏对话框；onClose 在用户返回、退后台或权限被拒时调用
@Composable
fun AstroCameraDialog(lat: Double, lon: Double, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        CameraContent(lat, lon, onClose)
    }
}

@Composable
private fun CameraContent(lat: Double, lon: Double, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    rememberCompass(lat, lon) // 启动 / 停止 Heading 引擎（引用计数）
    var granted by remember { mutableStateOf(ctx.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var asked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; asked = true }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    // 锁竖屏（投影按竖屏设备坐标算）、常亮；退出恢复
    DisposableEffect(Unit) {
        val a = ctx.act()
        val before = a?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        a?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        view.keepScreenOn = true
        (view.parent as? DialogWindowProvider)?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { a?.requestedOrientation = before; view.keepScreenOn = false }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { onClose() } // 退到后台就关相机

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (!granted) {
            Column(Modifier.fillMaxSize().padding(24.dp).statusBarsPadding(), verticalArrangement = Arrangement.Center) {
                Text(if (asked) "没有相机权限，无法取景。可以回到星图用“列表 + 星图”对准，或在系统设置里允许相机。" else "正在申请相机权限……", color = Color.White, fontSize = 18.sp)
                Spacer(Modifier.height(16.dp))
                BigButton("返回", onClose)
            }
            return@Box
        }
        if (!Heading.hasGyro) {
            Column(Modifier.fillMaxSize().padding(24.dp).statusBarsPadding(), verticalArrangement = Arrangement.Center) {
                Text("本机没有陀螺仪，不能天体校准。", color = Color.White, fontSize = 18.sp)
                Spacer(Modifier.height(16.dp)); BigButton("返回", onClose)
            }
            return@Box
        }
        val setup = remember { pickCamera(ctx) }
        if (setup == null) {
            Column(Modifier.fillMaxSize().padding(24.dp).statusBarsPadding(), verticalArrangement = Arrangement.Center) {
                Text("本机没有可用的后置摄像头。", color = Color.White, fontSize = 18.sp)
                Spacer(Modifier.height(16.dp)); BigButton("返回", onClose)
            }
            return@Box
        }
        CameraBody(setup, lat, lon, onClose)
    }
}

@Composable
private fun BoxScope.CameraBody(setup: CamSetup, lat: Double, lon: Double, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val runner = remember { CamRunner(ctx, setup) }
    DisposableEffect(Unit) { onDispose { runner.close() } }

    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { nowMs = System.currentTimeMillis(); delay(2000) } }
    val bodies = remember(nowMs) { Astro.all(nowMs, lat, lon) }
    val cands = remember(bodies) { bodies.filter { it.alt > 2.0 && (it.kind != Astro.Kind.STAR || it.mag <= 2.5f) } }
    val sunAlt = bodies.firstOrNull { it.id == "sun" }?.alt ?: 0.0
    val cam by produceState<Heading.Pointing?>(null) { while (true) { value = Heading.camera.value; delay(150) } }
    var manual by remember { mutableStateOf("") }
    var showLines by remember { mutableStateOf(true) }
    var result by remember { mutableStateOf("") }
    val azKnown = cam?.azimuth != null

    fun dist(b: Astro.Body): Double {
        val c = cam ?: return 999.0
        val az = c.azimuth
        return if (az != null) CamProj.angularDistance(az.toDouble(), c.elevation.toDouble(), b.az, b.alt) else abs(c.elevation - b.alt)
    }
    // 方位已知：默认取离准星最近的；方位未知（offset NaN）：不自动选，必须用户按名字手选
    val target = remember(cam, cands, manual, azKnown) {
        val m = cands.firstOrNull { it.id == manual }
        if (azKnown) m ?: cands.minByOrNull { dist(it) } else m
    }
    val ranked = remember(cam, cands, azKnown) { if (azKnown || cam == null) emptyList() else cands.sortedBy { abs(cam!!.elevation - it.alt) }.take(14) }
    val tDist = target?.let { dist(it) }
    val dEl = if (cam != null && target != null) cam!!.elevation - target.alt.toFloat() else null
    val canCal = cam != null && target != null && abs(dEl!!) <= 3f && cam!!.elevation < 80f && target.alt < 80.0 &&
        (!azKnown || (tDist ?: 999.0) < 5.0) // 方位已知时目标必须离准星不到 5°

    // ---- 取景 + 叠加 ----
    val pw = setup.size.height.toFloat(); val ph = setup.size.width.toFloat() // 竖屏下预览的宽、高（缓冲区像素）
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { delay(50); frame++ } } // ~20 fps 重绘叠加层；只在绘制阶段读取，不触发重组
    val rot = remember { FloatArray(9) }
    val tmp = remember { FloatArray(2) }
    val tmp2 = remember { FloatArray(2) }
    val hitX = remember { FloatArray(80) }; val hitY = remember { FloatArray(80) }; val hitId = remember { arrayOfNulls<String>(80) }
    var hitN by remember { mutableIntStateOf(0) }
    val white = Color.White.toArgb()
    val txt = remember { Paint().apply { color = white; textSize = 13f * density.density; isAntiAlias = true; setShadowLayer(4f, 0f, 0f, android.graphics.Color.BLACK) } }
    val txtT = remember { Paint(txt).apply { color = 0xFF69F0AE.toInt(); isFakeBoldText = true } }
    val accent = Color(0xFF69F0AE)

    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val cw = constraints.maxWidth.toFloat(); val ch = constraints.maxHeight.toFloat()
        val s = max(cw / pw, ch / ph)
        AndroidView(
            factory = { c ->
                TextureView(c).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = runner.open(st)
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { runner.close(); return true }
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                    }
                }
            },
            modifier = Modifier.align(Alignment.Center).requiredSize(with(density) { (pw * s).toDp() }, with(density) { (ph * s).toDp() }),
        )
        val f = CamProj.focalPx(setup.fMm, setup.sw, setup.sh, setup.size.width, setup.size.height, s)

        Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures { tap ->
                var best = -1; var bd = 48.dp.toPx()
                for (i in 0 until hitN) { val d = Offset(hitX[i] - tap.x, hitY[i] - tap.y).getDistance(); if (d < bd) { bd = d; best = i } }
                if (best >= 0) manual = hitId[best] ?: ""
            }
        }) {
            frame // 读取以便每帧重绘
            val w = size.width; val h = size.height; val cx = w / 2; val cy = h / 2
            val okRot = Heading.rotationInto(rot)
            val off = Heading.offsetDeg().let { if (it.isNaN()) 0f else it }
            val known = !Heading.offsetDeg().isNaN()
            if (okRot) {
                // 地平线
                var px = 0f; var py = 0f; var pOk = false
                for (a in 0..360 step 5) {
                    val ok = CamProj.project(rot, off, a.toDouble(), 0.0, f, cx, cy, tmp)
                    if (ok && abs(tmp[0]) < 6000 && abs(tmp[1]) < 6000) {
                        if (pOk) drawLine(Color(0xFFFFB300), Offset(px, py), Offset(tmp[0], tmp[1]), 3f)
                        px = tmp[0]; py = tmp[1]; pOk = true
                    } else pOk = false
                }
                if (known) for ((t, a) in listOf("北" to 0.0, "东" to 90.0, "南" to 180.0, "西" to 270.0)) {
                    if (CamProj.project(rot, off, a, 0.0, f, cx, cy, tmp) && tmp[0] in 0f..w && tmp[1] in 0f..h)
                        drawIntoCanvas { it.nativeCanvas.drawText(t, tmp[0] - 6f, tmp[1] - 10f, txt) }
                }
                if (!known) { // 方位未知：只画目标所在的“等高线”（与方位无关），不画会误导的星位置
                    target?.let { t ->
                        var qx = 0f; var qy = 0f; var qOk = false; var best = Float.MAX_VALUE; var lx = 0f; var ly = 0f
                        for (a in 0..360 step 5) {
                            val ok = CamProj.project(rot, 0f, a.toDouble(), t.alt, f, cx, cy, tmp)
                            if (ok && abs(tmp[0]) < 6000 && abs(tmp[1]) < 6000) {
                                if (qOk) drawLine(Color(0xFF69F0AE), Offset(qx, qy), Offset(tmp[0], tmp[1]), 3f)
                                qx = tmp[0]; qy = tmp[1]; qOk = true
                                if (abs(tmp[0] - cx) < best) { best = abs(tmp[0] - cx); lx = tmp[0]; ly = tmp[1] }
                            } else qOk = false
                        }
                        drawIntoCanvas { it.nativeCanvas.drawText(t.name.substringBefore(' ') + " 高度 %.0f°".format(t.alt), 16f, ly - 10f, txtT) }
                    }
                } else {
                // 天极
                val (paz, palt) = Astro.pole(lat)
                if (CamProj.project(rot, off, paz, palt, f, cx, cy, tmp) && tmp[0] in -50f..w + 50 && tmp[1] in -50f..h + 50) {
                    drawCircle(Color(0xFF40C4FF), 10.dp.toPx(), Offset(tmp[0], tmp[1]), style = Stroke(3f))
                    drawIntoCanvas { it.nativeCanvas.drawText(if (lat >= 0) "北天极" else "南天极", tmp[0] + 14.dp.toPx(), tmp[1], txt) }
                }
                // 星座连线
                if (showLines) for ((a, b) in Astro.lines) {
                    val x = bodies.firstOrNull { it.id == a } ?: continue; val y = bodies.firstOrNull { it.id == b } ?: continue
                    if (CamProj.project(rot, off, x.az, x.alt, f, cx, cy, tmp) && CamProj.project(rot, off, y.az, y.alt, f, cx, cy, tmp2) &&
                        abs(tmp[0]) < 4000 && abs(tmp2[0]) < 4000 && abs(tmp[1]) < 4000 && abs(tmp2[1]) < 4000)
                        drawLine(Color.White.copy(alpha = 0.35f), Offset(tmp[0], tmp[1]), Offset(tmp2[0], tmp2[1]), 2f)
                }
                // 天体
                var n = 0
                for (b in cands) {
                    if (!CamProj.project(rot, off, b.az, b.alt, f, cx, cy, tmp)) continue
                    val x = tmp[0]; val y = tmp[1]
                    if (x < -40f || x > w + 40f || y < -40f || y > h + 40f) continue
                    val isT = b.id == target?.id
                    when (b.kind) {
                        Astro.Kind.SUN -> drawCircle(Color(0xFFFFC107), 8.dp.toPx(), Offset(x, y), style = Stroke(4f))
                        Astro.Kind.MOON -> drawCircle(Color(0xFFCFD8DC), 8.dp.toPx(), Offset(x, y), style = Stroke(4f))
                        else -> drawCircle(if (isT) accent else Color.White, 5.dp.toPx(), Offset(x, y), style = Stroke(3f))
                    }
                    if (isT) drawCircle(accent, 16.dp.toPx(), Offset(x, y), style = Stroke(3f))
                    drawIntoCanvas { it.nativeCanvas.drawText(b.name.substringBefore(' '), x + 12.dp.toPx(), y - 8.dp.toPx(), if (isT) txtT else txt) }
                    if (n < hitId.size) { hitX[n] = x; hitY[n] = y; hitId[n] = b.id; n++ }
                }
                hitN = n
                }
            }
            // 十字准星 = 光轴
            val g = 10.dp.toPx(); val l = 34.dp.toPx()
            for (col in listOf(Color.Black to 7f, Color.White to 3f)) {
                drawLine(col.first, Offset(cx - l, cy), Offset(cx - g, cy), col.second); drawLine(col.first, Offset(cx + g, cy), Offset(cx + l, cy), col.second)
                drawLine(col.first, Offset(cx, cy - l), Offset(cx, cy - g), col.second); drawLine(col.first, Offset(cx, cy + g), Offset(cx, cy + l), col.second)
            }
            drawCircle(Color.White, 3.dp.toPx(), Offset(cx, cy), style = Stroke(2f))
        }
    }

    // ---- 顶部 ----
    Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClose, Modifier.size(56.dp).background(Color(0x99000000), MaterialTheme.shapes.large)) { Icon(Icons.Filled.Close, "关闭", tint = Color.White) }
            Spacer(Modifier.weight(1f))
            FilterChip(showLines, { showLines = !showLines }, { Text("星座连线", fontSize = 15.sp) }, Modifier.heightIn(min = 48.dp))
        }
        if (cam != null && !azKnown) Surface(color = Color(0xE6B71C1C), shape = MaterialTheme.shapes.medium) {
            Text("方位未知，只有高度可信，所以不显示星的位置，只画地平线和目标的等高线。请在下方按名字选一个你能认出的天体（已按与准星高度的差排序），在画面里找到它放进准星后点“对准了”。", Modifier.padding(12.dp), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }

    // ---- 底部 ----
    Column(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(8.dp)
            .background(Color(0xB3000000), MaterialTheme.shapes.large).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (target != null && cam != null) {
            Text(
                "目标：${target.name} · " + (if (azKnown) "角距 %.1f°".format(tDist) else "高度差 %.1f°（方位未知）".format(tDist)) +
                    "\n它的真方位 %.0f° %s，高度 %.0f°".format(target.az, bearingName(target.az.toFloat()), target.alt),
                color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp,
            )
            Text("准星仰角 %.0f°，与目标高度差 %+.1f°".format(cam!!.elevation, dEl), color = if (canCal) accent else Color(0xFFFFAB91), fontSize = 15.sp)
        } else if (cam != null) Text("方位未知：请从下面选一个天体作为目标。", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        else Text("等待陀螺仪读数……", color = Color.White, fontSize = 16.sp)
        if (!azKnown && cam != null) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ranked.forEach { b -> FilterChip(manual == b.id, { manual = b.id }, { Text(b.name.substringBefore(' ') + " 高%.0f°".format(b.alt), fontSize = 15.sp) }, Modifier.heightIn(min = 48.dp)) }
        }
        val tip = when {
            target?.kind == Astro.Kind.SUN || sunAlt > 0 -> "相机可以短时间对着太阳取景，但人眼不要直视太阳；用屏幕看。"
            sunAlt < -6 -> "夜里相机通常只拍得到月亮、行星和最亮的几颗星；看不到星时，把手机举到眼前，沿着手机背面方向对准肉眼看到的星，再点“对准了”。"
            else -> null
        }
        tip?.let { Text(it, color = Color(0xFFFFE082), fontSize = 13.sp, lineHeight = 18.sp) }
        if (target != null && !canCal && cam != null) Text(
            when {
                azKnown && (tDist ?: 0.0) >= 5.0 -> "目标离准星还有 %.0f°：把目标放到准星中心（角距要小于 5°）才能校准。".format(tDist)
                cam!!.elevation >= 80f || target.alt >= 80.0 -> "太接近头顶，方位不稳，换一个低一点的目标。"
                else -> "准星仰角与目标高度差超过 3°：${if (dEl!! > 0) "手机放低一点" else "手机抬高一点"}，让目标落在准星高度。"
            }, color = Color(0xFFFFAB91), fontSize = 14.sp)
        if (azKnown) Text("点画面上的名字可换目标。", color = Color(0xFFB0BEC5), fontSize = 12.sp)
        BigButton("对准了", {
            val t = target; val before = cam?.azimuth
            if (t != null && Heading.calibrateCamera(t.az.toFloat())) {
                result = if (before != null) "已校准，修正了 %.1f°".format(abs(Heading.wrapSigned(t.az.toFloat() - before))) else "已校准（此前方位未知）"
            } else result = "校准失败：本机没有陀螺仪或还没有读数。"
        }, enabled = canCal)
        if (result.isNotEmpty()) Text(result, color = accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}
