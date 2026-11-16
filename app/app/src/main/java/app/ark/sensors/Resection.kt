// 文件职责：两点后方交会的纯计算（见下方 KDoc）。调用方：地图页/工具页的“没有 GPS 时定位”。无 Android 依赖。
package app.ark.sensors

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * 两点后方交会（没有 GPS 时用指南针定位，FM 3-25.26 第 6 章 resection）：
 * 在地图上认出两个现实里看得见的地标，用指南针分别测出“从我到地标”的方位角，
 * 两条反方向的方位线交点就是自己的位置。
 * 几公里到几十公里范围内用局部等距平面近似（误差远小于手机指南针 2–5° 的误差）。
 */
object Resection {
    data class Landmark(val lat: Double, val lon: Double, val bearing: Float) // bearing：真北方位角，从我指向地标
    data class Fix(val lat: Double, val lon: Double, val angle: Float)        // angle：两条方位线的夹角，30–150° 才可靠

    /**
     * 求交点。两条方位线近乎平行（det<1e-6）或交点在地标“后面”（测量方向反了）时返回 null。
     * 返回的 [Fix.angle] 越接近 90° 越可靠；调用方应自己按 30–150° 提示可信度。
     */
    fun solve(a: Landmark, b: Landmark): Fix? {
        val lat0 = Math.toRadians((a.lat + b.lat) / 2)
        val kx = 111_320.0 * cos(lat0) // 每度经度的米数
        val ky = 110_540.0
        // 平面坐标（米），以地标 A 为原点；x 向东，y 向北
        val bx = (b.lon - a.lon) * kx
        val by = (b.lat - a.lat) * ky
        // 我 = A − s·u = B − t·v，u、v 是从我指向地标的单位向量
        val ua = Math.toRadians(a.bearing.toDouble()); val vb = Math.toRadians(b.bearing.toDouble())
        val ux = sin(ua); val uy = cos(ua); val vx = sin(vb); val vy = cos(vb)
        // s·u − t·v = A − B = (−bx, −by)
        val det = -ux * vy + vx * uy
        if (abs(det) < 1e-6) return null // 两条线平行
        val s = (-bx * -vy - -by * -vx) / det
        val t = (ux * -by - uy * -bx) / det
        if (s < 0 || t < 0) return null // 交点在地标“后面”，测量有误
        val px = -s * ux; val py = -s * uy
        var ang = abs(a.bearing - b.bearing) % 360f
        if (ang > 180f) ang = 360f - ang
        return Fix(a.lat + py / ky, a.lon + px / kx, ang)
    }
}
