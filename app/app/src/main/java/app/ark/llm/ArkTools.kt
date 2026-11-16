package app.ark.llm

// 给模型用的计算器工具（LiteRT-LM 工具调用）。电脑端 tools/embed/src/main/kotlin/ArkTools.kt 是同一份代码，改一处要同步另一处。
// 小模型心算常出错，也常漏掉单位换算（146 MHz 写成 146），所以计算器自己认单位：模型只要把数字连同单位写进去。
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

class ArkTools : ToolSet {
    val calls = ArrayList<Pair<String, String>>() // 调用记录（算式 → 结果），评测和日志用

    @Tool(description = "计算器，认识单位。凡是需要算数（代入公式、换算单位、按体重算剂量、算长度时间）都调用它，不要心算。" +
        "数字后面直接写单位，结尾用 to 指定想要的单位，例如：3e8 m/s / 146 MHz / 4 to cm；100 N*m to lbf*ft；32 psi to bar；" +
        "15 mg/kg * 18 kg；40 degC to degF；234 / 146（无单位的经验公式直接写数字）。" +
        "支持 + - * / ^ 括号、sqrt、sin/cos/tan（角度制）、pi、c（光速）。")
    fun calculate(@ToolParam(description = "带单位的算式，可以用 to 指定结果单位，例如 3e8 m/s / 7.1 MHz / 2 to m") expression: String): String {
        val r = runCatching { Calc.eval(expression) }.getOrElse {
            "算式有误（${it.message}）。请改写后再调用：只写数字、单位和运算符，例如 234 / 146 或 3e8 m/s / 146 MHz / 4 to cm；改不好就自己按资料公式算，照常回答。"
        }
        calls += expression to r
        return r
    }
}

/** 带量纲的数：v 是国际单位制下的数值，d 是 [米, 千克, 秒, 安培] 的幂次。 */
class Q(val v: Double, val d: IntArray = IntArray(4)) {
    val dimless get() = d.all { it == 0 }
    operator fun times(o: Q) = Q(v * o.v, IntArray(4) { d[it] + o.d[it] })
    operator fun div(o: Q) = Q(v / o.v, IntArray(4) { d[it] - o.d[it] })
    fun pow(n: Int) = Q(v.pow(n), IntArray(4) { d[it] * n })
    fun same(o: Q) = d.contentEquals(o.d)
}

