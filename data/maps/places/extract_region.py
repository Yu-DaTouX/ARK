#!/usr/bin/env python3
"""从一个 OSM PBF 区域文件里提取地名、求助点、街道，生成可放进 App 的 SQLite。

和 count_osm.py 的区别：这里真的写库，并且只保留有名字或坐标有用的东西。

用法：
  python extract_region.py <file.osm.pbf> <out.db> --region australia-nz

输出表：
  place(id TEXT PRIMARY KEY, name, zh, kind, lat, lon, pop)      居民点（含岛屿、小村、农场）
  poi(id TEXT PRIMARY KEY, kind, name, zh, lat, lon, tags)       医院/药店/警局/消防/取水点/避难所/加油站…
  street(id TEXT PRIMARY KEY, name, zh, lat, lon)                有名街道（取中心点，用于“找街道”）
  meta(k, v)

坐标都用中位数（所在区域的重心），足够用来“跳过去看”。
"""
import os
import sqlite3
import sys

import osmium

PLACE = {
    "city", "town", "village", "hamlet", "suburb", "quarter", "neighbourhood",
    "locality", "isolated_dwelling", "farm", "island", "islet", "archipelago",
}
AMENITY = {
    "hospital", "clinic", "doctors", "pharmacy", "police", "fire_station",
    "drinking_water", "shelter", "toilets", "fuel", "ranger_station",
    "community_centre", "social_facility", "water_point", "spring",
}
SHOP = {"supermarket", "convenience", "hardware", "doityourself", "water"}


def zh_name(t):
    return t.get("name:zh") or t.get("name:zh-Hans") or t.get("name:zh-Hant") or ""


def poi_kind(t):
    a = t.get("amenity")
    if a in AMENITY:
        # amenity=shelter 在澳洲大量是公交候车亭，不是避难所
        if a == "shelter" and t.get("shelter_type") in ("public_transport", "bus_stop", "weather_shelter"):
            if t.get("shelter_type") != "weather_shelter":
                return None
        return a
    e = t.get("emergency")
    if e in ("ambulance_station", "water_tank"):
        return "emergency_" + e
    s = t.get("shop")
    if s in SHOP:
        return "shop_" + s
    t2 = t.get("tourism")
    if t2 in ("alpine_hut", "wilderness_hut", "camp_site"):
        return "shelter_" + t2
    return None


class Extract(osmium.SimpleHandler):
    def __init__(self, db):
        super().__init__()
        self.db = db
        self.places, self.pois, self.streets = [], [], []
        self.poi_seen = set()

    def node(self, n):
        t = n.tags
        if not t:
            return
        name = t.get("name") or zh_name(t)
        if not name:
            return
        p = t.get("place")
        if p in PLACE:
            pop = t.get("population") or ""
            pop = int(pop) if pop.isdigit() else 0
            self.places.append((f"n{n.id}", name, zh_name(t), p, round(n.location.lat, 5),
                                round(n.location.lon, 5), pop))
            return
        k = poi_kind(t)
        if k and n.id not in self.poi_seen:
            self.poi_seen.add(n.id)
            tags = []
            for key in ("operator", "phone", "opening_hours", "addr:street", "addr:housenumber",
                        "emergency", "healthcare", "drinking_water", "description"):
                if t.get(key):
                    tags.append(f"{key}={t.get(key)}")
            self.pois.append((f"n{n.id}", k, name, zh_name(t), round(n.location.lat, 5),
                              round(n.location.lon, 5), "; ".join(tags)[:300]))

    def area(self, a):
        t = a.tags
        if not t:
            return
        name = t.get("name") or zh_name(t)
        k = poi_kind(t) if t else None
        if name and k:
            try:
                c = a.geometry().centroid()
            except Exception:
                return
            aid = f"a{a.id}"
            if aid not in self.poi_seen:
                self.poi_seen.add(aid)
                self.pois.append((aid, k, name, zh_name(t), round(c.lat, 5), round(c.lon, 5), ""))

    def way(self, w):
        t = w.tags
        if not t:
            return
        if t.get("highway") and t.get("name"):
            self.streets.append((f"w{w.id}", t.get("name"), zh_name(t)))
        k = poi_kind(t)
        name = t.get("name") or zh_name(t)
        if k and name and w.id not in self.poi_seen:
            self.poi_seen.add(w.id)
            self.pois.append((f"w{w.id}", k, name, zh_name(t), None, None, ""))


def main():
    src, out = sys.argv[1], sys.argv[2]
    region = sys.argv[sys.argv.index("--region") + 1] if "--region" in sys.argv else ""

    if os.path.exists(out):
        os.remove(out)
    db = sqlite3.connect(out)
    db.executescript("""
        PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF;
        CREATE TABLE place(id TEXT PRIMARY KEY, name TEXT, zh TEXT, kind TEXT, lat REAL, lon REAL, pop INTEGER);
        CREATE TABLE poi(id TEXT PRIMARY KEY, kind TEXT, name TEXT, zh TEXT, lat REAL, lon REAL, tags TEXT);
        CREATE TABLE street(id TEXT PRIMARY KEY, name TEXT, zh TEXT, lat REAL, lon REAL);
        CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT);
    """)
    h = Extract(db)
    h.apply_file(src, locations=True)
    db.executemany("INSERT OR IGNORE INTO place VALUES(?,?,?,?,?,?,?)", h.places)
    # 没有坐标的面状 POI 先不入库（面积中心需要额外一遍几何计算，对“附近医院”用处不大）
    db.executemany("INSERT OR IGNORE INTO poi VALUES(?,?,?,?,?,?,?)",
                   [p for p in h.pois if p[4] is not None])
    db.execute("INSERT INTO meta(k,v) VALUES('region',?)", (region,))
    db.execute("INSERT INTO meta(k,v) VALUES('place_count',?)", (str(len(h.places)),))
    db.commit()
    n_poi = db.execute("SELECT count(*) FROM poi").fetchone()[0]
    print(f"{region}: 地名 {len(h.places)}，求助点 {n_poi}，街道 {len(h.streets)}（街道暂不入库）")
    db.execute("VACUUM")
    db.close()
    print(f"写出 {out}")


if __name__ == "__main__":
    main()
