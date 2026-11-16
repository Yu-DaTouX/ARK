package app.ark.ui.tools

import java.time.Instant
import java.time.LocalDate
import kotlin.math.*

/**
 * 日出日落，离线计算。
 * 算法来源：NOAA Global Monitoring Laboratory《Solar Calculation Details》/ NOAA Solar Calculator 电子表格，
 * 即 Jean Meeus《Astronomical Algorithms》第 25、28 章（太阳位置、时差）的简化式。精度约 ±1 分钟（纬度 72° 以内）。
 *  - 日出 / 日落：太阳天顶角 90.833°（= 90° + 大气折射 34′ + 太阳视半径 16′）。
 *  - 民用晨光始 / 昏影终：太阳中心在地平线下 6°，即天顶角 96°（国际通用的民用曙暮光定义）。
 *  - 正午：太阳过当地子午线，= 720 − 4·经度 − 时差（分钟，UTC）。
 * 经度东正西负。不含海拔对地平线的修正。
 */
object Solar {
    data class Day(
        val dawn: Instant?, val sunrise: Instant?, val noon: Instant,
        val sunset: Instant?, val dusk: Instant?,
        /** 太阳整天在地平线上 / 下（极昼 / 极夜）时的说明，否则 null。 */
        val polar: String?,
    )

    private fun rad(d: Double) = Math.toRadians(d)

    /** 儒略世纪（J2000.0 起算），由 Unix 毫秒。 */
    private fun jc(ms: Long) = (ms / 86400000.0 + 2440587.5 - 2451545.0) / 36525.0

    /** 返回 (太阳赤纬 弧度, 时差 分钟)。公式见 NOAA 电子表格各列。 */
    private fun sunParams(t: Double): Pair<Double, Double> {
        val l0 = (280.46646 + t * (36000.76983 + t * 0.0003032)).mod(360.0)
        val m = 357.52911 + t * (35999.05029 - 0.0001537 * t)
        val e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val c = sin(rad(m)) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sin(rad(2 * m)) * (0.019993 - 0.000101 * t) + sin(rad(3 * m)) * 0.000289
        val trueLong = l0 + c
        val omega = 125.04 - 1934.136 * t
        val appLong = trueLong - 0.00569 - 0.00478 * sin(rad(omega))
        val obliqMean = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        val obliq = obliqMean + 0.00256 * cos(rad(omega))
        val decl = asin(sin(rad(obliq)) * sin(rad(appLong)))
        val y = tan(rad(obliq / 2)).pow(2)
        val eq = 4.0 * Math.toDegrees(
            y * sin(2 * rad(l0)) - 2 * e * sin(rad(m)) + 4 * e * y * sin(rad(m)) * cos(2 * rad(l0)) -
                0.5 * y * y * sin(4 * rad(l0)) - 1.25 * e * e * sin(2 * rad(m))
        )
        return decl to eq
    }

    /** 太阳到达天顶角 zenith 时的时角（度）；太阳整天不到该高度返回 null，整天都在上面返回 NaN。 */
    private fun hourAngle(latDeg: Double, decl: Double, zenith: Double): Double? {
        val lat = rad(latDeg)
        val cosH = (cos(rad(zenith)) - sin(lat) * sin(decl)) / (cos(lat) * cos(decl))
        return when {
            cosH > 1 -> null
            cosH < -1 -> Double.NaN
            else -> Math.toDegrees(acos(cosH))
        }
    }

    /** 太阳视赤经、视赤纬（度，当日分点），由 Unix 毫秒。同一套 NOAA / Meeus 公式（视黄经 + 修正黄赤交角），给天体定向用。 */
    fun equatorial(ms: Long): Pair<Double, Double> {
        val t = jc(ms)
        val l0 = (280.46646 + t * (36000.76983 + t * 0.0003032)).mod(360.0)
        val m = 357.52911 + t * (35999.05029 - 0.0001537 * t)
        val c = sin(rad(m)) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sin(rad(2 * m)) * (0.019993 - 0.000101 * t) + sin(rad(3 * m)) * 0.000289
        val omega = 125.04 - 1934.136 * t
        val appLong = rad(l0 + c - 0.00569 - 0.00478 * sin(rad(omega)))
        val obliqMean = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        val obliq = rad(obliqMean + 0.00256 * cos(rad(omega)))
        val ra = Math.toDegrees(atan2(cos(obliq) * sin(appLong), cos(appLong))).mod(360.0)
        val dec = Math.toDegrees(asin(sin(obliq) * sin(appLong)))
        return ra to dec
    }

    /**
     * 当地日期 date（按 zone 的日历日）的日出日落等。算法按 UTC 日历日求解，所以对前一天、当天、后一天（UTC）各算一次，
     * 再挑出落在 zone 本地日期 = date 内的那一个——日期变更线附近（UTC+13/+14，或西经但时区在东侧）也不会算成别的日子。
     */
    fun compute(date: LocalDate, lat: Double, lon: Double, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Day {
        val days = listOf(-1L, 0L, 1L).map { computeUtc(date.plusDays(it), lat, lon) }
        fun pick(f: (Day) -> Instant?): Instant? = days.mapNotNull(f).firstOrNull { it.atZone(zone).toLocalDate() == date }
        val base = days[1]
        return Day(
            pick { it.dawn }, pick { it.sunrise }, pick { it.noon } ?: base.noon,
            pick { it.sunset }, pick { it.dusk }, base.polar,
        )
    }

    private fun computeUtc(date: LocalDate, lat: Double, lon: Double): Day {
        val dayStart = date.toEpochDay() * 86400000L // 当天 UTC 0 点
        fun at(min: Double) = dayStart + (min * 60000.0).toLong()
        var noonMin = 720.0 - 4 * lon
        repeat(2) { noonMin = 720.0 - 4 * lon - sunParams(jc(at(noonMin))).second }
        val (declNoon, _) = sunParams(jc(at(noonMin)))

        fun event(zenith: Double, rising: Boolean): Instant? {
            val h0 = hourAngle(lat, declNoon, zenith) ?: return null
            if (h0.isNaN()) return null
            val sign = if (rising) -1 else 1
            var t = noonMin + sign * 4 * h0
            repeat(2) { // 用事件时刻的赤纬再算一次
                val (d, eq) = sunParams(jc(at(t)))
                val h = hourAngle(lat, d, zenith) ?: return null
                if (h.isNaN()) return null
                t = 720.0 - 4 * lon - eq + sign * 4 * h
            }
            return Instant.ofEpochMilli(at(t))
        }

        val noon = Instant.ofEpochMilli(at(noonMin))
        val h = hourAngle(lat, declNoon, 90.833)
        val polar = when {
            h == null -> "今天太阳不会升到地平线上（极夜）"
            h.isNaN() -> "今天太阳不会落到地平线下（极昼）"
            else -> null
        }
        return Day(event(96.0, true), event(90.833, true), noon, event(90.833, false), event(96.0, false), polar)
    }
}
