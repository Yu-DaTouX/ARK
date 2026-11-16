// 电脑上给资料包算段落向量，和手机用同一个 .litertlm 模型（EmbeddingGemma 2 270M），保证向量一致。
//
//   pack  <model> <arkpack> [dim]       给所有段落算向量，写进资料包的 vec 表（int8 + 每条一个缩放系数）
//   query <model> <in.txt> <out.jsonl> [dim]   每行一个查询 → 每行一个 JSON 数组（给 Python 评测用）
//   gen   <大模型.litertlm> <in.jsonl> <out.jsonl> [maxTokens]   每行 {"system","user",...} → 加上 answer/seconds/tokens
//         和 App 同样的运行时与参数（GPU 优先、贪心、关闭思考），用来在电脑上检查自己导出的模型
//
// 文本格式按模型说明：段落 "title: {标题|none} | text: {正文}"，查询 "task: search result | query: {问题}"。
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.sql.DriverManager
import kotlin.math.abs
import kotlin.math.roundToInt

fun docTitle(t: String?): String {
    if (t.isNullOrBlank()) return "none"
    var s = t.substringAfterLast('/').removeSuffix(".pdf").removeSuffix(".html")
    if (s.startsWith("en_wtnd")) return "Where There Is No Doctor"
    s = s.replace(Regex("\\s*\\(\\d+\\)$"), "").replace('_', ' ').trim()
    return s.ifBlank { "none" }
}

/** v3 起段落第一行是“书名 › 章节”：拿它当标题，比文件名更能说明这段讲什么。 */
fun docText(title: String?, text: String): String {
    val nl = text.indexOf('\n')
    if (nl in 1..200 && !title.isNullOrBlank() && text.startsWith(title)) return "title: ${text.substring(0, nl)} | text: ${text.substring(nl + 1)}"
    return "title: ${docTitle(title)} | text: $text"
}
fun queryText(q: String) = "task: search result | query: $q"

fun engine(model: String, maxLen: Int): EmbeddingEngine {
    val gpu = System.getenv("ARK_EMBED_CPU") == null
    for (b in if (gpu) listOf("gpu", "cpu") else listOf("cpu")) {
        try {
            val be = if (b == "gpu") Backend.GPU() else Backend.CPU(Runtime.getRuntime().availableProcessors().coerceAtMost(16), null)
            val e = EmbeddingEngine(EmbeddingEngineConfig(modelPath = model, backend = be, maxInputLength = maxLen,
                cacheDir = File(System.getProperty("java.io.tmpdir"), "ark-embed").apply { mkdirs() }.path))
            e.initialize()
            System.err.println("backend=$b")
            return e
        } catch (t: Throwable) {
            System.err.println("$b 初始化失败：${t.message}")
        }
    }
    error("没有可用的后端")
}

fun embedOne(eng: EmbeddingEngine, text: String, o: EmbeddingOptions): com.google.ai.edge.litertlm.EmbeddingResponse {
    var x = text
    while (true) {
        try { return eng.computeEmbedding(listOf(InputData.Text(x)), o) } catch (e: Exception) {
            System.err.println("截短：${x.length} 字符"); x = x.substring(0, x.length * 3 / 4)
        }
    }
}

fun quantize(v: FloatArray): ByteArray {
    val m = v.maxOf { abs(it) }.coerceAtLeast(1e-9f)
    val bb = ByteBuffer.allocate(4 + v.size).order(ByteOrder.LITTLE_ENDIAN)
    bb.putFloat(m / 127f)
    v.forEach { bb.put((it / m * 127f).roundToInt().coerceIn(-127, 127).toByte()) }
    return bb.array()
}

fun main(args: Array<String>) {
    val mode = args[0]
    val opts = { dim: Int -> EmbeddingOptions(normalize = true, outputSize = dim) }
    when (mode) {
        "pack" -> {
            val dim = args.getOrNull(3)?.toInt() ?: 256
            val eng = engine(args[1], 1024)
            DriverManager.getConnection("jdbc:sqlite:${args[2]}").use { db ->
                db.createStatement().use {
                    it.execute("DROP TABLE IF EXISTS vec")
                    it.execute("CREATE TABLE vec(chunk_id INTEGER PRIMARY KEY, v BLOB NOT NULL)")
                    it.execute("INSERT OR REPLACE INTO meta(key, value) VALUES('vec_model', 'embeddinggemma-2-text-270m'), ('vec_dim', '$dim')")
                }
                val rows = ArrayList<Triple<Long, String?, String>>()
                db.createStatement().use { st ->
                    st.executeQuery("SELECT c.id, coalesce(d.title, c.title), c.text FROM chunks c LEFT JOIN docs d ON d.id = c.doc_id ORDER BY c.id").use { rs ->
                        while (rs.next()) rows += Triple(rs.getLong(1), rs.getString(2), rs.getString(3))
                    }
                }
                db.autoCommit = false
                val ins = db.prepareStatement("INSERT INTO vec(chunk_id, v) VALUES(?, ?)")
                val t0 = System.currentTimeMillis()
                rows.chunked(16).forEachIndexed { bi, batch ->
                    val texts = batch.map { docText(it.second, it.third) }
                    val res = try {
                        eng.computeEmbeddingBatch(texts.map { listOf(InputData.Text(it)) }, opts(dim))
                    } catch (e: Exception) {
                        texts.map { embedOne(eng, it, opts(dim)) } // 个别段落超过 1024 token：逐条算，超长的截短
                    }
                    batch.zip(res).forEach { (r, e) -> ins.setLong(1, r.first); ins.setBytes(2, quantize(e.embedding)); ins.addBatch() }
                    ins.executeBatch()
                    if (bi % 20 == 0) System.out.println("${(bi + 1) * 16}/${rows.size}  ${(System.currentTimeMillis() - t0) / 1000}s")
                }
                db.commit()
                println("写入 ${rows.size} 条，${dim} 维，用时 ${(System.currentTimeMillis() - t0) / 1000} 秒")
            }
            eng.close()
        }
        "query" -> {
            val dim = args.getOrNull(4)?.toInt() ?: 256
            val eng = engine(args[1], 256)
            File(args[3]).printWriter().use { out ->
                File(args[2]).readLines().filter { it.isNotBlank() }.forEach { q ->
                    val t0 = System.nanoTime()
                    val e = eng.computeEmbedding(listOf(InputData.Text(queryText(q))), opts(dim)).embedding
                    System.err.println("%.1f ms  %s".format((System.nanoTime() - t0) / 1e6, q))
                    out.println(e.joinToString(",", "[", "]"))
                }
            }
            eng.close()
        }
        "gen" -> gen(args[1], args[2], args[3], args.getOrNull(4)?.toInt() ?: 900)
        else -> error("未知模式 $mode")
    }
}
