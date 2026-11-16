/*
 * Common.kt —— 问答/资料共用的显示组件和文本处理。
 *
 * 内容：delatex（LaTeX → 纯文本）、renderAnswer（Markdown/出处链接/红色数字）、unverifiedNumbers（数字核对）、
 * QaCard / SourceList / PassageRow（问答卡片与出处行）、ReaderDialog（全屏阅读原文，可切译文/原文）。
 * 被 AskScreen、BookmarksScreen、LibraryScreen、Root 使用。
 * 设计：小模型会把数字抄错（剂量错一位可能出人命），所以回答里在出处和问题里找不到的数字一律标红并提示；
 * 资料里常有 LaTeX，手机上不渲染，转成可读文本。
 */
package app.ark.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.ark.data.Passage
import app.ark.data.Qa
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// LaTeX 命令 → 符号的对照表（delatex 用）
private val greek = mapOf("times" to "×", "cdot" to "·", "lambda" to "λ", "approx" to "≈", "le" to "≤", "leq" to "≤", "ge" to "≥", "geq" to "≥",
    "pm" to "±", "circ" to "°", "degree" to "°", "Omega" to "Ω", "omega" to "ω", "mu" to "μ", "pi" to "π", "Delta" to "Δ", "delta" to "δ",
    "alpha" to "α", "beta" to "β", "theta" to "θ", "sigma" to "σ", "rho" to "ρ", "eta" to "η", "infty" to "∞", "neq" to "≠", "rightarrow" to "→",
    "to" to "→", "%" to "%", "," to " ", ";" to " ", "quad" to " ", "qquad" to " ", "left" to "", "right" to "")

/** 资料和回答里的 LaTeX 公式（如 $\\frac{492}{f_{MHz}}$）在手机上显示成可读的纯文本：492/f_MHz。只处理常见写法；没有 `\` 和 `$` 时原样返回。纯函数。 */
fun delatex(src: String): String {
    if ('\\' !in src && '$' !in src) return src
    var s = src.replace(Regex("\\\\(begin|end)\\{(equation|align|displaymath)\\*?\\}"), " ").replace("\\[", "\\(").replace("\\]", "\\)")
    fun math(m0: String): String {
        var m = m0.replace('~', ' ')
        repeat(6) { // 嵌套（分数里套上下标等）一层层剥，最多 6 层
            m = m.replace(Regex("\\\\(?:mathrm|text|textbf|mathbf|mathit|operatorname|textrm)\\{([^{}]*)\\}"), "$1")
                .replace(Regex("\\\\[dt]?frac\\{([^{}]*)\\}\\{([^{}]*)\\}")) { r ->
                    fun w(x: String) = if (Regex("^[\\w.]+$").matches(x.trim())) x.trim() else "(${x.trim()})"
                    w(r.groupValues[1]) + "/" + w(r.groupValues[2])
                }
                .replace(Regex("\\\\sqrt\\{([^{}]*)\\}"), "√($1)")
                .replace(Regex("([_^])\\{([^{}]*)\\}"), "$1$2")
        }
        m = Regex("\\\\([A-Za-z]+|[%,;])").replace(m) { r -> greek[r.groupValues[1]] ?: r.groupValues[1] }
        return m.replace("{", "").replace("}", "").replace(Regex("\\^\\\\?circ|\\^°"), "°").replace(Regex(" {2,}"), " ")
    }
    // \\(…\\)、\\[…\\]、$$…$$、$…$：只转换公式片段；正文里的 ~（如“1~2 片”）和花括号不动
    s = Regex("\\\\\\((.+?)\\\\\\)", RegexOption.DOT_MATCHES_ALL).replace(s) { math(it.groupValues[1]) }
    s = Regex("\\$\\$([^$]+)\\$\\$").replace(s) { math(it.groupValues[1]) }
    // 单个 $…$ 容易和正文里的美元符号混淆：只有内容像公式（含 \ _ ^ = / { } 或很短）才转换
    s = Regex("\\$([^$\\n]{1,300}?)\\$").replace(s) { r ->
        val c = r.groupValues[1]
        if (Regex("[\\\\_^=/{}]").containsMatchIn(c) || (c.length <= 3 && ' ' !in c)) math(c) else r.value
    }
    // 剩下没包在定界符里的命令（\\frac{a}{b}、\\times 等）逐个替换，不碰其余文字
    if ('\\' in s) s = Regex("\\\\[dt]?frac\\{[^{}]*\\}\\{[^{}]*\\}|\\\\(?:mathrm|text|mathbf|sqrt)\\{[^{}]*\\}|\\\\[A-Za-z]+").replace(s) { math(it.value) }
    return s
}

