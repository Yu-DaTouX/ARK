#!/usr/bin/env python3
"""生成 places.db：全球地名索引，供 App 地图页离线搜索。

输入（放在本目录）：
  cities500.txt              GeoNames cities500（约 23.7 万个居民点，CC BY 4.0）
  alternateNamesV2.txt       GeoNames 别名（用于取中文名）

输出：places.db（SQLite）
  place(id, name, ascii, zh, lat, lon, kind, pop, cc, admin1)
    kind 用 GeoNames feature code：PPLC 首都 / PPLA 省府 / PPL 城镇 / PPLL 村 …
    zh 是首选中文名，没有则为空
  country(cc, name)           国家代码 → 名称（英文 + 中文）
  meta(k, v)

用法：python build_places.py [--out places.db]
"""
import os
import sqlite3
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ZH = {"zh", "zh-CN", "zh-TW", "zh-Hans", "zh-Hant", "yue", "cmn", "wuu"}

# 常用国家代码的中文名（只给 App 显示用，不求全）
CC_ZH = {
    "CN": "中国", "AU": "澳大利亚", "NZ": "新西兰", "JP": "日本", "KR": "韩国", "KP": "朝鲜",
    "US": "美国", "CA": "加拿大", "MX": "墨西哥", "GB": "英国", "IE": "爱尔兰", "FR": "法国",
    "DE": "德国", "IT": "意大利", "ES": "西班牙", "PT": "葡萄牙", "NL": "荷兰", "BE": "比利时",
    "CH": "瑞士", "AT": "奥地利", "SE": "瑞典", "NO": "挪威", "FI": "芬兰", "DK": "丹麦",
    "PL": "波兰", "CZ": "捷克", "SK": "斯洛伐克", "HU": "匈牙利", "RO": "罗马尼亚", "BG": "保加利亚",
    "GR": "希腊", "TR": "土耳其", "RU": "俄罗斯", "UA": "乌克兰", "BY": "白俄罗斯", "KZ": "哈萨克斯坦",
    "UZ": "乌兹别克斯坦", "KG": "吉尔吉斯斯坦", "TJ": "塔吉克斯坦", "TM": "土库曼斯坦", "MN": "蒙古",
    "IN": "印度", "PK": "巴基斯坦", "BD": "孟加拉国", "NP": "尼泊尔", "BT": "不丹", "LK": "斯里兰卡",
    "MM": "缅甸", "TH": "泰国", "LA": "老挝", "VN": "越南", "KH": "柬埔寨", "MY": "马来西亚",
    "SG": "新加坡", "ID": "印度尼西亚", "PH": "菲律宾", "BN": "文莱", "TL": "东帝汶",
    "IR": "伊朗", "IQ": "伊拉克", "SY": "叙利亚", "SA": "沙特阿拉伯", "AE": "阿联酋", "QA": "卡塔尔",
    "KW": "科威特", "OM": "阿曼", "YE": "也门", "JO": "约旦", "LB": "黎巴嫩", "IL": "以色列",
    "PS": "巴勒斯坦", "EG": "埃及", "LY": "利比亚", "TN": "突尼斯", "DZ": "阿尔及利亚", "MA": "摩洛哥",
    "SD": "苏丹", "ET": "埃塞俄比亚", "KE": "肯尼亚", "TZ": "坦桑尼亚", "UG": "乌干达", "NG": "尼日利亚",
    "GH": "加纳", "ZA": "南非", "ZW": "津巴布韦", "ZM": "赞比亚", "MZ": "莫桑比克", "AO": "安哥拉",
    "MG": "马达加斯加", "BR": "巴西", "AR": "阿根廷", "CL": "智利", "PE": "秘鲁", "CO": "哥伦比亚",
    "VE": "委内瑞拉", "EC": "厄瓜多尔", "BO": "玻利维亚", "PY": "巴拉圭", "UY": "乌拉圭", "CU": "古巴",
    "GT": "危地马拉", "HN": "洪都拉斯", "SV": "萨尔瓦多", "NI": "尼加拉瓜", "CR": "哥斯达黎加",
    "PA": "巴拿马", "DO": "多米尼加", "HT": "海地", "JM": "牙买加", "IS": "冰岛", "HR": "克罗地亚",
    "RS": "塞尔维亚", "BA": "波黑", "SI": "斯洛文尼亚", "AL": "阿尔巴尼亚", "MK": "北马其顿",
    "ME": "黑山", "LT": "立陶宛", "LV": "拉脱维亚", "EE": "爱沙尼亚", "GE": "格鲁吉亚", "AM": "亚美尼亚",
    "AZ": "阿塞拜疆", "AF": "阿富汗", "FJ": "斐济", "PG": "巴布亚新几内亚", "SB": "所罗门群岛",
    "VU": "瓦努阿图", "NC": "新喀里多尼亚", "PF": "法属波利尼西亚", "GL": "格陵兰",
}


