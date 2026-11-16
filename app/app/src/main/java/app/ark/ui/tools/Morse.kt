package app.ark.ui.tools

/**
 * 国际摩尔斯码（ITU-R M.1677-1《International Morse code》）：
 *  - 点 = 1 单位；划 = 3 单位；字符内部点划之间 = 1 单位（灭）；字母之间 = 3 单位；单词之间 = 7 单位。
 *  - SOS = ··· --- ···，作为连续的三个字母发送（字母间隔 3，不是一个字）。
 * 一个单位取 250 ms（约 4.8 WPM，慢而清楚，远处和夜里容易看清）。
 */
object Morse {
    const val UNIT_MS = 250L

    /** 一段亮 / 灭，持续 units 个单位。 */
    data class Step(val on: Boolean, val units: Int)

    // ITU-R M.1677-1 表 1：字母与数字
    private val table = mapOf(
        'A' to ".-", 'B' to "-...", 'C' to "-.-.", 'D' to "-..", 'E' to ".", 'F' to "..-.",
        'G' to "--.", 'H' to "....", 'I' to "..", 'J' to ".---", 'K' to "-.-", 'L' to ".-..",
        'M' to "--", 'N' to "-.", 'O' to "---", 'P' to ".--.", 'Q' to "--.-", 'R' to ".-.",
        'S' to "...", 'T' to "-", 'U' to "..-", 'V' to "...-", 'W' to ".--", 'X' to "-..-",
        'Y' to "-.--", 'Z' to "--..",
        '0' to "-----", '1' to ".----", '2' to "..---", '3' to "...--", '4' to "....-",
        '5' to ".....", '6' to "-....", '7' to "--...", '8' to "---..", '9' to "----.",
    )

    /** 只保留字母、数字、空格（字母转大写）；其他字符丢掉。 */
    fun clean(s: String): String = s.uppercase().filter { it in table || it == ' ' }.trim().replace(Regex(" +"), " ")

    /** 文字 → 点划字符串，字母间一个空格，单词间 " / "。 */
    fun toDots(text: String): String = clean(text).split(' ').filter { it.isNotEmpty() }
        .joinToString(" / ") { w -> w.map { table[it]!! }.joinToString(" ") }

    /** 文字 → 亮灭时序。末尾带 7 单位词间隔，循环播放时下一轮直接接上。 */
    fun steps(text: String): List<Step> {
        val out = ArrayList<Step>()
        val words = clean(text).split(' ').filter { it.isNotEmpty() }
        words.forEachIndexed { wi, w ->
            if (wi > 0) out += Step(false, 7)
            w.forEachIndexed { li, ch ->
                if (li > 0) out += Step(false, 3)
                table[ch]!!.forEachIndexed { ei, e ->
                    if (ei > 0) out += Step(false, 1)
                    out += Step(true, if (e == '.') 1 else 3)
                }
            }
        }
        if (out.isNotEmpty()) out += Step(false, 7)
        return out
    }
}
