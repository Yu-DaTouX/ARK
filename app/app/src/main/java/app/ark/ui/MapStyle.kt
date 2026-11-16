package app.ark.ui

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/**
 * 地图样式（MapLibre style JSON），全部离线：
 * - 地球仪投影（"projection": globe），缩小时看到整个地球。
 * - 卫星底图：NASA Blue Marble（公有领域）切成 z0–6，内置在 APK。
 * - 矢量地图：Protomaps（OpenStreetMap，ODbL）。内置全球 z0–5；区域详图（如澳洲 z0–12）是可选下载，放在 maps/ 目录。
 * - 放大到 6 级以后卫星图逐渐淡出、矢量地图淡入（卫星图只到 6 级，再放大会糊）。
 * - 字体：只带拉丁字母的字形；中文地名由 MapLibre 用手机自带字体在本地绘制，不用打包几十 MB 的中文字库。
 * - 海图（可开关）：GEBCO 水深分层着色（栅格）+ 等深线（矢量，带水深数字）+ OpenSeaMap 航标（矢量，z5–14）。
 *   内置 z0–5 概略版（chart-depth-z5 / chart-z5）；下载的 chart-*.pmtiles 按瓦片类型区分：栅格是水深着色，矢量是等深线和航标。
 *   数据由 data/maps/chart/ 下的脚本生成；只供应急参考，不能代替正规航海图。
 */
object MapStyle {
    const val WORLD_SAT = "pmtiles://asset://maps/world-satellite.pmtiles"
    const val WORLD_VEC = "pmtiles://asset://maps/world-z5.pmtiles"
    const val CHART_LOW = "pmtiles://asset://maps/chart-z5.pmtiles"
    const val CHART_DEPTH_LOW = "pmtiles://asset://maps/chart-depth-z5.pmtiles"

    /** 海图文件（水深 + 航标）以 chart 开头，单独作为海图图层，不当作陆地详图。 */
    fun isChart(f: File) = f.name.startsWith("chart")

    /** PMTiles v3 文件头第 99 字节是瓦片类型：1 矢量 / 2 png / 3 jpeg / 4 webp / 5 avif。 */
    fun tileType(f: File): Int = runCatching {
        RandomAccessFile(f, "r").use { r ->
            val b = ByteArray(127)
            r.readFully(b)
            if (String(b, 0, 7) != "PMTiles") 0 else b[99].toInt()
        }
    }.getOrDefault(0)

