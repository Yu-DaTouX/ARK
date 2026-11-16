/*
 * AlarmTab.kt —— “警报声”标签：选一种求救声样式（AlarmPattern），由前台服务 AlarmService 以最大音量循环播放，
 * 并显示粗估的耗电和已播放时长/实际掉电。声音服务本身见 comm/AlarmService.kt。
 */
package app.ark.ui.comm

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ark.comm.AlarmPattern
import app.ark.comm.AlarmService
import kotlinx.coroutines.delay

/** 警报声页 */
@Composable
fun AlarmTab() {
    val ctx = LocalContext.current
    val active by AlarmService.active.collectAsState()
    val err by AlarmService.error.collectAsState()
    val now by produceState(System.currentTimeMillis()) { while (true) { value = System.currentTimeMillis(); delay(3_000) } }
    val (cap, measured) = remember { AlarmService.capacityMah(ctx) }
    var pending by remember { mutableStateOf<AlarmPattern?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { pending?.let { AlarmService.start(ctx, it) }; pending = null }
    fun go(p: AlarmPattern) {
        // Android 13+ 要先有通知权限，前台服务的通知（及“停止”按钮）才可见；用户拒绝则不启动
        if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) { pending = p; launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        else AlarmService.start(ctx, p)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = GAP) {
        Text(
            "用扬声器发出最大音量的求救声（走“闹钟”音量，开始时自动调到最大，停止后还原）。前台服务运行，关屏、切到别的应用都不会停；通知栏可停止。" +
                "请勿贴近耳朵——声音很响，可能伤听力。",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AlarmPattern.entries.forEach { p ->
            val on = active == p
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(p.label, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(p.desc, fontSize = 13.sp)
                    val ma = AlarmService.estimateMa(p)
                    Text("估计耗电：约 ${ma.toInt()} mA ≈ 每小时 %.1f%%（满电可连续约 %.0f 小时）".format(ma / cap * 100, cap / ma), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (on) Button(onClick = { AlarmService.stop(ctx) }, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("停止", fontSize = 18.sp) }
                    else Button(onClick = { go(p) }, Modifier.fillMaxWidth()) { Text(if (active != null) "改为此样式" else "开始播放", fontSize = 18.sp) }
                }
            }
        }
        active?.let {
            val s = ((now - AlarmService.startedAt) / 1000).coerceAtLeast(0)
            val lv = AlarmService.batteryPct(ctx)
            val used = if (AlarmService.startLevel >= 0 && lv >= 0) AlarmService.startLevel - lv else null
            Text("已播放 %d 分 %02d 秒".format(s / 60, s % 60) + (if (used != null) "，电量 ${AlarmService.startLevel}% → $lv%（实际 −${used}%）" else ""), fontWeight = FontWeight.Bold)
        }
        err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text(
            "耗电为粗估：按电池容量 ${cap.toInt()} mAh（${if (measured) "由系统电量计推算" else "未读到，按 4000 mAh 假定"}），" +
                "关屏时基础约 70 mA + 扬声器功放约 230 mA × 发声时间占比；不同手机可差一倍，以上面“实际”的电量变化为准。",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
