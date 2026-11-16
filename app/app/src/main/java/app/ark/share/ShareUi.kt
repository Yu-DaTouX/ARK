/**
 * ShareUi.kt —— 资料页“分享给附近的人”卡片（开关、网址、二维码、开热点说明）和二维码绘制。
 *
 * 位置：LibraryScreen 的资料页；服务状态来自 ShareServer.state，开关走 ShareService。
 * 为什么不用系统分享：断网时让对方浏览器直接下载 APK / 资料包 / 地图，不用装 App、不用配对。
 * 安全：无认证，同一网络内谁都能下（手机热点通常要密码）；分享的只是公开文件，没有个人数据。
 */
package app.ark.share

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** 资料页“分享给附近的人”卡片：开关、网址、二维码、开热点说明。 */
/** 分享卡片。Android 13+ 要先拿通知权限，否则前台服务通知不显示、用户无从关闭。 */
@Composable
fun ShareCard() {
    val ctx = LocalContext.current
    val info by ShareServer.state.collectAsState()
    val cs = MaterialTheme.colorScheme
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ShareService.start(ctx) } // 权限对话框回来就照开（拒绝也能运行，只是没有常驻通知）
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            "断网时把方舟传给身边的人：对方不用装任何东西，用浏览器就能下载安装包、资料包和已下载的地图。",
            style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
        )
        Text(
            "怎么连：① 你的手机在「设置 → 网络 → 个人热点 / WLAN 热点」里手动打开热点（方舟不会自动改系统设置，多数手机不需要流量或 SIM 卡）；" +
                "② 对方手机连上你的热点，或你们连同一个 Wi-Fi；③ 点下面开启，让对方扫二维码或输入网址。",
            style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
        )
        if (!info.running) {
            Button(onClick = {
                if (Build.VERSION.SDK_INT >= 33 && !app.ark.sensors.Perms.granted(ctx, Manifest.permission.POST_NOTIFICATIONS)) notif.launch(Manifest.permission.POST_NOTIFICATIONS)
                else ShareService.start(ctx)
            }) { Text("开启分享") }
        } else {
            if (info.urls.isEmpty()) {
                Text("没有找到可用的网络地址。请先打开热点或连上 Wi-Fi，再重新开启。", style = MaterialTheme.typography.bodyMedium, color = cs.error)
            } else {
                Text(info.urls.first(), style = MaterialTheme.typography.titleMedium, color = cs.primary)
                if (info.urls.size > 1) Text("也可以试：" + info.urls.drop(1).joinToString("  "), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                Qr(info.urls.first())
            }
            Text("已完成下载 ${info.downloads} 次 · 约 ${info.idleLeftMin} 分钟内无人访问将自动关闭。传输时请保持屏幕不要锁。", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            OutlinedButton(onClick = { ShareService.stop(ctx) }) { Text("关闭分享") }
        }
    }
}

/** 二维码：zxing core 生成矩阵，Canvas 直接画（白底黑块，深色主题下也能扫）。 */
@Composable
private fun Qr(text: String) {
    // 纠错等级用最低的 L：屏幕距离近，换更短的码（网址不长，扫得动）
    val m = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 2))
    }
    Canvas(Modifier.size(200.dp)) {
        drawRect(Color.White)
        val cell = size.width / m.width
        for (y in 0 until m.height) for (x in 0 until m.width) if (m.get(x, y)) {
            // 每块多画 0.5px，盖住相邻块之间的抗锯齿白缝
            drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}
