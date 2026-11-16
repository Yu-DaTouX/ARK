/**
 * Gen.kt —— 电脑端批量生成（评测用），从 Embed.kt 的 "gen" 模式调用。
 *
 * 输入每行 {"system","user","q?","tools?"}，输出每行多出 answer / seconds / total_tokens（带计算器时还有 calls）。
 * 参数与 App 的 LlmManager 一致（GPU 优先、topK=1、temp=0.1、关思考），所以这里测出的准确度能代表手机。
 * 断点续跑：读输出文件已有行数，从下一行接着做（改模型或参数后记得删除旧输出）。
 * 环境变量：ARK_ACT=FLOAT32 指定激活精度（和手机默认一致）；ARK_EMBED_CPU 强制走 CPU。
 */

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.tool
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.File

/** 和 App 的 LlmManager 相同的加载与生成参数。 */
fun gen(model: String, inFile: String, outFile: String, maxTokens: Int) {
    val cpu = System.getenv("ARK_EMBED_CPU") != null
    val cache = File(System.getProperty("java.io.tmpdir"), "ark-embed").apply { mkdirs() }.path
    var eng: Engine? = null
    for (gpu in if (cpu) listOf(false) else listOf(true, false)) {
        try {
            val t0 = System.nanoTime()
            val e = Engine(EngineConfig(modelPath = model, backend = if (gpu) Backend.GPU() else Backend.CPU(), maxNumTokens = 4096, cacheDir = cache,
                activationDataType = System.getenv("ARK_ACT")?.let { com.google.ai.edge.litertlm.ActivationDataType.valueOf(it) }))
            e.initialize()
            System.err.println("backend=${if (gpu) "gpu" else "cpu"} 加载 %.1f s".format((System.nanoTime() - t0) / 1e9))
            eng = e
            break
        } catch (t: Throwable) {
            System.err.println("${if (gpu) "gpu" else "cpu"} 初始化失败：${t.message}")
        }
    }
    val e = eng ?: error("没有可用的后端")
    val gson = Gson()
    // 断点续跑：输出行数与输入的非空行一一对应，已有多少行就从下一行接着做
    val done = File(outFile).takeIf { it.exists() }?.readLines()?.size ?: 0
    File(outFile).appendText("")
    File(inFile).readLines().filter { it.isNotBlank() }.drop(done).forEach { line ->
        val r = gson.fromJson(line, JsonObject::class.java)
        val calc = if (r.has("tools") && r["tools"].asBoolean) ArkTools() else null // 输入里 tools=true 就给模型计算器
        val conv = e.createConversation(ConversationConfig(
            systemInstruction = Contents.of(r["system"].asString),
            tools = if (calc != null) listOf(tool(calc)) else emptyList(),
            automaticToolCalling = true,
            samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.1),
            maxOutputToken = maxTokens,
            thinkingConfig = ThinkingConfig(enableThinking = false),
        ))
        val t0 = System.nanoTime()
        val ans = conv.sendMessage(r["user"].asString).toString().trim()
        val sec = (System.nanoTime() - t0) / 1e9
        val toks = runCatching { conv.getTokenCount() }.getOrDefault(-1)
        conv.close()
        r.addProperty("answer", ans)
        r.addProperty("seconds", sec)
        r.addProperty("total_tokens", toks)
        if (calc != null) r.add("calls", gson.toJsonTree(calc.calls.map { listOf(it.first, it.second) }))
        File(outFile).appendText(gson.toJson(r) + "\n") // 逐题落盘：中途中断也不丢已完成的题
        System.err.println("%5.1f s %4d 字  %s".format(sec, ans.length, ans.take(60).replace("\n", " ")))
    }
    e.close()
}
