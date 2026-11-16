# -*- coding: utf-8 -*-
"""离线资料库清单：读 G:/Offline/_lists 里原来的 aria2 下载清单，查清每个文件的大小 / 校验值、最终放在 G 盘哪里、
下载后要怎么处理，并标出 G 盘上已经完整的、F 盘上已有可以直接拷的。结果写 F:/OfflineStage/inventory.json，给 pull.py 用。

  python inventory.py
"""
import concurrent.futures as cf, json, os, re, urllib.request, xml.etree.ElementTree as ET

LISTS = ["phase1_priority.txt", "phase1b_austopo.txt", "phase2_enwiki.txt", "phase2_osmand_world.txt", "phase4_gutenberg.txt", "phase5_zh.txt"]
LDIR = "G:/Offline/_lists"
G = "G:/Offline"
STAGE = "F:/OfflineStage"
LOCAL = ["F:/ARK/data/zim"]  # F 盘上已有的文件（直接拷，不删源文件）
UA = {"User-Agent": "Mozilla/5.0 ark-offline"}


def parse(fn):
    items, cur = [], None
    for line in open(os.path.join(LDIR, fn), encoding="utf-8"):
        if line.startswith("http"):
            cur = dict(url=line.strip(), list=fn); items.append(cur)
        elif cur and "=" in line:
            k, v = line.strip().split("=", 1); cur[k] = v
    return items


def head(url):
    req = urllib.request.Request(url, method="HEAD", headers=UA)
    with urllib.request.urlopen(req, timeout=60) as r:
        return int(r.headers.get("Content-Length") or 0)


def meta4(url):
    x = urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60).read()
    f = ET.fromstring(x).find("{urn:ietf:params:xml:ns:metalink}file")
    ns = "{urn:ietf:params:xml:ns:metalink}"
    sha = next((h.text for h in f.findall(ns + "hash") if h.get("type") == "sha-256"), None)
    return f.get("name"), int(f.find(ns + "size").text), sha


def plan(it):
    d = it.get("dir", G).replace("\\", "/")
    url = it["url"]
    if url.endswith(".meta4"):
        it["name"], it["size"], it["sha256"] = meta4(url)
    else:
        it["name"] = it.get("out") or os.path.basename(url.split("?")[0])
        it["size"] = head(url)
    cat = d.replace(G + "/", "")
    post, final = "none", it["name"]
    if cat == "_staging/osmand_au":
        cat, post, final = "01_Maps/OsmAnd_Australia_Oceania", "unzip", it["name"][:-4]
    elif cat == "_staging/osmand_world":
        cat, post, final = "01_Maps/OsmAnd_World_xz", "xz", it["name"][:-4] + ".xz"
    elif it["name"].endswith(".obf.zip"):
        post, final = "unzip", it["name"][:-4]
    it.update(cat=cat, post=post, final=final)
    dst = f"{G}/{cat}/{final}"
    partial = os.path.exists(dst + ".aria2") or os.path.exists(dst + ".part")
    ok = os.path.exists(dst) and not partial and os.path.getsize(dst) > 0
    if ok and post == "none" and it["size"]:
        ok = os.path.getsize(dst) == it["size"]
    it["status"] = "done" if ok else "todo"
    if not ok:
        for L in LOCAL:
            p = f"{L}/{it['name']}"
            if os.path.exists(p) and os.path.getsize(p) == it["size"]:
                it.update(status="local", local=p)
    return it


def main():
    items = [it for fn in LISTS for it in parse(fn)]
    for i, it in enumerate(items):
        it["order"] = i
    with cf.ThreadPoolExecutor(16) as ex:
        futs = {ex.submit(plan, it): it for it in items}
        for f in cf.as_completed(futs):
            try:
                f.result()
            except Exception as e:
                futs[f].update(status="error", error=str(e)[:200])
    os.makedirs(STAGE, exist_ok=True)
    json.dump(items, open(f"{STAGE}/inventory.json", "w", encoding="utf-8"), ensure_ascii=False, indent=0)
    gb = lambda xs: sum(x.get("size", 0) for x in xs) / 1e9
    by = {}
    for it in items:
        by.setdefault((it["cat"] if "cat" in it else "?", it["status"]), []).append(it)
    for (c, s), xs in sorted(by.items()):
        print(f"{c:36s} {s:6s} {len(xs):5d} 个 {gb(xs):8.1f} GB")
    todo = [x for x in items if x["status"] == "todo"]
    print(f"合计 {len(items)} 个；要下载 {len(todo)} 个 {gb(todo):.1f} GB；F 盘已有 {sum(x['status'] == 'local' for x in items)} 个；"
          f"出错 {sum(x['status'] == 'error' for x in items)} 个")
    for x in items:
        if x["status"] == "error":
            print("  出错：", x["url"], x["error"])


if __name__ == "__main__":
    main()
