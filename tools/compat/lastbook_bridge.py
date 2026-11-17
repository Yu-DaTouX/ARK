#!/usr/bin/env python3
"""方舟 ARK ↔ Lastbook 地图数据互转。

两边都是 SQLite，表结构都简单，但用途不同：
  方舟：place(id,name,ascii/zh,...) + poi(id,kind,name,zh,lat,lon,tags)  —— 给 MapLibre + 离线搜索
  Lastbook：place(id,name,kind,detail,lat,lon,rank,source) + place_fts（FTS5）
            + national.sqlite 里的 poi(id,cls,name,lon,lat)            —— 给他们自己的 JS 渲染

底图不同源（他们用 feat 表里的打包几何自绘，我们用 PMTiles），这部分不转换；
地名的中文名、求助点分类、坐标都可以直接对搬。

用法：
  # 把我们的区域库导出成 Lastbook 认的两个文件（places.sqlite / national.sqlite）
  python lastbook_bridge.py to-lastbook au.db outdir/

  # 把 Lastbook 的数据目录（含 places.sqlite、national.sqlite）读成我们的区域库
  python lastbook_bridge.py from-lastbook <data_folder_or_file> out.db --region australia-nz
"""
import os
import sqlite3
import sys

# 我们的 poi.kind → Lastbook 的 poi.cls（他们用 fclass/amenity 短名）
OUR_TO_CLS = {
    "hospital": "hospital", "clinic": "clinic", "doctors": "doctors", "pharmacy": "pharmacy",
    "police": "police", "fire_station": "fire_station", "drinking_water": "drinking_water",
    "shelter": "shelter", "toilets": "toilets", "fuel": "fuel", "ranger_station": "ranger_station",
    "community_centre": "community_centre", "social_facility": "nursing_home",
    "water_point": "drinking_water", "emergency_water_tank": "drinking_water",
    "emergency_ambulance_station": "fire_station",
    "shop_supermarket": "supermarket", "shop_convenience": "convenience",
    "shop_hardware": "hardware", "shop_doityourself": "doityourself", "shop_water": "supermarket",
    "shelter_alpine_hut": "shelter", "shelter_wilderness_hut": "shelter", "shelter_camp_site": "camp_site",
}
CLS_TO_OUR = {v: k for k, v in OUR_TO_CLS.items()}
CLS_TO_OUR.update({
    "supermarket": "shop_supermarket", "convenience": "shop_convenience",
    "chemist": "pharmacy", "camp_site": "shelter_camp_site",
})


def to_lastbook(src, outdir):
    os.makedirs(outdir, exist_ok=True)
    s = sqlite3.connect(src)
    p_path = os.path.join(outdir, "places.sqlite")
    n_path = os.path.join(outdir, "national.sqlite")
    for p in (p_path, n_path):
        if os.path.exists(p):
            os.remove(p)

    places = sqlite3.connect(p_path)
    places.executescript("""
        CREATE TABLE place(id INTEGER PRIMARY KEY, name TEXT, kind TEXT, detail TEXT,
                           lat REAL, lon REAL, rank INTEGER, source TEXT);
        CREATE VIRTUAL TABLE place_fts USING fts5(name, detail, content='place', content_rowid='id', tokenize='unicode61');
        CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT);
    """)
    rows = s.execute("SELECT rowid, name, kind, zh, lat, lon, pop FROM place").fetchall()
    places.executemany(
        "INSERT INTO place(id,name,kind,detail,lat,lon,rank,source) VALUES(?,?,?,?,?,?,?,?)",
        [(r[0], r[1], r[2] or "place", r[3] or "", r[4], r[5], r[6] or 0, "ARK") for r in rows])
    places.execute("INSERT INTO place_fts(place_fts) VALUES('rebuild')")
    places.execute("INSERT INTO meta VALUES('source','ARK places.db')")
    places.commit()
    places.close()

    nat = sqlite3.connect(n_path)
    nat.executescript("""
        CREATE TABLE poi(id INTEGER PRIMARY KEY, cls TEXT, name TEXT, lon REAL, lat REAL);
        CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT);
    """)
    poi = s.execute("SELECT rowid, kind, name, zh, lat, lon FROM poi").fetchall()
    nat.executemany("INSERT INTO poi(id,cls,name,lon,lat) VALUES(?,?,?,?,?)",
                    [(r[0], OUR_TO_CLS.get(r[1], r[1]), (r[2] or r[3]), r[5], r[4]) for r in poi])
    nat.execute("INSERT INTO meta VALUES('source','ARK region db')")
    nat.commit()
    nat.close()
    print(f"导出 {len(rows)} 个地名 → {p_path}；{len(poi)} 个求助点 → {n_path}")
    s.close()


