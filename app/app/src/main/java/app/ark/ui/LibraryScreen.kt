/*
 * LibraryScreen.kt —— “资料”标签页：资料原文搜索（不用 AI）、AI 模型 / 语义检索模型 / 资料包 / 地图的
 * 安装、下载、删除、开关，文件导入，回答长度/省电/外观设置，关于。
 * 这是 App 的“管理页”：所有状态来自 AppViewModel，下载目录项来自 assets/catalog.json（data/Catalog.kt）。
 * 下载由系统 DownloadManager 执行；已安装的项目从目录里隐藏，避免重复下载。
 */
package app.ark.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ark.data.CatalogItem
import app.ark.llm.LlmManager

/** 字节数 → "1.2 GB" / "165 MB" / "12 KB" */
fun sizeText(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
    b >= 1L shl 20 -> "%.0f MB".format(b / (1L shl 20).toDouble())
    else -> "%.0f KB".format(b / 1024.0)
}

/** 资料页（整页是一个 LazyColumn，按分区排列） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(vm: AppViewModel) {
    val packs by vm.packs.packs.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    val embed by vm.embedModel.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val llm by vm.llm.state.collectAsStateWithLifecycle()
    val results by vm.searchResults.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val importing by vm.importProgress.collectAsStateWithLifecycle()
    val maps by vm.maps.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val focus = LocalFocusManager.current
    val cs = MaterialTheme.colorScheme
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.import(it) } }
    val current = models.firstOrNull { it.name == settings.model } ?: models.firstOrNull()

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { Text("资料", style = MaterialTheme.typography.titleLarge) }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索资料原文（不用 AI，几乎不耗电）") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = ""; vm.searchLibrary("") }) { Icon(Icons.Filled.Clear, "清除") }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.searchLibrary(query); focus.clearFocus() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        results?.let { rs ->
            item {
                Text(
                    when {
                        rs.isNotEmpty() -> "找到 ${rs.size} 段"
                        embed == null -> "没有找到。英文资料要用英文词搜（如 snake bite）；下载下面的「语义检索」模型后可以直接用中文搜。"
                        else -> "没有找到，换个说法试试。"
                    },
                    style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant,
                )
            }
            items(rs) { p -> PassageRow(p) { vm.open(p) } }
        }

        // ---------------- 模型 ----------------
        item { Section("AI 模型") }
        item {
            val status = when (val s = llm) {
                is LlmManager.State.Ready -> "已加载：${s.model}（${s.backend}）"
                is LlmManager.State.Loading -> "正在加载 ${s.model}…"
                is LlmManager.State.Failed -> "加载失败：${s.message}"
                else -> if (models.isEmpty()) "还没有模型。下载一个，或把 .litertlm 文件导入进来。" else "未加载（提问时自动加载，闲置 10 分钟自动释放）"
            }
            Text(status, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        items(models, key = { it.path }) { f ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = f == current, onClick = { vm.update { it.copy(model = f.name) } })
                Column(Modifier.weight(1f)) {
                    Text(LlmManager.displayName(f), style = MaterialTheme.typography.bodyLarge)
                    Text(sizeText(f.length()), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
                IconButton(onClick = { confirm = "删除模型 ${f.name}？" to { vm.deleteModel(f) } }) { Icon(Icons.Filled.Delete, "删除") }
            }
        }
        items(vm.catalog.filter { it.kind == "model" && models.none { m -> m.name == it.file } }, key = { "c" + it.file }) { c ->
            CatalogRow(c, downloads.firstOrNull { it.item.file == c.file }, vm)
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("语义检索", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (embed != null) "已安装（${sizeText(embed!!.length())}）：中文问题直接找英文原文，找得更准"
                        else "未安装：只能靠 AI 改写的英文关键词检索，容易漏掉相关段落",
                        style = MaterialTheme.typography.labelSmall, color = if (embed != null) cs.onSurfaceVariant else cs.tertiary,
                    )
                }
                embed?.let { f -> IconButton(onClick = { confirm = "删除语义检索模型？" to { vm.deleteModel(f) } }) { Icon(Icons.Filled.Delete, "删除") } }
            }
        }
        items(vm.catalog.filter { it.kind == "embed" && embed == null }, key = { "c" + it.file }) { c ->
            CatalogRow(c, downloads.firstOrNull { it.item.file == c.file }, vm)
        }
        item {
            SwitchRow("GPU 加速", "更快、更省电（实测功耗约为 CPU 的一半）。不支持的手机会自动改用 CPU。", settings.gpu) { on ->
                if (on) vm.llm.resetGpu() // 手动打开视为“再试一次 GPU”，清掉崩溃标记
                vm.update { it.copy(gpu = on) }
            }
            if (settings.gpu && vm.llm.gpuBroken) {
                Text("上次用 GPU 时程序意外退出，现在改用 CPU。打开开关可重试 GPU。", style = MaterialTheme.typography.labelSmall, color = cs.tertiary)
            }
        }

        // ---------------- 资料包 ----------------
        item { Section("资料包") }
        items(packs, key = { it.file.path }) { p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.title, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        listOfNotNull("${p.chunks} 段", sizeText(p.file.length()), if (p.bundled) "内置" else null, p.license.ifBlank { null })
                            .joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!p.bundled) IconButton(onClick = { confirm = "删除资料包「${p.title}」？" to { vm.deletePack(p) } }) { Icon(Icons.Filled.Delete, "删除") }
                Switch(checked = p.enabled, onCheckedChange = { vm.setPackEnabled(p, it) })
            }
        }
        items(vm.catalog.filter { it.kind == "pack" && packs.none { p -> p.file.name == it.file } }, key = { "c" + it.file }) { c ->
            CatalogRow(c, downloads.firstOrNull { it.item.file == c.file }, vm)
        }

        // ---------------- 地图 ----------------
        item { Section("地图") }
        item {
            Text("内置全球卫星图和概略图。下载区域详图可以看到城镇和道路；下载海图后在地图页点「海图」显示水深和航标。",
                style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
        items(maps, key = { it.path }) { f ->
            val title = vm.catalog.firstOrNull { it.file == f.name }?.title ?: f.nameWithoutExtension
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge)
                    Text(sizeText(f.length()), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
                IconButton(onClick = { confirm = "删除地图「$title」？" to { vm.deleteMap(f) } }) { Icon(Icons.Filled.Delete, "删除") }
            }
        }
        items(vm.catalog.filter { it.kind == "map" && maps.none { m -> m.name == it.file } }, key = { "c" + it.file }) { c ->
            CatalogRow(c, downloads.firstOrNull { it.item.file == c.file }, vm)
        }

        // ---------------- 地形图（1:25 万官方图幅） ----------------
        item { Section("地形图叠加（1:25 万）") }
        item {
            Text(
                "澳洲官方 1:250 000 地形图，按 MGA 分带整幅配准后切成瓦片：等高线、水系、水潭、土路、管线都在里面。" +
                    "下载后在地图页「更多 → 地形图叠加」打开；它压在道路之上、地名之下，认野外地形比矢量图准。",
                style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
            )
        }
        items(vm.catalog.filter { it.kind == "topo" && maps.none { m -> m.name == it.file } }, key = { "c" + it.file }) { c ->
            CatalogRow(c, downloads.firstOrNull { it.item.file == c.file }, vm)
        }

        // ---------------- 地名与求助点 ----------------
        item { Section("地名与求助点") }
        item {
            val pctx = LocalContext.current
            var placesVer by remember { mutableStateOf(0) }
            val placeFiles = remember(placesVer) {
                app.ark.data.Storage(pctx).places.listFiles { f -> f.isFile && f.name.endsWith(".db") && f.name != "global.db" }
                    .orEmpty().sortedBy { it.name }
            }
            val missing = vm.catalog.filter { it.kind == "places" && placeFiles.none { f -> f.name == it.file } }
            Column {
                Text(
                    "内置全球 23.7 万个城镇（含 4.2 万个中文名），任何地方都能搜到城镇。" +
                        "区域包更细：小村、农场、郊区，以及医院、药店、警局、消防、取水点、营地——地图页「更多 → 附近求助点」用的就是它。",
                    style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                )
                placeFiles.forEach { f ->
                    val title = vm.catalog.firstOrNull { it.file == f.name }?.title ?: f.nameWithoutExtension
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.bodyLarge)
                            Text(sizeText(f.length()), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                        }
                        IconButton(onClick = {
                            confirm = "删除「$title」？" to { if (f.delete()) placesVer++ }
                        }) { Icon(Icons.Filled.Delete, "删除") }
                    }
                }
                missing.forEach { c ->
                    CatalogRow(c, downloads.firstOrNull { it.item.file == c.file }, vm)
                }
            }
        }

        // ---------------- 离线分享 ----------------
        item { Section("分享给附近的人") }
        item { app.ark.share.ShareCard() }

        // ---------------- 导入 ----------------
        item {
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = importing == null) {
                Icon(ArkIcons.Upload, null); Spacer(Modifier.size(6.dp)); Text("从手机文件导入（模型 / 资料包 / 地图）")
            }
            importing?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) }
            Text(
                "也可以用数据线直接拷到：Android/data/app.ark.offline/files/ 下的 models、packs、maps 文件夹。",
                style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
            )
        }

        // ---------------- 设置 ----------------
        item { Section("设置") }
        item {
            Text("回答长度", style = MaterialTheme.typography.bodyLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // 最长输出 token 数（直接影响耗电和耗时）
                listOf("简短" to 320, "标准" to 512, "详细" to 900).forEach { (label, n) ->
                    FilterChip(selected = settings.maxTokens == n, onClick = { vm.update { it.copy(maxTokens = n) } }, label = { Text(label) })
                }
            }
            Text("越短越省电。", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
        item {
            SwitchRow("省电模式", "只检索资料原文，不用 AI 生成回答。", settings.searchOnly) { on -> vm.update { it.copy(searchOnly = on) } }
        }
        item {
            Text("外观", style = MaterialTheme.typography.bodyLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("跟随系统" to 0, "纯黑（省电）" to 1, "浅色" to 2).forEach { (label, n) ->
                    FilterChip(selected = settings.theme == n, onClick = { vm.update { it.copy(theme = n) } }, label = { Text(label) })
                }
            }
        }

        // ---------------- 关于 ----------------
        item { Section("关于") }
        item {
            Text(
                "方舟 ${androidx.compose.ui.platform.LocalContext.current.let { c -> runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: "" }}，开源软件（Apache-2.0）。\n" +
                    "本软件仅为个人爱好项目，请勿相信其中内容，未经任何医疗、救援或航海机构审核。\n" +
                    "回答由手机上的 AI 根据离线资料生成，可能出错。用药和急救请以资料原文为准，有条件时尽快就医。\n" +
                    "资料版权归原作者，许可见各资料包说明；地图数据 © OpenStreetMap 贡献者。",
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    confirm?.let { (text, action) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(text) },
            confirmButton = { TextButton(onClick = { confirm = null; action() }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 18.dp, bottom = 2.dp))
}

@Composable
private fun SwitchRow(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(desc, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun CatalogRow(c: CatalogItem, dl: Download?, vm: AppViewModel) {
    val cs = MaterialTheme.colorScheme
    Surface(color = cs.surfaceContainerLow, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.title, style = MaterialTheme.typography.bodyLarge)
                    Text(listOfNotNull(if (c.size > 0) sizeText(c.size) else null, c.note.ifBlank { null }).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
                if (dl == null) {
                    IconButton(onClick = { vm.download(c) }) { Icon(ArkIcons.Download, "下载") }
                } else {
                    IconButton(onClick = { vm.cancelDownload(dl) }) { Icon(Icons.Filled.Close, "取消下载") }
                }
            }
            if (dl != null) {
                val total = if (dl.total > 0) dl.total else c.size
                if (total > 0) LinearProgressIndicator(progress = { (dl.done.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(end = 8.dp))
                else LinearProgressIndicator(Modifier.fillMaxWidth().padding(end = 8.dp))
                Text("${sizeText(dl.done)} / ${if (total > 0) sizeText(total) else "?"}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
        }
    }
}
