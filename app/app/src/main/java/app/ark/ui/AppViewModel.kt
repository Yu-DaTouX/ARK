/*
 * AppViewModel.kt —— 全应用唯一的 ViewModel：设置、提问流程、资料搜索/阅读、文件导入与下载的界面状态。
 *
 * 位置：MainActivity 创建，各 Screen 通过 StateFlow 订阅；业务对象（llm / packs / qa / embedder / storage）
 * 来自 ArkApp 单例，本类不拥有它们的生命周期。
 *
 * 提问流程（ask）：语义向量(Embedder) → [模型加载] → 模型把中文问题改写成英文关键词 → PackRepository.search
 * 混合检索取 4 段 → Prompts.RAG + 资料生成回答（无资料则用 CLOSED 闭卷）→ 存入 QaStore。
 * 关键约定：
 *  - 只有“检索用英文”的包（lang 不以 zh 开头；中文显示版包 lang 仍是 en）才需要模型改写关键词，
 *    纯中文包用原问题的 trigram 检索即可，省一次生成。
 *  - 停止：stop() 置 stopReq 并让引擎中断，各步骤之间用 checkStop() 抛 StopAsk 退出，
 *    不当作错误显示。
 *  - 10 分钟无操作自动卸载模型和向量模型（idleJob 挂在 ArkApp.appScope，不随 ViewModel 取消）。
 */
package app.ark.ui

import android.app.Application
import android.app.DownloadManager
import android.content.SharedPreferences
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.ark.ArkApp
import app.ark.data.Catalog
import app.ark.data.CatalogItem
import app.ark.data.PackInfo
import app.ark.data.Passage
import app.ark.data.Qa
import app.ark.llm.LlmManager
import app.ark.llm.Prompts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 用户设置，持久化在 SharedPreferences("ark") */
data class Settings(
    val model: String? = null,   // models/ 下的文件名
    val gpu: Boolean = true,
    val maxTokens: Int = 512,
    val searchOnly: Boolean = false, // 省电：只检索资料，不让模型生成
    val theme: Int = 0,              // 0 跟随系统 / 1 深色（纯黑，OLED 省电）/ 2 浅色
) {
    fun save(p: SharedPreferences) = p.edit().putString("model", model).putBoolean("gpu", gpu)
        .putInt("maxTokens", maxTokens).putBoolean("searchOnly", searchOnly).putInt("theme", theme).apply()

    companion object {
        fun load(p: SharedPreferences) = Settings(
            p.getString("model", null), p.getBoolean("gpu", true), p.getInt("maxTokens", 512),
            p.getBoolean("searchOnly", false), p.getInt("theme", 0),
        )
    }
}

/** 正在进行的一次提问（还没存进历史）。 */
data class Live(
    val question: String,
    val status: String,
    val answer: String = "",
    val sources: List<Passage> = emptyList(),
)

/** 一个进行中的系统下载；done/total 字节，total 未知时用目录里的 size 估计 */
data class Download(val id: Long, val item: CatalogItem, val done: Long = 0, val total: Long = 0)

