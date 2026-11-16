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
    private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "ark-embed") }.asCoroutineDispatcher()
    private var engine: EmbeddingEngine? = null
    private var path: String? = null

    /** 问题 → 256 维单位向量；没有模型或出错时返回 null（退回关键词检索）。 */
    suspend fun query(model: File?, q: String): FloatArray? = withContext(thread) {
        if (model == null) return@withContext null
        try {
            if (engine == null || path != model.path) {
                close()
                engine = EmbeddingEngine(
                    EmbeddingEngineConfig(
                        modelPath = model.path,
                        backend = Backend.CPU(),
                        maxInputLength = 256,
                        cacheDir = ctx.cacheDir.path,
                    )
                ).also { it.initialize() }
                path = model.path
            }
            engine!!.computeEmbedding(
                listOf(InputData.Text("task: search result | query: $q")),
                EmbeddingOptions(normalize = true, outputSize = DIM),
            ).embedding
        } catch (t: Throwable) {
            Log.w("ark", "embedding failed", t)
            close()
            null
        }
    }

    suspend fun unload() = withContext(thread) { close() }

    private fun close() {
        runCatching { engine?.close() }
        engine = null
        path = null
    }

    companion object {
        const val DIM = 256
    }
}
