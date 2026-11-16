package app.ark.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val tabs = listOf(
    "问答" to ArkIcons.Chat,
    "资料" to ArkIcons.Book,
    "地图" to ArkIcons.Map,
    "书签" to ArkIcons.Bookmark,
)

@Composable
fun ArkRoot(vm: AppViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val msg by vm.message.collectAsStateWithLifecycle()
    val reading by vm.reading.collectAsStateWithLifecycle()

    LaunchedEffect(msg) {
        msg?.let {
            snackbar.showSnackbar(it)
            vm.message.value = null
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(icon, null) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { inner ->
        Box(Modifier.padding(inner).consumeWindowInsets(inner).imePadding()) {
            when (tab) {
                0 -> AskScreen(vm, onOpenLibrary = { tab = 1 })
                1 -> LibraryScreen(vm)
                2 -> MapScreen(vm)
                else -> BookmarksScreen(vm)
            }
        }
    }

    reading?.let { ReaderDialog(it, onStep = { d -> vm.step(d) }, onClose = vm::closeReader, loadOriginal = { p -> vm.packs.original(p.packId, p.chunkId) }) }
}
