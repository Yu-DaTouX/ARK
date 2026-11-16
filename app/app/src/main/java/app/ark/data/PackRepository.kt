package app.ark.data

import android.content.SharedPreferences
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class PackInfo(
    val id: String,
    val title: String,
    val lang: String,
    val license: String,
    val version: String,
    val chunks: Int,
    val file: File,
    val enabled: Boolean,
    val bundled: Boolean,
    /** 检索走英文、显示中文的包（meta.display = zh）：chunks.text 是译文，text_en 是 zlib 压缩的英文原文。 */
    val displayZh: Boolean = false,
)

/** 资料里的一段原文。packId + docId + seq 可以定位到前后文。 */
data class Passage(
    val packId: String,
    val packTitle: String,
    val chunkId: Long,
    val docId: Long,
    val seq: Int,
    val title: String,
    val text: String,
    val score: Double = 0.0,
)

/**
 * 读取 .arkpack 资料包（电脑上用 data/build_pack.py 生成）。
 * 表：meta(key,value)、docs(id,path,title,src)、chunks(id,doc_id,seq,text,title)、chunks_fts(FTS5)。
 * 检索用 FTS5 的 BM25 排序：英文包用 porter 词干，中文包用 trigram。
 */
class PackRepository(private val storage: Storage, private val prefs: SharedPreferences) {
    private val driver = BundledSQLiteDriver()
    private val lock = Mutex()
    private val conns = HashMap<String, Pair<String, SQLiteConnection>>() // 路径 → (文件大小:修改时间, 连接)
    private val vecCache = HashMap<String, Vecs?>()
    private val _packs = MutableStateFlow<List<PackInfo>>(emptyList())
    val packs: StateFlow<List<PackInfo>> = _packs

    suspend fun refresh() = withContext(Dispatchers.IO) {
        lock.withLock {
            val files = storage.packFiles()
            conns.keys.filter { k -> files.none { it.path == k } }.forEach { conns.remove(it)?.second?.close(); vecCache.remove(it) }
            _packs.value = files.mapNotNull { f -> runCatching { info(f) }.getOrNull() }
        }
    }

    /** 文件被替换过（App 更新后重新复制内置包、重新下载）就重开连接、丢掉向量缓存，否则会一直读旧文件。 */
    private fun conn(f: File): SQLiteConnection {
        val sig = "${f.length()}:${f.lastModified()}"
        conns[f.path]?.let { (s, c) -> if (s == sig) return c else { c.close(); vecCache.remove(f.path) } }
        return driver.open(f.path, SQLITE_OPEN_READONLY).also { conns[f.path] = sig to it }
    }

    private fun info(f: File): PackInfo {
        val meta = HashMap<String, String>()
        conn(f).prepare("SELECT key, coalesce(value,'') FROM meta").use { st ->
            while (st.step()) meta[st.getText(0)] = st.getText(1)
        }
        val id = meta["id"] ?: f.nameWithoutExtension
        return PackInfo(
            id = id,
            title = meta["title"] ?: id,
            lang = meta["lang"] ?: "en",
            license = meta["license"] ?: "",
            version = meta["version"] ?: "",
            chunks = meta["chunks"]?.toIntOrNull() ?: 0,
            file = f,
            enabled = !prefs.getBoolean("pack_off_$id", false),
            bundled = storage.isBundled(f.name),
            displayZh = meta["display"] == "zh",
        )
    }

    suspend fun setEnabled(p: PackInfo, on: Boolean) {
        prefs.edit().putBoolean("pack_off_${p.id}", !on).apply()
        refresh()
    }

    suspend fun delete(p: PackInfo) = withContext(Dispatchers.IO) {
        lock.withLock {
            conns.remove(p.file.path)?.second?.close()
            vecCache.remove(p.file.path)
            p.file.delete()
        }
        refresh()
    }