/** 见文件头。所有 fun 都在主线程调用，耗时部分内部切到 IO / 模型线程。 */
class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val ark = app as ArkApp
    val storage = ark.storage
    val packs = ark.packs
    val qa = ark.qa
    val llm = ark.llm
    private val embedder = ark.embedder
    val prefs = ark.prefs

    val settings = MutableStateFlow(Settings.load(prefs))
    val models = MutableStateFlow<List<File>>(emptyList())
    val embedModel = MutableStateFlow<File?>(null)
    val maps = MutableStateFlow<List<File>>(emptyList())
    val catalog = Catalog.load(app)
    val live = MutableStateFlow<Live?>(null)
    val message = MutableStateFlow<String?>(null)
    val importProgress = MutableStateFlow<Float?>(null)
    val downloads = MutableStateFlow<List<Download>>(emptyList())
    val searchResults = MutableStateFlow<List<Passage>?>(null)
    val reading = MutableStateFlow<Passage?>(null)

    // 放在 ArkApp 里，ViewModel 被销毁（Activity 彻底退出）后倒计时仍然有效
    private var idleJob: Job?
        get() = ark.idleJob
        set(v) { ark.idleJob = v }
    private var pollJob: Job? = null
    @Volatile private var stopReq = false
    // 用户点“停止”后在各步骤之间退出提问流程用的内部信号，不是错误
    private class StopAsk : RuntimeException()
    private fun checkStop() { if (stopReq) throw StopAsk() }

    /** 仓库操作（数据库 / 存储）出错不能让根协程崩掉整个应用 */
    private fun safeLaunch(block: suspend () -> Unit) = viewModelScope.launch {
        try { block() } catch (c: CancellationException) { throw c } catch (t: Throwable) {
            Log.w("ark", "op failed", t)
            message.value = "操作失败：${t.message ?: t.javaClass.simpleName}"
        }
    }

    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { storage.installBundledPacks() } }
            refreshFiles()
            runCatching { qa.reload() }.onFailure { if (it is CancellationException) throw it; Log.w("ark", "qa reload failed", it) }
        }
        restoreDownloads()
    }

    /** 修改设置并立即写入 prefs */
    fun update(f: (Settings) -> Settings) {
        settings.update { f(it).also { s -> s.save(prefs) } }
    }

    /** 重新扫描 models/ packs/ maps/（用户可能用数据线拷了文件，MainActivity.onResume 会调用） */
    fun refreshFiles() {
        models.value = storage.modelFiles()
        embedModel.value = storage.embedModel()
        maps.value = storage.mapFiles()
        viewModelScope.launch { packs.refresh() }
    }

    /** 设置里选中的模型；没选或文件没了就退到第一个；一个都没有返回 null */
    fun currentModel(): File? {
        val all = models.value.ifEmpty { storage.modelFiles() }
        return all.firstOrNull { it.name == settings.value.model } ?: all.firstOrNull()
    }

    // ---------------- 提问：改写检索词 → 检索资料 → 按资料作答 ----------------

    /**
     * 发起一次提问（已有进行中的提问时忽略）。异步，结果通过 [live]（过程）和 QaStore（完成后）反映；
     * 出错/停止通过 [message] 提示。没有模型或开了“只检索”时只做检索，mode=search。
     */
    fun ask(question: String) {
        val q = question.trim()
        if (q.isEmpty() || live.value != null) return
        idleJob?.cancel()
        stopReq = false
        viewModelScope.launch {
            val t0 = SystemClock.elapsedRealtime()
            val s = settings.value
            val model = currentModel()
            live.value = Live(q, "准备中…")
            fun status(t: String) = live.update { it?.copy(status = t) }
            try {
                var terms = Prompts.parseKeywords(q) // 问题里本来就有的英文词（药名、型号等）
                val qvec = embedder.query(storage.embedModel(), q) // 语义向量：中文问题直接找英文段落
                checkStop()
                if (model == null || s.searchOnly) {
                    status("正在检索资料…")
                    val ps = packs.search(terms, q, 6, qvec) // 只检索展示 6 段；交给模型时只取 4 段
                    val note = when {
                        ps.isNotEmpty() -> ""
                        model == null && qvec == null -> "没有找到相关资料。\n还没有安装模型：中文问题需要语义检索模型或 AI 模型才能查英文资料。请到「资料」页下载，或者直接用英文关键词搜索。"
                        else -> "没有找到相关资料。可以换个说法，或用英文关键词再试。"
                    }
                    save(Qa(question = q, answer = note, sources = ps, model = "", mode = "search"), t0)
                    return@launch
                }
                status(if (llm.state.value is LlmManager.State.Ready) "正在理解问题…" else "正在加载模型（首次约 10–30 秒）…")
                llm.ensure(model, s.gpu)
                checkStop()
                if (packs.packs.value.any { it.enabled && !it.lang.startsWith("zh") }) {
                    status("正在理解问题…")
                    terms = (Prompts.parseKeywords(llm.generate(Prompts.KEYWORDS, q, 48)) + terms).distinct() // 48 token 足够 3–6 个关键词
                }
                checkStop()
                status("正在检索资料…")
                val ps = packs.search(terms, q, 4, qvec)
                checkStop()
                Log.i("ark", "q=$q terms=$terms vec=${qvec != null} hits=${ps.map { it.title + "#" + it.seq }}")
                live.update { it?.copy(sources = ps) }
                status(if (ps.isEmpty()) "资料里没找到，正在用模型自身知识回答…" else "正在根据 ${ps.size} 段资料回答…")
                val (sys, user) = if (ps.isEmpty()) Prompts.CLOSED to q else Prompts.RAG to Prompts.ragUser(ps.map { it.text }, q)
                var answer = llm.generate(sys, user, s.maxTokens) { txt -> live.update { it?.copy(answer = txt) } }
                // 小模型偶尔在长资料后忘了问题，回“请提供您的问题”。把问题挪到资料前面再问一次
                if (ps.isNotEmpty() && !stopReq && Prompts.lostQuestion(answer)) {
                    Log.i("ark", "lost question, retry with question first")
                    answer = llm.generate(sys, "问题：$q\n\n$user", s.maxTokens) { txt -> live.update { it?.copy(answer = txt) } }
                }
                val name = (llm.state.value as? LlmManager.State.Ready)?.let { "${it.model} · ${it.backend}" } ?: ""
                Log.i("ark", "done model=$name sec=${(SystemClock.elapsedRealtime() - t0) / 1000.0} chars=${answer.length} answer=${answer.replace("\n", "⏎")}")
                save(Qa(question = q, answer = answer.ifBlank { "（没有生成内容）" }, sources = ps, model = name,
                    mode = if (ps.isEmpty()) "closed" else "rag"), t0)
            } catch (c: CancellationException) {
                throw c
            } catch (_: StopAsk) {
                message.value = "已停止"
            } catch (t: Throwable) {
                message.value = "出错了：${t.message ?: t.javaClass.simpleName}"
            } finally {
                live.value = null
                scheduleIdleUnload()
            }
        }
    }

    /**
     * 仅调试：逐行读 {system, user, q?} 的 jsonl，用当前模型生成，结果写到 out（每行 q / answer / seconds / bench）。
     * 不经过检索，输入和电脑上的 tools/embed gen 完全相同，用来在手机 GPU 上测速、跑评测、测功耗。
     */
    fun debugBatch(inFile: File, outFile: File, maxTokens: Int?) {
        if (live.value != null) return
        idleJob?.cancel()
        viewModelScope.launch {
            val model = currentModel() ?: return@launch
            val s = settings.value
            live.value = Live("批量测试", "加载模型…")
            try {
                llm.ensure(model, s.gpu)
                val lines = inFile.readLines().filter { it.isNotBlank() }
                outFile.bufferedWriter().use { w ->
                    lines.forEachIndexed { i, l ->
                        val o = org.json.JSONObject(l)
                        live.update { it?.copy(status = "批量 ${i + 1}/${lines.size}") }
                        val t0 = SystemClock.elapsedRealtime()
                        llm.lastBench = null
                        val ans = llm.generate(o.getString("system"), o.getString("user"), maxTokens ?: s.maxTokens)
                        val sec = (SystemClock.elapsedRealtime() - t0) / 1000.0
                        val r = org.json.JSONObject().put("q", o.optString("q")).put("answer", ans).put("seconds", sec)
                            .put("bench", llm.lastBench ?: "").put("model", model.name)
                        w.write(r.toString()); w.newLine(); w.flush()
                        Log.i("ark", "batch ${i + 1}/${lines.size} sec=$sec ${llm.lastBench ?: ""}")
                    }
                }
                Log.i("ark", "batch done ${outFile.path}")
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.e("ark", "batch failed", t)
                Log.i("ark", "batch done (failed: ${t.message})")
            } finally {
                live.value = null
                scheduleIdleUnload()
            }
        }
    }

    private suspend fun save(q: Qa, t0: Long) {
        qa.add(q.copy(seconds = (SystemClock.elapsedRealtime() - t0) / 1000.0))
    }

    /** 停止当前提问：不等模型出完，已生成的部分丢弃（不存入历史） */
    fun stop() { stopReq = true; llm.stop() }

    /** 10 分钟没提问就释放模型，把内存（约 2–3 GB）还给系统和地图。 */
    private fun scheduleIdleUnload() {
        idleJob?.cancel()
        idleJob = ark.appScope.launch {
            delay(10 * 60 * 1000L)
            llm.unload()
            embedder.unload()
        }
    }

    fun unloadModel() = safeLaunch { llm.unload() }

    fun bookmark(item: Qa, on: Boolean) = safeLaunch { qa.setBookmark(item.id, on) }
    fun deleteQa(item: Qa) = safeLaunch { qa.delete(item.id) }
    fun clearHistory() = safeLaunch { qa.clearHistory() }

    // ---------------- 资料：直接搜索和阅读（不用模型，几乎不耗电） ----------------

    /** 资料页搜索：不调大模型，结果写入 [searchResults]（最多 30 段）；空串清空结果 */
    fun searchLibrary(text: String) {
        val q = text.trim()
        if (q.isEmpty()) { searchResults.value = null; return }
        viewModelScope.launch {
            val qvec = embedder.query(storage.embedModel(), q)
            val t0 = SystemClock.elapsedRealtime()
            searchResults.value = packs.search(Prompts.parseKeywords(q), q, 30, qvec)
            Log.i("ark", "search q=$q vec=${qvec != null} ms=${SystemClock.elapsedRealtime() - t0} top=${searchResults.value?.take(4)?.map { it.title + "#" + it.seq }}")
            scheduleIdleUnload()
        }
    }

    fun open(p: Passage) { reading.value = p }
    fun closeReader() { reading.value = null }
    /** 阅读器翻到同一文档的上一段(-1)/下一段(+1)；到头时提示 */
    fun step(delta: Int) = safeLaunch {
        val cur = reading.value ?: return@safeLaunch
        packs.chunkAt(cur.packId, cur.docId, cur.seq + delta)?.let { reading.value = it }
            ?: run { message.value = if (delta > 0) "已经是最后一段" else "已经是第一段" }
    }

    fun setPackEnabled(p: PackInfo, on: Boolean) = safeLaunch { packs.setEnabled(p, on) }
    fun deletePack(p: PackInfo) = safeLaunch { packs.delete(p) }

    fun deleteModel(f: File) = safeLaunch {
        if (currentModel() == f) llm.unload()
        if (f == embedModel.value) embedder.unload()
        withContext(Dispatchers.IO) { f.delete() }
        refreshFiles()
    }

    fun deleteMap(f: File) = safeLaunch {
        withContext(Dispatchers.IO) { f.delete() }
        refreshFiles()
    }

    // ---------------- 导入和下载 ----------------

    /** 导入用户选的 .litertlm / .arkpack / .pmtiles（进度见 [importProgress]） */
    fun import(uri: Uri) = viewModelScope.launch {
        importProgress.value = 0f
        try {
            val f = storage.import(uri) { importProgress.value = it }
            message.value = "已导入 ${f.name}"
            refreshFiles()
        } catch (t: Throwable) {
            message.value = t.message ?: "导入失败"
        } finally {
            importProgress.value = null
        }
    }

    /** 交给系统下载管理器下载目录项，并持久化任务 id（重启 App 后继续跟踪） */
    fun download(item: CatalogItem) {
        if (downloads.value.any { it.item.file == item.file }) return
        val id = runCatching { storage.startDownload(item.url, item.file, item.title) }.getOrElse {
            message.value = "无法开始下载：${it.message}"; return
        }
        downloads.update { it + Download(id, item) }
        persistDownloads()
        poll()
    }

    fun cancelDownload(d: Download) {
        storage.cancelDownload(d.id, d.item.file)
        downloads.update { l -> l.filter { it.id != d.id } }
        persistDownloads()
    }

    private fun persistDownloads() {
        prefs.edit().putStringSet("downloads", downloads.value.map { "${it.id}|${it.item.file}" }.toSet()).apply()
    }

    private fun restoreDownloads() {
        val saved = prefs.getStringSet("downloads", emptySet()).orEmpty().mapNotNull { s ->
            val (id, file) = s.split('|', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            val item = catalog.firstOrNull { it.file == file } ?: return@mapNotNull null
            Download(id.toLong(), item)
        }
        downloads.value = saved
        if (saved.isNotEmpty()) poll()
    }

    // 每秒查一次系统下载状态直到没有任务；成功→把 .part 改名为正式文件，失败/记录丢失→清掉半成品
    private fun poll() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (downloads.value.isNotEmpty()) {
                val snapshot = downloads.value
                val progress = HashMap<Long, Download>()
                val gone = HashSet<Long>()
                for (d in snapshot) {
                    try {
                        val st = withContext(Dispatchers.IO) { storage.queryDownload(d.id) }
                        when (st?.status) {
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                withContext(Dispatchers.IO) { storage.finishDownload(d.item.file) }
                                gone += d.id
                                message.value = "下载完成：${d.item.title}"
                                refreshFiles()
                            }
                            DownloadManager.STATUS_FAILED, null -> {
                                withContext(Dispatchers.IO) { storage.cancelDownload(d.id, d.item.file) }
                                gone += d.id
                                message.value = "下载失败：${d.item.title}（代码 ${st?.reason ?: "-"}）"
                            }
                            else -> progress[d.id] = d.copy(done = st.done, total = if (st.total > 0) st.total else d.item.size)
                        }
                    } catch (c: CancellationException) {
                        throw c
                    } catch (t: Throwable) {
                        Log.w("ark", "download poll failed", t)
                        gone += d.id
                        message.value = "下载出错：${d.item.title}（${t.message ?: t.javaClass.simpleName}）"
                    }
                }
                // 按 id 合并，不用旧快照覆盖：轮询期间新增 / 取消的下载保持原样
                var changed = false
                downloads.update { cur ->
                    val n = cur.filterNot { it.id in gone }.map { progress[it.id] ?: it }
                    changed = n.map { it.id } != cur.map { it.id } || gone.isNotEmpty()
                    n
                }
                if (changed) persistDownloads() // 只在任务列表变化时写盘，不再每秒写一次 SharedPreferences
                delay(1000)
            }
        }
    }
}
