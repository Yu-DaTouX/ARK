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
    fun installBundledPacks() {
        val updated = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime
        for (n in bundled) {
            val dst = File(packs, n)
            if (dst.exists() && dst.lastModified() >= updated) continue
            val tmp = File(packs, "$n.part")
            ctx.assets.open("packs/$n").use { inp -> tmp.outputStream().use { inp.copyTo(it, 1 shl 16) } }
            dst.delete()
            tmp.renameTo(dst)
        }
    }

    /** 从系统文件选择器导入：按扩展名放进对应目录，先写 .part 再改名，中途失败不会留下半个文件。 */
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
        val tmp = File(dir, "$name.part")
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
                }
            }
            dst.delete()
            if (!tmp.renameTo(dst)) error("保存失败")
        } finally {
            tmp.delete()
        }
        dst
    }

    // ---------------- 可选下载（系统下载管理器，App 退到后台也能继续） ----------------

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
        dst.delete()
        return File(dir, "$fileName.part").renameTo(dst)
    }

    fun cancelDownload(id: Long, fileName: String) {
        dm.remove(id)
        dirFor(fileName)?.let { File(it, "$fileName.part").delete() }
    }
}
