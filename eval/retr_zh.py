# -*- coding: utf-8 -*-
"""中文资料包的检索评测：和 App 里只启用中文包时一样（不让模型生成英文检索词，只用问题本身 + 问题里的英文词）。

  python retr_zh.py [中文包] [配置名=zh/app]
  每题前 4 段按 chunk id 换回英文原文（中文包和 v4 段落一一对应），写进 retr/top.json，
  这样能复用 labels.jsonl 里已有的 Opus 评分；没评过的用 retr_judge.py tasks 出题，再 retr_judge.py score 看汇总。
"""
import json, os, re, sqlite3, sys, zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rag_real as RR
import retr_eval as E

PACK = sys.argv[1] if len(sys.argv) > 1 else "F:/ARK/data/packs/zh/ark-core.arkpack"
CFG = sys.argv[2] if len(sys.argv) > 2 else "zh/app"
ZH_STOP = re.compile("怎么样|怎么办|怎么|如何|什么|哪些|为什么|是否|可以|应该|需要|请问|一下|吗|呢|吧|啊|的|了|我|你")


def zh_grams(q):  # 与 App 的 PackRepository.zhGrams 一致
    out = []
    for run in re.findall(r"[\u4e00-\u9fff]{3,}", ZH_STOP.sub(" ", q)):
        for i in range(len(run) - 2):
            if run[i:i + 3] not in out: out.append(run[i:i + 3])
    return out[:20]


def zh_match(q, terms):  # 与 App 的 PackRepository.zhMatch 一致
    parts = [f'"{g}"' for g in zh_grams(q)]
    words = []
    for w in [t.lower() for t in terms + re.findall(r"[A-Za-z0-9]{3,}", q)]:
        if len(w) >= 3 and w.isascii() and w.isalnum() and w not in words: words.append(w)
    for w in words[:8]:
        parts.append("(" + " AND ".join(f'"{w[i:i + 3]}"' for i in range(len(w) - 2)) + ")")
    return " OR ".join(parts)


def search(db, q, terms, qvec, ids, M, k=4, cand=50):  # 与 App 的 PackRepository.search 一致（中文包分支）
    import numpy as np
    kw = []
    m = zh_match(q, terms)
    if m:
        rows = db.execute("SELECT c.id, bm25(chunks_fts), c.text FROM chunks_fts JOIN chunks c ON c.id=chunks_fts.rowid "
                          "WHERE chunks_fts MATCH ? ORDER BY 2 LIMIT ?", (m, cand)).fetchall()
        stems = [t.lower()[:5] for t in terms if len(t) >= 3]
        grams = zh_grams(q)
        rows.sort(key=lambda r: (-(sum(s in r[2].lower() for s in stems) + sum(g in r[2] for g in grams)), r[1]))
        kw = [r[0] for r in rows]
    sem = [int(ids[i]) for i in np.argsort(-(M @ qvec))[:cand]]
    sc = {}
    for L in (kw, sem):
        for i, c in enumerate(L):
            sc[c] = sc.get(c, 0) + 1 / (60 + i + 1)
    seen, out = set(), []
    for cid in sorted(sc, key=lambda c: -sc[c]):
        text, en = db.execute("SELECT text, text_en FROM chunks WHERE id=?", (cid,)).fetchone()
        key = re.sub(r"\W", "", text[:200].lower())
        if key in seen: continue
        seen.add(key)
        out.append(dict(id=cid, zh=text, text=zlib.decompress(en).decode("utf-8")))
        if len(out) == k: break
    return out


def main():
    import numpy as np
    db = sqlite3.connect(PACK)
    ids, M = RR.load_vecs(db)
    M = M / np.linalg.norm(M, axis=1, keepdims=True)
    qs = list(E.ALL)
    qv = RR.query_vecs(qs, M.shape[1])
    top = {}
    for q in qs:
        v = qv[q] / np.linalg.norm(qv[q])
        top[q] = [dict(id=p["id"], title="", text=p["text"]) for p in search(db, q, RR.parse_keywords(q), v, ids, M)]
    p = os.path.join(E.D, "top.json")
    old = json.load(open(p, encoding="utf-8")) if os.path.exists(p) else {}
    old[CFG] = top
    json.dump(old, open(p, "w", encoding="utf-8"), ensure_ascii=False)
    lab = E.labels()
    miss = sum(E.key(q, x["text"]) not in lab for q in qs for x in top[q])
    print(f"{CFG}：{len(qs)} 题，前 4 段共 {4 * len(qs)} 段，未评分 {miss}")


if __name__ == "__main__":
    main()
