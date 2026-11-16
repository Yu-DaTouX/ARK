/*
 * LlmManager.kt —— 端侧大模型（LiteRT-LM，Gemma 4 E2B/E4B）的加载、生成、停止、卸载。
 *
 * 位置：由 ArkApp 创建一份；AppViewModel 调用 ensure()/generate()/stop()/unload()；提示词在 Prompts.kt，
 * 可选的计算器工具在 ArkTools.kt。电脑端 tools/embed/Gen.kt 用同样的参数批量跑回答。
 *
 * 关键设计决定：
 *  - 一条专用单线程 + Mutex：引擎不是线程安全，生成是挂起的，只靠单线程不足以防止 unload 与活跃对话交错。
 *  - GPU 激活精度默认 FLOAT32：运行时默认的低精度会把数字抄错（146→1466、492→499），
 *    实测数字抄错数 6–11 → 0，代价是每问多约 9 J、慢约 6 秒（docs/HANDOFF.md 4c）。
 *  - GPU 崩溃自愈：初始化前写 gpu_init_pending，成功/捕获异常后清除；如果下次启动还是 true，
 *    说明上次在驱动里直接把进程崩了，标记 gpu_broken，以后默认走 CPU。写 prefs 必须 commit()（同步），
 *    否则进程死的时候 apply() 的异步写盘还没落地。
 *  - 调试开关走 SharedPreferences（debug_spec、debug_act），由 MainActivity 的 adb Intent 写入。
 */
package app.ark.llm

import android.content.Context
import android.content.SharedPreferences
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.ai.edge.litertlm.tool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * 端侧模型（LiteRT-LM）。所有调用排在一条专用线程上，一次只跑一个请求。
 *
 * 省电相关的取舍：
 * - 默认 GPU：小米 12S Ultra 实测 GPU 读资料快 6 倍，整机功耗 4.7 W，CPU 要 8.2 W 且发烫到 96°C。
 * - 贪心解码（topK = 1）、关闭“思考”、限制最长输出：少生成一个字就少耗一份电，答案也更稳定。
 * - 每次提问新建对话，不带历史，提示词短，读资料快。
 * - GPU 初始化如果让进程直接崩掉（个别机型的驱动问题），下次启动会记住并改用 CPU。
 */
class LlmManager(private val ctx: Context, private val prefs: SharedPreferences) {

