package app.ark.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlin.math.pow

/**
 * 不联网也能用的传感器：指南针（旋转矢量 / 地磁）、气压计。地图页和工具页共用。
 * 只在界面显示时注册监听（rememberCompass / rememberBarometer），离开页面自动注销，不额外耗电。
 */

/**
 * 指南针读数（来自 Heading 引擎）。heading = 真北方位角，NaN 表示还没有可用方位；magnetic = true 表示未知位置、没修正磁偏角；
 * accuracy 用 SensorManager.SENSOR_STATUS_*；source 方位来源；interference 当前磁场不可信；
 * fieldUT / expectedUT 实测和当地理论磁场强度（µT，理论值 0 = 不知道位置）；celestialMin 距上次天体校准的分钟数（-1 = 没校准过）。
 */
data class Compass(
    val heading: Float, val magnetic: Boolean, val accuracy: Int,
    val source: Heading.Source = Heading.Source.MAG, val interference: Boolean = false,
    val fieldUT: Float = 0f, val expectedUT: Float = 0f, val celestialMin: Int = -1,
)

/** 气压读数（hPa）。 */
data class Pressure(val hPa: Float, val time: Long)

object Baro {
    const val STD = 1013.25f

    /** 由气压估算海拔（米）。seaLevel 为当地海平面气压，不知道就用标准大气压，误差可能有上百米，用 GPS 海拔校准后才准。 */
    fun altitude(hPa: Float, seaLevel: Float = STD): Float = 44330f * (1f - (hPa / seaLevel).toDouble().pow(1 / 5.255).toFloat())

    /** 已知所在海拔（例如 GPS 给出）反推海平面气压，用于校准。 */
    fun seaLevel(hPa: Float, altitudeM: Float): Float = (hPa / (1 - altitudeM / 44330.0).pow(5.255)).toFloat()

    /**
     * 气压趋势 → 天气提示（经验规则，3 小时变化量）：
     * 下降 ≥ 6 hPa 风暴可能临近；下降 ≥ 3 天气转坏；±1 内稳定；上升 ≥ 3 天气转好。
     */
    fun trendText(delta3h: Float): String = when {
        delta3h <= -6f -> "气压快速下降：可能有风暴，尽快找避难处"
        delta3h <= -3f -> "气压下降：天气可能转坏（降雨、起风）"
        delta3h < -1f -> "气压略降：留意天气变化"
        delta3h <= 1f -> "气压稳定：天气短时间内变化不大"
        delta3h < 3f -> "气压略升：天气趋于好转"
        else -> "气压上升：天气转好"
    }
}

/**
 * 记录最近几小时的气压，用来算趋势。每 5 分钟最多记一个点，保留 6 小时，存在 SharedPreferences，重启后保留（重启前后若隔了很久，旧点超过 6 小时会被丢掉）。
 */
object PressureLog {
    private val points = ArrayList<Pressure>()
    private var prefs: android.content.SharedPreferences? = null

    /** 绑定 SharedPreferences（"ark_pressure_log"）：把最近 6 小时的记录存下来，重启后接着用。重复调用无副作用。 */
    @Synchronized fun attach(ctx: Context) {
        if (prefs != null) return
        val sp = ctx.applicationContext.getSharedPreferences("ark_pressure_log", Context.MODE_PRIVATE)
        prefs = sp
        val now = System.currentTimeMillis()
        sp.getString("points", "")?.split(';')?.forEach { e ->
            val kv = e.split(':')
            val t = kv.getOrNull(0)?.toLongOrNull(); val h = kv.getOrNull(1)?.toFloatOrNull()
            if (t != null && h != null && t in (now - 6 * 3600_000L)..now && points.none { it.time >= t }) points.add(Pressure(h, t))
        }
        points.sortBy { it.time }
    }

    @Synchronized fun add(p: Pressure) {
        var changed = false
        if (points.isEmpty() || p.time - points.last().time >= 5 * 60_000L) { points += p; changed = true }
        val cut = p.time - 6 * 3600_000L
        if (points.removeAll { it.time < cut }) changed = true
        if (changed) prefs?.edit()?.putString("points", points.joinToString(";") { "${it.time}:${it.hPa}" })?.apply()
    }

