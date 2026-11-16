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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    init {
        // 上次 GPU 初始化到一半进程没了 → 这台机器的 GPU 驱动不可靠，以后默认走 CPU
        if (prefs.getBoolean(GPU_PENDING, false)) {
            prefs.edit().putBoolean(GPU_PENDING, false).putBoolean(GPU_BROKEN, true).commit()
        }
    }

    val gpuBroken get() = prefs.getBoolean(GPU_BROKEN, false)
    fun resetGpu() = prefs.edit().putBoolean(GPU_BROKEN, false).apply()

    suspend fun ensure(model: File, preferGpu: Boolean) = withContext(thread) {
        val spec = prefs.getString("debug_spec", "") // 调试：""=默认，"0"/"1"=关/开推测解码（MTP）
        // GPU 激活精度：默认 FLOAT32。运行时默认的低精度会把数字抄错（146→1466、492→499），CPU 和 GPU+FLOAT32 都不会。
        // 调试：ark_act=DEFAULT 用运行时默认精度做对比
        val act = prefs.getString("debug_act", "FLOAT32") ?: "FLOAT32"
        val k = "${model.path}|$preferGpu|$spec|$act"
        if (engine != null && key == k) return@withContext
        closeEngine()
        val name = displayName(model)
        _state.value = State.Loading(name)
        val order = if (preferGpu && !gpuBroken) listOf(true, false) else listOf(false)
        var last: Throwable? = null
        for (gpu in order) {
            if (gpu) prefs.edit().putBoolean(GPU_PENDING, true).commit()
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
                        maxNumTokens = 4096,
                        cacheDir = ctx.cacheDir.path, // GPU 内核缓存：第二次加载从 ~30 秒降到 ~9 秒
                        activationDataType = if (gpu && act != "DEFAULT") com.google.ai.edge.litertlm.ActivationDataType.valueOf(act) else null,
                    )
                )
                e.initialize()
                engine = e
                key = k
                _state.value = State.Ready(name, if (gpu) "GPU" else "CPU")
                android.util.Log.i("ark", "engine ${if (gpu) "gpu" else "cpu"} act=$act")
                return@withContext
            } catch (t: Throwable) {
                last = t
            } finally {
                if (gpu) prefs.edit().putBoolean(GPU_PENDING, false).commit()
            }
        }
        _state.value = State.Failed(name, last?.message ?: "加载失败")
        throw IllegalStateException("模型加载失败：${last?.message}", last)
    }

    /** 生成一段回答；onText 收到的是到目前为止的完整文本。stop() 之后返回已生成的部分。 */
    @OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)
    suspend fun generate(system: String, user: String, maxTokens: Int, onText: (String) -> Unit = {}): String =
        withContext(thread) {
            val e = engine ?: error("模型未加载")
            stopped = false
            val conv = try {
                e.createConversation(config(system, maxTokens, thinkingOff = true))
            } catch (t: Throwable) {
                e.createConversation(config(system, maxTokens, thinkingOff = false)) // 不支持思考开关的模型
            }
            running = conv
            val sb = StringBuilder()
            try {
                conv.sendMessageAsync(user).collect { m ->
                    val piece = m.toString()
                    sb.append(piece)
                    // 小模型贪心解码偶尔会把同一行重复很多次：刚写完的一行和前面某行一样就截掉并停止，省时间也省电
                    if ('\n' in piece) repeatCut(sb)?.let { sb.setLength(it); onText(clean(sb)); logBench(conv); throw RepeatStop() }
                    onText(clean(sb))
                }
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
        }

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

    fun stop() {
        stopped = true
        running?.let { runCatching { it.cancelProcess() } }
    }

    suspend fun unload() = withContext(thread) { closeEngine() }

    private fun closeEngine() {
        runCatching { engine?.close() }
        engine = null
        key = null
        _state.value = State.Unloaded
    }

    private fun config(system: String, maxTokens: Int, thinkingOff: Boolean) = ConversationConfig(
        systemInstruction = Contents.of(system),
        samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.1),
        maxOutputToken = maxTokens,
        thinkingConfig = if (thinkingOff) ThinkingConfig(enableThinking = false) else null,
    )

    companion object {
        private const val GPU_PENDING = "gpu_init_pending"
        private const val GPU_BROKEN = "gpu_broken"
        private val THINK = Regex("(?s)<think>.*?(</think>|$)")

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

        fun displayName(f: File) = f.nameWithoutExtension
            .replace("_mixed_int4_gpu_composite_v0190", "")
            .replace("-it", "").replace('_', ' ')
    }
}