class Calc private constructor(src: String) {
    companion object {
        private fun u(v: Double, m: Int = 0, kg: Int = 0, s: Int = 0, a: Int = 0) = Q(v, intArrayOf(m, kg, s, a))
        private val L = u(1e-3, m = 3)
        private val UNITS: Map<String, Q> = linkedMapOf<String, Q>().apply {
            fun def(names: String, q: Q) { for (n in names.split(' ')) this[n] = q }
            def("m 米 公尺", u(1.0, m = 1)); def("cm 厘米", u(0.01, m = 1)); def("mm 毫米", u(1e-3, m = 1))
            def("km 公里 千米", u(1000.0, m = 1)); def("um µm 微米", u(1e-6, m = 1)); def("nm", u(1e-9, m = 1))
            def("in inch inches 英寸", u(0.0254, m = 1)); def("ft foot feet 英尺", u(0.3048, m = 1)); def("yd yard yards 码", u(0.9144, m = 1))
            def("mi mile miles 英里", u(1609.344, m = 1)); def("nmi 海里", u(1852.0, m = 1))
            def("kg 千克 公斤", u(1.0, kg = 1)); def("g gram grams 克", u(1e-3, kg = 1)); def("mg 毫克", u(1e-6, kg = 1))
            def("ug µg mcg 微克", u(1e-9, kg = 1)); def("t tonne 吨", u(1000.0, kg = 1)); def("lb lbs pound pounds 磅", u(0.45359237, kg = 1))
            def("oz ounce 盎司", u(0.028349523125, kg = 1)); def("斤", u(0.5, kg = 1))
            def("s sec second seconds 秒", u(1.0, s = 1)); def("ms 毫秒", u(1e-3, s = 1)); def("min minute minutes 分钟", u(60.0, s = 1))
            def("h hr hour hours 小时", u(3600.0, s = 1)); def("d day days 天", u(86400.0, s = 1)); def("week weeks 周", u(604800.0, s = 1))
            def("L l liter liters litre litres 升 公升", L); def("mL ml 毫升 cc", u(1e-6, m = 3)); def("m3 立方米", u(1.0, m = 3)); def("cm3", u(1e-6, m = 3))
            def("gal gallon gallons 加仑", u(3.785411784e-3, m = 3)); def("qt quart", u(9.46352946e-4, m = 3)); def("pt pint", u(4.73176473e-4, m = 3))
            def("cup cups 杯", u(2.365882365e-4, m = 3)); def("floz", u(2.95735295625e-5, m = 3))
            def("tbsp tablespoon 汤匙 大勺", u(1.478676478125e-5, m = 3)); def("tsp teaspoon 茶匙 小勺", u(4.92892159375e-6, m = 3))
            def("Hz 赫兹", u(1.0, s = -1)); def("kHz", u(1e3, s = -1)); def("MHz 兆赫", u(1e6, s = -1)); def("GHz", u(1e9, s = -1))
            def("N 牛 牛顿", u(1.0, m = 1, kg = 1, s = -2)); def("牛米 牛顿米", u(1.0, m = 2, kg = 1, s = -2)); def("kN", u(1e3, m = 1, kg = 1, s = -2)); def("Nm", u(1.0, m = 2, kg = 1, s = -2))
            def("lbf 磅力", u(4.4482216152605, m = 1, kg = 1, s = -2)); def("磅英尺 英尺磅 磅力英尺 lbft ftlb", u(1.3558179483314, m = 2, kg = 1, s = -2)); def("kgf", u(9.80665, m = 1, kg = 1, s = -2))
            def("Pa", u(1.0, m = -1, kg = 1, s = -2)); def("hPa", u(100.0, m = -1, kg = 1, s = -2)); def("kPa 千帕", u(1e3, m = -1, kg = 1, s = -2))
            def("MPa", u(1e6, m = -1, kg = 1, s = -2)); def("bar 巴", u(1e5, m = -1, kg = 1, s = -2)); def("mbar", u(100.0, m = -1, kg = 1, s = -2))
            def("psi", u(6894.757293168, m = -1, kg = 1, s = -2)); def("atm 大气压", u(101325.0, m = -1, kg = 1, s = -2))
            def("mmHg", u(133.322387415, m = -1, kg = 1, s = -2)); def("inHg", u(3386.389, m = -1, kg = 1, s = -2))
            def("J 焦", u(1.0, m = 2, kg = 1, s = -2)); def("kJ 千焦", u(1e3, m = 2, kg = 1, s = -2)); def("MJ", u(1e6, m = 2, kg = 1, s = -2))
            def("cal 卡", u(4.184, m = 2, kg = 1, s = -2)); def("kcal Cal 千卡 大卡", u(4184.0, m = 2, kg = 1, s = -2))
            def("Wh", u(3600.0, m = 2, kg = 1, s = -2)); def("kWh 度", u(3.6e6, m = 2, kg = 1, s = -2))
            def("W 瓦", u(1.0, m = 2, kg = 1, s = -3)); def("mW", u(1e-3, m = 2, kg = 1, s = -3)); def("kW 千瓦", u(1e3, m = 2, kg = 1, s = -3))
            def("hp", u(745.69987158, m = 2, kg = 1, s = -3))
            def("A 安", u(1.0, a = 1)); def("mA 毫安", u(1e-3, a = 1)); def("Ah", u(3600.0, s = 1, a = 1)); def("mAh", u(3.6, s = 1, a = 1))
            def("V 伏", u(1.0, m = 2, kg = 1, s = -3, a = -1)); def("mV", u(1e-3, m = 2, kg = 1, s = -3, a = -1)); def("kV", u(1e3, m = 2, kg = 1, s = -3, a = -1))
            def("ohm ohms Ω 欧", u(1.0, m = 2, kg = 1, s = -3, a = -2)); def("kohm kΩ", u(1e3, m = 2, kg = 1, s = -3, a = -2))
            def("kmh kph", u(1000.0 / 3600, m = 1, s = -1)); def("mph", u(0.44704, m = 1, s = -1)); def("kn knot knots 节", u(1852.0 / 3600, m = 1, s = -1))
            def("c", u(299792458.0, m = 1, s = -1))
            def("drop drops 滴", u(5e-8, m = 3)) // 约 0.05 mL
            def("percent", u(0.01))
        }
        private val LOWER: Map<String, Q> = HashMap<String, Q>().apply { UNITS.forEach { (k, v) -> putIfAbsent(k.lowercase(), v) } }
        private fun unit(name: String) = UNITS[name] ?: LOWER[name.lowercase()]

        // 结果没指定单位时，按量纲挑一个常见单位显示
        private val SHOW = listOf("m", "kg", "s", "Hz", "L", "N", "Pa", "J", "W", "A", "V", "ohm", "m/s")
        private val TO = Regex("""\s+to\s+|->|=>|→|换算成|换算为|换成""", RegexOption.IGNORE_CASE)
        private val TEMP = Regex("""^\s*(-?[\d.]+)\s*(°C|℃|degC|摄氏度|C|°F|℉|degF|华氏度|F|K)\s*$""", RegexOption.IGNORE_CASE)

        fun eval(src0: String): String {
            val src = src0.trim().removeSuffix("。").replace(Regex("""^\s*[^=]{1,12}=(?!=)\s*"""), "") // “λ = 300/146” 只算等号右边
            val parts = src.split(TO)
            val (expr, target) = if (parts.size >= 2) parts.dropLast(1).joinToString(" to ") to parts.last().trim() else src to null
            temperature(expr, target)?.let { return it }
            val q = Calc(expr).parse()
            if (target != null) {
                var t = Calc(target).parse()
                if (!q.same(t)) { // 扭矩、力里常把磅力写成 lb、把公斤力写成 kg
                    val alt = target.replace(Regex("""\blbs?\b"""), "lbf").replace(Regex("""\bkg\b"""), "kgf").replace(Regex("磅(?!力)"), "磅力").replace(Regex("(?:公斤|千克)(?!力)"), "kgf")
                    runCatching { Calc(alt).parse() }.getOrNull()?.takeIf { q.same(it) }?.let { t = it }
                }
                if (q.dimless) return "${fmt(q.v)}（算式里没写单位，结果按 $target 理解前请确认单位换算）"
                require(q.same(t)) { "单位对不上：结果的单位不能换算成 $target" }
                return "${fmt(q.v / t.v)} $target"
            }
            if (q.dimless) return fmt(q.v)
            SHOW.firstOrNull { Calc(it).parse().same(q) }?.let { return "${fmt(q.v / Calc(it).parse().v)} $it" }
            val names = listOf("m", "kg", "s", "A")
            return fmt(q.v) + " " + q.d.withIndex().filter { it.value != 0 }.joinToString("·") { names[it.index] + if (it.value != 1) "^${it.value}" else "" }
        }

        private fun temperature(expr: String, target: String?): String? {
            val m = TEMP.find(expr) ?: return null
            val v = m.groupValues[1].toDouble()
            fun kind(s: String) = s.lowercase().let { when { 'c' in it || '摄' in it || '℃' in it -> 'C'; 'f' in it || '华' in it || '℉' in it -> 'F'; else -> 'K' } }
            val from = kind(m.groupValues[2])
            val to = target?.let { kind(it.trim()) } ?: return null
            val c = when (from) { 'C' -> v; 'F' -> (v - 32) * 5 / 9; else -> v - 273.15 }
            val r = when (to) { 'C' -> c; 'F' -> c * 9 / 5 + 32; else -> c + 273.15 }
            return "${fmt(r)} ${mapOf('C' to "°C", 'F' to "°F", 'K' to "K")[to]}"
        }

        fun fmt(v: Double): String {
            if (v.isNaN() || v.isInfinite()) return "无法计算：结果不是有限数"
            if (v == 0.0) return "0"
            val a = abs(v)
            return if (a >= 1e-4 && a < 1e9) "%.4g".format(v).let { s -> if ('.' in s && 'e' !in s) s.trimEnd('0').trimEnd('.') else s }
            else "%.4e".format(v)
        }
    }