    /**
     * 在所有启用的资料包里检索，关键词和语义两路合并。
     * enTerms：英文检索词（模型改写出来的 + 问题里本来就有的英文词），用于英文包的全文检索；
     * question：原问题，用于中文包（trigram 分词）；
     * qvec：问题的语义向量（没有向量模型时为 null），和包里预先算好的段落向量比相似度，中文问题也能直接找到英文段落。
     * 两路各取前 50，按名次倒数相加（RRF）合并，再按开头文字去重（同一本书常被多个合集重复收录）。
     * 实测（eval/hybrid_test.py，40 个真实问题）：前 4 段命中好段落 关键词 34 → 合并 36，排第一 27 → 33。
     */
    suspend fun search(enTerms: List<String>, question: String, limit: Int, qvec: FloatArray? = null): List<Passage> =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val kw = keywordSearch(enTerms, question)
                val sem = if (qvec != null) vectorSearch(qvec) else emptyList()
                val ranked = if (sem.isEmpty()) kw else {
                    val score = LinkedHashMap<String, Double>() // 同分时关键词那一路在前，和 hybrid_test.py 一致
                    val byKey = HashMap<String, Passage>()
                    for (list in listOf(kw, sem)) list.forEachIndexed { i, p ->
                        val k = "${p.packId}#${p.chunkId}"
                        score[k] = (score[k] ?: 0.0) + 1.0 / (RRF_K + i + 1)
                        byKey.putIfAbsent(k, p)
                    }
                    score.entries.sortedByDescending { it.value }.map { byKey.getValue(it.key) }
                }
                val seen = HashSet<String>()
                ranked.filter { seen.add(it.text.take(200).lowercase().filter(Char::isLetterOrDigit)) }.take(limit)
            }
        }

    /** 全文检索：FTS5 OR 查询取候选，先按“覆盖了几个检索词”排（OR 查询会把只命中一个词的长段排到前面），再按 BM25。 */
    private fun keywordSearch(enTerms: List<String>, question: String): List<Passage> {
        val found = ArrayList<Passage>()
        val enText = HashMap<Passage, String>() // 显示中文的包：用英文原文算检索词覆盖数，和 v4 排序一致
        for (p in _packs.value) {
            if (!p.enabled) continue
            val match = if (p.lang.startsWith("zh")) zhMatch(question, enTerms) else enMatch(enTerms)
            if (match.isEmpty()) continue
            runCatching {
                conn(p.file).prepare(if (p.displayZh) SEARCH_ZH else SEARCH).use { st ->
                    st.bindText(1, match)
                    st.bindLong(2, CANDIDATES.toLong())
                    while (st.step()) {
                        val ps = Passage(
                            p.id, p.title, st.getLong(0), st.getLong(1), st.getLong(2).toInt(),
                            st.getText(3), st.getText(4), st.getDouble(5),
                        )
                        found += ps
                        if (p.displayZh && !st.isNull(6)) enText[ps] = inflate(st.getBlob(6))
                    }
                }
            }
        }
        val stems = enTerms.map { it.lowercase().take(5) }.filter { it.length >= 3 }.distinct()
        val grams = zhGrams(question)
        fun coverage(p: Passage): Int {
            val low = (enText[p] ?: p.text).lowercase()
            return stems.count { low.contains(it) } + grams.count { p.text.contains(it) }
        }
        return found.map { it to coverage(it) }
            .sortedWith(compareByDescending<Pair<Passage, Int>> { it.second }.thenBy { it.first.score })
            .map { it.first }
    }

    /** 语义检索：逐条算点积（int8 向量 × 缩放系数），7800 段约几毫秒。 */
    private fun vectorSearch(q: FloatArray): List<Passage> {
        data class Hit(val pack: PackInfo, val id: Long, val sim: Float)
        val hits = ArrayList<Hit>()
        for (p in _packs.value) {
            if (!p.enabled) continue
            val v = vecs(p.file) ?: continue
            if (v.dim != q.size) continue
            val sims = FloatArray(v.ids.size)
            for (i in v.ids.indices) {
                var acc = 0f
                val off = i * v.dim
                for (j in 0 until v.dim) acc += q[j] * v.q[off + j]
                sims[i] = acc * v.scale[i]
            }
            sims.indices.sortedByDescending { sims[it] }.take(CANDIDATES).forEach { hits += Hit(p, v.ids[it], sims[it]) }
        }
        val top = hits.sortedByDescending { it.sim }.take(CANDIDATES)
        val out = ArrayList<Passage>()
        for ((pack, group) in top.groupBy { it.pack }) {
            val sim = group.associate { it.id to it.sim }
            runCatching {
                conn(pack.file).prepare(BY_IDS.replace("?", group.joinToString(",") { it.id.toString() })).use { st ->
                    while (st.step()) {
                        val id = st.getLong(0)
                        out += Passage(pack.id, pack.title, id, st.getLong(1), st.getLong(2).toInt(), st.getText(3), st.getText(4),
                            -(sim[id] ?: 0f).toDouble())
                    }
                }
            }
        }
        return out.sortedBy { it.score }
    }

    private class Vecs(val ids: LongArray, val q: ByteArray, val scale: FloatArray, val dim: Int)

    /** 资料包里的 vec 表（chunk_id, v = 4 字节缩放系数 + dim 个 int8），第一次用时读进内存（7800 段约 2 MB）。 */
    private fun vecs(f: File): Vecs? {
        val c = conn(f) // 先检查文件有没有换过
        if (vecCache.containsKey(f.path)) return vecCache[f.path]
        val v = runCatching {
            val dim = c.prepare("SELECT value FROM meta WHERE key = 'vec_dim'").use { if (it.step()) it.getText(0).toInt() else 0 }
            if (dim <= 0) return@runCatching null
            val ids = ArrayList<Long>()
            val scales = ArrayList<Float>()
            val buf = ByteArrayOutputStream()
            c.prepare("SELECT chunk_id, v FROM vec ORDER BY chunk_id").use { st ->
                while (st.step()) {
                    val b = st.getBlob(1)
                    if (b.size != 4 + dim) continue
                    ids += st.getLong(0)
                    scales += ByteBuffer.wrap(b, 0, 4).order(ByteOrder.LITTLE_ENDIAN).float
                    buf.write(b, 4, dim)
                }
            }
            Vecs(ids.toLongArray(), buf.toByteArray(), scales.toFloatArray(), dim)
        }.getOrNull()
        vecCache[f.path] = v
        return v
    }

    /** 中文包里这一段的英文原文（chunks.text_en，zlib 压缩）；英文包或没有原文时返回 null。 */
    suspend fun original(packId: String, chunkId: Long): String? = withContext(Dispatchers.IO) {
        lock.withLock {
            val p = _packs.value.firstOrNull { it.id == packId } ?: return@withLock null
            if (!p.displayZh) return@withLock null
            runCatching {
                conn(p.file).prepare("SELECT text_en FROM chunks WHERE id = ?").use { st ->
                    st.bindLong(1, chunkId)
                    if (!st.step() || st.isNull(0)) null else inflate(st.getBlob(0))
                }
            }.getOrNull()
        }
    }

    /** 读同一篇文档里第 seq 段（用于阅读器的上一段 / 下一段）。 */
    suspend fun chunkAt(packId: String, docId: Long, seq: Int): Passage? = withContext(Dispatchers.IO) {
        lock.withLock {
            val p = _packs.value.firstOrNull { it.id == packId } ?: return@withLock null
            conn(p.file).prepare(AT).use { st ->
                st.bindLong(1, docId)
                st.bindLong(2, seq.toLong())
                if (st.step()) Passage(p.id, p.title, st.getLong(0), docId, seq, st.getText(1), st.getText(2)) else null
            }
        }
    }

    companion object {
        private const val MAX_INFLATE = 4L shl 20 // 单段解压上限 4 MB
        private const val CANDIDATES = 50
        private const val RRF_K = 60
        private const val BY_IDS = """
            SELECT c.id, c.doc_id, c.seq, coalesce(d.title, c.title, ''), c.text
            FROM chunks c LEFT JOIN docs d ON d.id = c.doc_id WHERE c.id IN (?)"""
        private const val SEARCH = """
            SELECT c.id, c.doc_id, c.seq, coalesce(d.title, c.title, ''), c.text, bm25(chunks_fts, 1.0, 0.0) AS s
            FROM chunks_fts JOIN chunks c ON c.id = chunks_fts.rowid LEFT JOIN docs d ON d.id = c.doc_id
            WHERE chunks_fts MATCH ? ORDER BY s LIMIT ?"""
        private const val SEARCH_ZH = """
            SELECT c.id, c.doc_id, c.seq, coalesce(d.title, c.title, ''), c.text, bm25(chunks_fts, 1.0, 0.0) AS s, c.text_en
            FROM chunks_fts JOIN chunks c ON c.id = chunks_fts.rowid LEFT JOIN docs d ON d.id = c.doc_id
            WHERE chunks_fts MATCH ? ORDER BY s LIMIT ?"""

        fun inflate(b: ByteArray): String {
            val inf = java.util.zip.Inflater()
            try {
                inf.setInput(b)
                val out = ByteArrayOutputStream(b.size * 3)
                val buf = ByteArray(8192)
                var total = 0L
                while (!inf.finished()) {
                    val n = inf.inflate(buf)
                    if (n == 0) {
                        // 需要字典 / 数据截断 / 无进展：直接报错，不能空转
                        if (inf.needsInput() || inf.needsDictionary()) break
                        throw java.util.zip.DataFormatException("inflate 无进展")
                    }
                    total += n
                    if (total > MAX_INFLATE) throw java.util.zip.DataFormatException("解压结果过大")
                    out.write(buf, 0, n)
                }
                return out.toString("UTF-8")
            } finally {
                inf.end()
            }
        }

        private const val AT = """
            SELECT c.id, coalesce(d.title, c.title, ''), c.text FROM chunks c LEFT JOIN docs d ON d.id = c.doc_id
            WHERE c.doc_id = ? AND c.seq = ?"""

        fun enMatch(terms: List<String>): String =
            terms.map { t -> t.filter { it.isLetterOrDigit() } }
                .filter { it.length >= 2 }.distinct().take(10)
                .joinToString(" OR ") { "\"$it\"" }

        private val ZH_STOP = Regex("怎么样|怎么办|怎么|如何|什么|哪些|为什么|是否|可以|应该|需要|请问|一下|吗|呢|吧|啊|的|了|我|你")

        /** 问题里的汉字串去掉虚词后切成的三字片段。 */
        fun zhGrams(q: String): List<String> {
            val grams = LinkedHashSet<String>()
            for (run in Regex("[\\u4e00-\\u9fff]{3,}").findAll(q.replace(ZH_STOP, " "))) {
                val s = run.value
                for (i in 0..s.length - 3) grams += s.substring(i, i + 3)
            }
            return grams.take(20).toList()
        }

        /**
         * 中文包（trigram、detail=none，只能查单个三字片段）：汉字三字片段用 OR；
         * 英文词（药名、型号，译文里常保留英文）拆成三字母片段用 AND 连起来，近似整词匹配。与 eval/rag_real.zh_match 一致。
         */
        fun zhMatch(q: String, enTerms: List<String> = emptyList()): String {
            val parts = zhGrams(q).map { "\"$it\"" }.toMutableList()
            val words = (enTerms + Regex("[A-Za-z0-9]{3,}").findAll(q).map { it.value }).map { it.lowercase() }
                .filter { w -> w.length >= 3 && w.all { it.isLetterOrDigit() && it.code < 128 } }.distinct().take(8)
            for (w in words) parts += (0..w.length - 3).joinToString(" AND ", "(", ")") { "\"${w.substring(it, it + 3)}\"" }
            return parts.joinToString(" OR ")
        }
    }
}
