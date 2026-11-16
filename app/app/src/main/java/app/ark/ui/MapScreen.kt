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
import android.graphics.RectF
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import app.ark.data.Gpx
import app.ark.data.TrackPoint
import app.ark.data.TrackStore
import app.ark.data.Waypoint
import app.ark.data.WaypointStore
import app.ark.data.WpType
import app.ark.sensors.Beacon
import app.ark.sensors.BeaconDialog
import app.ark.sensors.BeaconService
import app.ark.sensors.Geo
import app.ark.sensors.MapIcons
import app.ark.sensors.NavPanel
import app.ark.sensors.Perms
import app.ark.sensors.TrackService
import app.ark.sensors.WaypointDialog
import app.ark.sensors.WaypointListDialog
import app.ark.sensors.rememberMyLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
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
    // 指南针和气压计只在地图页显示时监听；磁偏角只按真实定位算；地图中心可能是随手拖到的地方
    val myFix by app.ark.sensors.MyFix.last.collectAsState()
    val known = remember { app.ark.sensors.MyFix.lastKnown(ctx) }
    val here = myFix ?: known
    val compass = rememberCompass(here?.latitude, here?.longitude)
    val baro = rememberBarometer()
    var seaLevel by remember { mutableStateOf(prefs.getFloat("baro_sea_level", Baro.STD)) }
    var followHeading by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    // 后方交会：landmarks 已测好的地标；pending 已在地图上选中、还没测方位的地标
    var resecting by rememberSaveable { mutableStateOf(false) }
    var pending by remember { mutableStateOf<LatLng?>(null) }
    var landmarks by remember { mutableStateOf(listOf<Resection.Landmark>()) }
    var fix by remember { mutableStateOf<Resection.Fix?>(null) }

    // ---- 航点 / 轨迹 / 蓝牙信标 ----
    remember { WaypointStore.init(ctx); TrackStore.init(ctx); Beacon.loadConfig(ctx) }
    val scope = rememberCoroutineScope()
    val waypoints by WaypointStore.list.collectAsState()
    val trackPts by TrackStore.points.collectAsState()
    val recording by TrackStore.recording.collectAsState()
    val beaconOn by Beacon.running.collectAsState()
    val beaconErr by Beacon.error.collectAsState()
    val seen by Beacon.seen.collectAsState()
    var addAt by remember { mutableStateOf<LatLng?>(null) }
    var editWp by remember { mutableStateOf<Waypoint?>(null) }
    var selWp by remember { mutableStateOf<String?>(null) }
    var selBeacon by remember { mutableStateOf<Int?>(null) }
    var returning by remember { mutableStateOf(false) }
    var showWpList by remember { mutableStateOf(false) }
    var showBeacon by remember { mutableStateOf(false) }
    var confirmDel by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var afterPerm by remember { mutableStateOf<(() -> Unit)?>(null) }
    var afterPerms by remember { mutableStateOf<Array<String>>(emptyArray()) }
    val askPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val a = afterPerm; afterPerm = null
        if (a != null) { if (Perms.ready(ctx, afterPerms)) a() else Toast.makeText(ctx, "权限不够，没有开启（需要定位；蓝牙信标还需要“附近设备”权限）", Toast.LENGTH_LONG).show() }
    }
    fun withPerms(p: Array<String>, run: () -> Unit) {
        if (p.all { Perms.granted(ctx, it) }) run() else { afterPerm = run; afterPerms = p; askPerm.launch(p) }
    }
    val navOn = selWp != null || selBeacon != null || returning || showWpList || showBeacon
    val meLoc by rememberMyLocation(navOn)

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
                m.addOnMapLongClickListener { ll -> addAt = ll; true }
                m.addOnMapClickListener { ll ->
                    val pt = m.projection.toScreenLocation(ll)
                    val hit = m.queryRenderedFeatures(RectF(pt.x - 28f, pt.y - 28f, pt.x + 28f, pt.y + 28f), "wps-sym", "beacons-sym").firstOrNull()
                    when (hit?.getStringProperty("kind")) {
                        "wp" -> { selWp = hit.getStringProperty("id"); selBeacon = null; returning = false; true }
                        "bc" -> { selBeacon = hit.getStringProperty("id")?.toIntOrNull(); selWp = null; returning = false; true }
                        else -> false
                    }
                }
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

    // 航点 / 轨迹 / 信标图层：每次样式重建后补上，数据变化时只更新数据
    LaunchedEffect(style, waypoints) {
        val s = style ?: return@LaunchedEffect
        overlayInit(s)
        s.getSourceAs<GeoJsonSource>("wps")?.setGeoJson(FeatureCollection.fromFeatures(waypoints.map {
            Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)).apply {
                addStringProperty("kind", "wp"); addStringProperty("id", it.id); addStringProperty("name", it.name); addStringProperty("icon", "wp-${it.type.name}")
            }
        }))
    }
    LaunchedEffect(style, trackPts.size) {
        val s = style ?: return@LaunchedEffect
        overlayInit(s)
        val line = if (trackPts.size >= 2) LineString.fromLngLats(trackPts.map { Point.fromLngLat(it.lon, it.lat) }) else null
        s.getSourceAs<GeoJsonSource>("track")?.setGeoJson(FeatureCollection.fromFeatures(listOfNotNull(line?.let { Feature.fromGeometry(it) })))
    }
    LaunchedEffect(style, seen) {
        val s = style ?: return@LaunchedEffect
        overlayInit(s)
        s.getSourceAs<GeoJsonSource>("beacons")?.setGeoJson(FeatureCollection.fromFeatures(seen.filter { it.lat != null && it.lon != null }.map {
            Feature.fromGeometry(Point.fromLngLat(it.lon!!, it.lat!!)).apply {
                addStringProperty("kind", "bc"); addStringProperty("id", it.id.toString()); addStringProperty("icon", if (it.sos) "bc-sos" else "bc-ok")
                addStringProperty("name", it.msg.ifBlank { if (it.sos) "求救" else "平安" })
            }
        }))
    }

    // 当前指向的目标：选中的航点 / 信标，或“沿轨迹返回”的下一个点
    val ret = if (returning) meLoc?.let { returnTarget(trackPts, it.latitude, it.longitude) } else null
    val tgt: Triple<String, String?, LatLng>? = when {
        returning -> ret?.let { Triple(if (it.arrived) "已回到起点附近" else "沿轨迹返回", "剩余约 ${Geo.distText(it.left)}（沿你走过的路）", LatLng(it.lat, it.lon)) }
            ?: Triple("沿轨迹返回", if (trackPts.isEmpty()) "还没有轨迹" else "等待定位…", LatLng(trackPts.lastOrNull()?.lat ?: 0.0, trackPts.lastOrNull()?.lon ?: 0.0))
        selWp != null -> waypoints.firstOrNull { it.id == selWp }?.let { Triple(it.name, it.type.label, LatLng(it.lat, it.lon)) }
        selBeacon != null -> seen.firstOrNull { it.id == selBeacon && it.lat != null }?.let {
            Triple((if (it.sos) "求救：" else "平安：") + it.msg.ifBlank { "无留言" }, "${Geo.agoText(System.currentTimeMillis() - it.time)}收到", LatLng(it.lat!!, it.lon!!))
        }
        else -> null
    }
    LaunchedEffect(style, meLoc, tgt?.third) {
        val s = style ?: return@LaunchedEffect
        overlayInit(s)
        val me = meLoc; val t = tgt?.third
        s.getSourceAs<GeoJsonSource>("navline")?.setGeoJson(
            if (me != null && t != null && !(returning && ret == null))
                FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(LineString.fromLngLats(listOf(Point.fromLngLat(me.longitude, me.latitude), Point.fromLngLat(t.longitude, t.latitude))))))
            else FeatureCollection.fromFeatures(emptyList<Feature>())
        )
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
        // 只用 2 分钟内、垂直精度 ≤ 20 m 的定位；6 小时内在气压页手动校准过就不覆盖
        val manualAt = ctx.getSharedPreferences("ark_tools", android.content.Context.MODE_PRIVATE).getLong("baro_cal_at", 0L)
        val fresh = last != null && System.currentTimeMillis() - last.time < 120_000 &&
            (android.os.Build.VERSION.SDK_INT < 26 || !last.hasVerticalAccuracy() || last.verticalAccuracyMeters <= 20f)
        if (last != null && fresh && last.hasAltitude() && baro != null && System.currentTimeMillis() - manualAt > 6 * 3600_000L) {
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
                    item(if (recording) "停止记录轨迹（${trackPts.size} 点）" else "记录轨迹（后台 GPS，较省电）", recording) {
                        if (recording) TrackService.stop(ctx)
                        else withPerms(Perms.track()) { TrackService.start(ctx); Toast.makeText(ctx, "开始记录轨迹，通知栏可随时停止", Toast.LENGTH_SHORT).show() }
                    }()
                    item("沿轨迹返回", returning) {
                        if (trackPts.isEmpty()) Toast.makeText(ctx, "还没有轨迹。出发前先打开“记录轨迹”", Toast.LENGTH_LONG).show()
                        else { returning = !returning; selWp = null; selBeacon = null
                            if (returning && meLoc == null) withPerms(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) {} }
                    }()
                    item("在十字处添加航点（也可长按地图）", null) { addAt = center }()
                    item("航点列表（${waypoints.size}）", null) { showWpList = true }()
                    item("导出 GPX 到「下载」", null) {
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { runCatching { Gpx.exportToDownloads(ctx, waypoints, trackPts) } }
                            Toast.makeText(ctx, r.fold({ "已保存到 $it" }, { "导出失败：${it.message}" }), Toast.LENGTH_LONG).show()
                        }
                    }()
                    if (trackPts.isNotEmpty() && !recording) item("清除轨迹", null) {
                        confirmDel = "清除已记录的 ${trackPts.size} 个轨迹点？" to { returning = false; TrackStore.clear() }
                    }()
                    item("蓝牙求救信标…", beaconOn) { showBeacon = true }()
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
                modifier = Modifier.align(Alignment.BottomStart).padding(16.dp).padding(bottom = if (tgt != null) 96.dp else 0.dp).widthIn(max = 260.dp),
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
        } else if (tgt == null && regions.none { !MapStyle.isChart(it) }) {
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

        tgt?.let { (title, sub, to) ->
            NavPanel(
                title, sub, meLoc?.latitude, meLoc?.longitude, to.latitude, to.longitude, compass?.heading,
                Modifier.align(Alignment.BottomStart).padding(16.dp),
            ) { selWp = null; selBeacon = null; returning = false }
        }

        addAt?.let { at ->
            WaypointDialog("添加航点", "", WpType.CAMP, latLonText(at), { n, t ->
                val w = WaypointStore.add(n, t, at.latitude, at.longitude)
                selWp = w.id; selBeacon = null; returning = false; addAt = null
            }) { addAt = null }
        }
        editWp?.let { w -> WaypointDialog("编辑航点", w.name, w.type, null, { n, t -> WaypointStore.update(w.id, n, t); editWp = null }) { editWp = null } }
        if (showWpList) WaypointListDialog(
            waypoints, meLoc?.latitude, meLoc?.longitude,
            onSelect = { w ->
                selWp = w.id; selBeacon = null; returning = false; showWpList = false
                map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(w.lat, w.lon), maxOf(zoom, 13.0)))
            },
            onEdit = { editWp = it },
            onDelete = { w -> confirmDel = "删除航点「${w.name}」？" to { WaypointStore.delete(w.id); if (selWp == w.id) selWp = null } },
            onDismiss = { showWpList = false },
        )
        if (showBeacon) BeaconDialog(
            beaconOn, beaconErr, seen, meLoc?.latitude, meLoc?.longitude,
            onStart = { sos, msg ->
                Beacon.saveConfig(ctx, sos, msg)
                val bt = ctx.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter
                if (bt == null || !bt.isEnabled) Toast.makeText(ctx, "请先在系统里打开蓝牙（方舟不会自动打开）", Toast.LENGTH_LONG).show()
                else withPerms(Perms.beacon()) { BeaconService.start(ctx) }
            },
            onStop = { BeaconService.stop(ctx) },
            onPick = { b ->
                selBeacon = b.id; selWp = null; returning = false; showBeacon = false
                map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(b.lat!!, b.lon!!), maxOf(zoom, 14.0)))
            },
            onClear = { Beacon.clearSeen() },
            onDismiss = { showBeacon = false },
        )
        confirmDel?.let { (text, act) ->
            AlertDialog(
                onDismissRequest = { confirmDel = null }, title = { Text(text) },
                confirmButton = { TextButton(onClick = { confirmDel = null; act() }) { Text("确定") } },
                dismissButton = { TextButton(onClick = { confirmDel = null }) { Text("取消") } },
            )
        }
    }
}