    private val s = src.replace('×', '*').replace('÷', '/').replace('·', '*').replace("**", "^").replace('，', ',')
        .replace('（', '(').replace('）', ')').replace(Regex("""(\d),(\d{3})"""), "$1$2").replace(Regex("\\s+"), "")
    private var i = 0

    private fun parse(): Q {
        val v = expr()
        require(i == s.length) { "看不懂：${s.substring(i)}" }
        return v
    }

    private fun peek() = if (i < s.length) s[i] else '\u0000'
    private fun eat(c: Char): Boolean = if (peek() == c) { i++; true } else false

    private fun add(a: Q, b: Q, sign: Int): Q {
        require(a.same(b)) { "单位不一致，不能相加减" }
        return Q(a.v + sign * b.v, a.d)
    }

    private fun expr(): Q {
        var v = term()
        while (true) v = when { eat('+') -> add(v, term(), 1); eat('-') -> add(v, term(), -1); else -> return v }
    }

    private fun term(): Q {
        var v = power()
        while (true) v = when { eat('*') -> v * power(); eat('/') -> v / power(); else -> return v }
    }

    private fun power(): Q {
        val b = unary()
        if (!eat('^')) return b
        val e = power()
        require(e.dimless) { "指数不能带单位" }
        if (b.dimless) return Q(b.v.pow(e.v))
        val n = e.v.roundToInt()
        require(abs(e.v - n) < 1e-9) { "带单位的数只能做整数次方" }
        return b.pow(n)
    }

