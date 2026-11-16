package app.ark.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.ark.llm.LlmManager

private val examples = listOf(
    "被蛇咬了怎么急救",
    "怎么用漂白剂消毒饮用水",
    "伤口感染有哪些迹象",
    "严重腹泻怎么防止脱水",
    "怎么给骨折的腿上夹板",
    "失温的人怎么取暖",
)

@Composable
fun AskScreen(vm: AppViewModel, onOpenLibrary: () -> Unit) {
    val history by vm.qa.history.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val llm by vm.llm.state.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    val list = rememberLazyListState()
    var menu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(live?.question) { if (live != null) list.scrollToItem(0) }

    // 生成回答时保持亮屏（只在生成期间，答完就恢复系统的息屏时间）
    val view = LocalView.current
    val busy = live != null
    DisposableEffect(busy) {
        view.keepScreenOn = busy
        onDispose { view.keepScreenOn = false }
    }

    fun send(text: String) {
        if (text.isBlank() || live != null) return
        vm.ask(text)
        input = ""
    }

    Column(Modifier.fillMaxSize()) {
        // 顶栏：当前模型和状态
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("方舟", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            val current = models.firstOrNull { it.name == settings.model } ?: models.firstOrNull()
            val label = when {
                current == null -> "未安装模型 · 只检索"
                settings.searchOnly -> "省电模式 · 只检索"
                llm is LlmManager.State.Ready -> (llm as LlmManager.State.Ready).let { "${it.model} · ${it.backend}" }
                llm is LlmManager.State.Loading -> "正在加载模型…"
                llm is LlmManager.State.Failed -> "模型加载失败"
                else -> "${LlmManager.displayName(current)} · 待命"
            }
            AssistChip(onClick = onOpenLibrary, label = { Text(label, maxLines = 1) })
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "更多") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("清空问答历史（保留书签）") }, onClick = { menu = false; confirmClear = true })
                    if (llm is LlmManager.State.Ready) {
                        DropdownMenuItem(text = { Text("释放模型内存") }, onClick = { menu = false; vm.unloadModel() })
                    }
                }
            }
        }

        LazyColumn(
            state = list,
            reverseLayout = true,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            live?.let { l -> item(key = "live") { LiveCard(l, vm) } }
            items(history, key = { it.id }) { qa ->
                QaCard(qa, onBookmark = { vm.bookmark(qa, it) }, onOpen = vm::open, onDelete = { vm.deleteQa(qa) })
            }
            if (history.isEmpty() && live == null) item(key = "welcome") { Welcome(onPick = ::send) }
        }

        // 输入框
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("问点什么，比如：被蛇咬了怎么办") },
                maxLines = 4,
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send(input) }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(8.dp))
            if (live != null) {
                FilledIconButton(onClick = vm::stop) { Icon(ArkIcons.Stop, "停止") }
            } else {
                FilledIconButton(onClick = { send(input) }, enabled = input.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, "发送")
                }
            }
        }
    }

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("清空问答历史？") },
        text = { Text("加了书签的回答会保留。") },
        confirmButton = { TextButton(onClick = { confirmClear = false; vm.clearHistory() }) { Text("清空") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
    )
}

@Composable
private fun LiveCard(l: Live, vm: AppViewModel) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Surface(
            color = cs.primaryContainer,
            shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
            modifier = Modifier.align(Alignment.End).widthIn(max = 320.dp),
        ) { Text(l.question, Modifier.padding(horizontal = 14.dp, vertical = 9.dp), style = MaterialTheme.typography.bodyLarge) }
        Spacer(Modifier.height(8.dp))
        Surface(color = cs.surfaceContainerLow, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text(l.status, style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                }
                if (l.answer.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(renderAnswer(l.answer, cs.primary) { n -> l.sources.getOrNull(n - 1)?.let(vm::open) },
                        style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Welcome(onPick: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 24.dp)) {
        Text("离线求生助手", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "没有网络也能用。回答来自手机里的离线资料，每句都标出处，点编号就能看原文。" +
                "资料里没有的，它会直说。",
            style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            examples.forEach { AssistChip(onClick = { onPick(it) }, label = { Text(it) }) }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "AI 可能出错。用药、急救以原文为准，有条件时尽快联系医生或急救（澳洲 000，中国 120）。",
            style = MaterialTheme.typography.labelMedium, color = cs.tertiary,
        )
    }
}
