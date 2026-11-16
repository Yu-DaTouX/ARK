package app.ark.ui.tools

import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.roundToInt

/** 饮水消毒：美国 CDC《Making Water Safe in an Emergency》（Water Disinfection with Bleach）。 */
object WaterCalc {
    const val GAL_L = 3.785411784 // 1 美制加仑 = 3.785411784 L（NIST SP 811 精确值）
    const val DROP_ML = 0.05      // CDC：1 滴 ≈ 0.05 mL

    /** CDC：每美制加仑清水，6% 漂白剂 8 滴，8.25% 漂白剂 6 滴；浑浊水（先过滤）加倍。 */
    enum class Bleach(val label: String, val dropsPerGal: Int) {
        P6("5–6%（常见家用）", 8),
        P825("8.25%（浓缩型）", 6),
    }

    fun drops(b: Bleach, liters: Double, cloudy: Boolean): Int {
        val d = b.dropsPerGal * (liters / GAL_L) * (if (cloudy) 2 else 1)
        return maxOf(1, d.roundToInt())
    }
}

/** 单位换算。系数来源：NIST SP 811（International yard and pound 协议、美制液量）。 */
object Units {
    class Unit(val name: String, val toBase: Double)
    class Cat(val name: String, val units: List<Unit>, val temp: Boolean = false)

    val cats = listOf(
        // 基准：米。1 ft = 0.3048 m，1 mile = 1609.344 m，1 海里 = 1852 m（均为精确定义）
        Cat("长度", listOf(Unit("m", 1.0), Unit("ft", 0.3048), Unit("mile", 1609.344), Unit("km", 1000.0), Unit("海里", 1852.0))),
        // 基准：千克。1 lb = 0.45359237 kg（精确定义）
        Cat("重量", listOf(Unit("kg", 1.0), Unit("lb", 0.45359237))),
        Cat("温度", listOf(Unit("°C", 1.0), Unit("°F", 1.0)), temp = true),
        // 基准：帕。1 psi = 6894.757293168 Pa；1 bar = 100000 Pa
        Cat("压力", listOf(Unit("psi", 6894.757293168), Unit("bar", 100000.0), Unit("kPa", 1000.0), Unit("hPa", 100.0))),
        // 基准：升。美制 1 gal = 3.785411784 L；1 qt = 1/4 gal = 0.946352946 L
        Cat("容积", listOf(Unit("L", 1.0), Unit("gal(美)", 3.785411784), Unit("qt(美)", 0.946352946))),
    )

    fun convert(cat: Cat, from: Unit, to: Unit, v: Double): Double {
        if (cat.temp) {
            val c = if (from.name == "°C") v else (v - 32) * 5 / 9
            return if (to.name == "°C") c else c * 9 / 5 + 32
        }
        return v * from.toBase / to.toBase
    }

    /** 5 位有效数字，不用科学计数法。 */
    fun fmt(v: Double): String =
        if (v == 0.0 || v.isNaN() || v.isInfinite()) "0" else BigDecimal(v).round(MathContext(5)).stripTrailingZeros().toPlainString()
}
