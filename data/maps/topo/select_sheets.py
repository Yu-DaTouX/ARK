#!/usr/bin/env python3
"""挑出某个州该包含的图幅：图幅经纬度范围与州界线框相交就算。

    python3 select_sheets.py <state> [--mb]

输出该州图幅的 vp json 路径（--mb 时输出已渲染好的 mbtiles 路径）。
边界图幅会在相邻州重复出现——宁可重叠，也不留空洞。
"""
import glob
import json
import os
import sys
sys.stdout.reconfigure(newline="\n")

# 州界线框（略放宽，保证边界地带不漏）；塔州目前没有免费图幅
STATES = {
    "wa":  (112.0, -36.0, 129.0, -13.0),
    "nt":  (128.5, -26.5, 138.2, -10.5),
    "sa":  (128.5, -38.5, 141.2, -25.8),
    "qld": (137.8, -29.5, 154.5,  -9.5),
    "nsw": (140.8, -38.0, 154.5, -28.0),
    "vic": (140.8, -39.6, 150.2, -33.8),
    "tas": (143.5, -44.0, 149.2, -39.1),
}
NAMES = {"wa": "西澳", "nt": "北领地", "sa": "南澳", "qld": "昆士兰", "nsw": "新南威尔士", "vic": "维多利亚", "tas": "塔斯马尼亚"}


def main():
    state = sys.argv[1]
    want_mb = "--mb" in sys.argv
    lon0, lat0, lon1, lat1 = STATES[state]
    for f in sorted(glob.glob("vp/*.json")):
        d = json.load(open(f))
        lats, lons = d["gpts"][0::2], d["gpts"][1::2]
        if max(lons) < lon0 or min(lons) > lon1 or max(lats) < lat0 or min(lats) > lat1:
            continue
        if want_mb:
            stem = os.path.basename(f)[:-5]
            hits = glob.glob(f"build/zone*/mb/{stem}.mbtiles")
            if hits:
                print(hits[0])
        else:
            print(f)


if __name__ == "__main__":
    main()