    @Synchronized fun all(): List<Pressure> = points.toList()

    /** 3 小时变化量（hPa）；记录不足 1 小时返回 null。不足 3 小时按已有时长线性折算。 */
    @Synchronized fun delta3h(): Float? {
        if (points.size < 2) return null
        val b = points.last()
        val a = points.first { b.time - it.time <= 3 * 3600_000L } // 3 小时内最早的点
        val h = (b.time - a.time) / 3600_000f
        if (h < 1f) return null
        return (b.hPa - a.hPa) * 3f / h
    }
}

/**
 * 指南针：订阅 Heading 引擎（陀螺 + 磁力计融合、抗干扰、可天体校准）。lat/lon 已知时修正磁偏角。heading 为 NaN 时还没有方位。
 * stepDeg > 0：方位按这个步长取整、不带实时磁场强度，只在取整后的值变了才让调用处重组（地图页用，省掉 15 Hz 的整页重组）。
 */
@Composable
fun rememberCompass(lat: Double? = null, lon: Double? = null, stepDeg: Float = 0f): Compass? {
    val ctx = LocalContext.current
    LaunchedEffect(lat?.let { Math.round(it) }, lon?.let { Math.round(it) }) { Heading.setLocation(lat, lon) }
    // 跟随生命周期：退到后台（ON_STOP）就停传感器，省电；Heading.stop 5 秒后才真正注销，短暂切换不丢校准
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        var on = false
        fun up() { if (!on) { on = true; Heading.start(ctx) } }
        fun down() { if (on) { on = false; Heading.stop() } }
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_START) up() else if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) down()
        }
        if (owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) up()
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs); down() }
    }
    val raw = Heading.state.collectAsState()
    if (stepDeg > 0f) {
        val q by remember(stepDeg) {
            androidx.compose.runtime.derivedStateOf(androidx.compose.runtime.structuralEqualityPolicy()) {
                raw.value?.takeIf { !it.heading.isNaN() }?.let { it.copy(heading = Heading.wrap(Math.round(it.heading / stepDeg) * stepDeg), fieldUT = 0f) }
            }
        }
        return q
    }
    val c by raw
    return c?.takeIf { !it.heading.isNaN() }
}

/** 气压计：没有气压传感器的手机返回 null。读数同时记进 PressureLog。 */
@Composable
fun rememberBarometer(): Pressure? {
    val ctx = LocalContext.current
    var value by remember { mutableStateOf<Pressure?>(null) }
    DisposableEffect(Unit) {
        PressureLog.attach(ctx)
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val s = sm.getDefaultSensor(Sensor.TYPE_PRESSURE)
        var avg = Float.NaN
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                avg = if (avg.isNaN()) e.values[0] else avg * 0.9f + e.values[0] * 0.1f
                val p = Pressure(avg, System.currentTimeMillis())
                // 界面只显示 0.1 hPa / 1 m：变化不到 0.02 hPa（约 0.17 m）就不更新状态，免得整页（含地图页）5 Hz 重组
                val v = value
                if (v == null || kotlin.math.abs(v.hPa - avg) >= 0.02f) value = p
                PressureLog.add(p)
            }
            override fun onAccuracyChanged(s: Sensor, a: Int) {}
        }
        val owner = (ctx as? androidx.lifecycle.LifecycleOwner)
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) sm.unregisterListener(l)
            else if (e == androidx.lifecycle.Lifecycle.Event.ON_START) s?.let { sm.registerListener(l, it, SensorManager.SENSOR_DELAY_NORMAL) }
        }
        s?.let { sm.registerListener(l, it, SensorManager.SENSOR_DELAY_NORMAL) }
        owner?.lifecycle?.addObserver(obs)
        onDispose { owner?.lifecycle?.removeObserver(obs); sm.unregisterListener(l) }
    }
    return value
}

fun hasBarometer(ctx: Context) = (ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager).getDefaultSensor(Sensor.TYPE_PRESSURE) != null

/** 方位角 → 八方位中文，例如 45 → “东北”。 */
fun bearingName(deg: Float): String = listOf("北", "东北", "东", "东南", "南", "西南", "西", "西北")[(((deg % 360) + 360 + 22.5f) % 360 / 45).toInt() % 8]
