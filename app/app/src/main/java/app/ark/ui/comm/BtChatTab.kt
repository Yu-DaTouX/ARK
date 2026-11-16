package app.ark.ui.comm

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ark.comm.CommEngine
import app.ark.comm.CommProto
import app.ark.comm.CommService
import app.ark.comm.CommStore
import app.ark.sensors.Beacon
import app.ark.sensors.MyFix
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BtChatTab() {
    val ctx = LocalContext.current
    val running by CommEngine.running.collectAsState()
    val error by CommEngine.error.collectAsState()
    val neighbors by CommEngine.neighbors.collectAsState()
    val scanning by CommEngine.scanning.collectAsState()
    val all by CommStore.list.collectAsState()
    val kept by CommService.kept.collectAsState()
    val fix by MyFix.last.collectAsState()
    val now by produceState(System.currentTimeMillis()) { while (true) { value = System.currentTimeMillis(); delay(5_000) } }
    val myId = remember { CommEngine.nodeId(ctx) }
    var nick by remember { mutableStateOf(CommEngine.nick(ctx)) }
    var target by remember { mutableIntStateOf(0) } // 0 = 群发
    var text by remember { mutableStateOf("") }
    var withLoc by remember { mutableStateOf(false) }
    var btOn by remember { mutableStateOf(CommEngine.bluetoothOn(ctx)) }
    var pendingKeep by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    LaunchedEffect(Unit) { CommStore.load(ctx) }
    // 离开本页：界面这一路不再需要引擎（后台服务若在跑则继续）
    DisposableEffect(Unit) { onDispose { CommEngine.release(false) } }
    // 退到后台 / 锁屏 30 秒后也放掉“界面”这一路：原来界面模式（每 12 秒满功率扫 5 秒 + 均衡档广播）在后台一直跑。
    // 开了“保持在线”则退回后台低占空比（每 30 秒扫 5 秒），没开就停（与开关说明“锁屏也收得到”只属于保持在线一致）。回到前台自动恢复。
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        var released = false
        val rel = Runnable { if (CommEngine.uiActive) { released = true; CommEngine.release(false) } }
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) h.postDelayed(rel, 30_000L) // 宽限：让正在发的消息发完，短暂切出去不断线
            else if (e == androidx.lifecycle.Lifecycle.Event.ON_START) {
                h.removeCallbacks(rel)
                if (released) { released = false; if (CommEngine.missingPermissions(ctx).isEmpty()) CommEngine.start(ctx, false) }
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { h.removeCallbacks(rel); owner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(running, error) { btOn = CommEngine.bluetoothOn(ctx) }

    fun begin(keep: Boolean) {
        btOn = CommEngine.bluetoothOn(ctx)
        if (keep) CommService.start(ctx) else CommEngine.start(ctx, false)
    }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (CommEngine.missingPermissions(ctx).isEmpty()) begin(pendingKeep) else CommEngine.error.value = "没有蓝牙权限：到系统设置里给方舟授权“附近的设备”（旧系统是“位置信息”）"
    }
    fun request(keep: Boolean) {
        pendingKeep = keep
        val need = CommEngine.missingPermissions(ctx).toMutableList()
        if (keep && Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        if (CommEngine.missingPermissions(ctx).isEmpty()) begin(keep)
        else permLauncher.launch(need.toTypedArray())
    }

    val visible = all.filter { it.visible }.asReversed()
    val bytes = text.toByteArray(Charsets.UTF_8).size

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = GAP) {
            item {
                Card(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("不配对、不联网：用蓝牙低功耗直接给附近的方舟手机发短消息，空旷处约几十米；三台以上会自动接力（最多 ${CommProto.DEFAULT_TTL} 跳）。", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text("开启蓝牙通讯", fontWeight = FontWeight.Bold); Text(if (running) "在线：广播 + 扫描中" + (if (scanning) "（正在扫）" else "") else "未开启", fontSize = 12.sp) }
                            Switch(checked = running || kept, onCheckedChange = { on ->
                                if (on) request(false) else { CommService.stop(ctx); CommEngine.release(false) }
                            })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text("保持在线（后台）", fontWeight = FontWeight.Bold); Text("前台服务，每 30 秒扫 5 秒，锁屏也收得到；关掉就完全停止", fontSize = 12.sp) }
                            Switch(checked = kept, onCheckedChange = { on -> if (on) request(true) else CommService.stop(ctx) })
                        }
                        if (!btOn) {
                            Text("蓝牙没有打开。方舟不会自动打开蓝牙，请到系统设置里打开。", color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                            OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("打开蓝牙设置") }
                        }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                        if (Build.VERSION.SDK_INT < 31) Text("Android 11 及以下：扫描蓝牙需要“位置信息”权限，并且要打开系统的定位开关（方舟不会用它定位你）。", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(nick, { nick = CommProto.truncUtf8(it, CommProto.NICK_MAX) }, singleLine = true, label = { Text("我的昵称（≤12 字节，约 4 个汉字）") }, modifier = Modifier.weight(1f))
                            TextButton(onClick = { CommEngine.setNick(ctx, nick) }) { Text("保存") }
                        }
                        Text("我的短 ID：${CommProto.shortId(myId)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("附近的方舟设备（${neighbors.size}）", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(enabled = running, onClick = { CommEngine.scanNow() }) { Text("立即扫描") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = target == 0, onClick = { target = 0 }, label = { Text("群发（所有附近设备）") })
                }
            }
            if (neighbors.isEmpty()) item { Text(if (running) "还没发现附近的设备。对方也要开启“蓝牙通讯”。" else "开启后会显示附近的设备。", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(neighbors, key = { it.id }) { n ->
                val sec = ((now - n.lastSeen) / 1000).coerceAtLeast(0)
                val d = Beacon.estimateDistance(n.rssi)
                Card(Modifier.fillMaxWidth().clickable { target = if (target == n.id) 0 else n.id }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${n.nick.ifBlank { "（无昵称）" }}  #${CommProto.shortId(n.id)}", fontWeight = FontWeight.Bold)
                            Text("约 ${if (d < 10) "%.0f".format(d) else "%.0f".format(Math.round(d / 5.0) * 5.0)} 米（${n.rssi} dBm，仅供参考）· ${if (sec < 60) "${sec} 秒前" else "${sec / 60} 分钟前"}看到", fontSize = 12.sp)
                        }
                        if (target == n.id) Text("单发 ✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }
            }
            item { Row(verticalAlignment = Alignment.CenterVertically) { Text("消息（${visible.size}）", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); TextButton(onClick = { CommStore.clear() }) { Text("清空") } } }
            items(visible, key = { it.msg.key }) { s ->
                val m = s.msg
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val who = if (s.mine) "我 → ${if (m.broadcast) "所有人" else "#" + CommProto.shortId(m.dst)}" else "${m.nick.ifBlank { "#" + CommProto.shortId(m.src) }}（#${CommProto.shortId(m.src)}）" + (if (m.broadcast) "" else " → 我")
                        Text(who, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Text(m.text, fontSize = 17.sp)
                        val meta = buildString {
                            append(fmt.format(Date(m.time * 1000)))
                            if (s.mine) append(" · " + when { s.delivered > 0 -> "已送达 ${s.delivered} 台"; s.status == CommStore.ST_FAILED -> "未送达（有邻居出现时自动重试）"; else -> "等待邻居…" })
                            else append(" · 经 ${(CommProto.DEFAULT_TTL + 1 - m.ttl).coerceAtLeast(1)} 跳")
                            if (m.lat != null && m.lon != null) append(" · 位置 %.4f, %.4f".format(m.lat, m.lon))
                        }
                        Text(meta, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = withLoc, onCheckedChange = { withLoc = it }, enabled = fix != null)
                Text(if (fix != null) "附带我的位置（精度约 11 米）" else "附带位置（暂无定位，可先在地图页定位）", fontSize = 12.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    text, { text = CommProto.truncUtf8(it, CommProto.TEXT_MAX) }, modifier = Modifier.weight(1f), maxLines = 3,
                    label = { Text(if (target == 0) "群发消息（$bytes/${CommProto.TEXT_MAX} 字节）" else "发给 #${CommProto.shortId(target)}（$bytes/${CommProto.TEXT_MAX}）") },
                )
                Button(onClick = { if (CommEngine.send(ctx, text, target, withLoc && fix != null) != null) text = "" }, enabled = text.isNotBlank(), modifier = Modifier.padding(start = 8.dp)) { Text("发送") }
            }
        }
    }
}
