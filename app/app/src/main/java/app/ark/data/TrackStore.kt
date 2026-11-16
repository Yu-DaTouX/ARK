package app.ark.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

data class TrackPoint(val time: Long, val lat: Double, val lon: Double, val alt: Double?)

/**
 * 轨迹：filesDir/track.csv，每行 “毫秒时间,纬度,经度,海拔(可空)”，追加写入，崩溃/没电也不丢已记的点。
 * recording 是“后台服务是否在记录”的状态（TrackService 设置）。
 */
object TrackStore {
    private var file: File? = null
    private val _points = MutableStateFlow<List<TrackPoint>>(emptyList())
    val points: StateFlow<List<TrackPoint>> = _points
    val recording = MutableStateFlow(false)
    private const val MAX = 50_000

    @Synchronized fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.applicationContext.filesDir, "track.csv")
        file = f
        if (!f.exists()) return
        runCatching {
            _points.value = f.readLines().mapNotNull { ln ->
                val p = ln.split(',')
                if (p.size < 3) null else runCatching {
                    TrackPoint(p[0].toLong(), p[1].toDouble(), p[2].toDouble(), p.getOrNull(3)?.toDoubleOrNull())
                }.getOrNull()
            }
        }
    }

    /** 写盘失败时的提示（null = 正常）；失败的点不进内存，界面不会显示盘上并不存在的轨迹。 */
    val error = MutableStateFlow<String?>(null)

    /** 返回 false = 这个点没存下来（写盘失败）。 */
    @Synchronized fun add(p: TrackPoint): Boolean {
        var l = _points.value
        if (l.size >= MAX) {
            // 满了不再静默丢点：每隔一个点抽稀一半（覆盖范围不变、密度减半），原子重写文件后继续记
            val thin = l.filterIndexed { i, _ -> i % 2 == 0 || i == l.size - 1 }
            if (!rewrite(thin)) { error.value = "轨迹已满且无法整理"; return false }
            l = thin
        }
        val ok = runCatching { file?.appendText("${p.time},${p.lat},${p.lon},${p.alt ?: ""}\n") }.isSuccess
        if (!ok) { error.value = "轨迹写入失败（存储空间不足？）"; return false }
        error.value = null
        _points.value = l + p
        return true
    }

    private fun rewrite(l: List<TrackPoint>): Boolean {
        val f = file ?: return true
        val af = android.util.AtomicFile(f)
        var os: java.io.FileOutputStream? = null
        return try {
            os = af.startWrite()
            os.write(l.joinToString("") { "${it.time},${it.lat},${it.lon},${it.alt ?: ""}\n" }.toByteArray())
            af.finishWrite(os)
            _points.value = l
            true
        } catch (t: Throwable) {
            os?.let { af.failWrite(it) }
            false
        }
    }

    @Synchronized fun clear() {
        _points.value = emptyList()
        runCatching { file?.delete() }
    }
}
