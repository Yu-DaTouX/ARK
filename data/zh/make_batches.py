# -*- coding: utf-8 -*-
"""全量翻译的分批：按资料包顺序（同一本书连在一起）把段落切成每批约 WORDS 词，写成子代理好读好写的纯文本。
  python make_batches.py [资料包]  → in/b_000.txt …（每段以 “### 段落id” 开头）、batches.json
"""
import json, os, sqlite3, sys

HERE = os.path.dirname(os.path.abspath(__file__))
PACK = sys.argv[1] if len(sys.argv) > 1 else "F:/ARK/data/packs/v4/ark-core.arkpack"
WORDS = 70000


def main():
    os.makedirs(os.path.join(HERE, "in"), exist_ok=True)
    os.makedirs(os.path.join(HERE, "out"), exist_ok=True)
    rows = sqlite3.connect(PACK).execute("SELECT id, text FROM chunks ORDER BY id").fetchall()
    titles = json.load(open(os.path.join(HERE, "titles.json"), encoding="utf-8"))
    batches, cur, n = [], [], 0
    for i, t in rows:
        cur.append((i, t)); n += len(t.split())
        if n >= WORDS:
            batches.append(cur); cur, n = [], 0
    if cur: batches.append(cur)
    meta = []
    for k, b in enumerate(batches):
        name = f"b_{k:03d}"
        with open(os.path.join(HERE, "in", name + ".txt"), "w", encoding="utf-8", newline="\n") as f:
            for i, t in b:
                f.write(f"### {i}\n{t.strip()}\n\n")
        books = sorted({t.split("\n", 1)[0].split(" › ")[0].strip() for _, t in b} & set(titles))
        meta.append(dict(name=name, n=len(b), words=sum(len(t.split()) for _, t in b), first=b[0][0], last=b[-1][0],
                         titles={x: titles[x] for x in books}))
    json.dump(meta, open(os.path.join(HERE, "batches.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=0)
    print(len(batches), "批，每批", min(m["n"] for m in meta), "–", max(m["n"] for m in meta), "段；共", len(rows), "段")


if __name__ == "__main__":
    main()
