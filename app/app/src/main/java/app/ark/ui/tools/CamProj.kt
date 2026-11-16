/*
 * CamProj.kt —— 天体定向“相机取景”叠加层用的投影数学（把天体方位/高度投到屏幕像素）。AstroCamera 使用。
 */
package app.ark.ui.tools

import kotlin.math.*

/**
 * 相机叠加层的投影（纯函数，无 Android 依赖，方便在电脑上验证）。
 *
 * 坐标约定：
 *  - rot：SensorManager.getRotationMatrixFromVector 给出的 3×3 行主序矩阵，设备坐标 → 陀螺世界坐标（东、北、上）。
 *    GAME_ROTATION_VECTOR 的世界“北”是任意的，真方位 = 陀螺方位 + offset。
 *  - 天体真方位 az（北起顺时针）、高度 alt → 陀螺世界方位 = az − offset → 世界单位向量 (sin·cos, cos·cos, sin)。
 *  - 设备向量 d = rotᵀ · 世界向量。竖屏手机后摄看向设备 −Z，屏幕向右 = 设备 +X，向上 = 设备 +Y。
 *  - 针孔模型：x = cx + f·dx / (−dz)，y = cy − f·dy / (−dz)；−dz ≤ 0 表示在相机后面，不画。
 */
object CamProj {
    /**
     * 屏幕像素焦距。fMm 实际焦距，sw/sh 传感器物理宽高（mm，横向传感器方向），
     * wp/hp 预览缓冲区像素（横向，宽 > 高），s 预览缓冲区像素 → 屏幕像素的缩放（FILL_CENTER：max(屏宽/hp, 屏高/wp)）。
     * 预览宽高比比传感器更宽时，上下被裁，水平视场仍是整个传感器宽；否则左右被裁，垂直视场是整个传感器高。
     */
    fun focalPx(fMm: Float, sw: Float, sh: Float, wp: Int, hp: Int, s: Float): Float {
        val fPreview = if (wp.toFloat() / hp >= sw / sh) fMm / sw * wp else fMm / sh * hp
        return fPreview * s
    }

    /** 投影；在相机前方返回 true，结果写入 out[0]=x、out[1]=y（屏幕像素）。 */
    fun project(rot: FloatArray, offsetDeg: Float, azDeg: Double, altDeg: Double, f: Float, cx: Float, cy: Float, out: FloatArray): Boolean {
        val a = Math.toRadians(azDeg - offsetDeg.toDouble()); val h = Math.toRadians(altDeg)
        val e = (sin(a) * cos(h)).toFloat(); val n = (cos(a) * cos(h)).toFloat(); val u = sin(h).toFloat()
        val dx = rot[0] * e + rot[3] * n + rot[6] * u
        val dy = rot[1] * e + rot[4] * n + rot[7] * u
        val dz = rot[2] * e + rot[5] * n + rot[8] * u
        if (-dz <= 0.02f) return false
        out[0] = cx + f * dx / -dz
        out[1] = cy - f * dy / -dz
        return true
    }

    /** 两个方向（方位、仰角，度）之间的角距（度）。 */
    fun angularDistance(az1: Double, el1: Double, az2: Double, el2: Double): Double {
        val a1 = Math.toRadians(el1); val a2 = Math.toRadians(el2); val d = Math.toRadians(az1 - az2)
        return Math.toDegrees(acos((sin(a1) * sin(a2) + cos(a1) * cos(a2) * cos(d)).coerceIn(-1.0, 1.0)))
    }
}
