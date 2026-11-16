package app.ark.sensors

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * 抗干扰的方位引擎（地图页、工具页共用一份）：
 *
 *   真北方位 = 陀螺方位（GAME_ROTATION_VECTOR，只用陀螺仪 + 加速度计，不受磁场影响） + 偏移量
 *
 * 偏移量的来源，按可信程度：
 *   1. 天体校准：手机顶边对准已知方位的天体（太阳、月亮、北极星等亮星，方位由时间和位置算出）后调用 calibrate()。
 *      之后 30 分钟内不用磁力计修正（陀螺漂移一般每 10 分钟几度以内，超过 30 分钟提示重新校准）。
 *   2. 磁力计：只在“磁场干净”时缓慢修正偏移（时间常数约 3 秒），判定条件全部满足才算干净：
 *      - 磁场强度和当地理论值（GeomagneticField）相差 15% 以内（不知道位置时放宽到 25–65 µT）；
 *      - 系统给的磁力计精度不低于 MEDIUM；
 *      - “磁方位 − 陀螺方位”这个差值稳定（短时抖动 < 8°）。车、钢筋、磁吸手机壳、电机附近这个差值会乱跳。
 *      不干净时停止修正，陀螺保持原来的方位（状态显示“磁干扰，陀螺保持”）。
 *   没有陀螺仪的手机退回到纯磁力计（ROTATION_VECTOR 或 加速度计 + 磁力计）。
 *
 * 方位指的是“手机顶边”的水平方向；手机竖起来（顶边仰角 > 60°）时改用摄像头朝向，竖着拿也不会乱转。
 */
object Heading {
    enum class Source(val label: String) { MAG("磁力计"), HOLD("磁干扰，陀螺保持"), CELESTIAL("天体校准"), NONE("未校准") }

    /** 手机顶边指向（用于对星）：真北方位（未校准时 null）、仰角（度，向上为正）。 */
    data class Pointing(val azimuth: Float?, val elevation: Float)

    private val _state = MutableStateFlow<Compass?>(null)
    val state: StateFlow<Compass?> = _state
    private val _pointing = MutableStateFlow<Pointing?>(null)
    val pointing: StateFlow<Pointing?> = _pointing
    private val _camera = MutableStateFlow<Pointing?>(null)
    /** 摄像头光轴（设备 −Z）指向：真北方位（offset 未知时 null）、仰角。相机对准模式用。 */
    val camera: StateFlow<Pointing?> = _camera

    @Volatile var lat: Double? = null; private set
    @Volatile var lon: Double? = null; private set
    private var decl = 0f            // 磁偏角（东偏为正）
    private var expectedUT = 0f      // 当地理论磁场强度（µT），0 = 不知道

    private var users = 0
    private var sm: SensorManager? = null
    private val r = FloatArray(9)
    private var gyroOk = false

    private var offset = Float.NaN   // 真北 − 陀螺方位
    private var celestialAt = 0L     // 上次天体校准的时间
    private var diffAvg = Float.NaN  // “磁方位 − 陀螺方位”的滑动平均
    private var diffDev = 0f         // 它的平均偏离（抖动程度）
    private var magAcc = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
    private var fieldUT = 0f
    private var magHeading = Float.NaN // 磁力计算出的真北方位（已加磁偏角）
    private var gyroHeading = Float.NaN
    private var gyroPointAz = Float.NaN; private var pointEl = 0f
    private var gyroCamAz = Float.NaN; private var camEl = 0f // 摄像头（−Z）的陀螺方位 / 仰角
    private val rotCopy = FloatArray(9) // 当前陀螺旋转矩阵（回调线程写，rotation() 读）
    private var smooth = Float.NaN

    const val CELESTIAL_HOLD_MS = 30 * 60_000L

    fun setLocation(la: Double?, lo: Double?) {
        if (la == null || lo == null) return
        if (lat != null && abs(la - lat!!) < 0.5 && abs(lo - lon!!) < 0.5) return
        lat = la; lon = lo
        val g = GeomagneticField(la.toFloat(), lo.toFloat(), 0f, System.currentTimeMillis())
        decl = g.declination
        expectedUT = g.fieldStrength / 1000f // nT → µT
    }