def from_lastbook(path, out, region=""):
    """path 可以是目录（含 places.sqlite / national.sqlite）或单个 SQLite 文件。"""
    files = []
    if os.path.isdir(path):
        for root, _, names in os.walk(path):
            for n in names:
                if n.endswith(".sqlite"):
                    files.append(os.path.join(root, n))
    else:
        files = [path]

    place_rows, poi_rows = [], []
    for f in files:
        try:
            c = sqlite3.connect("file:" + f + "?mode=ro", uri=True)
            tables = {r[0] for r in c.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        except sqlite3.Error:
            continue
        if "place" in tables:
            cols = {r[1] for r in c.execute("PRAGMA table_info(place)")}
            if {"name", "lat", "lon"} <= cols:
                detail = "detail" if "detail" in cols else ("zh" if "zh" in cols else "''")
                kind = "kind" if "kind" in cols else "'place'"
                rank = "rank" if "rank" in cols else ("pop" if "pop" in cols else "0")
                for i, name, k, d, la, lo, rk in c.execute(
                        f"SELECT rowid, name, {kind}, {detail}, lat, lon, {rank} FROM place"):
                    zh = d if d and any("\u4e00" <= ch <= "\u9fff" for ch in d) else ""
                    place_rows.append((f"{os.path.basename(f)}#{i}", name, zh, k, la, lo, rk or 0))
        if "poi" in tables:
            cols = {r[1] for r in c.execute("PRAGMA table_info(poi)")}
            if {"name", "lat", "lon"} <= cols:
                cls = "cls" if "cls" in cols else ("kind" if "kind" in cols else "'poi'")
                for i, nm, cl, la, lo in c.execute(f"SELECT rowid, name, {cls}, lat, lon FROM poi"):
                    poi_rows.append((f"{os.path.basename(f)}#{i}", CLS_TO_OUR.get(cl, cl), nm, "", la, lo, ""))
        c.close()

    if os.path.exists(out):
        os.remove(out)
    db = sqlite3.connect(out)
    db.executescript("""
        CREATE TABLE place(id TEXT PRIMARY KEY, name TEXT, zh TEXT, kind TEXT, lat REAL, lon REAL, pop INTEGER);
        CREATE TABLE poi(id TEXT PRIMARY KEY, kind TEXT, name TEXT, zh TEXT, lat REAL, lon REAL, tags TEXT);
        CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT);
    """)
    db.executemany("INSERT OR IGNORE INTO place VALUES(?,?,?,?,?,?,?)", place_rows)
    db.executemany("INSERT OR IGNORE INTO poi VALUES(?,?,?,?,?,?,?)", poi_rows)
    db.execute("INSERT INTO meta VALUES('region',?)", (region,))
    db.execute("INSERT INTO meta VALUES('source','Lastbook')")
    db.commit()
    print(f"读入 {len(place_rows)} 个地名、{len(poi_rows)} 个求助点 → {out}")
    db.close()


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "to-lastbook":
        to_lastbook(sys.argv[2], sys.argv[3])
    elif cmd == "from-lastbook":
        from_lastbook(sys.argv[2], sys.argv[3], sys.argv[sys.argv.index("--region") + 1] if "--region" in sys.argv else "")
    else:
        print(__doc__)
        sys.exit(1)