    fun build(regions: List<File>, satellite: Boolean, chart: Boolean = false): String {
        val sources = JSONObject()
            .put("sat", JSONObject().put("type", "raster").put("url", WORLD_SAT).put("tileSize", 256))
            .put("world", JSONObject().put("type", "vector").put("url", WORLD_VEC))
        val layers = JSONArray()
        layers.put(JSONObject("""{"id":"bg","type":"background","paint":{"background-color":"#0b1a2a"}}"""))
        layers.put(
            JSONObject(
                """{"id":"sat","type":"raster","source":"sat","paint":{"raster-opacity":
                ["interpolate",["linear"],["zoom"],6,1,8.5,${if (satellite) 0.35 else 0}],"raster-fade-duration":0}}"""
            )
        )
        val vec = mutableListOf("world")
        val charts = mutableListOf<String>()      // 矢量：等深线 + 航标
        val depths = mutableListOf<String>()      // 栅格：水深着色
        if (chart) {
            sources.put("chart", JSONObject().put("type", "vector").put("url", CHART_LOW)); charts += "chart"
            sources.put("chartd", JSONObject().put("type", "raster").put("url", CHART_DEPTH_LOW).put("tileSize", 256)); depths += "chartd"
            regions.filter(::isChart).forEachIndexed { i, f ->
                val url = "pmtiles://file://${f.path}"
                if (tileType(f) == 1) { sources.put("chart$i", JSONObject().put("type", "vector").put("url", url)); charts += "chart$i" }
                else { sources.put("chartd$i", JSONObject().put("type", "raster").put("url", url).put("tileSize", 256)); depths += "chartd$i" }
            }
        }
        regions.filterNot(::isChart).forEachIndexed { i, f ->
            val id = "r$i"
            when (tileType(f)) {
                1 -> { sources.put(id, JSONObject().put("type", "vector").put("url", "pmtiles://file://${f.path}")); vec += id }
                2, 3, 4, 5 -> {
                    // 区域卫星图（栅格）：放在卫星底图之上
                    sources.put(id, JSONObject().put("type", "raster").put("url", "pmtiles://file://${f.path}").put("tileSize", 256))
                    layers.put(JSONObject().put("id", id).put("type", "raster").put("source", id))
                }
            }
        }
        // 先画所有面（各数据源），再画线，最后画文字，避免区域详图把全球图的文字盖住
        val fade = if (satellite) """["interpolate",["linear"],["zoom"],5.5,0,8,1]""" else "1"
        val waterFade = if (satellite) """["interpolate",["linear"],["zoom"],5.5,0,8,1]""" else "1"
        for (s in vec) {
            layers.put(fill("$s-earth", s, "earth", "#efebe0", fade))
            layers.put(
                JSONObject(
                    """{"id":"$s-landcover","type":"fill","source":"$s","source-layer":"landcover",
                    "paint":{"fill-color":["match",["get","kind"],["forest","wood"],"#cfe3c4",["grassland","scrub","farmland"],"#e3ead2",
                    ["barren","sand","desert"],"#f0e6cc",["glacier","ice"],"#f6fafc","#e6ebd8"],"fill-opacity":$fade}}"""
                )
            )
            layers.put(
                JSONObject(
                    """{"id":"$s-landuse","type":"fill","source":"$s","source-layer":"landuse","minzoom":9,
                    "paint":{"fill-color":["match",["get","kind"],["park","nature_reserve","national_park","forest","wood"],"#cfe3c4",
                    ["hospital"],"#f4d9d9",["residential"],"#ebe6dc",["industrial"],"#e5e0ea","#e9e6dc"],"fill-opacity":$fade}}"""
                )
            )
            layers.put(
                JSONObject(
                    """{"id":"$s-water","type":"fill","source":"$s","source-layer":"water","filter":["==",["geometry-type"],"Polygon"],
                    "paint":{"fill-color":"#a7cbe0","fill-opacity":$waterFade}}"""
                )
            )
        }
        // 海图：水深分层盖在海面上（卫星模式也盖住），再画等深线
        for (d in depths) {
            layers.put(JSONObject("""{"id":"$d","type":"raster","source":"$d","paint":{"raster-opacity":0.92,"raster-fade-duration":0}}"""))
        }
        for (c in charts) {
            layers.put(JSONObject("""{"id":"$c-contour","type":"line","source":"$c","source-layer":"contour",
                "paint":{"line-color":"#2f6ea3","line-opacity":0.7,"line-width":["interpolate",["linear"],["zoom"],3,0.3,10,1.0]}}"""))
        }
        for (s in vec) {
            layers.put(
                JSONObject(
                    """{"id":"$s-river","type":"line","source":"$s","source-layer":"water","minzoom":7,"filter":["==",["geometry-type"],"LineString"],
                    "paint":{"line-color":"#8fbcd6","line-width":["interpolate",["linear"],["zoom"],8,0.6,14,2.5]}}"""
                )
            )
            layers.put(
                JSONObject(
                    """{"id":"$s-roads","type":"line","source":"$s","source-layer":"roads","minzoom":5,
                    "filter":["!",["in",["get","kind"],["literal",["path","other"]]]],
                    "layout":{"line-cap":"round","line-join":"round"},
                    "paint":{"line-color":["match",["get","kind"],"highway","#f2a65a","major_road","#f6d27a","#ffffff"],
                    "line-width":["interpolate",["exponential",1.6],["zoom"],5,["match",["get","kind"],"highway",0.8,0.3],
                    14,["match",["get","kind"],"highway",6,"major_road",4,2],18,["match",["get","kind"],"highway",24,"major_road",16,9]]}}"""
                )
            )
            layers.put(
                JSONObject(
                    """{"id":"$s-tracks","type":"line","source":"$s","source-layer":"roads","minzoom":12,
                    "filter":["==",["get","kind"],"path"],
                    "paint":{"line-color":"#9a7b54","line-dasharray":[2,1.5],"line-width":["interpolate",["linear"],["zoom"],12,0.6,16,1.6]}}"""
                )
            )
            // 虚线不支持按数据变化，国界（实线）和州界（虚线）分两层
            layers.put(
                JSONObject(
                    """{"id":"$s-boundary-state","type":"line","source":"$s","source-layer":"boundaries",
                    "filter":["all",[">",["get","kind_detail"],2],["<=",["get","kind_detail"],4]],
                    "paint":{"line-color":["interpolate",["linear"],["zoom"],5,"#f5f1e6",8,"#8a7fa6"],"line-dasharray":[3,2],"line-width":0.7}}"""
                )
            )
            layers.put(
                JSONObject(
                    """{"id":"$s-boundary","type":"line","source":"$s","source-layer":"boundaries",
                    "filter":["<=",["get","kind_detail"],2],
                    "paint":{"line-color":["interpolate",["linear"],["zoom"],5,"#f5f1e6",8,"#8a7fa6"],"line-width":1.2}}"""
                )
            )
        }
        // 文字：低缩放（压在卫星图上）白字黑边，高缩放深字白边
        val dark = """["match",["get","kind"],"region","#7a6f60","#2b2b2b"]"""
        val light = """["match",["get","kind"],"region","#e8e0cc","#ffffff"]"""
        // 用 step 一次切换：渐变到中间那几级时，字和描边会是同一种灰，糊成一团
        val textColor = if (satellite) """["step",["zoom"],$light,7,$dark]""" else dark
        val halo = if (satellite) """["step",["zoom"],"rgba(0,0,0,0.75)",7,"rgba(255,255,255,0.9)"]""" else "\"rgba(255,255,255,0.9)\""
        for (s in vec) {
            layers.put(
                JSONObject(
                    """{"id":"$s-roadname","type":"symbol","source":"$s","source-layer":"roads","minzoom":13,
                    "layout":{"symbol-placement":"line","text-field":["get","name"],"text-font":["Noto Sans Regular"],"text-size":12},
                    "paint":{"text-color":"#3c3c3c","text-halo-color":"#ffffff","text-halo-width":1.5}}"""
                )
            )
            layers.put(
                JSONObject(
                    """{"id":"$s-places","type":"symbol","source":"$s","source-layer":"places",
                    "filter":["in",["get","kind"],["literal",["country","region","locality"]]],
                    "layout":{
                      "text-field":["case",["==",["get","kind"],"country"],["coalesce",["get","name:zh-Hans"],["get","name:zh"],["get","name"]],["get","name"]],
                      "text-font":["Noto Sans Medium"],
                      "text-size":["interpolate",["linear"],["zoom"],4,["match",["get","kind"],"country",14,"region",11,10],
                        12,["match",["get","kind"],"country",16,"region",13,15]],
                      "text-transform":["match",["get","kind"],"region","uppercase","none"],
                      "symbol-sort-key":["get","min_zoom"],"text-padding":4,"text-max-width":8},
                    "paint":{"text-color":$textColor,
                      "text-halo-color":$halo,"text-halo-width":1.4}}"""
                )
            )
        }
        for (c in charts) {
            layers.put(JSONObject("""{"id":"$c-contour-label","type":"symbol","source":"$c","source-layer":"contour","minzoom":6,
                "layout":{"symbol-placement":"line","text-field":["to-string",["get","d"]],"text-font":["Noto Sans Regular"],"text-size":10},
                "paint":{"text-color":"#1d4f7a","text-halo-color":"rgba(255,255,255,0.8)","text-halo-width":1.2}}"""))
            // 有灯的航标先画一圈黄色光晕
            layers.put(JSONObject("""{"id":"$c-light","type":"circle","source":"$c","source-layer":"seamark","filter":["has","l"],
                "paint":{"circle-color":"#ffd600","circle-opacity":0.45,"circle-radius":["interpolate",["linear"],["zoom"],5,4,12,9,16,14]}}"""))
            layers.put(JSONObject("""{"id":"$c-seamark","type":"circle","source":"$c","source-layer":"seamark",
                "paint":{"circle-color":$SEAMARK_COLOR,"circle-stroke-color":"#212121","circle-stroke-width":1,
                  "circle-radius":["interpolate",["linear"],["zoom"],5,2,12,4.5,16,7]}}"""))
            layers.put(JSONObject("""{"id":"$c-seamark-label","type":"symbol","source":"$c","source-layer":"seamark","minzoom":8,
                "layout":{"text-field":["case",["all",["has","n"],["has","l"]],["concat",["get","n"],"\n",["get","l"]],["coalesce",["get","n"],["get","l"],""]],
                  "text-font":["Noto Sans Regular"],"text-size":11,"text-anchor":"top","text-offset":[0,0.8],"text-optional":true,"text-max-width":10},
                "paint":{"text-color":"#3e2723","text-halo-color":"rgba(255,255,255,0.9)","text-halo-width":1.4}}"""))
        }
        return JSONObject()
            .put("version", 8)
            .put("name", "ARK offline")
            .put("projection", JSONObject().put("type", "globe"))
            .put("glyphs", "asset://fonts/{fontstack}/{range}.pbf")
            .put("sources", sources)
            .put("layers", layers)
            .toString()
    }

    /** 航标颜色：按标记颜色（红绿侧面标、黄色专用标等）；没写颜色的灯用黄色，沉船礁石棕色，港口锚地蓝色。 */
    private const val SEAMARK_COLOR = """["match",["get","c"],"red","#e53935","green","#2e7d32","yellow","#fbc02d","black","#212121",
        "white","#fafafa","orange","#fb8c00",["case",["has","l"],"#ffd600",
        ["match",["get","t"],["wreck","rock","obstruction"],"#6d4c41",["harbour","anchorage","small_craft_facility","mooring"],"#1565c0","#9e9e9e"]]]"""

    private fun fill(id: String, src: String, layer: String, color: String, opacity: String) = JSONObject(
        """{"id":"$id","type":"fill","source":"$src","source-layer":"$layer","paint":{"fill-color":"$color","fill-opacity":$opacity}}"""
    )
}
