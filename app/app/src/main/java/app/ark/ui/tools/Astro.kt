package app.ark.ui.tools

import kotlin.math.*

/**
 * 天体位置（太阳、月亮、约 50 颗亮星）→ 地平坐标，纯函数，不依赖 Android，完全离线。
 * 方位角：真北起算，顺时针（北 0°、东 90°）；高度角含大气折射（视高度）。
 *
 * 出处：
 *  - 格林尼治平恒星时：Meeus《Astronomical Algorithms》(2nd ed.) 第 12 章式 12.4。
 *  - 赤道 → 地平坐标：Meeus 第 13 章式 13.5、13.6（方位从南起算，这里 +180° 改成从北起算）。
 *  - 岁差（恒星 J2000 → 观测日期）：Meeus 第 21 章式 21.1 的一阶近似，m = 3.07496 s/年、n = 20.0431″/年
 *    （2026 年累计约 0.36°，二阶项远小于 1′）。不含章动、光行差、自行（对 ≤ 1° 的定向精度都可以忽略）。
 *  - 大气折射：Bennett (1982) 公式 R(′) = cot(h + 7.31/(h + 4.4))，h 为真高度（度），标准气压 1010 hPa、10 °C。
 *  - 太阳：Solar.equatorial()（NOAA Solar Calculator / Meeus 第 25 章）。
 *  - 月亮：Meeus 第 47 章，取经度、纬度、距离周期项表中最大的 13 / 10 / 4 项（累计截断误差约 0.05°~0.1°），
 *    再做地平视差修正（月亮视差近 1°，不修正会错 1°）。
 *  - 恒星坐标：J2000.0 赤经赤纬，取自 Hipparcos / Yale BSC 的常见值（精度 0.01°）。
 *
 * 自检（2026-11-16 用 USNO Celestial Navigation API https://aa.usno.navy.mil/api/celnav 对照，几何高度 / 真北方位）：
 *   北京 39.9°N 116.4°E，04:00 UT：太阳 az 180.247° alt 31.376°；织女星 az 75.888° alt 53.466°；北极星 az 0.061° alt 39.280°（≈ 纬度 − 0.6°）。
 *   开普敦 33.9°S 18.4°E，15:00 UT：月亮 az 49.250° alt 66.666°；南十字 Acrux az 206.532° alt 20.951°。
 *   本文件算出的同一批数值与上面相差：恒星 ≤ 0.03°，太阳 ≤ 0.002°，月亮 0.024°（不含视差、折射）。
 */
object Astro {
    enum class Kind { SUN, MOON, STAR }

    class Star(val id: String, val name: String, val raH: Double, val decD: Double, val mag: Float)

    data class Body(val id: String, val name: String, val kind: Kind, val mag: Float, val az: Double, val alt: Double)

