/*
 * QaStore.kt —— 问答历史与书签的 SQLite 存储（filesDir/ark.db，表 qa）。
 * 由 ArkApp 创建，AppViewModel 读写，BookmarksScreen/AskScreen 订阅 history / bookmarks。
 * 设计：出处原文（sources）连同回答一起以 JSON 存进同一行，这样资料包被删或更新后书签仍可读；
 * 历史只在内存里保留最近 100 条，书签全量。
 */
package app.ark.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 一次问答。mode：rag 按资料回答 / closed 资料里没找到、模型凭自身知识 / search 只检索不生成。 */
data class Qa(
    val id: Long = 0,
    val ts: Long = System.currentTimeMillis(),
    val question: String,
    val answer: String,
    val sources: List<Passage>,
    val model: String,
    val mode: String,
    val seconds: Double = 0.0,
    val bookmarked: Boolean = false,
)

/** 问答历史和书签存在同一张表，书签就是 bookmarked = 1 的记录；清空历史时书签保留。 */
class QaStore(private val file: File) {
    private val lock = Mutex()
    private var c: SQLiteConnection? = null
    private val _history = MutableStateFlow<List<Qa>>(emptyList())
    private val _bookmarks = MutableStateFlow<List<Qa>>(emptyList())
    val history: StateFlow<List<Qa>> = _history
    val bookmarks: StateFlow<List<Qa>> = _bookmarks

    // WAL：写入不阻塞读，异常断电后也不损坏。连接惰性打开，由 lock 保护
    private fun db(): SQLiteConnection = c ?: BundledSQLiteDriver().open(file.path).also {
        it.execSQL("PRAGMA journal_mode=WAL")
        it.execSQL(
            """CREATE TABLE IF NOT EXISTS qa(
                id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, question TEXT NOT NULL,
                answer TEXT NOT NULL, sources TEXT NOT NULL, model TEXT NOT NULL, mode TEXT NOT NULL,
                seconds REAL NOT NULL DEFAULT 0, bookmarked INTEGER NOT NULL DEFAULT 0)"""
        )
        c = it
    }

    private suspend fun <T> io(block: SQLiteConnection.() -> T): T = withContext(Dispatchers.IO) {
        lock.withLock {
            try {
                db().block()
            } catch (t: Throwable) {
                // 连接可能已坏：丢掉，下次重新打开；库文件本身不动
                runCatching { c?.close() }
                c = null
                throw t
            }
        }
    }

    /** 从库里重新读出历史（最近 100 条）和书签，刷新两个 StateFlow。其他写操作都会自动调用它。 */
    suspend fun reload() = io {
        _history.value = query("SELECT * FROM qa ORDER BY id DESC LIMIT 100")
        _bookmarks.value = query("SELECT * FROM qa WHERE bookmarked = 1 ORDER BY ts DESC")
    }

    /** 插入一条问答并刷新；返回新行 id。挂起，在 IO 线程执行；数据库错误会抛出。 */
    suspend fun add(q: Qa): Long {
        val id = io {
            prepare("INSERT INTO qa(ts, question, answer, sources, model, mode, seconds, bookmarked) VALUES(?,?,?,?,?,?,?,?)").use { st ->
                st.bindLong(1, q.ts); st.bindText(2, q.question); st.bindText(3, q.answer)
                st.bindText(4, encode(q.sources)); st.bindText(5, q.model); st.bindText(6, q.mode)
                st.bindDouble(7, q.seconds); st.bindLong(8, if (q.bookmarked) 1 else 0)
                st.step()
            }
            prepare("SELECT last_insert_rowid()").use { it.step(); it.getLong(0) }
        }
        reload()
        return id
    }

    suspend fun setBookmark(id: Long, on: Boolean) {
        io { exec("UPDATE qa SET bookmarked = ? WHERE id = ?", if (on) 1 else 0, id) }
        reload()
    }

    suspend fun delete(id: Long) {
        io { exec("DELETE FROM qa WHERE id = ?", id) }
        reload()
    }

    /** 清空历史，保留书签 */
    suspend fun clearHistory() {
        io { exec("DELETE FROM qa WHERE bookmarked = 0") }
        reload()
    }

    private fun SQLiteConnection.exec(sql: String, vararg args: Long) =
        prepare(sql).use { st -> args.forEachIndexed { i, v -> st.bindLong(i + 1, v) }; st.step() }

    private fun SQLiteConnection.exec(sql: String, a: Int, b: Long) = exec(sql, a.toLong(), b)

    private fun SQLiteConnection.query(sql: String): List<Qa> = prepare(sql).use { st ->
        val out = ArrayList<Qa>()
        while (st.step()) out += Qa(
            id = st.getLong(0), ts = st.getLong(1), question = st.getText(2), answer = st.getText(3),
            sources = decode(st.getText(4)), model = st.getText(5), mode = st.getText(6),
            seconds = st.getDouble(7), bookmarked = st.getLong(8) != 0L,
        )
        out
    }

    private fun encode(ps: List<Passage>) = JSONArray().apply {
        ps.forEach { p ->
            put(JSONObject().put("pack", p.packId).put("packTitle", p.packTitle).put("chunk", p.chunkId)
                .put("doc", p.docId).put("seq", p.seq).put("title", p.title).put("text", p.text))
        }
    }.toString()

    private fun decode(s: String): List<Passage> = runCatching {
        val a = JSONArray(s)
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Passage(o.getString("pack"), o.optString("packTitle"), o.getLong("chunk"), o.getLong("doc"),
                o.getInt("seq"), o.optString("title"), o.optString("text"))
        }
    }.getOrDefault(emptyList())
}
