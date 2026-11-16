/*
 * Prompts.kt —— 三套系统提示词（CLOSED / RAG / KEYWORDS）和相关的小工具。
 *
 * 位置：AppViewModel 拼提示词时调用；与 eval/run_eval.py 的 SYS_* 常量、微调数据逐字对应。
 * 设计：模型很小（E2B），提示词必须短且明确；“不确定就说不知道、不要编造药名剂量”是安全底线。
 */
package app.ark.llm

/**
 * 提示词。必须和 eval/run_eval.py 里的 SYS_CLOSED / SYS_RAG / SYS_KW 逐字一致：
 * 评测分数和微调数据都是用这三套提示词得到的，改了这里要同步改那边并重新评测。
 */
object Prompts {
    /** 资料里没找到时的“闭卷”提示：模型凭自身知识回答（Qa.mode = closed） */
    const val CLOSED = "你是一个离线求生助手，用户可能处在没有网络、没有医生的环境。请用简体中文回答：" +
        "先给结论，再给关键数字和步骤，尽量简短。只说你确定的事实；不知道或不确定就直接说不知道，" +
        "绝对不要编造药名、剂量、数字或方法。"

    /** 有检索资料时的提示：要求按资料答、标 [n] 出处；“资料中没有提到”是约定短语（界面/评测可能依赖） */
    const val RAG = "你是一个离线求生助手。" +
        "下面给出从离线资料库检索到的资料。" +
        "请根据资料，用简体中文简洁回答，并在用到资料的句子后标注出处编号，如 [1]。" +
        "资料往往不会逐字回答问题：如果资料讲了相关的原理、做法、公式或数字，就把它们整理成能照着做的步骤；需要计算时，用资料里的公式和问题里的数字算出结果，并写出算式。" +
        "只有资料里确实没有相关内容时，才回答“资料中没有提到”。" +
        "不要用资料以外的知识补充数字、剂量或药名。"

    /** 让模型把中文问题改写成英文检索词：资料包的 FTS 索引是英文的（检索走英文、显示中文） */
    const val KEYWORDS = "把用户的中文问题改写成用于搜索英文百科和急救手册的英文关键词。只输出 3 到 6 个英文关键词，用空格分隔，不要输出任何其他内容。"

    /** 拼资料 + 问题：资料编号从 1 开始，和回答里的 [n] 、界面出处列表对应；问题放在最后（资料太长时模型会“看不到问题”，见 lostQuestion） */
    fun ragUser(passages: List<String>, question: String): String =
        "资料：\n" + passages.mapIndexed { i, t -> "[${i + 1}] $t" }.joinToString("\n\n") + "\n\n问题：$question"

    private val LOST = Regex("^\\s*(请提供您?的问题|请问您有什么问题|请告诉我您的问题|您想问什么)")

    /** 模型没看到问题时的典型短回答（实测出现在资料很长时）。 */
    fun lostQuestion(answer: String) = answer.length < 80 && LOST.containsMatchIn(answer)

    private val STOP = setOf(
        "the", "and", "or", "of", "for", "to", "in", "on", "a", "an", "how", "what", "with", "is", "are", "do",
        "does", "can", "why", "when", "which", "from", "by", "at", "as", "be", "it", "use", "using", "keywords",
    )

    /** 从模型输出（或用户问题）里取英文检索词：取英文单词、小写、去停用词和重复、最多 8 个。纯中文输入返回空列表。 */
    fun parseKeywords(raw: String): List<String> =
        Regex("[A-Za-z][A-Za-z0-9-]*").findAll(raw)
            .map { it.value.lowercase().trim('-') }
            .filter { it.length >= 2 && it !in STOP }
            .distinct().take(8).toList()
}