    /** 模型状态，供界面显示；Ready.backend 是 "GPU" 或 "CPU"（实际生效的，可能是 GPU 失败后退回的） */
    sealed interface State {
        data object Unloaded : State
        data class Loading(val model: String) : State
        data class Ready(val model: String, val backend: String) : State
        data class Failed(val model: String, val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Unloaded)
    val state: StateFlow<State> = _state
    private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "ark-llm") }.asCoroutineDispatcher()
    private var engine: Engine? = null
    private var key: String? = null
    @Volatile private var running: Conversation? = null
    @Volatile private var stopped = false
    /** 初始化 / 生成 / 卸载互斥：生成挂起时线程会让出，不加锁 unload 会和活跃对话交错 */
    private val lock = Mutex()

    init {
        // 上次 GPU 初始化到一半进程没了 → 这台机器的 GPU 驱动不可靠，以后默认走 CPU
        if (prefs.getBoolean(GPU_PENDING, false)) {
            prefs.edit().putBoolean(GPU_PENDING, false).putBoolean(GPU_BROKEN, true).commit()
        }
    }

    /** 这台机器的 GPU 初始化曾经把进程崩掉，默认不再尝试 GPU */
    val gpuBroken get() = prefs.getBoolean(GPU_BROKEN, false)

    /** 用户在设置里手动“重新尝试 GPU”时清除标记 */
    fun resetGpu() = prefs.edit().putBoolean(GPU_BROKEN, false).apply()

    /**
     * 确保指定模型已加载（同一模型 + 同一后端设置已加载则直接返回）。
     *
     * @param model .litertlm 模型文件
     * @param preferGpu true 先试 GPU，失败自动退 CPU；若 [gpuBroken] 则直接 CPU
     * 挂起函数，可从任意协程调用（内部切到 ark-llm 线程）。首次加载 GPU 约 30 秒，有 cacheDir 缓存后约 9 秒；
     * 会在 cacheDir 写编译缓存、占用大量内存。
     * @throws IllegalStateException GPU 和 CPU 都加载失败（State 同时变 Failed）
     * @throws CancellationException 加载中被取消（已释放半初始化的引擎，State 回到 Unloaded）
     */
    suspend fun ensure(model: File, preferGpu: Boolean) = lock.withLock { withContext(thread) {
        val spec = prefs.getString("debug_spec", "") // 调试：""=默认，"0"/"1"=关/开推测解码（MTP）
        // GPU 激活精度：默认 FLOAT32。运行时默认的低精度会把数字抄错（146→1466、492→499），CPU 和 GPU+FLOAT32 都不会。
        // 调试：ark_act=DEFAULT 用运行时默认精度做对比
        val act = prefs.getString("debug_act", "FLOAT32") ?: "FLOAT32"
        // 缓存键包含调试开关，切换开关后会重建引擎
        val k = "${model.path}|$preferGpu|$spec|$act"
        if (engine != null && key == k) return@withContext
        closeEngine()
        val name = displayName(model)
        _state.value = State.Loading(name)
        val order = if (preferGpu && !gpuBroken) listOf(true, false) else listOf(false) // true=GPU，先 GPU 后 CPU
        var last: Throwable? = null
        for (gpu in order) {
            if (gpu) prefs.edit().putBoolean(GPU_PENDING, true).commit()
            var cand: Engine? = null
            try {
                // 调试版打开测速，生成后在 logcat 里输出 prefill / decode 速度
                if (ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
                    @OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)
                    com.google.ai.edge.litertlm.ExperimentalFlags.enableBenchmark = true
                @OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)
                com.google.ai.edge.litertlm.ExperimentalFlags.enableSpeculativeDecoding = when (spec) { "0" -> false; "1" -> true; else -> null }
                val e = Engine(
                    EngineConfig(
                        modelPath = model.path,
                        backend = if (gpu) Backend.GPU() else Backend.CPU(),
                        maxNumTokens = 4096, // 上下文总长上限（提示词 + 资料 + 输出）
                        cacheDir = ctx.cacheDir.path, // GPU 内核缓存：第二次加载从 ~30 秒降到 ~9 秒
                        activationDataType = if (gpu && act != "DEFAULT") com.google.ai.edge.litertlm.ActivationDataType.valueOf(act) else null,
                    )
                )
                cand = e // 初始化期间若失败/被取消，靠 cand 在 catch 里释放原生资源
                e.initialize()
                cand = null
                engine = e
                key = k
                _state.value = State.Ready(name, if (gpu) "GPU" else "CPU")
                android.util.Log.i("ark", "engine ${if (gpu) "gpu" else "cpu"} act=$act")
                return@withContext
            } catch (c: CancellationException) {
                runCatching { cand?.close() }
                _state.value = State.Unloaded
                throw c
            } catch (t: Throwable) {
                last = t
                runCatching { cand?.close() }
            } finally {
                if (gpu) prefs.edit().putBoolean(GPU_PENDING, false).commit()
            }
        }
        _state.value = State.Failed(name, last?.message ?: "加载失败")
        throw IllegalStateException("模型加载失败：${last?.message}", last)
    } }

    /**
     * 生成一段回答；onText 收到的是到目前为止的完整文本（已去掉 <think> 标签，约 8 次/秒限流）。stop() 之后返回已生成的部分。
     *
     * @param system 系统提示词（Prompts.*）
     * @param user 用户消息（通常是 Prompts.ragUser 拼好的“资料 + 问题”）
     * @param maxTokens 最长输出 token 数
     * @param calc true 时挂上计算器工具（目前 App 里不开，见 docs/HANDOFF.md 4d）
     * @param onText 流式回调，在 ark-llm 线程上被调用，调用方自己切到主线程更新 UI
     * @return 清理后的完整回答
     * 挂起，相当耗电（GPU 跑满）。未加载时抛 IllegalStateException("模型未加载")；非 stop() 引起的引擎异常原样抛出；
     * 协程被取消时会 cancelProcess 后重抛 CancellationException。
     */
    @OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)
    suspend fun generate(system: String, user: String, maxTokens: Int, calc: Boolean = false, onText: (String) -> Unit = {}): String =
        lock.withLock { withContext(thread) {
            val e = engine ?: error("模型未加载")
            stopped = false
            val conv = try {
                e.createConversation(config(system, maxTokens, thinkingOff = true, calc))
            } catch (t: Throwable) {
                e.createConversation(config(system, maxTokens, thinkingOff = false, calc)) // 不支持思考开关的模型：退回不设置，靠 clean() 去掉 <think>
            }
            running = conv
            val sb = StringBuilder()
            var lastEmit = 0L
            try {
                conv.sendMessageAsync(user).collect { m ->
                    val piece = m.toString()
                    sb.append(piece)
                    // 小模型贪心解码偶尔会把同一行重复很多次：刚写完的一行和前面某行一样就截掉并停止，省时间也省电
                    if ('\n' in piece) repeatCut(sb)?.let { sb.setLength(it); onText(clean(sb)); logBench(conv); throw RepeatStop() }
                    // 界面刷新限流：每个 token 都 clean()+重组+排版太费 CPU（CPU 后端时还和解码抢核），约 8 次/秒足够跟上阅读
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastEmit >= UI_EMIT_MS) { lastEmit = now; onText(clean(sb)) }
                }
                onText(clean(sb)) // 最后一段一定刷出去
                logBench(conv)
            } catch (r: RepeatStop) {
                runCatching { conv.cancelProcess() }
                android.util.Log.i("ark", "repeat stop at ${sb.length}")
            } catch (c: CancellationException) {
                runCatching { conv.cancelProcess() }
                throw c
            } catch (t: Throwable) {
                if (!stopped) throw t
            } finally {
                running = null
                runCatching { conv.close() }
            }
            clean(sb)
        } }

    /** 最近一次生成的测速结果（调试版），批量测试写进输出文件 */
    @Volatile var lastBench: String? = null

    @OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)
    private fun logBench(conv: Conversation) {
        runCatching { conv.getBenchmarkInfo() }.getOrNull()?.let { b ->
            val s = "prefill=${b.lastPrefillTokenCount}@%.1f decode=${b.lastDecodeTokenCount}@%.1f ttft=%.2f"
                .format(b.lastPrefillTokensPerSecond, b.lastDecodeTokensPerSecond, b.timeToFirstTokenInSecond)
            lastBench = s
            android.util.Log.i("ark", "bench $s")
        }
    }

    /** 请求停止当前生成（非挂起，任意线程可调）；generate 会返回已生成的部分而不是抛错。 */
    fun stop() {
        stopped = true
        running?.let { runCatching { it.cancelProcess() } }
    }

    /** 卸载引擎释放内存：先 stop() 再拿锁，等活跃的生成结束。挂起函数。 */
    suspend fun unload() {
        stop() // 先让活跃的生成结束，再拿锁关引擎
        lock.withLock { withContext(thread) { closeEngine() } }
    }

    private fun closeEngine() {
        runCatching { engine?.close() }
        engine = null
        key = null
        _state.value = State.Unloaded
    }

    /** calc=true 时给模型认单位的计算器（ArkTools），由引擎自动执行工具调用。 */
    private fun config(system: String, maxTokens: Int, thinkingOff: Boolean, calc: Boolean = false) = ConversationConfig(
        systemInstruction = Contents.of(system),
        tools = if (calc) listOf(tool(ArkTools())) else emptyList(),
        automaticToolCalling = true,
        samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.1), // 贪心解码；temperature 仅作保险
        maxOutputToken = maxTokens,
        thinkingConfig = if (thinkingOff) ThinkingConfig(enableThinking = false) else null,
    )

    companion object {
        private const val GPU_PENDING = "gpu_init_pending"
        private const val GPU_BROKEN = "gpu_broken"
        private const val UI_EMIT_MS = 120L // 界面刷新最小间隔 ≈ 8 次/秒
        private val THINK = Regex("(?s)<think>.*?(</think>|$)")

        /** 去掉 <think>…</think>（未闭合的 <think> 连同后面所有内容一起去掉）并删除开头空白 */
        fun clean(sb: CharSequence) = THINK.replace(sb, "").trimStart()

        private class RepeatStop : RuntimeException()

        /** 最后一个写完的行（至少 6 个字）如果和前面某行相同，返回它的起始位置（从这里截断），否则 null。 */
        fun repeatCut(s: CharSequence): Int? {
            val t = s.toString()
            val end = t.lastIndexOf('\n')
            if (end <= 0) return null
            val start = t.lastIndexOf('\n', end - 1) + 1
            val line = t.substring(start, end).trim()
            if (line.length < 6) return null
            return if (t.substring(0, start).lineSequence().any { it.trim() == line }) start else null
        }

        /** 模型文件名 → 界面显示名（去掉量化/后端后缀） */
        fun displayName(f: File) = f.nameWithoutExtension
            .replace("_mixed_int4_gpu_composite_v0190", "")
            .replace("-it", "").replace('_', ' ')
    }
}
