package app.ark.ui.comm

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ark.comm.AcousticLink
import app.ark.comm.AcousticModem
import app.ark.comm.ModemConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AcousticTab() {
    val ctx = LocalContext.current
    if (ctx.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) AcousticLink.dumpDir = ctx.getExternalFilesDir(null)
    val cfg by AcousticLink.cfg.collectAsState()
    val listening by AcousticLink.listening.collectAsState()
    val sending by AcousticLink.sending.collectAsState()
    val level by AcousticLink.level.collectAsState()
    val score by AcousticLink.score.collectAsState()
    val rx by AcousticLink.received.collectAsState()
    val msg by AcousticLink.message.collectAsState()
    val self by AcousticLink.selfTest.collectAsState()
    var text by remember { mutableStateOf("") }
    var pendingTest by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    fun hasMic() = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // 离开本页就关麦克风
    DisposableEffect(Unit) { onDispose { AcousticLink.stopListening() } }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { if (pendingTest) AcousticLink.runSelfTest() else AcousticLink.startListening() }
        else AcousticLink.message.value = "没有录音权限：只能发送，不能接收"
        pendingTest = false
    }

    fun setMode(c: ModemConfig) {
        if (c === cfg) return
        val was = listening
        AcousticLink.stopListening()
        AcousticLink.cfg.value = c
        if (was) AcousticLink.startListening()
    }

    val bytes = text.toByteArray(Charsets.UTF_8).size
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = GAP) {
        Text(
            "用人耳几乎听不见的近超声（17–19.5 kHz）在两台手机之间传短消息：发送方扬声器播放，接收方麦克风解码。" +
                "不需要蓝牙和配对，适合蓝牙坏了或两台手机互相找不到时，近距离（约 1 米内，扬声器对着麦克风）安静处使用。" +
                "约 9 字节/秒，一条最多 140 字节（约 46 个汉字），有纠错和校验，校验失败的会被丢弃。部分手机扬声器发不出这么高的音，请先点“单机自测”。",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("接收（开麦克风监听）", fontWeight = FontWeight.Bold); Text(if (listening) "监听中：只在本页开着时运行" else "未开启", fontSize = 12.sp) }
                    Switch(checked = listening, onCheckedChange = { on ->
                        if (!on) AcousticLink.stopListening() else if (hasMic()) AcousticLink.startListening() else launcher.launch(Manifest.permission.RECORD_AUDIO)
                    })
                }
                if (listening) {
                    Text("环境声音", fontSize = 12.sp)
                    LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth())
                    Text(if (score > 0.4f) "听到同步信号，正在接收…" else "等待信号（${(score * 100).toInt()}%）", fontSize = 12.sp)
                }
                msg?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("发送", fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    text, { text = AcousticLink.clip(it) }, modifier = Modifier.fillMaxWidth(), maxLines = 4,
                    label = { Text("消息（$bytes/${AcousticModem.MAX_BYTES} 字节）") },
                )
                val dur = AcousticLink.duration(text, cfg)
                Text(if (text.isBlank()) "把手机音量调大，扬声器对着对方的麦克风。" else "约 %.0f 秒播完；播放期间请保持安静、不要移动手机。".format(dur), fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { AcousticLink.send(text) }, enabled = text.isNotBlank() && !sending) { Text(if (sending) "播放中…" else "播放发送") }
                    OutlinedButton(onClick = { if (hasMic()) AcousticLink.runSelfTest() else { pendingTest = true; launcher.launch(Manifest.permission.RECORD_AUDIO) } }, enabled = !sending) { Text("单机自测") }
                }
                self?.let { Text(it, fontSize = 13.sp, color = if (it.startsWith("通")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) }
                Text("单机自测：本机同时播放和录音，验证“扬声器→麦克风”这条路能通（会用到录音权限，约 4 秒）。", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Text("收到的消息（${rx.size}）", fontWeight = FontWeight.Bold)
        if (rx.isEmpty()) Text("还没有收到。", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        rx.forEach { r ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(r.text, fontSize = 17.sp)
                    Text("${fmt.format(Date(r.time))} · 纠正 ${r.corrected} 字节错误 · 信号 ${(r.quality * 100).toInt()}%", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
