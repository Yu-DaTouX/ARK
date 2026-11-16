package app.ark.ui.comm

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ark.ui.ArkIcons

/** 通讯：蓝牙消息 / 声音传信 / 警报声。无网络、无基站时让附近的手机互相联系。 */
@Composable
fun CommScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(56.dp)) { Icon(ArkIcons.ChevronBack, "返回", Modifier.size(32.dp)) }
            Text("通讯", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        TabRow(selectedTabIndex = tab) {
            listOf("蓝牙消息", "声音传信", "警报声").forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, fontSize = 16.sp) })
            }
        }
        when (tab) {
            0 -> BtChatTab()
            1 -> AcousticTab()
            else -> AlarmTab()
        }
    }
}

internal val GAP = Arrangement.spacedBy(10.dp)