private class Ret(val lat: Double, val lon: Double, val left: Double, val arrived: Boolean)

/** 沿轨迹返回：找离我最近的轨迹点，再往回（起点方向）取 40 米外的点作为下一个目标；离轨迹超过 60 米先回到轨迹上。 */
private fun returnTarget(pts: List<TrackPoint>, la: Double, lo: Double): Ret? {
    if (pts.isEmpty()) return null
    var bi = 0; var bd = Double.MAX_VALUE
    pts.forEachIndexed { i, p -> val d = Geo.distance(la, lo, p.lat, p.lon); if (d < bd) { bd = d; bi = i } }
    var left = bd
    for (i in bi downTo 1) left += Geo.distance(pts[i].lat, pts[i].lon, pts[i - 1].lat, pts[i - 1].lon)
    if (bi == 0 && bd < 25) return Ret(pts[0].lat, pts[0].lon, bd, true)
    if (bd > 60) return Ret(pts[bi].lat, pts[bi].lon, left, false)
    var j = bi
    while (j > 0 && Geo.distance(pts[j].lat, pts[j].lon, pts[bi].lat, pts[bi].lon) < 40) j--
    return Ret(pts[j].lat, pts[j].lon, left, false)
}

/** 添加航点 / 轨迹 / 导航线 / 信标图层（每个样式只加一次）。 */
private fun overlayInit(s: Style) {
    if (s.getSource("track") != null) return
    WpType.entries.forEach { s.addImage("wp-${it.name}", MapIcons.waypoint(it)) }
    s.addImage("bc-sos", MapIcons.beacon(true)); s.addImage("bc-ok", MapIcons.beacon(false))
    s.addSource(GeoJsonSource("track"))
    s.addLayer(LineLayer("track-line", "track").withProperties(
        PropertyFactory.lineColor("#ff6d00"), PropertyFactory.lineWidth(4f), PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
    s.addSource(GeoJsonSource("navline"))
    s.addLayer(LineLayer("navline-l", "navline").withProperties(
        PropertyFactory.lineColor("#00e5ff"), PropertyFactory.lineWidth(3f), PropertyFactory.lineDasharray(arrayOf(2f, 2f))))
    fun symbols(id: String, textColor: String) = SymbolLayer("$id-sym", id).withProperties(
        PropertyFactory.iconImage(Expression.get("icon")), PropertyFactory.iconSize(0.6f), PropertyFactory.iconAllowOverlap(true),
        PropertyFactory.textField(Expression.get("name")), PropertyFactory.textFont(arrayOf("Noto Sans Regular")), PropertyFactory.textSize(12f),
        PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP), PropertyFactory.textOffset(arrayOf(0f, 1.2f)), PropertyFactory.textOptional(true),
        PropertyFactory.textColor(textColor), PropertyFactory.textHaloColor("#000000"), PropertyFactory.textHaloWidth(1.6f))
    s.addSource(GeoJsonSource("wps")); s.addLayer(symbols("wps", "#ffffff"))
    s.addSource(GeoJsonSource("beacons")); s.addLayer(symbols("beacons", "#ffcdd2"))
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