    /** 天体校准：此刻手机顶边正对着真北方位 trueAzimuth（度）的天体。返回是否成功（没有陀螺仪时不能校准）。 */
    fun calibrate(trueAzimuth: Float): Boolean {
        if (!gyroOk || gyroPointAz.isNaN()) return false
        offset = wrap(trueAzimuth - gyroPointAz)
        celestialAt = System.currentTimeMillis()
        smooth = Float.NaN
        return true
    }

    /** 同 calibrate，但此刻是摄像头光轴（手机背面）正对真北方位 trueAzimuth 的天体。 */
    fun calibrateCamera(trueAzimuth: Float): Boolean {
        if (!gyroOk || gyroCamAz.isNaN()) return false
        offset = wrap(trueAzimuth - gyroCamAz)
        celestialAt = System.currentTimeMillis()
        smooth = Float.NaN
        return true
    }

    /** 当前陀螺旋转矩阵（3x3 行主序，设备 → 陀螺世界坐标）的副本；没有陀螺仪或还没读数时 null。 */
    fun rotation(): FloatArray? = if (gyroOk && !gyroHeading.isNaN()) rotCopy.copyOf() else null

    /** 不分配对象的版本：把旋转矩阵写进 out（长度 ≥ 9），返回是否可用。 */
    fun rotationInto(out: FloatArray): Boolean {
        if (!gyroOk || gyroHeading.isNaN()) return false
        System.arraycopy(rotCopy, 0, out, 0, 9)
        return true
    }

    /** 方位偏移量（真北 − 陀螺方位，度）；还没有时 NaN。陀螺世界方位 = 真方位 − offsetDeg()。 */
    fun offsetDeg(): Float = offset

    /** 清除天体校准，回到磁力计。 */
    fun clearCalibration() { celestialAt = 0L }

    fun start(ctx: Context) {
        if (users++ > 0) return
        val m = ctx.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sm = m
        val game = m.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        val rot = m.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        gyroOk = game != null
        game?.let { m.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
        rot?.let { m.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
        if (rot == null) m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { m.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
        m.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let { m.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        if (--users > 0) return
        users = 0
        sm?.unregisterListener(listener)
        sm = null
    }

    private var grav: FloatArray? = null
    private var mag: FloatArray? = null

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            when (e.sensor.type) {
                Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                    SensorManager.getRotationMatrixFromVector(r, e.values)
                    val (az, el) = direction(r)
                    gyroHeading = az
                    val (paz, pel) = topEdge(r)
                    gyroPointAz = paz; pointEl = pel
                    System.arraycopy(r, 0, rotCopy, 0, 9)
                    gyroCamAz = wrap(Math.toDegrees(atan2(-r[2].toDouble(), -r[5].toDouble())).toFloat())
                    camEl = Math.toDegrees(asin((-r[8]).coerceIn(-1f, 1f).toDouble())).toFloat()
                    update()
                }
                Sensor.TYPE_ROTATION_VECTOR -> {
                    val rm = FloatArray(9)
                    SensorManager.getRotationMatrixFromVector(rm, e.values)
                    magHeading = wrap(direction(rm).first + decl)
                    if (!gyroOk) update()
                }
                Sensor.TYPE_ACCELEROMETER -> grav = e.values.clone()
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    mag = e.values.clone()
                    magAcc = e.accuracy
                    fieldUT = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
                    val g = grav
                    if (g != null && sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) == null) {
                        val rm = FloatArray(9)
                        if (SensorManager.getRotationMatrix(rm, null, g, e.values)) magHeading = wrap(direction(rm).first + decl)
                        if (!gyroOk) update()
                    }
                }
            }
        }