    private fun unary(): Q = when {
        eat('-') -> unary().let { Q(-it.v, it.d) }
        eat('+') -> unary()
        else -> atom()
    }

    private fun isNameChar(c: Char) = c.isLetter() || c == 'µ' || c == 'Ω' || c == '°'

    private fun name(): String {
        val st = i
        while (isNameChar(peek()) || (i > st && peek().isDigit() && s.substring(st, i) in setOf("m", "cm"))) i++
        return s.substring(st, i)
    }

    /** 数字后面紧跟的单位，可以带整数次方（m^2）；也认 m/s、mg/kg 这种连写在一起的复合单位 */
    private fun unitAfterNumber(): Q? {
        if (!isNameChar(peek())) return null
        val st = i
        val n = name()
        var q = unit(n) ?: if (n.all { it.code in 0x4E00..0x9FFF }) Q(1.0) else run { i = st; return null } // “4 个人”的量词当作 1
        if (peek() == '^' && i + 1 < s.length && s[i + 1].isDigit()) { i++; val e0 = i; while (peek().isDigit()) i++; q = q.pow(s.substring(e0, i).toInt()) }
        return q
    }

    private fun atom(): Q {
        if (eat('(')) { val v = expr(); require(eat(')')) { "缺少右括号" }; return v }
        val c = peek()
        if (c.isDigit() || c == '.') {
            val st = i
            while (peek().isDigit() || peek() == '.') i++
            if ((peek() == 'e' || peek() == 'E') && i + 1 < s.length && (s[i + 1].isDigit() || s[i + 1] == '-' || s[i + 1] == '+')) {
                i++; if (peek() == '-' || peek() == '+') i++
                while (peek().isDigit()) i++
            }
            var v = Q(s.substring(st, i).toDouble())
            if (eat('%')) v = Q(v.v / 100)
            unitAfterNumber()?.let { return v * it }
            // “1/2 tsp”“3/4 cup”：纯数字分数后面紧跟单位，按 (1/2) tsp 理解
            if (peek() == '/' && i + 1 < s.length && s[i + 1].isDigit()) {
                val back = i
                i++
                val n0 = i
                while (peek().isDigit() || peek() == '.') i++
                val den = s.substring(n0, i).toDouble()
                unitAfterNumber()?.let { return Q(v.v / den) * it }
                i = back
            }
            return v
        }
        if (isNameChar(c)) {
            val n = name()
            val low = n.lowercase()
            if (low == "pi" || n == "π") return Q(Math.PI)
            if (peek() == '(' && low in setOf("sqrt", "ln", "log", "log10", "sin", "cos", "tan", "abs")) {
                i++
                val a = expr(); require(eat(')')) { "缺少右括号" }
                if (low == "sqrt") {
                    require(a.d.all { it % 2 == 0 }) { "这个单位不能开平方" }
                    return Q(kotlin.math.sqrt(a.v), IntArray(4) { a.d[it] / 2 })
                }
                if (low == "abs") return Q(abs(a.v), a.d)
                require(a.dimless) { "$n() 里不能带单位" }
                return Q(when (low) {
                    "ln" -> kotlin.math.ln(a.v)
                    "log", "log10" -> kotlin.math.log10(a.v)
                    "sin" -> kotlin.math.sin(Math.toRadians(a.v))
                    "cos" -> kotlin.math.cos(Math.toRadians(a.v))
                    else -> kotlin.math.tan(Math.toRadians(a.v))
                })
            }
            unit(n)?.let { u ->
                if (peek() == '^' && i + 1 < s.length && s[i + 1].isDigit()) { i++; val e0 = i; while (peek().isDigit()) i++; return u.pow(s.substring(e0, i).toInt()) }
                return u
            }
            // “4 个人 * 7 天”里的“个人”、people 这类计数词当作 1
            if (n.all { it.code in 0x4E00..0x9FFF } || n.lowercase() in setOf("person", "persons", "people", "times", "tablet", "tablets", "pill", "pills", "dose", "doses"))
                return Q(1.0)
            error("不认识 $n（字母只能是单位或函数，未知量要先代入数字）")
        }
        error("看不懂：${s.substring(i)}")
    }
}
