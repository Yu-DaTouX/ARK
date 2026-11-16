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
 */
object MapStyle {
    const val WORLD_SAT = "pmtiles://asset://maps/world-satellite.pmtiles"
    const val WORLD_VEC = "pmtiles://asset://maps/world-z5.pmtiles"

    /** PMTiles v3 文件头第 99 字节是瓦片类型：1 矢量 / 2 png / 3 jpeg / 4 webp / 5 avif。 */
    fun tileType(f: File): Int = runCatching {
        RandomAccessFile(f, "r").use { r ->
            val b = ByteArray(127)
            r.readFully(b)
            if (String(b, 0, 7) != "PMTiles") 0 else b[99].toInt()
        }
    }.getOrDefault(0)

    fun build(regions: List<File>, satellite: Boolean): String {
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
        regions.forEachIndexed { i, f ->
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
        val textColor = if (satellite) """["interpolate",["linear"],["zoom"],6,$light,8,$dark]""" else dark
        val halo = if (satellite) """["interpolate",["linear"],["zoom"],6,"rgba(0,0,0,0.75)",8,"rgba(255,255,255,0.9)"]""" else "\"rgba(255,255,255,0.9)\""
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
        return JSONObject()
            .put("version", 8)
            .put("name", "ARK offline")
            .put("projection", JSONObject().put("type", "globe"))
            .put("glyphs", "asset://fonts/{fontstack}/{range}.pbf")
            .put("sources", sources)
            .put("layers", layers)
            .toString()
    }

    private fun fill(id: String, src: String, layer: String, color: String, opacity: String) = JSONObject(
        """{"id":"$id","type":"fill","source":"$src","source-layer":"$layer","paint":{"fill-color":"$color","fill-opacity":$opacity}}"""
    )
}