/** 把模型输出里常见的 Markdown（标题、列表、粗体）和出处编号 [n] 渲染出来；点 [n] 打开对应原文。 */
fun renderAnswer(
    text: String,
    accent: Color,
    flagged: Set<String> = emptySet(), // 原文里找不到的数字：红色加下划线
    onCite: (Int) -> Unit,
): AnnotatedString = renderAnswer0(delatex(text), accent, flagged, onCite)

private fun renderAnswer0(text: String, accent: Color, flagged: Set<String>, onCite: (Int) -> Unit): AnnotatedString = buildAnnotatedString {
    val warn = SpanStyle(color = Color(0xFFD32F2F), fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline)
    fun plain(s: String) {
        val t = s.replace("**", "").replace("*", "") // 模型的粗体、斜体标记，手机上都不显示
        if (flagged.isEmpty()) { append(t); return }
        var p = 0
        for (m in Regex("\\d+(?:[.,]\\d+)?").findAll(t)) {
            if (m.value !in flagged) continue
            append(t.substring(p, m.range.first))
            withStyle(warn) { append(m.value) }
            p = m.range.last + 1
        }
        append(t.substring(p))
    }
    // 粗体 **x**；出处 [1] / [2, 3] / 【1】
    val inline = Regex("\\*\\*(.+?)\\*\\*|[\\[【](\\d{1,2}(?:\\s*[,，、]\\s*\\d{1,2})*)[]】]")
    // 小模型偶尔输出残缺的 Markdown：只有星号的空行去掉
    val lines = text.trimEnd().lines().filter { it.isBlank() || it.trim().trim('*').isNotEmpty() }
    lines.forEachIndexed { i, raw ->
        if (i > 0) append('\n')
        var line = raw
        var bold = false
        Regex("^\\s*#{1,6}\\s+").find(line)?.let { line = line.substring(it.range.last + 1); bold = true }
        Regex("^(\\s*)[-*•]\\s+").find(line)?.let {
            append("  ".repeat(it.groupValues[1].length / 2) + "• ")
            line = line.substring(it.range.last + 1)
        }
        if (bold) pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
        var pos = 0
        for (m in inline.findAll(line)) {
            plain(line.substring(pos, m.range.first))
            if (m.groupValues[1].isNotEmpty()) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { plain(m.groupValues[1]) }
            } else {
                val style = TextLinkStyles(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold))
                Regex("\\d+").findAll(m.groupValues[2]).forEach { d ->
                    val n = d.value.toInt()
                    withLink(LinkAnnotation.Clickable("cite$n-${m.range.first}", style) { onCite(n) }) { append("[$n]") }
                }
            }
            pos = m.range.last + 1
        }
        plain(line.substring(pos))
        if (bold) pop()
    }
}

/**
 * 数字核对：回答里的每个数字都应该能在出处原文（或问题）里找到。
 * 小模型抄数字会出错（实测把 “16 drops per gallon” 写成 “164 滴”），剂量错一位就可能出人命，所以找不到的数字要醒目提示。
 * 不算：出处编号 [n]、行首的列表序号 “1.”。单位换算出来的数字也会被标出来（宁可多提示）。
 */
