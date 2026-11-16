package app.ark.ui.tools

/**
 * Zambretti 式简易天气预报。
 * 原版 Zambretti 预报器（Negretti & Zambra，1915）按“海平面气压 + 气压趋势 + 季节”给出 26 档预报。
 * 这里不是原版的 26 档公式，而是同一思路的三档简化：
 *  - 趋势：3 小时气压变化，±1.6 hPa 以内算稳定（Zambretti 类预报通用阈值），≤ −6 hPa 视为风暴前兆（和 Baro.trendText 一致）；
 *  - 气压水平：海平面气压 < 1005 低、1005–1020 中、> 1020 高 hPa（需要已知海拔才能换算，不知道就只按趋势）；
 *  - 季节：夏季下降多为阵雨 / 雷暴，冬季多为持续降水；南半球季节相反。
 * 仅供参考，局地天气可能完全不同。
 */
object Zambretti {
    class Result(val text: String, val season: String)

    /** @param seaLevelHPa 海平面气压（hPa），未知传 null。@param month 1–12。@param south 南半球。 */
    fun forecast(seaLevelHPa: Float?, delta3h: Float, month: Int, south: Boolean): Result {
        val summer = if (south) month in listOf(10, 11, 12, 1, 2, 3) else month in 4..9
        val season = if (summer) "夏半年：气压下降时多为阵雨或雷暴，常来得快去得快。" else "冬半年：气压下降时多为持续的雨雪和大风。"
        val level = when { seaLevelHPa == null -> 1; seaLevelHPa < 1005f -> 0; seaLevelHPa > 1020f -> 2; else -> 1 }
        val known = seaLevelHPa != null
        val text = when {
            delta3h <= -6f -> "风暴可能临近：大风、强降水，尽快找避难处。"
            delta3h <= -1.6f -> when {
                !known -> "天气将转坏：多云转阴，可能有雨或起风。"
                level == 2 -> "目前还算好，但将转阴，之后可能有阵雨。"
                level == 1 -> "将转阴，可能有雨、风渐大。"
                else -> "天气转坏，有雨、起风，可能持续。"
            }
            delta3h >= 1.6f -> when {
                !known -> "天气趋于好转，云渐散。"
                level == 2 -> "晴好稳定的天气。"
                level == 1 -> "天气转好，云渐散，转晴。"
                else -> "天气好转，但仍不稳定，可能还有短时阵雨。"
            }
            else -> when {
                !known -> "天气短时间变化不大。"
                level == 2 -> "晴好稳定，天气持续。"
                level == 1 -> "天气维持，变化小，多云或晴间多云。"
                else -> "不稳定的阴雨天气短时间内持续。"
            }
        }
        return Result(text, season)
    }
}
