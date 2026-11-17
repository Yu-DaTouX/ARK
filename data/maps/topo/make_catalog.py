#!/usr/bin/env python3
"""把已生成的州包写进 App 的下载目录 assets/catalog.json（kind=topo）。

    python3 make_catalog.py            # 需要 GitHub Release 已上传，资产名 topo-<state>.pmtiles
"""
import json
import os

TAG = "topo-2026-11"
BASE = f"https://github.com/Yu-DaTouX/ARK/releases/download/{TAG}"
STATES = [
    ("wa", "西澳（含珀斯、皮尔巴拉、金矿田）"),
    ("nt", "北领地（达尔文、爱丽斯泉、乌鲁鲁）"),
    ("sa", "南澳（阿德莱德、弗林德斯、袋鼠岛）"),
    ("qld", "昆士兰（布里斯班、凯恩斯、内陆）"),
    ("nsw", "新南威尔士 · 首都领地（悉尼、蓝山、内陆）"),
    ("vic", "维多利亚（墨尔本、大洋路、高山）"),
]
cat_path = "../../../app/app/src/main/assets/catalog.json"
cat = json.load(open(cat_path, encoding="utf-8"))
cat["items"] = [i for i in cat["items"] if i.get("kind") != "topo"]
for st, desc in STATES:
    f = f"topo-{st}.pmtiles"
    if not os.path.exists(f):
        print("跳过", f, "（还没生成）")
        continue
    cat["items"].append({
        "kind": "topo",
        "title": f"地形图 · {desc}",
        "file": f,
        "url": f"{BASE}/{f}",
        "size": os.path.getsize(f),
        "note": "AUSTopo 1:250 000 · 等高线/水系/水潭/土路 · 与相邻州有重叠图幅；"
                "地图页「更多 → 地形图叠加」打开",
    })
json.dump(cat, open(cat_path, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
print("catalog.json 现有", len(cat["items"]), "项，其中地形图",
      sum(1 for i in cat["items"] if i.get("kind") == "topo"))
