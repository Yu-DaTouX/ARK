/*
 * BookmarksScreen.kt —— “书签”标签页：列出 bookmarked=1 的问答，可按关键词在问题和回答里过滤。
 * 数据来自 QaStore.bookmarks；出处原文随书签存储，不依赖资料包还在不在。
 */
package app.ark.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** 书签页 */
@Composable
fun BookmarksScreen(vm: AppViewModel) {
    val all by vm.qa.bookmarks.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf("") }
    val shown = if (filter.isBlank()) all else all.filter {
        it.question.contains(filter, true) || it.answer.contains(filter, true)
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Text("书签", style = MaterialTheme.typography.titleLarge) }
        if (all.isEmpty()) {
            item {
                Text(
                    "在回答下方点书签图标，重要的回答就会存在这里，连同出处原文一起保存，关机重启也不会丢。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            item {
                OutlinedTextField(
                    value = filter, onValueChange = { filter = it },
                    placeholder = { Text("在 ${all.size} 条书签里查找") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    singleLine = true, shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                )
            }
            items(shown, key = { it.id }) { qa ->
                QaCard(qa, onBookmark = { vm.bookmark(qa, it) }, onOpen = vm::open)
            }
        }
    }
}
