// 给模型用的计算器工具（LiteRT-LM 工具调用）。App 里 app/src/main/java/app/ark/llm/ArkTools.kt 是同一份代码，改一处要同步另一处。
// 小模型心算常出错（把 146 写成 466 等），让它把算式交给这里算。
import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import kotlin.math.abs
import kotlin.math.pow

class ArkTools : ToolSet {
    val calls = ArrayList<Pair<String, String>>() // 调用记录（算式 → 结果），评测和日志用

    @Tool(description = "计算一个数学算式并返回结果。凡是需要算数（换算单位、代入公式、求长度剂量等）都调用它，不要心算。" +
        "支持 + - * / ^ 括号、科学计数法（如 3e8）、sqrt、ln、log10、sin/cos/tan（角度制）、pi。")
    fun calculate(@ToolParam(description = "算式，例如 3e8 / 7.1e6 / 2 或 100 * 0.7376") expression: String): String {
        val r = runCatching { format(Calc(expression).parse()) }.getOrElse { "无法计算：${it.message}" }
        calls += expression to r
        return r
    }

    companion object {
        fun format(v: Double): String {
            if (v.isNaN() || v.isInfinite()) return "无法计算：结果不是有限数"
            if (v == 0.0) return "0"
            val a = abs(v)
            return if (a >= 1e-4 && a < 1e9) "%.6g".format(v).let { s -> if ('.' in s && 'e' !in s) s.trimEnd('0').trimEnd('.') else s }
            else "%.6e".format(v)
        }
    }
}

/** 递归下降：expr = term (('+'|'-') term)*；term = power (('*'|'/') power)*；power = unary ('^' power)?；unary = '-' unary | atom */
class Calc(src: String) {
    private val s = src.replace('×', '*').replace('÷', '/').replace("**", "^").replace('，', ',').replace(" ", "")
    private var i = 0

    fun parse(): Double {
        val v = expr()
        require(i == s.length) { "多余字符：${s.substring(i)}" }
        return v
    }

    private fun peek() = if (i < s.length) s[i] else '\u0000'
    private fun eat(c: Char): Boolean = if (peek() == c) { i++; true } else false

    private fun expr(): Double {
        var v = term()
        while (true) v = when { eat('+') -> v + term(); eat('-') -> v - term(); else -> return v }
    }

    private fun term(): Double {
        var v = power()
        while (true) v = when { eat('*') -> v * power(); eat('/') -> v / power(); else -> return v }
    }

    private fun power(): Double {
        val b = unary()
        return if (eat('^')) b.pow(power()) else b
    }

    private fun unary(): Double = when {
        eat('-') -> -unary()
        eat('+') -> unary()
        else -> atom()
    }

    private fun atom(): Double {
        if (eat('(')) { val v = expr(); require(eat(')')) { "缺少右括号" }; return v }
        val c = peek()
        if (c.isDigit() || c == '.') {
            val st = i
            while (peek().isDigit() || peek() == '.') i++
            if ((peek() == 'e' || peek() == 'E') && i + 1 < s.length && (s[i + 1].isDigit() || s[i + 1] == '-' || s[i + 1] == '+')) {
                i++; if (peek() == '-' || peek() == '+') i++
                while (peek().isDigit()) i++
            }
            var v = s.substring(st, i).toDouble()
            if (eat('%')) v /= 100
            return v
        }
        if (c.isLetter()) {
            val st = i
            while (peek().isLetterOrDigit()) i++
            val name = s.substring(st, i).lowercase()
            if (name == "pi") return Math.PI
            if (name == "e") return Math.E
            require(eat('(')) { "未知名称：$name" }
            val a = expr(); require(eat(')')) { "缺少右括号" }
            return when (name) {
                "sqrt" -> kotlin.math.sqrt(a)
                "ln" -> kotlin.math.ln(a)
                "log", "log10" -> kotlin.math.log10(a)
                "sin" -> kotlin.math.sin(Math.toRadians(a))
                "cos" -> kotlin.math.cos(Math.toRadians(a))
                "tan" -> kotlin.math.tan(Math.toRadians(a))
                "abs" -> abs(a)
                else -> error("未知函数：$name")
            }
        }
        error("看不懂：${s.substring(i)}")
    }
}
