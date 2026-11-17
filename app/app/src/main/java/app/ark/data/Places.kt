/*
 * Places.kt —— 离线地名搜索和“附近求助点”。
 *
 * 数据：两个来源，都是 SQLite，见 data/maps/places/README.md
 *   places/global.db    GeoNames 全球 23.7 万个居民点（4.2 万个有中文名），随 APK 内置，首次启动拷出来
 *   places/<区域>.db    OSM 区域提取：更细的地名 + 医院/药店/警局/消防/取水点/营地/超市（按区域下载）
 * 两个库表结构一致：place(id,name,zh,kind,lat,lon,pop[+ascii,cc,admin1])、poi(id,kind,name,zh,lat,lon,tags)
 *
 * 线程：init / search / nearby 都是纯 SQLite 读，须在 IO 线程调用（调用方用 Dispatchers.IO）。
 */
package app.ark.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object Places {
    /** 一条搜索结果。region 是它来自哪个库（"global" 或区域名），界面上用来判断能不能显示求助点。 */
    data class Hit(val name: String, val zh: String, val kind: String, val lat: Double, val lon: Double,
                   val pop: Int, val region: String) {
        val label: String get() = if (zh.isNotBlank()) "$zh（$name）" else name
    }

    /** 附近的一个求助点；distM 是到中心点的直线距离。 */
    data class Poi(val kind: String, val name: String, val zh: String, val lat: Double, val lon: Double,
                   val tags: String, val distM: Double) {
        val label: String get() = if (zh.isNotBlank()) zh else name
    }

    /** 求助点分类在界面上的名字和显示顺序。 */
    val KIND_LABELS = listOf(
        "hospital" to "医院", "clinic" to "诊所", "doctors" to "医生", "pharmacy" to "药店",
        "police" to "警局", "fire_station" to "消防站", "emergency_ambulance_station" to "急救站",
        "drinking_water" to "饮水点", "water_point" to "取水点", "emergency_water_tank" to "储水罐",
        "shelter" to "避难所", "shelter_camp_site" to "营地", "shelter_wilderness_hut" to "野外小屋",
        "shelter_alpine_hut" to "山屋", "fuel" to "加油站", "toilets" to "厕所",
        "shop_supermarket" to "超市", "shop_convenience" to "便利店", "ranger_station" to "护林站",
        "community_centre" to "社区中心", "social_facility" to "福利机构",
    )
    val KEY_KINDS = listOf(
        "hospital", "clinic", "doctors", "pharmacy", "police", "fire_station", "emergency_ambulance_station",
        "drinking_water", "water_point", "emergency_water_tank", "shelter", "shelter_camp_site",
        "shelter_wilderness_hut", "shelter_alpine_hut", "fuel", "toilets", "shop_supermarket", "shop_convenience",
    )

    fun kindLabel(kind: String) = KIND_LABELS.firstOrNull { it.first == kind }?.second ?: kind

    @Volatile private var global: SQLiteDatabase? = null
    @Volatile private var regions: List<Pair<String, SQLiteDatabase>> = emptyList()
    @Volatile var globalError: String? = null

    /** 数据目录：Android/data/app.ark.offline/files/places */
    fun dir(ctx: Context): File = File(Storage(ctx).root, "places").apply { mkdirs() }
    private fun storage(ctx: Context) = Storage(ctx)

    /**
     * 打开全球库并把所有区域库挂上。可以重复调用（换区域库后刷新）。
     * 全球库第一次从 assets 拷到 places/global.db；App 更新后重拷（和资料包同一套判断）。
     */
    fun init(ctx: Context) {
        val d = dir(ctx)
        val g = File(d, "global.db")
        runCatching {
            val updated = ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime
            if (!g.exists() || g.lastModified() < updated) {
                val tmp = File(d, "global.db.inst.part")
                ctx.assets.open("places.db").use { ins -> tmp.outputStream().use { outs -> ins.copyTo(outs) } }
                if (!tmp.renameTo(g)) { g.delete(); tmp.renameTo(g) }
            }
        }.onFailure { globalError = "内置地名库安装失败：${it.message}" }
        global?.close()
        global = runCatching { SQLiteDatabase.openDatabase(g.path, null, SQLiteDatabase.OPEN_READONLY) }
            .onFailure { globalError = "地名库打不开：${it.message}" }.getOrNull()
        regions.forEach { it.second.close() }
        regions = d.listFiles { f -> f.isFile && f.name.endsWith(".db") && f.name != "global.db" }
            .orEmpty().sortedBy { it.name }
            .mapNotNull { f -> runCatching { f.name.removeSuffix(".db") to SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY) }.getOrNull() }
    }

    /** 已安装的区域库名字（如 "australia-nz"）。 */
    fun regions(): List<String> = synchronized(this) { regions.map { it.first } }

    /** 搜索地名。先查所有区域库（更细），再查全球库；前缀命中的排在包含命中的前面，同级按人口。 */
    fun search(q: String, limit: Int = 24): List<Hit> {
        val s = q.trim()
        if (s.length < 1) return emptyList()
        val like = "%" + s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val prefix = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val out = ArrayList<Hit>()
        fun one(db: SQLiteDatabase?, region: String) {
            if (db == null) return
            val cols = runCatching {
                db.rawQuery("SELECT * FROM place LIMIT 1", null).use { c -> c?.columnNames?.toSet() ?: emptySet() }
            }.getOrDefault(emptySet())
            val hasAscii = "ascii" in cols
            val sql = if (hasAscii)
                "SELECT name, zh, kind, lat, lon, pop FROM place WHERE (name LIKE ? ESCAPE '\\' OR ascii LIKE ? ESCAPE '\\' OR zh LIKE ? ESCAPE '\\') LIMIT 200"
            else
                "SELECT name, zh, kind, lat, lon, pop FROM place WHERE (name LIKE ? ESCAPE '\\' OR zh LIKE ? ESCAPE '\\') LIMIT 200"
            val args: Array<String> = if (hasAscii) arrayOf(like, like, like) else arrayOf(like, like)
            runCatching {
                db.rawQuery(sql, args).use { c ->
                    while (c != null && c.moveToNext()) {
                        out += Hit(c.getString(0) ?: "", c.getString(1) ?: "", c.getString(2) ?: "",
                            c.getDouble(3), c.getDouble(4), c.getInt(5), region)
                    }
                }
            }
        }
        synchronized(this) {
            regions.forEach { (name, db) -> one(db, name) }
            one(global, "global")
        }
        val ql = s.lowercase()
        return out.distinctBy { Triple(it.name, it.zh, it.region) }
            .sortedWith(compareBy<Hit> {
                when {
                    it.zh.lowercase().startsWith(ql) || it.name.lowercase().startsWith(ql) -> 0
                    it.zh.lowercase().contains(ql) || it.name.lowercase().contains(ql) -> 1
                    else -> 2
                }
            }.thenByDescending { kindRank(it.kind) }.thenByDescending { it.pop })
            .take(limit)
    }

    /** 居民点等级：城市优先，其次是镇、村，最后是农场、孤立住户这类。 */
    private fun kindRank(kind: String) = when (kind) {
        "city" -> 5; "town" -> 4; "village" -> 3; "suburb", "quarter", "neighbourhood" -> 2
        "hamlet" -> 1; else -> 0
    }

    /**
     * 中心点附近 [radiusKm] 内的求助点。kinds 为空表示所有分类。
     * 用 lat/lon 的矩形先粗筛（区域库有 (lat,lon) 索引），再按真实距离过滤和排序。
     */
    fun nearby(lat: Double, lon: Double, radiusKm: Double = 30.0, kinds: Set<String> = emptySet(), limit: Int = 40): List<Poi> {
        val dLat = radiusKm / 111.0
        val dLon = radiusKm / (111.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.05))
        val out = ArrayList<Poi>()
        synchronized(this) {
            for ((_, db) in regions) {
                val sql = buildString {
                    append("SELECT kind, name, zh, lat, lon, tags FROM poi WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?")
                    if (kinds.isNotEmpty()) append(" AND kind IN (").append(kinds.joinToString(",") { "?" }).append(")")
                }
                val args: Array<String> = (listOf(lat - dLat, lat + dLat, lon - dLon, lon + dLon).map { it.toString() } + kinds.toList()).toTypedArray()
                runCatching {
                    db.rawQuery(sql, args).use { c ->
                        while (c != null && c.moveToNext()) {
                            val la = c.getDouble(3); val lo = c.getDouble(4)
                            val dm = distanceM(lat, lon, la, lo)
                            if (dm <= radiusKm * 1000) out += Poi(c.getString(0) ?: "", c.getString(1) ?: "",
                                c.getString(2) ?: "", la, lo, c.getString(5) ?: "", dm)
                        }
                    }
                }
            }
        }
        return out.sortedBy { it.distM }.take(limit)
    }

    /** 两点间直线距离（米，球面近似）。 */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** 方位角（度，正北 0，顺时针）和八方位中文名。 */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360
    }

    fun dirName(deg: Double): String {
        val names = arrayOf("北", "东北", "东", "东南", "南", "西南", "西", "西北")
        return names[(((deg + 22.5) % 360) / 45).toInt().coerceIn(0, 7)]
    }

    /** 距离的易读写法：<1 km 用米，否则用公里（保留一位）。 */
    fun distText(m: Double) = if (m < 1000) "${m.toInt()} m" else String.format("%.1f km", m / 1000)
}