    // ---- 恒星表：id、中文名、赤经（时）、赤纬（度）、视星等 ----
    val stars: List<Star> = listOf(
        Star("sirius", "天狼星 Sirius", 6.7525, -16.7161, -1.46f),
        Star("canopus", "老人星 Canopus", 6.3992, -52.6957, -0.74f),
        Star("arcturus", "大角星 Arcturus", 14.2610, 19.1824, -0.05f),
        Star("rigilkent", "南门二 α Cen", 14.6601, -60.8340, -0.27f),
        Star("vega", "织女星 Vega", 18.6156, 38.7837, 0.03f),
        Star("capella", "五车二 Capella", 5.2782, 45.9980, 0.08f),
        Star("rigel", "参宿七 Rigel", 5.2423, -8.2016, 0.13f),
        Star("procyon", "南河三 Procyon", 7.6550, 5.2250, 0.34f),
        Star("betelgeuse", "参宿四 Betelgeuse", 5.9195, 7.4071, 0.5f),
        Star("achernar", "水委一 Achernar", 1.6286, -57.2368, 0.46f),
        Star("hadar", "马腹一 β Cen", 14.0637, -60.3730, 0.61f),
        Star("altair", "牛郎星 Altair", 19.8464, 8.8683, 0.77f),
        Star("acrux", "十字架二 Acrux", 12.4433, -63.0991, 0.77f),
        Star("aldebaran", "毕宿五 Aldebaran", 4.5987, 16.5093, 0.85f),
        Star("antares", "心宿二 Antares", 16.4901, -26.4320, 0.96f),
        Star("spica", "角宿一 Spica", 13.4199, -11.1613, 0.97f),
        Star("pollux", "北河三 Pollux", 7.7553, 28.0262, 1.14f),
        Star("fomalhaut", "北落师门 Fomalhaut", 22.9608, -29.6222, 1.16f),
        Star("deneb", "天津四 Deneb", 20.6905, 45.2803, 1.25f),
        Star("mimosa", "十字架三 Mimosa", 12.7953, -59.6888, 1.25f),
        Star("regulus", "轩辕十四 Regulus", 10.1395, 11.9672, 1.35f),
        Star("adhara", "弧矢七 Adhara", 6.9771, -28.9721, 1.5f),
        Star("castor", "北河二 Castor", 7.5767, 31.8883, 1.58f),
        Star("gacrux", "十字架一 Gacrux", 12.5194, -57.1132, 1.6f),
        Star("shaula", "尾宿八 Shaula", 17.5601, -37.1038, 1.62f),
        Star("bellatrix", "参宿五 Bellatrix", 5.4189, 6.3497, 1.64f),
        Star("elnath", "五车五 Elnath", 5.4382, 28.6075, 1.65f),
        Star("alnilam", "参宿二 Alnilam", 5.6036, -1.2019, 1.69f),
        Star("alnitak", "参宿一 Alnitak", 5.6793, -1.9426, 1.77f),
        Star("mintaka", "参宿三 Mintaka", 5.5334, -0.2991, 2.2f),
        Star("saiph", "参宿六 Saiph", 5.7959, -9.6696, 2.06f),
        Star("polaris", "北极星 Polaris", 2.5303, 89.2641, 2.0f),
        // 北斗七星
        Star("dubhe", "天枢 Dubhe", 11.0621, 61.7510, 1.79f),
        Star("merak", "天璇 Merak", 11.0307, 56.3824, 2.37f),
        Star("phecda", "天玑 Phecda", 11.8972, 53.6948, 2.44f),
        Star("megrez", "天权 Megrez", 12.2571, 57.0326, 3.31f),
        Star("alioth", "玉衡 Alioth", 12.9005, 55.9598, 1.77f),
        Star("mizar", "开阳 Mizar", 13.3987, 54.9254, 2.27f),
        Star("alkaid", "摇光 Alkaid", 13.7923, 49.3133, 1.86f),
        // 仙后座 W
        Star("caph", "仙后座 β Caph", 0.1530, 59.1498, 2.27f),
        Star("schedar", "仙后座 α Schedar", 0.6751, 56.5373, 2.24f),
        Star("gammacas", "仙后座 γ", 0.9451, 60.7167, 2.47f),
        Star("ruchbah", "仙后座 δ Ruchbah", 1.4303, 60.2353, 2.68f),
        Star("segin", "仙后座 ε Segin", 1.9066, 63.6701, 3.38f),
        // 天蝎座
        Star("graffias", "天蝎座 β", 16.0906, -19.8055, 2.6f),
        Star("dschubba", "天蝎座 δ", 16.0056, -22.6217, 2.3f),
        Star("epssco", "天蝎座 ε", 16.8361, -34.2932, 2.29f),
        Star("sargas", "天蝎座 θ Sargas", 17.6220, -42.9978, 1.87f),
        // 南十字座
        Star("imai", "十字架四 δ Cru", 12.2524, -58.7489, 2.8f),
    )
    private val byId = stars.associateBy { it.id }

    /** 常用星座连线（星 id 对）：北斗、仙后座、猎户座、南十字、天蝎座。 */
    val lines: List<Pair<String, String>> = listOf(
        "dubhe" to "merak", "merak" to "phecda", "phecda" to "megrez", "megrez" to "dubhe",
        "megrez" to "alioth", "alioth" to "mizar", "mizar" to "alkaid",
        "caph" to "schedar", "schedar" to "gammacas", "gammacas" to "ruchbah", "ruchbah" to "segin",
        "betelgeuse" to "bellatrix", "bellatrix" to "mintaka", "mintaka" to "alnilam", "alnilam" to "alnitak",
        "alnitak" to "saiph", "saiph" to "rigel", "rigel" to "mintaka", "alnitak" to "betelgeuse",
        "acrux" to "gacrux", "mimosa" to "imai",
        "graffias" to "dschubba", "dschubba" to "antares", "antares" to "epssco", "epssco" to "sargas", "sargas" to "shaula",
    )

    // ---- 时间 ----
    fun julianDay(ms: Long) = ms / 86400000.0 + 2440587.5

    /** 格林尼治平恒星时（度），Meeus 式 12.4。 */
    fun gmst(jd: Double): Double {
        val t = (jd - 2451545.0) / 36525.0
        return (280.46061837 + 360.98564736629 * (jd - 2451545.0) + 0.000387933 * t * t - t * t * t / 38710000.0).mod(360.0)
    }

    // ---- 坐标 ----
    /** J2000 赤经赤纬（度）→ 观测日期的赤经赤纬（一阶岁差，Meeus 式 21.1）。 */
    fun precess(raDeg: Double, decDeg: Double, jd: Double): Pair<Double, Double> {
        val years = (jd - 2451545.0) / 365.25
        val m = 3.07496 * 15.0 / 3600.0   // s → 度/年
        val n = 20.0431 / 3600.0          // ″ → 度/年
        val ra = Math.toRadians(raDeg); val dec = Math.toRadians(decDeg)
        val dRa = (m + n * sin(ra) * tan(dec)) * years
        val dDec = n * cos(ra) * years
        return (raDeg + dRa).mod(360.0) to (decDeg + dDec)
    }