        override fun onAccuracyChanged(s: Sensor, a: Int) { if (s.type == Sensor.TYPE_MAGNETIC_FIELD) magAcc = a }
    }

    private fun fieldClean(): Boolean {
        if (fieldUT <= 0f) return false
        val okStrength = if (expectedUT > 0f) abs(fieldUT / expectedUT - 1f) <= 0.15f else fieldUT in 25f..65f
        return okStrength && magAcc >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
    }

    private fun update() {
        val now = System.currentTimeMillis()
        val celestial = celestialAt > 0 && now - celestialAt < CELESTIAL_HOLD_MS
        var src: Source
        val h: Float
        if (gyroOk && !gyroHeading.isNaN()) {
            var clean = fieldClean() && !magHeading.isNaN()
            if (!magHeading.isNaN()) {
                val d = wrap(magHeading - gyroHeading)
                if (diffAvg.isNaN()) diffAvg = d
                val dev = abs(wrapSigned(d - diffAvg))
                diffDev = diffDev * 0.95f + dev * 0.05f
                diffAvg = wrap(diffAvg + wrapSigned(d - diffAvg) * 0.05f)
                if (diffDev > 8f) clean = false
                if (clean && !celestial) {
                    offset = if (offset.isNaN()) d else wrap(offset + wrapSigned(d - offset) * 0.02f)
                }
            }
            src = when {
                celestial -> Source.CELESTIAL
                offset.isNaN() -> Source.NONE
                clean -> Source.MAG
                else -> Source.HOLD
            }
            if (offset.isNaN()) { _pointing.value = Pointing(null, pointEl); _camera.value = Pointing(null, camEl); publish(Float.NaN, src, !clean, now); return } // 还没有方位偏移也要给仰角，天体校准需要
            h = wrap(gyroHeading + offset)
            _pointing.value = Pointing(wrap(gyroPointAz + offset), pointEl)
            _camera.value = Pointing(wrap(gyroCamAz + offset), camEl)
            publish(h, src, !clean, now)
            return
        } else {
            if (magHeading.isNaN()) return
            src = if (fieldClean()) Source.MAG else Source.HOLD // 没有陀螺仪：只能提示干扰，没法保持
            h = magHeading
            _pointing.value = Pointing(null, pointEl)
        }
        publish(h, src, !fieldClean(), now)
    }

    private fun publish(h: Float, src: Source, interference: Boolean, now: Long) {
        if (h.isNaN()) {
            _state.value = Compass(Float.NaN, lat == null, magAcc, src, interference, fieldUT, expectedUT, 0)
            return
        }
        smooth = if (smooth.isNaN()) h else wrap(smooth + wrapSigned(h - smooth) * 0.25f)
        val age = if (celestialAt > 0) ((now - celestialAt) / 60_000L).toInt() else -1
        _state.value = Compass(smooth, lat == null && src != Source.CELESTIAL, magAcc, src, interference, fieldUT, expectedUT, age)
    }

    /** 显示用方位：顶边水平方向；顶边仰角 > 60°（手机竖起）时改用摄像头（−Z）方向。返回（方位, 仰角）。 */
    private fun direction(m: FloatArray): Pair<Float, Float> {
        val top = topEdge(m)
        if (abs(top.second) <= 60f) return top
        // 摄像头方向 = 设备 −Z 轴在世界坐标中的方向
        val x = -m[2]; val y = -m[5]; val z = -m[8]
        return Pair(wrap(Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat()), Math.toDegrees(asin(z.coerceIn(-1f, 1f).toDouble())).toFloat())
    }

    /** 设备 +Y 轴（顶边）在世界坐标（东、北、上）中的方位和仰角。 */
    private fun topEdge(m: FloatArray): Pair<Float, Float> {
        val x = m[1]; val y = m[4]; val z = m[7]
        return Pair(wrap(Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat()), Math.toDegrees(asin(z.coerceIn(-1f, 1f).toDouble())).toFloat())
    }

    fun wrap(a: Float) = ((a % 360f) + 360f) % 360f
    fun wrapSigned(a: Float): Float { var d = wrap(a); if (d > 180f) d -= 360f; return d }
}
