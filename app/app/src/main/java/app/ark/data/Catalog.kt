package app.ark.data

import android.content.Context
import org.json.JSONObject

/** 可选下载目录（assets/catalog.json）。kind：model / pack / map。 */
data class CatalogItem(
    val kind: String,
    val title: String,
    val file: String,
    val url: String,
    val size: Long,
    val note: String,
)

object Catalog {
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
