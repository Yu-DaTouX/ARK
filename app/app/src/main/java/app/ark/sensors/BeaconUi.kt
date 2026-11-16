package app.ark.sensors

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** 蓝牙信标：本机状态设置 + 开关 + 附近收到的信标列表（点一行 → 地图飞过去并指向它）。 */
@Composable
fun BeaconDialog(
    running: Boolean, error: String?, seen: List<Beacon.Seen>, meLat: Double?, meLon: Double?,
    onStart: (sos: Boolean, msg: String) -> Unit, onStop: () -> Unit, onPick: (Beacon.Seen) -> Unit, onClear: () -> Unit, onDismiss: () -> Unit,
) {
    var sos by remember { mutableStateOf(Beacon.sos) }
    var msg by remember { mutableStateOf(Beacon.message) }
    val cs = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("蓝牙求救信标") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "不用联网：用蓝牙低功耗广播你的位置（约 11 米精度）。附近（空旷处约几十米）装了方舟的手机会在地图上看到你。" +
                        "任何装了方舟的人都能收到，请只在需要时开启。每分钟扫描 10 秒，耗电很小。",
                    style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = sos, onClick = { sos = true }, label = { Text("求救") })
                    FilterChip(selected = !sos, onClick = { sos = false }, label = { Text("平安") })
                }
                OutlinedTextField(
                    msg, { msg = String(Beacon.truncate(it), Charsets.UTF_8) }, singleLine = true,
                    label = { Text("短消息（可空，最多约 4 个汉字）") }, modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = cs.error) }
                Row {
                    if (running) {
                        TextButton(onClick = { onStart(sos, msg) }) { Text("更新状态") }
                        TextButton(onClick = onStop) { Text("关闭信标") }
                    } else TextButton(onClick = { onStart(sos, msg) }) { Text("开启信标（广播 + 扫描）") }
                }
                Row(Modifier.fillMaxWidth()) {
                    Text(if (running) "附近的人（${seen.size}）" else "附近的人（开启后才会扫描）", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    if (seen.isNotEmpty()) TextButton(onClick = onClear) { Text("清空") }
                }
                if (seen.isEmpty()) Text("还没有收到。扫描每分钟一次，请稍等。", style = MaterialTheme.typography.bodySmall)
                else LazyColumn(Modifier.heightIn(max = 220.dp)) {
                    items(seen, key = { it.id }) { s ->
                        Column(Modifier.fillMaxWidth().clickable(enabled = s.lat != null) { onPick(s) }.padding(vertical = 4.dp)) {
                            Text(
                                (if (s.sos) "求救" else "平安") + (if (s.msg.isNotBlank()) "：${s.msg}" else ""),
                                style = MaterialTheme.typography.bodyLarge, color = if (s.sos) Color(0xFFD50000) else cs.onSurface,
                            )
                            val real = if (s.lat != null && s.lon != null && meLat != null && meLon != null) "距你 ${Geo.distText(Geo.distance(meLat, meLon, s.lat, s.lon))} · " else ""
                            Text(
                                "${real}信号估算约 ${Geo.distText(Beacon.estimateDistance(s.rssi))}（${s.rssi} dBm） · ${Geo.agoText(System.currentTimeMillis() - s.time)}" +
                                    if (s.lat == null) " · 对方没有定位" else if (s.ageMin >= 5) " · 位置是 ${s.ageMin} 分钟前的" else "",
                                style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭窗口") } },
    )
}