fun unverifiedNumbers(answer: String, sources: List<Passage>, question: String): List<String> {
    if (sources.isEmpty()) return emptyList()
    val num = Regex("\\d+(?:[.,]\\d+)?")
    // 1,000 → 1000；5.50 → 5.5（只去掉小数点后面多余的 0）
    fun norm(s: String): String {
        val t = s.replace(",", "")
        return if ('.' in t) t.trimEnd('0').trimEnd('.') else t
    }
    val known = HashSet<String>()
    val texts = sources.map { it.text } + question
    texts.forEach { t -> num.findAll(t).forEach { known += norm(it.value) } }
    // 原文常把数字写成英文单词（five minutes、two drops），问题里可能写中文数字（二十分钟）
    texts.forEach { t -> Regex("[a-z]+").findAll(t.lowercase()).forEach { w -> NUM_WORDS[w.value]?.let { known += it } } }
    known += zhNumbers(question)
    val body = answer
        .replace(Regex("[\\[【]\\d{1,2}(?:\\s*[,，、]\\s*\\d{1,2})*[]】]"), " ")
        .replace(Regex("(?m)^\\s*\\d{1,2}[.、)]\\s"), " ")
    return num.findAll(body).map { it.value }.filter { norm(it) !in known }.distinct().toList()
}

private val NUM_WORDS: Map<String, String> =
    ("zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen " +
        "eighteen nineteen twenty").split(" ").withIndex().associate { it.value to it.index.toString() } +
        mapOf("thirty" to "30", "forty" to "40", "fifty" to "50", "sixty" to "60", "seventy" to "70", "eighty" to "80",
            "ninety" to "90", "hundred" to "100", "thousand" to "1000", "half" to "0.5", "once" to "1", "twice" to "2",
            "dozen" to "12", "single" to "1", "double" to "2", "triple" to "3")

private val ZH_DIGIT = mapOf('零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)

/** 问题里的中文数字（一百以内）：二十 → 20，十五 → 15，三 → 3。 */
private fun zhNumbers(t: String): List<String> = Regex("[零一二两三四五六七八九十]+").findAll(t).mapNotNull { m ->
    val s = m.value
    val i = s.indexOf('十')
    when {
        i >= 0 -> ((if (i == 0) 1 else ZH_DIGIT[s[0]] ?: 1) * 10 + (s.getOrNull(i + 1)?.let { ZH_DIGIT[it] } ?: 0)).toString()
        s.length == 1 -> ZH_DIGIT[s[0]]?.toString()
        else -> null
    }
}.toList()

/** 文档路径/文件名 → 好看的标题：去目录、扩展名，下划线换空格 */
fun prettyTitle(t: String) = t.substringAfterLast('/').removeSuffix(".pdf").removeSuffix(".html").replace('_', ' ')

private val timeFmt = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)

/**
 * 一问一答卡片。onBookmark(true/false) 切换书签；onOpen 点出处编号/出处行；onDelete 为 null 时不显示删除键（书签页不显示）。
 * 数字核对在这里按 qa.id 缓存计算。
 */
