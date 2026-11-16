/*
 * Embedder.kt —— 语义检索的“问题向量化”，手机端只算问题，不算资料段落。
 *
 * 位置：由 AppViewModel 在提问/搜索时调用 query()，得到的向量交给 PackRepository.vectorSearch
 * 和包内预存的 int8 向量做点积。段落向量由 tools/embed（Embed.kt 的 pack 命令）用同一个 .litertlm 预先算好，
 * 所以两边的模型文件、输出维度（DIM=256，经 EmbeddingOptions.outputSize 指定）、提示前缀必须保持一致，否则向量不在同一空间。
 * 设计：独占一条单线程（引擎非线程安全）；用 CPU 以免和大模型抢 GPU；失败一律返回 null 让上层退回纯关键词检索。
 */
package app.ark.llm

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * 语义检索用的向量模型（EmbeddingGemma 2 270M，165 MB）。
 * 中文问题直接和英文段落比相似度，不需要大模型先改写成英文检索词。
 * 资料包里的段落向量是电脑上用同一个 .litertlm 预先算好的（tools/embed），手机只算问题的向量。
 * 用 CPU：一次只算一句话（约几十毫秒），GPU 留给大模型。
 */
class Embedder(private val ctx: Context) {
    // 引擎和 path 只在 thread 这条线程上读写，无需加锁
    private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "ark-embed") }.asCoroutineDispatcher()
    private var engine: EmbeddingEngine? = null
    private var path: String? = null

    /**
     * 问题 → 256 维单位向量；没有模型或出错时返回 null（退回关键词检索）。
     *
     * 挂起函数，内部切到 ark-embed 线程，可在任意协程调用。模型文件变了会自动重建引擎；
     * 首次调用要加载 165 MB 模型（有耗时），读 cacheDir 写 GPU/CPU 编译缓存。
     * 异常全部在内部吞掉（记 logcat 并关闭引擎，下次重试）；CancellationException 也会被 catch(Throwable) 吞掉。
     */
    suspend fun query(model: File?, q: String): FloatArray? = withContext(thread) {
        if (model == null) return@withContext null
        try {
            if (engine == null || path != model.path) {
                close()
                val cand = EmbeddingEngine(
                    EmbeddingEngineConfig(
                        modelPath = model.path,
                        backend = Backend.CPU(),
                        maxInputLength = 256, // 问题很短，256 token 足够
                        cacheDir = ctx.cacheDir.path,
                    )
                )
                try { cand.initialize() } catch (t: Throwable) { runCatching { cand.close() }; throw t } // 初始化失败也要释放原生资源
                engine = cand
                path = model.path
            }
            engine!!.computeEmbedding(
                // EmbeddingGemma 的检索任务前缀；段落端在 tools/embed 里用 "title: … | text: …" 格式，两边必须配套
                listOf(InputData.Text("task: search result | query: $q")),
                EmbeddingOptions(normalize = true, outputSize = DIM),
            ).embedding
        } catch (t: Throwable) {
            Log.w("ark", "embedding failed", t)
            close()
            null
        }
    }

    /** 释放原生引擎（空闲卸载或用户删除模型时调用），可重复调用。 */
    suspend fun unload() = withContext(thread) { close() }

    private fun close() {
        runCatching { engine?.close() }
        engine = null
        path = null
    }

    companion object {
        /** 向量维度，须与资料包 meta.vec_dim 一致（PackRepository.vectorSearch 里 dim 不等就跳过该包） */
        const val DIM = 256
    }
}
