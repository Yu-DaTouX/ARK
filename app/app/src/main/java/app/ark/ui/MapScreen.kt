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
    var center by remember { mutableStateOf<LatLng?>(null) }
    var zoom by remember { mutableStateOf(0.0) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var wantLocate by remember { mutableStateOf(false) }

    val mapView = remember {
        MapView(ctx).apply {
            onCreate(null)
            getMapAsync { m ->
                m.cameraPosition = CameraPosition.Builder().target(LatLng(-25.0, 134.0)).zoom(1.2).build()
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
    LaunchedEffect(map, regions, satellite) {
        val m = map ?: return@LaunchedEffect
        m.setStyle(Style.Builder().fromJson(MapStyle.build(regions, satellite))) { s -> style = s }
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
            SmallFloatingActionButton(onClick = { satellite = !satellite }) {
                Text(if (satellite) "地图" else "卫星", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 10.dp))
            }
            SmallFloatingActionButton(onClick = {
                map?.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(LatLng(-25.0, 134.0)).zoom(1.2).bearing(0.0).tilt(0.0).build()), 1500)
            }) { Text("地球", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 10.dp)) }
            SmallFloatingActionButton(onClick = {
                val fine = ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (fine) locate() else permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }) { Icon(Icons.Filled.LocationOn, "我的位置") }
        }

        if (regions.isEmpty()) {
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