@Composable
fun QaCard(
    qa: Qa,
    onBookmark: (Boolean) -> Unit,
    onOpen: (Passage) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Surface(
            color = cs.primaryContainer,
            shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
            modifier = Modifier.align(Alignment.End).widthIn(max = 320.dp),
        ) {
            SelectionContainer { Text(qa.question, Modifier.padding(horizontal = 14.dp, vertical = 9.dp), style = MaterialTheme.typography.bodyLarge) }
        }
        Spacer(Modifier.height(8.dp))
        Surface(color = cs.surfaceContainerLow, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 2.dp)) {
                ModeTag(qa.mode)
                val bad = remember(qa.id) { unverifiedNumbers(qa.answer, qa.sources, qa.question) }
                if (bad.isNotEmpty()) {
                    Text(
                        "⚠ 这些数字在原文里找不到：${bad.joinToString("、")}。可能是抄错或换算，用药和剂量请点出处核对原文。",
                        style = MaterialTheme.typography.labelMedium, color = cs.tertiary,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 8.dp, bottom = 6.dp),
                    )
                }
                if (qa.answer.isNotBlank()) {
                    SelectionContainer(Modifier.padding(end = 8.dp)) {
                        Text(
                            renderAnswer(qa.answer, cs.primary, bad.toSet()) { n -> qa.sources.getOrNull(n - 1)?.let(onOpen) },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                if (qa.sources.isNotEmpty()) SourceList(qa.sources, initiallyOpen = qa.mode == "search", onOpen = onOpen)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val meta = listOfNotNull(
                        timeFmt.format(Date(qa.ts)),
                        qa.model.ifBlank { null },
                        if (qa.seconds > 0) "%.0f 秒".format(qa.seconds) else null,
                    ).joinToString(" · ")
                    Text(meta, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        ctx.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("方舟", "问：${qa.question}\n答：${qa.answer}"))
                        Toast.makeText(ctx, "已复制", Toast.LENGTH_SHORT).show()
                    }) { Icon(ArkIcons.Copy, "复制", tint = cs.onSurfaceVariant) }
                    if (onDelete != null) IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "删除", tint = cs.onSurfaceVariant) }
                    IconButton(onClick = { onBookmark(!qa.bookmarked) }) {
                        Icon(
                            if (qa.bookmarked) ArkIcons.Bookmark else ArkIcons.BookmarkBorder,
                            if (qa.bookmarked) "取消书签" else "加书签",
                            tint = if (qa.bookmarked) cs.secondary else cs.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** 回答上方的小标签，说明这条回答的来源类型（见 Qa.mode）；未知类型不显示 */
@Composable
fun ModeTag(mode: String) {
    val cs = MaterialTheme.colorScheme
    val (text, color) = when (mode) {
        "rag" -> "根据离线资料回答，点 [编号] 看原文" to cs.primary
        "closed" -> "资料里没找到，以下是 AI 自身的知识，可能出错，请谨慎" to cs.tertiary
        "search" -> "只检索，没有用 AI 生成" to cs.onSurfaceVariant
        else -> return
    }
    Text(text, style = MaterialTheme.typography.labelMedium, color = color, modifier = Modifier.padding(bottom = 6.dp))
}

/** 可折叠的出处列表；检索类问答默认展开（结果本身就是答案） */
@Composable
fun SourceList(sources: List<Passage>, initiallyOpen: Boolean, onOpen: (Passage) -> Unit) {
    var open by rememberSaveable { mutableStateOf(initiallyOpen) }
    Column(Modifier.padding(end = 8.dp)) {
        TextButton(onClick = { open = !open }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            Text(if (open) "收起出处" else "出处（${sources.size} 段原文）")
        }
        if (open) sources.forEachIndexed { i, p -> PassageRow(p, "[${i + 1}] ") { onOpen(p) } }
    }
}

/** 一条资料摘要（标题、包名、段号、前 3 行） */
@Composable
fun PassageRow(p: Passage, prefix: String = "", onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = cs.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Text(prefix + prettyTitle(p.title), style = MaterialTheme.typography.labelLarge, color = cs.primary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${p.packTitle} · 第 ${p.seq} 段", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            Text(delatex(p.text), style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * 全屏阅读原文，可以往前往后翻同一篇文档的相邻段落。
 * loadOriginal：取英文原文（仅“检索英文、显示中文”的包返回非 null；此时显示“原文/译文”切换和机器翻译提示）。
 */
@Composable
fun ReaderDialog(p: Passage, onStep: (Int) -> Unit, onClose: () -> Unit, loadOriginal: suspend (Passage) -> String? = { null }) {
    val cs = MaterialTheme.colorScheme
    var showEn by remember(p.packId, p.chunkId) { mutableStateOf(false) }
    val en by produceState<String?>(null, p.packId, p.chunkId) { value = loadOriginal(p) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = cs.background, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.statusBarsPadding().navigationBarsPadding()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(prettyTitle(p.title), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${p.packTitle} · 第 ${p.seq} 段" + if (en != null) " · 机器翻译，剂量等以原文为准" else "",
                            style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                    }
                    if (en != null) TextButton(onClick = { showEn = !showEn }) { Text(if (showEn) "译文" else "原文") }
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "关闭") }
                }
                HorizontalDivider(Modifier.padding(top = 8.dp), color = cs.outlineVariant)
                SelectionContainer(Modifier.weight(1f)) {
                    Text(
                        delatex(if (showEn) en ?: p.text else p.text),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                    )
                }
                HorizontalDivider(color = cs.outlineVariant)
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { onStep(-1) }) { Icon(ArkIcons.ChevronBack, null); Text("上一段") }
                    TextButton(onClick = { onStep(1) }) { Text("下一段"); Icon(ArkIcons.Chevron, null) }
                }
            }
        }
    }
}
