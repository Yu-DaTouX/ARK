/*
 * MapSearchDialogs.kt —— 地图页的两个对话框：搜地名、找附近求助点。
 *
 * 数据来自 data/Places.kt（内置全球地名库 + 按区域下载的地名/求助点库）。
 * 设计：点结果只做“跳到那里”，不画路线——离线没有路网图，给方向和直线距离比假路线可靠。
 * 线程：查询都在 Dispatchers.IO，界面只拿结果。
 */
package app.ark.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ark.data.Places
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 搜地名：输入中文或英文，列出城镇/村庄/岛屿；点击跳过去。 */
@Composable
fun PlaceSearchDialog(
    installedRegions: List<String>,
    onPick: (Places.Hit) -> Unit,
    onDismiss: () -> Unit,
) {
    var q by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<Places.Hit>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(q) {
        if (q.isBlank()) { hits = emptyList(); return@LaunchedEffect }
        busy = true
        delay(150) // 打字时不要每个键都查一次
        hits = withContext(Dispatchers.IO) { Places.search(q) }
        busy = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("搜地名") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(q, { q = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("例如：悉尼 / Kununurra / 达尔文") })
                when {
                    q.isBlank() -> Text(
                        if (installedRegions.isEmpty()) "只有全球城镇库（内置）。想要小村和街道级地名，到「资料」页下载区域地名包。"
                        else "已安装区域：${installedRegions.joinToString("、")}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    busy -> Text("查找中…", style = MaterialTheme.typography.labelSmall)
                    hits.isEmpty() -> Text("没找到。换个写法，或先下载对应区域的地名包（小村、农场只在区域包里）。",
                        style = MaterialTheme.typography.labelSmall)
                    else -> LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        items(hits, key = { it.region + it.name + it.lat }) { h ->
                            Column(Modifier.fillMaxWidth().clickable { onPick(h) }.padding(vertical = 6.dp)) {
                                Text(h.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${h.kind} · ${"%.3f".format(h.lat)}, ${"%.3f".format(h.lon)}" +
                                        if (h.pop > 0) " · 人口 ${h.pop}" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/** 附近求助点：以地图十字或当前位置为中心，列出最近的医院、药店、取水点等。 */
@Composable
fun NearbyPoiDialog(
    lat: Double,
    lon: Double,
    fromMe: Boolean,
    installedRegions: List<String>,
    onPick: (Places.Poi) -> Unit,
    onDismiss: () -> Unit,
) {
    var kind by remember { mutableStateOf("hospital") }
    var radiusKm by remember { mutableStateOf(30.0) }
    var list by remember { mutableStateOf<List<Places.Poi>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(kind, radiusKm, installedRegions) {
        busy = true
        list = withContext(Dispatchers.IO) { Places.nearby(lat, lon, radiusKm, setOf(kind)) }
        busy = false
    }
    val kinds = listOf("hospital", "clinic", "pharmacy", "police", "fire_station", "drinking_water",
        "shelter", "shelter_camp_site", "fuel", "shop_supermarket", "toilets")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (fromMe) "我附近的求助点" else "这一带的求助点") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (installedRegions.isEmpty()) {
                    Text("求助点数据要按区域下载（6 MB 左右）：到「资料」页找“澳洲 · 新西兰地名与求助点”。",
                        style = MaterialTheme.typography.labelSmall)
                } else {
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        kinds.forEach { k ->
                            FilterChip(kind == k, { kind = k }, label = {
                                Text(Places.kindLabel(k), style = MaterialTheme.typography.labelSmall)
                            })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(10.0, 30.0, 100.0).forEach { r ->
                            FilterChip(radiusKm == r, { radiusKm = r }, label = { Text("${r.toInt()} km") })
                        }
                    }
                    when {
                        busy -> Text("查找中…", style = MaterialTheme.typography.labelSmall)
                        list.isEmpty() -> Text("${radiusKm.toInt()} km 内没有记录。放宽范围，或下载对应区域的数据。",
                            style = MaterialTheme.typography.labelSmall)
                        else -> LazyColumn(Modifier.heightIn(max = 300.dp)) {
                            items(list, key = { it.kind + it.name + it.lat + it.lon }) { p ->
                                val b = Places.bearing(lat, lon, p.lat, p.lon)
                                Column(Modifier.fillMaxWidth().clickable { onPick(p) }.padding(vertical = 6.dp)) {
                                    Text(p.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyMedium)
                                    val tags = if (p.tags.contains("emergency=yes")) " · 有急诊" else ""
                                    Text("${Places.distText(p.distM)} · ${Places.dirName(b)}" +
                                        "${if (p.zh.isNotBlank() && p.name.isNotBlank()) " · ${p.name}" else ""}$tags",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
