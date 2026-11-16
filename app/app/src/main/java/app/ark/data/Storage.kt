/*
 * Storage.kt —— 文件布局、内置资料包安装、文件导入、系统下载管理器封装。
 * 由 ArkApp 创建；PackRepository 用它列文件，AppViewModel / LibraryScreen 用它导入和下载。
 * 设计：用外部专属目录（无需存储权限，用户可用数据线直接拷文件）；所有写入都先写 .part 再 rename，
 * 中途失败或没电不会留下半个文件；下载交给系统 DownloadManager，App 退后台也能继续。
 */
package app.ark.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * App 的文件布局：外部专属目录（Android/data/app.ark.offline/files）下
 *   models/  *.litertlm   模型
 *   packs/   *.arkpack    资料包（SQLite + FTS5）
 *   maps/    *.pmtiles    离线地图
 * 不需要存储权限；用户也可以用数据线直接把文件拷进这些目录。
 */
class Storage(private val ctx: Context) {
    val root: File = ctx.getExternalFilesDir(null) ?: ctx.filesDir
    val models = File(root, "models").apply { mkdirs() }
    val packs = File(root, "packs").apply { mkdirs() }
    val maps = File(root, "maps").apply { mkdirs() }
    private val dm = ctx.getSystemService(DownloadManager::class.java)

    /** 按扩展名决定放哪个目录；不认识的扩展名返回 null */
    fun dirFor(name: String): File? {
        val n = name.lowercase()
        return when {
            n.endsWith(".litertlm") -> models
            n.endsWith(".arkpack") -> packs
            n.endsWith(".pmtiles") -> maps
            else -> null
        }
    }

    /** 大模型（对话用）；向量模型单独列出。 */
    fun modelFiles(): List<File> = list(models, ".litertlm").filterNot { isEmbedder(it.name) }
    fun embedModel(): File? = list(models, ".litertlm").firstOrNull { isEmbedder(it.name) }
    fun packFiles(): List<File> = list(packs, ".arkpack")
    fun mapFiles(): List<File> = list(maps, ".pmtiles")

    companion object {
        fun isEmbedder(name: String) = name.startsWith("embeddinggemma", ignoreCase = true)
    }

    private fun list(dir: File, ext: String) =
        dir.listFiles { f -> f.isFile && f.name.endsWith(ext, true) }.orEmpty().sortedBy { it.name.lowercase() }

    private val bundled: Set<String> by lazy { ctx.assets.list("packs").orEmpty().toSet() }
    fun isBundled(name: String) = name in bundled

    /** APK 里自带的资料包：首次启动或 App 更新后复制出来（SQLite 不能直接读 assets）。 */
    // 判断依据：目标文件的修改时间 >= 本 App 的安装/更新时间，说明已是这一版的包，跳过；否则覆盖（更新后包可能换了）
    // 注意：会读写 100 MB 级文件，须在后台线程调用；rename 失败不报错（下次启动重试）
    fun installBundledPacks() {
        val updated = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime
        for (n in bundled) {
            val dst = File(packs, n)
            if (dst.exists() && dst.lastModified() >= updated) continue
            val tmp = File(packs, "$n.inst.part") // 专用临时名，不和导入 / 下载的 .part 冲突
            try {
                ctx.assets.open("packs/$n").use { inp -> tmp.outputStream().use { o -> inp.copyTo(o, 1 shl 16); o.fd.sync() } }
                tmp.renameTo(dst) // rename 直接原子覆盖，不先删旧文件
            } finally {
                tmp.delete()
            }
        }
    }

    /** 从系统文件选择器导入：按扩展名放进对应目录，先写 .part 再改名，中途失败不会留下半个文件。 */
    /** @param progress 0..1 的进度回调（拿不到文件大小时不回调）；返回落地文件。不支持的扩展名会抛 IllegalStateException。IO 线程执行。 */
    suspend fun import(uri: Uri, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        var name = "file"
        var size = -1L
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0) ?: name
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        name = File(name).name
        val dir = dirFor(name) ?: error("不支持的文件：$name\n支持 .litertlm 模型、.arkpack 资料包、.pmtiles 地图")
        val tmp = File(dir, "$name.${java.util.UUID.randomUUID().toString().take(8)}.imp.part") // 每次导入独立临时名，不碰下载的 .part
        val dst = File(dir, name)
        try {
            (cr.openInputStream(uri) ?: error("打不开文件")).use { inp ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 20)
                    var done = 0L
                    while (true) {
                        val r = inp.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        done += r
                        if (size > 0) progress(done.toFloat() / size)
                    }
                    out.fd.sync()
                }
            }
            if (!tmp.renameTo(dst)) error("保存失败") // 不先删旧文件：rename 原子覆盖，失败时旧文件仍在
        } finally {
            tmp.delete()
        }
        dst
    }

    // ---------------- 可选下载（系统下载管理器，App 退到后台也能继续） ----------------

    /** 把 url 下载到对应目录的 "<fileName>.part"，返回下载 id；完成后由调用方 [finishDownload] 改名。会清掉同名旧 .part。 */
    fun startDownload(url: String, fileName: String, title: String): Long {
        val dir = dirFor(fileName) ?: error("不支持的文件：$fileName")
        File(dir, "$fileName.part").delete()
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle(title)
            .setDescription("方舟离线资料")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(ctx, null, "${dir.name}/$fileName.part")
        return dm.enqueue(req)
    }

    data class DlStatus(val id: Long, val status: Int, val done: Long, val total: Long, val reason: Int)

    /** 查询下载进度；id 不存在返回 null */
    fun queryDownload(id: Long): DlStatus? =
        dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
            if (!c.moveToFirst()) return null
            DlStatus(
                id,
                c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
            )
        }

    /** 下载完成：.part 改成正式文件名。 */
    fun finishDownload(fileName: String): Boolean {
        val dir = dirFor(fileName) ?: return false
        val dst = File(dir, fileName)
        return File(dir, "$fileName.part").renameTo(dst) // 原子覆盖，不先删旧文件
    }

    /** 取消并删除半成品 */
    fun cancelDownload(id: Long, fileName: String) {
        dm.remove(id)
        dirFor(fileName)?.let { File(it, "$fileName.part").delete() }
    }
}