def read_cities(path):
    rows = []
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            c = line.rstrip("\n").split("\t")
            if len(c) < 19:
                continue
            try:
                gid = int(c[0])
                lat, lon = float(c[4]), float(c[5])
            except ValueError:
                continue
            pop = int(c[14]) if c[14].isdigit() else 0
            rows.append((gid, c[1], c[2], lat, lon, c[7], pop, c[8], c[10]))
    return rows


def read_zh(path, wanted):
    """geonameid → 中文名。优先 isPreferredName，其次短的。"""
    best = {}
    if path.endswith(".zip"):
        zf = zipfile.ZipFile(path)
        cand = zf.namelist()
        alt = [n for n in cand if 'alternateNames' in n and n.endswith('.txt')]
        name = alt[0] if alt else cand[0]
        stream = zf.open(name)
        import io
        f = io.TextIOWrapper(stream, encoding="utf-8", errors="replace")
    else:
        f = open(path, encoding="utf-8", errors="replace")
    with f:
        for line in f:
            c = line.rstrip("\n").split("\t")
            if len(c) < 4 or c[2] not in ZH:
                continue
            try:
                gid = int(c[1])
            except ValueError:
                continue
            if gid not in wanted:
                continue
            nm = c[3].strip()
            if not nm or not any("\u4e00" <= ch <= "\u9fff" for ch in nm):
                continue
            pref = c[4] == "1" if len(c) > 4 else False
            old = best.get(gid)
            if old is None or (pref and not old[0]) or (pref == old[0] and len(nm) < len(old[1])):
                best[gid] = (pref, nm)
    return {k: v[1] for k, v in best.items()}


def main():
    out = sys.argv[sys.argv.index("--out") + 1] if "--out" in sys.argv else os.path.join(HERE, "places.db")
    cities = os.path.join(HERE, "cities500.txt")
    alts = os.path.join(HERE, "alternateNamesV2.zip")
    if not os.path.exists(alts):
        alts = os.path.join(HERE, "alternateNamesV2.txt")
    rows = read_cities(cities)
    wanted = {r[0] for r in rows}
    print(f"{len(rows)} 个居民点")
    zh = read_zh(alts, wanted)
    print(f"其中 {len(zh)} 个有中文名")

    if os.path.exists(out):
        os.remove(out)
    db = sqlite3.connect(out)
    db.executescript("""
        PRAGMA journal_mode=OFF;
        PRAGMA page_size=4096;
        CREATE TABLE place(id INTEGER PRIMARY KEY, name TEXT NOT NULL, ascii TEXT, zh TEXT,
                           lat REAL NOT NULL, lon REAL NOT NULL, kind TEXT, pop INTEGER, cc TEXT, admin1 TEXT);
        CREATE TABLE country(cc TEXT PRIMARY KEY, name TEXT, zh TEXT);
        CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT);
    """)
    db.executemany(
        "INSERT INTO place(id,name,ascii,zh,lat,lon,kind,pop,cc,admin1) VALUES(?,?,?,?,?,?,?,?,?,?)",
        [(r[0], r[1], r[2], zh.get(r[0], ""), r[3], r[4], r[5], r[6], r[7], r[8]) for r in rows])
    names = {c: c for c in {r[7] for r in rows if r[7]}}
    db.executemany("INSERT INTO country(cc,name,zh) VALUES(?,?,?)",
                   [(cc, cc, CC_ZH.get(cc, "")) for cc in names])
    db.execute("INSERT INTO meta(k,v) VALUES('source','GeoNames cities500 + alternateNames (CC BY 4.0)')")
    db.execute("INSERT INTO meta(k,v) VALUES('count',?)", (str(len(rows)),))
    db.executescript("""
        CREATE INDEX idx_place_name ON place(name COLLATE NOCASE);
        CREATE INDEX idx_place_ascii ON place(ascii COLLATE NOCASE);
        CREATE INDEX idx_place_zh ON place(zh);
        CREATE INDEX idx_place_pop ON place(pop DESC);
    """)
    db.commit()
    db.execute("VACUUM")
    db.close()
    print(f"写出 {out}  {os.path.getsize(out)/1e6:.1f} MB")


if __name__ == "__main__":
    main()
