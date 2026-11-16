package app.ark.sensors

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path as UiPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import app.ark.data.Waypoint
import app.ark.data.WpType

/** 地图上航点 / 信标的图标：Canvas 画“彩色圆 + 一个字”，每种类型颜色和字都不同。 */
object MapIcons {
    private fun disc(color: Int, glyph: String, triangle: Boolean = false): Bitmap {
        val s = 72
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = android.graphics.Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 6f }
        if (triangle) {
            val p = Path().apply { moveTo(s / 2f, 4f); lineTo(s - 4f, s - 8f); lineTo(4f, s - 8f); close() }
            c.drawPath(p, fill); c.drawPath(p, ring)
        } else {
            c.drawCircle(s / 2f, s / 2f, s / 2f - 5f, fill); c.drawCircle(s / 2f, s / 2f, s / 2f - 5f, ring)
        }
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = android.graphics.Color.WHITE; textSize = 34f; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
        c.drawText(glyph, s / 2f, s / 2f + (if (triangle) 20f else 12f), t)
        return bmp
    }

    fun waypoint(t: WpType) = disc(t.color, t.glyph)
    fun beacon(sos: Boolean) = if (sos) disc(0xFFD50000.toInt(), "救", triangle = true) else disc(0xFF00897B.toInt(), "安")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WaypointDialog(title: String, name0: String, type0: WpType, hint: String?, onOk: (String, WpType) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(name0) }
    var type by remember { mutableStateOf(type0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                hint?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                OutlinedTextField(name, { name = it.take(20) }, label = { Text("名称（可空）") }, singleLine = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WpType.entries.forEach { t -> FilterChip(selected = type == t, onClick = { type = t }, label = { Text("${t.glyph} ${t.label}") }) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onOk(name.trim(), type) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun WaypointListDialog(
    list: List<Waypoint>, meLat: Double?, meLon: Double?,
    onSelect: (Waypoint) -> Unit, onEdit: (Waypoint) -> Unit, onDelete: (Waypoint) -> Unit, onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("航点（${list.size}）") },
        text = {
            if (list.isEmpty()) Text("还没有航点。长按地图上的位置可以添加。")
            else LazyColumn(Modifier.heightIn(max = 380.dp)) {
                items(list, key = { it.id }) { w ->
                    Row(Modifier.fillMaxWidth().clickable { onSelect(w) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = Color(w.type.color), shape = RoundedCornerShape(50), modifier = Modifier.size(32.dp)) {
                            Box(contentAlignment = Alignment.Center) { Text(w.type.glyph, color = Color.White, style = MaterialTheme.typography.labelLarge) }
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text(w.name, style = MaterialTheme.typography.bodyLarge)
                            val d = if (meLat != null && meLon != null) "${Geo.distText(Geo.distance(meLat, meLon, w.lat, w.lon))} · ${Math.round(Geo.bearing(meLat, meLon, w.lat, w.lon))}°" else "等待定位"
                            Text("${w.type.label} · $d", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { onEdit(w) }) { Icon(Icons.Filled.Edit, "编辑") }
                        IconButton(onClick = { onDelete(w) }) { Icon(Icons.Filled.Delete, "删除") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/** 指向某个目标的面板：距离、真北方位、一个随手机朝向转动的箭头（箭头朝上 = 目标在正前方）。 */
@Composable
fun NavPanel(
    title: String, subtitle: String?, myLat: Double?, myLon: Double?, toLat: Double, toLon: Double,
    heading: Float?, modifier: Modifier = Modifier, onClose: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f), modifier = modifier.widthIn(max = 280.dp)) {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            val hasMe = myLat != null && myLon != null
            val bearing = if (hasMe) Geo.bearing(myLat!!, myLon!!, toLat, toLon) else null
            val rel = if (bearing != null && heading != null) Heading.wrapSigned(bearing - heading) else null
            val tint = MaterialTheme.colorScheme.primary
            ComposeCanvas(Modifier.size(56.dp)) {
                val c = center; val r = size.minDimension / 2
                drawCircle(tint.copy(alpha = 0.15f), r)
                if (rel != null) rotate(rel, c) {
                    val p = UiPath().apply {
                        moveTo(c.x, c.y - r * 0.85f); lineTo(c.x + r * 0.5f, c.y + r * 0.6f)
                        lineTo(c.x, c.y + r * 0.3f); lineTo(c.x - r * 0.5f, c.y + r * 0.6f); close()
                    }
                    drawPath(p, tint)
                } else drawCircle(tint.copy(alpha = 0.4f), r * 0.15f, Offset(c.x, c.y))
            }
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                when {
                    !hasMe -> Text("等待 GPS 定位…（到室外开阔处）", style = MaterialTheme.typography.bodySmall)
                    else -> {
                        Text("${Geo.distText(Geo.distance(myLat!!, myLon!!, toLat, toLon))} · 方位 ${Math.round(bearing!!)}° ${bearingName(bearing)}", style = MaterialTheme.typography.labelLarge)
                        Text(if (rel != null) Geo.turnText(rel) else "指南针还没有读数", style = MaterialTheme.typography.bodyMedium, color = tint)
                    }
                }
                subtitle?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "取消") }
        }
    }
}
