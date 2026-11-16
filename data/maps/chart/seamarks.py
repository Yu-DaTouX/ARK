# -*- coding: utf-8 -*-
"""海图第二部分：OpenSeaMap 全球航标（https://tiles.openseamap.org/seamark/world.osm，OSM 数据，ODbL）→ 精简的 GeoJSONSeq。

  osmium export world.osm -f geojsonseq --geometry-types=point -o raw.geojsons
  python3 seamarks.py raw.geojsons seamarks.geojsons

每个点只留 App 要用的属性：
  t  航标类型（light_major、buoy_lateral、beacon_cardinal、harbour、anchorage、wreck、rock …）
  n  名称
  c  颜色（第一个，red / green / yellow / black / white …）
  k  分类（port / starboard / north / south / east / west …）
  l  灯质，按海图惯例缩写，例如 Fl(2)W 10s 15M
显示级别（tippecanoe.minzoom）：射程 ≥ 15 海里的灯 5 级，≥ 8 海里 7 级，其他灯和大型标志 9 级，浮标立标 11 级，
礁石沉船等危险物 12 级，系泊、桩、告示牌、里程标这类数量多又不影响安全的 14 级（不然港口里密密麻麻）。
"""
import json, re, sys

COL = {"white": "W", "red": "R", "green": "G", "yellow": "Y", "blue": "Bu", "orange": "Or", "violet": "Vi", "amber": "Am"}
LIGHT_TYPES = {"light_major", "light_minor", "light_vessel", "light_float", "light"}
KEEP_TYPES = None  # 全部保留
MINOR = {"mooring", "pile", "notice", "distance_mark", "berth", "small_craft_facility", "bridge", "cable_overhead",
         "cable_submarine", "pipeline_submarine", "gate", "dyke", "shoreline_construction", "building", "platform", "hulk"}


def light_text(p):
    ch = p.get("seamark:light:character") or p.get("seamark:light:1:character")
    if not ch:
        return ""
    grp = p.get("seamark:light:group") or p.get("seamark:light:1:group")
    cols = []
    for k, v in p.items():
        if re.fullmatch(r"seamark:light(:\d+)?:colour", k):
            for c in v.split(";"):
                a = COL.get(c.strip())
                if a and a not in cols:
                    cols.append(a)
    per = p.get("seamark:light:period") or p.get("seamark:light:1:period")
    rng = p.get("seamark:light:range") or p.get("seamark:light:1:range")
    s = ch + (f"({grp})" if grp else "") + "".join(cols)
    if per: s += f" {per}s"
    if rng: s += f" {rng}M"
    return s


def num(v):
    m = re.match(r"\s*([\d.]+)", v or "")
    return float(m.group(1)) if m else 0.0


def main(src, dst):
    n_in = n_out = 0
    with open(src, encoding="utf-8") as f, open(dst, "w", encoding="utf-8") as out:
        for line in f:
            line = line.strip().lstrip("\x1e")
            if not line:
                continue
            n_in += 1
            ft = json.loads(line)
            p = ft.get("properties") or {}
            t = p.get("seamark:type")
            if not t:
                continue
            q = {"t": t}
            name = p.get("seamark:name") or p.get("name")
            if name: q["n"] = name[:40]
            col = p.get(f"seamark:{t}:colour") or p.get("seamark:light:colour")
            if col: q["c"] = col.split(";")[0]
            cat = p.get(f"seamark:{t}:category")
            if cat: q["k"] = cat.split(";")[0]
            lt = light_text(p)
            if lt: q["l"] = lt
            rng = num(p.get("seamark:light:range") or p.get("seamark:light:1:range"))
            if t in LIGHT_TYPES or lt:
                mz = 5 if rng >= 15 else 7 if rng >= 8 else 9
            elif t in ("harbour", "landmark", "light_vessel", "rescue_station", "signal_station_traffic"):
                mz = 9
            elif t.startswith(("buoy", "beacon")):
                mz = 11
            elif t in MINOR:
                mz = 14
            else:
                mz = 12
            out.write(json.dumps({"type": "Feature", "geometry": ft["geometry"], "properties": q,
                                  "tippecanoe": {"minzoom": mz}}, ensure_ascii=False) + "\n")
            n_out += 1
    print(f"读入 {n_in}，写出 {n_out} 个航标")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
