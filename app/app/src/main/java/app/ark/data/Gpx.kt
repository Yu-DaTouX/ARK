/*
 * Gpx.kt —— 把航点和轨迹导出为 GPX 1.1，写到系统“下载”目录，便于换到其他导航软件。
 * 数据来自 WaypointStore / TrackStore；由地图页的导出按钮调用。
 */
package app.ark.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** GPX 1.1 导出（航点 + 轨迹），保存到系统“下载”目录。 */
object Gpx {
    // 时间一律 UTC（GPX 规范要求），坐标固定 Locale.US 和 6 位小数（约 0.1 m），避免小数点被本地化成逗号
    private fun iso(t: Long) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(t))
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** 生成 GPX 文本；没有轨迹点时不输出 <trk>。纯函数，任意线程。 */
    fun build(wps: List<Waypoint>, track: List<TrackPoint>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<gpx version="1.1" creator="方舟 ARK" xmlns="http://www.topografix.com/GPX/1/1">""").append('\n')
        for (w in wps) {
            append("""<wpt lat="%.6f" lon="%.6f"><time>%s</time><name>%s</name><type>%s</type></wpt>""".format(Locale.US, w.lat, w.lon, iso(w.time), esc(w.name), w.type.label)).append('\n')
        }
        if (track.isNotEmpty()) {
            append("<trk><name>方舟轨迹</name><trkseg>\n")
            for (p in track) {
                append("""<trkpt lat="%.6f" lon="%.6f">""".format(Locale.US, p.lat, p.lon))
                p.alt?.let { append("<ele>%.1f</ele>".format(Locale.US, it)) }
                append("<time>").append(iso(p.time)).append("</time></trkpt>\n")
            }
            append("</trkseg></trk>\n")
        }
        append("</gpx>\n")
    }

    /** 写到 Download/ark-时间.gpx。成功返回提示文字，失败抛异常。Android 9 及以下需要存储权限，没有时会失败。 */
    // 写文件（IO），请在后台线程调用
    fun exportToDownloads(ctx: Context, wps: List<Waypoint>, track: List<TrackPoint>): String {
        val name = "ark-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".gpx"
        val bytes = build(wps, track).toByteArray()
        // Android 10+ 用 MediaStore 写 Download（无需权限）；9 及以下走传统路径，需要 WRITE_EXTERNAL_STORAGE（清单里限 maxSdk 28）
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/gpx+xml")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: error("无法创建文件")
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("无法写入")
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            File(dir, name).writeBytes(bytes)
        }
        return "Download/$name"
    }
}
