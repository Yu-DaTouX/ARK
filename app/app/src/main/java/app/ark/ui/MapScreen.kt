package app.ark.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import app.ark.sensors.Baro
import app.ark.sensors.Resection
import app.ark.sensors.bearingName
import app.ark.sensors.rememberBarometer
import app.ark.sensors.rememberCompass
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.layout.Spacer
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Point
import java.util.Locale
import kotlin.math.abs

/**
 * 离线地图：地球仪 + 卫星底图 + 矢量地图。GPS 定位不需要网络。
 * 屏幕中心十字线显示坐标，方便通过无线电报告位置；点坐标可复制。
 */
@SuppressLint("MissingPermission")
@Composable
fun MapScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val regions by vm.maps.collectAsStateWithLifecycle()
    var satellite by rememberSaveable { mutableStateOf(true) }
    var chart by rememberSaveable { mutableStateOf(false) }
    var center by remember { mutableStateOf<LatLng?>(null) }
    var zoom by remember { mutableStateOf(0.0) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var wantLocate by remember { mutableStateOf(false) }
    val prefs = remember { ctx.getSharedPreferences("ark", android.content.Context.MODE_PRIVATE) }
    // 指南针（按地图中心修正磁偏角）和气压计；只在地图页显示时监听
    val compass = rememberCompass(center?.latitude, center?.longitude)
    val baro = rememberBarometer()
    var seaLevel by remember { mutableStateOf(prefs.getFloat("baro_sea_level", Baro.STD)) }
    var followHeading by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    // 后方交会：landmarks 已测好的地标；pending 已在地图上选中、还没测方位的地标
    var resecting by rememberSaveable { mutableStateOf(false) }
    var pending by remember { mutableStateOf<LatLng?>(null) }
    var landmarks by remember { mutableStateOf(listOf<Resection.Landmark>()) }
    var fix by remember { mutableStateOf<Resection.Fix?>(null) }

    val mapView = remember {
        MapView(ctx).apply {
            onCreate(null)
            getMapAsync { m ->
                m.cameraPosition = CameraPosition.Builder().target(LatLng(-25.0, 134.0)).zoom(1.2).build()
                // 调试：--es ark_goto "纬度,经度,缩放"（截图用），用一次就清掉
                prefs.getString("debug_goto", null)?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() }?.takeIf { it.size == 3 }?.let { (la, lo, z) ->
                    m.cameraPosition = CameraPosition.Builder().target(LatLng(la, lo)).zoom(z).build()
                    prefs.edit().remove("debug_goto").apply()
                }
                m.uiSettings.isAttributionEnabled = false
                m.uiSettings.isLogoEnabled = false
                m.uiSettings.setCompassMargins(0, 200, 32, 0)
                m.addOnCameraIdleListener { center = m.cameraPosition.target; zoom = m.cameraPosition.zoom }
                center = m.cameraPosition.target
                map = m
            }
        }
    }

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        onDispose {
            lifecycle.removeObserver(obs)
            mapView.onPause(); mapView.onStop(); mapView.onDestroy()
        }
    }

    // 样式随“已有地图文件”和“卫星/地图”开关重建
    LaunchedEffect(map, regions, satellite, chart) {
        val m = map ?: return@LaunchedEffect
        m.setStyle(Style.Builder().fromJson(MapStyle.build(regions, satellite, chart))) { s -> style = s }
    }

    // “朝向”模式：地图随手机朝向旋转，正前方朝上
    LaunchedEffect(followHeading, compass?.heading?.let { Math.round(it / 2f) }) {
        val m = map ?: return@LaunchedEffect
        val h = compass?.heading ?: return@LaunchedEffect
        if (followHeading) m.moveCamera(CameraUpdateFactory.bearingTo(h.toDouble()))
    }

    // 交会结果画成一个点（样式重建后重新加）
    LaunchedEffect(style, fix) {
        val s = style ?: return@LaunchedEffect
        s.getLayer("fix")?.let { s.removeLayer(it) }
        s.getSource("fix")?.let { s.removeSource(it) }
        val f = fix ?: return@LaunchedEffect
        s.addSource(GeoJsonSource("fix", Point.fromLngLat(f.lon, f.lat)))
        s.addLayer(CircleLayer("fix", "fix").withProperties(
            PropertyFactory.circleRadius(9f), PropertyFactory.circleColor("#7c4dff"),
            PropertyFactory.circleStrokeColor("#ffffff"), PropertyFactory.circleStrokeWidth(3f)))
    }

    fun locate() {
        val m = map ?: return
        val s = style ?: return
        val lc = m.locationComponent
        if (!lc.isLocationComponentActivated) {
            lc.activateLocationComponent(LocationComponentActivationOptions.builder(ctx, s).useDefaultLocationEngine(true).build())
        }
        lc.isLocationComponentEnabled = true
        lc.renderMode = RenderMode.COMPASS
        lc.cameraMode = CameraMode.TRACKING
        val last = lc.lastKnownLocation
        // 有 GPS 海拔时顺便校准气压海拔（海平面气压），没有 GPS 时也能用气压估海拔
        if (last != null && last.hasAltitude() && baro != null) {
            seaLevel = Baro.seaLevel(baro.hPa, last.altitude.toFloat())
            prefs.edit().putFloat("baro_sea_level", seaLevel).apply()
        }
        if (last != null) {
            m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(last.latitude, last.longitude), maxOf(m.cameraPosition.zoom, 13.0)))
        } else {
            Toast.makeText(ctx, "正在等待 GPS 信号，到室外开阔处更快", Toast.LENGTH_LONG).show()
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.any { it }) wantLocate = true
        else Toast.makeText(ctx, "没有定位权限，无法显示你的位置", Toast.LENGTH_SHORT).show()
    }
    LaunchedEffect(wantLocate, style) {
        if (wantLocate && style != null) { locate(); wantLocate = false }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView({ mapView }, Modifier.fillMaxSize())

        // 中心十字线
        Icon(Icons.Filled.Add, null, tint = Color(0xFFE53935), modifier = Modifier.align(Alignment.Center).size(28.dp))

        // 顶部：中心点坐标
        center?.let { c ->
            Surface(
                onClick = {
                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("坐标", latLonText(c)))
                    Toast.makeText(ctx, "坐标已复制", Toast.LENGTH_SHORT).show()
                },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(latLonText(c), style = MaterialTheme.typography.labelLarge)
                    Text(
                        String.format(Locale.US, "%.5f, %.5f · 缩放 %.1f", c.latitude, c.longitude, zoom),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val extra = listOfNotNull(
                        compass?.let {
                            "朝向 ${Math.round(it.heading)}° ${bearingName(it.heading)}" + when {
                                it.source == app.ark.sensors.Heading.Source.CELESTIAL -> "（天体校准）"
                                it.source == app.ark.sensors.Heading.Source.HOLD -> "（磁干扰，陀螺保持）"
                                it.magnetic -> "（磁北）"
                                else -> ""
                            }
                        },
                        baro?.let { "气压海拔 ≈ ${Math.round(Baro.altitude(it.hPa, seaLevel))} m${if (seaLevel == Baro.STD) "（未校准）" else ""}" },
                    )
                    if (extra.isNotEmpty()) Text(extra.joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // 右下：按钮
        Column(
            Modifier.align(Alignment.BottomEnd).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.End,
        ) {
            // 放大 / 缩小：戴手套或单手时比双指捏合好用
            SmallFloatingActionButton(onClick = { map?.animateCamera(CameraUpdateFactory.zoomIn(), 300) }) {
                Text("＋", style = MaterialTheme.typography.titleLarge)
            }
            SmallFloatingActionButton(onClick = { map?.animateCamera(CameraUpdateFactory.zoomOut(), 300) }) {
                Text("－", style = MaterialTheme.typography.titleLarge)
            }
            // 其余功能收进“更多”菜单，右侧只留 4 个按钮
            Box {
                SmallFloatingActionButton(onClick = { menuOpen = true }) {
                    Text("更多", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 10.dp))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    fun item(label: String, checked: Boolean?, onClick: () -> Unit) = @Composable {
                        DropdownMenuItem(
                            text = { Text(label, style = MaterialTheme.typography.bodyLarge) },
                            leadingIcon = { if (checked == true) Icon(Icons.Filled.Check, null) else Spacer(Modifier.size(24.dp)) },
                            onClick = { menuOpen = false; onClick() },
                        )
                    }
                    item("卫星底图", satellite) { satellite = !satellite }()
                    item("海图（水深、航标）", chart) {
                        chart = !chart
                        if (chart && regions.none(MapStyle::isChart))
                            Toast.makeText(ctx, "现在只有概略水深。到「资料」页下载海图，可看近岸等深线和航标", Toast.LENGTH_LONG).show()
                    }()
                    if (compass != null) {
                        item("地图随朝向旋转", followHeading) {
                            followHeading = !followHeading
                            if (!followHeading) map?.animateCamera(CameraUpdateFactory.bearingTo(0.0), 300)
                        }()
                        item("交会定位（没有 GPS 时）", resecting) { resecting = !resecting; pending = null; landmarks = emptyList() }()
                    }
                    item("回到地球全景", null) {
                        followHeading = false
                        map?.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(LatLng(-25.0, 134.0)).zoom(1.2).bearing(0.0).tilt(0.0).build()), 1500)
                    }()
                }
            }
            SmallFloatingActionButton(onClick = {
                val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (fine) locate() else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }) { Icon(Icons.Filled.LocationOn, "我的位置") }
        }

        if (resecting) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                modifier = Modifier.align(Alignment.BottomStart).padding(16.dp).widthIn(max = 260.dp),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("交会定位（没有 GPS 时）", style = MaterialTheme.typography.titleSmall)
                    val n = landmarks.size + 1
                    when {
                        landmarks.size >= 2 -> {
                            val f = fix
                            if (f == null) Text("两条方位线没有交点，请重测。", style = MaterialTheme.typography.bodySmall)
                            else {
                                Text(latLonText(LatLng(f.lat, f.lon)), style = MaterialTheme.typography.labelLarge)
                                Text(
                                    "两线夹角 ${Math.round(f.angle)}°" + if (f.angle < 30 || f.angle > 150) "：太小或太大，结果不可靠，换个地标" else "：结果可用（误差随距离增大）",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            Row {
                                if (f != null) TextButton(onClick = { map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(f.lat, f.lon), maxOf(zoom, 12.0))) }) { Text("移到这里") }
                                TextButton(onClick = { landmarks = emptyList(); pending = null; fix = null }) { Text("重来") }
                            }
                        }
                        pending == null -> {
                            Text("第 $n 步：在远处找一个看得见、地图上也有的地标（山顶、塔、岬角），把十字对准地图上的它。", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { pending = center }) { Text("十字处是地标 $n") }
                        }
                        else -> {
                            Text("把手机平放，顶部对准真实的地标 $n，读数稳定后点记录。", style = MaterialTheme.typography.bodySmall)
                            Text("当前方位 ${compass?.let { "${Math.round(it.heading)}°" } ?: "—"}", style = MaterialTheme.typography.labelLarge)
                            if (compass != null && compass.accuracy <= android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_LOW)
                                Text("指南针精度低：在空中画 8 字校准，远离铁器", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                            TextButton(onClick = {
                                val p = pending ?: return@TextButton
                                val h = compass?.heading ?: return@TextButton
                                landmarks = landmarks + Resection.Landmark(p.latitude, p.longitude, h)
                                pending = null
                                if (landmarks.size >= 2) fix = Resection.solve(landmarks[0], landmarks[1])
                            }) { Text("记录方位") }
                        }
                    }
                }
            }
        } else if (regions.none { !MapStyle.isChart(it) }) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
            ) {
                Text(
                    "内置全球概略图。详细地图（如澳洲）可在「资料」页下载或导入 .pmtiles",
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(10.dp).size(width = 200.dp, height = 36.dp),
                )
            }
        }
    }
}

/** 度分格式，例如 33°52.1′S 151°12.6′E（无线电报位置常用）。 */
fun latLonText(c: LatLng): String {
    fun dm(v: Double, pos: Char, neg: Char): String {
        val tenths = Math.round(abs(v) * 600) // 先整体四舍五入到 0.1 分，避免出现 “24°60.0′”
        val d = tenths / 600
        val m = (tenths % 600) / 10.0
        return String.format(Locale.US, "%d°%04.1f′%c", d, m, if (v >= 0) pos else neg)
    }
    return dm(c.latitude, 'N', 'S') + "  " + dm(c.longitude, 'E', 'W')
}
