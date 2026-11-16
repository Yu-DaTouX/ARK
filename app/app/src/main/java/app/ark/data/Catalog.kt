/*
 * Catalog.kt —— 读取 assets/catalog.json（可选下载目录：模型 / 资料包 / 地图）。
 * 由资料页（LibraryScreen）经 AppViewModel 使用，下载由 Storage.startDownload 执行。
 */
package app.ark.data

import android.content.Context
import org.json.JSONObject

/** 可选下载目录（assets/catalog.json）。kind：model / embed（语义检索模型）/ pack / map（LibraryScreen 按这四类分区显示）。 */
data class CatalogItem(
    val kind: String,
    val title: String,
    val file: String,
    val url: String,
    val size: Long,
    val note: String,
)

object Catalog {
    /** 解析目录；文件缺失或格式错误时返回空列表（不抛异常）。读 assets，建议在 IO 线程调用。 */
    fun load(ctx: Context): List<CatalogItem> = runCatching {
        val root = JSONObject(ctx.assets.open("catalog.json").bufferedReader().readText())
        val items = root.getJSONArray("items")
        (0 until items.length()).map { i ->
            val o = items.getJSONObject(i)
            CatalogItem(o.getString("kind"), o.getString("title"), o.getString("file"), o.getString("url"),
                o.optLong("size"), o.optString("note"))
        }
    }.getOrDefault(emptyList())
}