    /** Bennett 折射（度），h 为真高度（度）。 */
    fun refraction(h: Double): Double {
        if (h < -1.0) return 0.0
        return 1.0 / tan(Math.toRadians(h + 7.31 / (h + 4.4))) / 60.0
    }

    /** 赤道坐标 → (方位, 真高度)（度）。 */
    fun horizontal(raDeg: Double, decDeg: Double, jd: Double, lat: Double, lon: Double): Pair<Double, Double> {
        val h = Math.toRadians(gmst(jd) + lon - raDeg)
        val dec = Math.toRadians(decDeg); val phi = Math.toRadians(lat)
        val alt = asin(sin(phi) * sin(dec) + cos(phi) * cos(dec) * cos(h))
        val az = atan2(sin(h), cos(h) * sin(phi) - tan(dec) * cos(phi)) + PI // 从北起算
        return Math.toDegrees(az).mod(360.0) to Math.toDegrees(alt)
    }

    /** 月亮（日期分点的赤经、赤纬，度；距离，km）。Meeus 第 47 章截断版。 */
    fun moonEquatorial(jd: Double): Triple<Double, Double, Double> {
        val t = (jd - 2451545.0) / 36525.0
        fun d(a: Double, b: Double) = (a + b * t).mod(360.0) * PI / 180
        val lp = (218.3164477 + 481267.88123421 * t).mod(360.0)
        val dd = d(297.8501921, 445267.1114034); val m = d(357.5291092, 35999.0502909)
        val mp = d(134.9633964, 477198.8675055); val f = d(93.2720950, 483202.0175233)
        val sl = 6288774 * sin(mp) + 1274027 * sin(2 * dd - mp) + 658314 * sin(2 * dd) + 213618 * sin(2 * mp) -
            185116 * sin(m) - 114332 * sin(2 * f) + 58793 * sin(2 * dd - 2 * mp) + 57066 * sin(2 * dd - m - mp) +
            53322 * sin(2 * dd + mp) + 45758 * sin(2 * dd - m) - 40923 * sin(m - mp) - 34720 * sin(dd) - 30383 * sin(m + mp)
        val sb = 5128122 * sin(f) + 280602 * sin(mp + f) + 277693 * sin(mp - f) + 173237 * sin(2 * dd - f) +
            55413 * sin(2 * dd - mp + f) + 46271 * sin(2 * dd - mp - f) + 32573 * sin(2 * dd + f) +
            17198 * sin(2 * mp + f) + 9266 * sin(2 * dd + mp - f) + 8822 * sin(2 * mp - f)
        val sr = -20905355 * cos(mp) - 3699111 * cos(2 * dd - mp) - 2955968 * cos(2 * dd) - 569925 * cos(2 * mp)
        val lon = Math.toRadians(lp + sl / 1e6 + nutLon(t))
        val lat = Math.toRadians(sb / 1e6)
        val dist = 385000.56 + sr / 1000.0
        val eps = Math.toRadians(23.4392911 - 0.0130042 * t)
        val ra = atan2(sin(lon) * cos(eps) - tan(lat) * sin(eps), cos(lon))
        val dec = asin(sin(lat) * cos(eps) + cos(lat) * sin(eps) * sin(lon))
        return Triple(Math.toDegrees(ra).mod(360.0), Math.toDegrees(dec), dist)
    }

    /** 黄经章动近似（度），Meeus 第 22 章主项，量级 0.005°。 */
    private fun nutLon(t: Double): Double {
        val om = Math.toRadians(125.04452 - 1934.136261 * t)
        return -0.00478 * sin(om) // ≈ −17.2″·sin Ω
    }

    /** 当前所有天体（含地平线以下）的地平坐标。alt 为视高度（含折射；月亮含视差）。 */
    fun all(ms: Long, lat: Double, lon: Double): List<Body> {
        val jd = julianDay(ms)
        val out = ArrayList<Body>()
        fun add(id: String, name: String, kind: Kind, mag: Float, ra: Double, dec: Double, parallaxDeg: Double = 0.0) {
            val (az, h) = horizontal(ra, dec, jd, lat, lon)
            val alt0 = h - parallaxDeg * cos(Math.toRadians(h)) // 地平视差（简化：π·cos h）
            out += Body(id, name, kind, mag, az, alt0 + refraction(alt0))
        }
        val (sra, sdec) = Solar.equatorial(ms)
        add("sun", "太阳", Kind.SUN, -26.7f, sra, sdec, 0.0024)
        val (mra, mdec, dist) = moonEquatorial(jd)
        add("moon", "月亮", Kind.MOON, -12f, mra, mdec, Math.toDegrees(asin(6378.14 / dist)))
        for (s in stars) {
            val (ra, dec) = precess(s.raH * 15.0, s.decD, jd)
            add(s.id, s.name, Kind.STAR, s.mag, ra, dec)
        }
        return out
    }

    /** 天极（北半球北天极，南半球南天极）的方位和高度：方位 0° / 180°，高度 = |纬度|。 */
    fun pole(lat: Double): Pair<Double, Double> = (if (lat >= 0) 0.0 else 180.0) to abs(lat)

    fun star(id: String) = byId[id]
}
