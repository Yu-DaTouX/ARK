# 离线地名与求助点（places）

给地图页的离线搜索和“附近求助点”提供数据。两条来源，互相补充：

| 来源 | 覆盖 | 体积 | 用途 |
|---|---|---|---|
| GeoNames `cities500` + `alternateNamesV2` | 全球 23.7 万个居民点，其中 4.2 万个有中文名 | `places.db` 30 MB | **内置**，任何地方都能搜到城镇 |
| OSM PBF（Geofabrik 区域提取） | 每个区域的全部居民点（含小村、农场、岛屿）、有名字的医院/药店/警局/消防/取水点/避难所/加油站/超市，以及街道名和门牌号 | 澳洲 30,439 地名 + 29,179 求助点 = **6 MB** | **按区域下载**，比 GeoNames 细 6 倍，还带求助点 |

对比（澳大利亚）：

| | GeoNames | OSM 提取 |
|---|---|---|
| 居民点 | 4,902 | 30,439 |
| 带中文名 | 194 | 605 |
| 医院/诊所/药店/警局/消防/取水点 | 0 | 6,500+ |
| 街道名 / 门牌号 | — | 137 万 / 432 万（暂不入库） |

生成命令：

```bash
# 全球基础库（一次）
curl -O https://download.geonames.org/export/dump/cities500.zip
curl -O https://download.geonames.org/export/dump/alternateNamesV2.zip
unzip -o cities500.zip
python build_places.py                      # → places.db

# 区域库（每个区域一次，需要该区域的 OSM PBF）
curl -L -o pbf/australia.osm.pbf https://download.geofabrik.de/australia-oceania/australia-latest.osm.pbf
python extract_region.py pbf/australia.osm.pbf au.db --region australia-nz
python count_osm.py pbf/australia.osm.pbf   # 只看统计

# 和 Lastbook 互转（见 tools/compat/lastbook_bridge.py）
python ../../../tools/compat/lastbook_bridge.py to-lastbook au.db out/
python ../../../tools/compat/lastbook_bridge.py from-lastbook <lastbook 数据目录> out.db --region australia-nz
```

字段：`place(id,name,zh,kind,lat,lon,pop)`、`poi(id,kind,name,zh,lat,lon,tags)`；
`kind` 用 OSM 的 `place=*` / `amenity=*` / `shop=*` 值（`shelter_camp_site`、`shop_supermarket` 这类前缀是自己加的）。

注意：`amenity=shelter` 在澳洲大量是公交候车亭，提取时按 `shelter_type` 过滤掉了，只留真正的避难所和营地。

数据许可：GeoNames CC BY 4.0；OSM 数据 © OpenStreetMap contributors，ODbL。
