#!/usr/bin/env python3
"""统计一份 OSM PBF 里的地物构成，用来和 GeoNames 方案比较覆盖度。

用法：python count_osm.py <file.osm.pbf> [--bbox minlat,minlon,maxlat,maxlon]

输出：居民点按 place=* 分类的数量、带 name:zh 的数量、各类 amenity 数量、
      带名字的街道（way/highway）数量、address（addr:housenumber）数量。
"""
import collections
import sys

import osmium

PLACE = ("city", "town", "village", "hamlet", "suburb", "quarter", "neighbourhood",
         "locality", "isolated_dwelling", "farm", "island", "islet")
AMENITY = ("hospital", "clinic", "doctors", "pharmacy", "police", "fire_station",
           "drinking_water", "shelter", "toilets", "fuel", "shelter_building", "ranger_station")
SHOP = ("supermarket", "convenience", "hardware", "doityourself")


class Counter(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.place = collections.Counter()
        self.place_zh = collections.Counter()
        self.amenity = collections.Counter()
        self.amenity_zh = collections.Counter()
        self.shop = collections.Counter()
        self.named_streets = 0
        self.streets_zh = 0
        self.addresses = 0
        self.named_nodes = 0

    def node(self, n):
        t = n.tags
        if not t:
            return
        name = t.get("name")
        if name:
            self.named_nodes += 1
        p = t.get("place")
        if p in PLACE:
            self.place[p] += 1
            if t.get("name:zh") or t.get("name:zh-Hans"):
                self.place_zh[p] += 1
        a = t.get("amenity")
        if a in AMENITY:
            self.amenity[a] += 1
            if t.get("name:zh") or t.get("name:zh-Hans"):
                self.amenity_zh[a] += 1
        s = t.get("shop")
        if s in SHOP:
            self.shop[s] += 1
        if t.get("addr:housenumber"):
            self.addresses += 1

    def way(self, w):
        t = w.tags
        if not t:
            return
        if t.get("highway") and t.get("name"):
            self.named_streets += 1
            if t.get("name:zh") or t.get("name:zh-Hans"):
                self.streets_zh += 1
        if t.get("addr:housenumber"):
            self.addresses += 1
        a = t.get("amenity")
        if a in AMENITY:
            self.amenity[a] += 1


def main():
    path = sys.argv[1]
    h = Counter()
    h.apply_file(path, locations=False)
    print("== 居民点 place=*")
    for k, v in h.place.most_common():
        print(f"  {k:18s} {v:8d}   带中文名 {h.place_zh.get(k, 0)}")
    print(f"  合计 {sum(h.place.values())}，带中文名 {sum(h.place_zh.values())}")
    print("== 求助点 amenity=*")
    for k, v in h.amenity.most_common():
        print(f"  {k:18s} {v:8d}   带中文名 {h.amenity_zh.get(k, 0)}")
    print(f"  合计 {sum(h.amenity.values())}")
    print("== 商店 shop=*")
    print("  " + "  ".join(f"{k} {v}" for k, v in h.shop.most_common()))
    print(f"== 带名字的街道 {h.named_streets}（带中文名 {h.streets_zh}）")
    print(f"== 门牌号 {h.addresses}")
    print(f"== 带名字的节点 {h.named_nodes}")


if __name__ == "__main__":
    main()
