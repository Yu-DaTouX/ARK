# -*- coding: utf-8 -*-
"""用大模型（E2B 本身）给检索候选重排：混合检索取前 N 段 → 只给模型看每段的“书名 › 章节”和开头 → 模型挑出最能回答问题的几段。

  python retr_rerank.py <资料包> <检索词名 old|new> <查询方式 zh|zh+en> <模型.litertlm> [N=20]
结果写进 retr/top.json 的 “<包>/<检索词>/<查询>+rr<N>” 方案（之后用 retr_judge.py 评分、retr_eval.py 看汇总）。
"""
import json, os, re, sqlite3, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import numpy as np
import rag_real as RR
import retr_eval as E
from probe_hard import gen

SYS_RR = ("你是检索助手。下面是从求生和急救资料库里检索到的候选段落，每条是“编号 书名 › 章节：开头几句”。"
          "选出最可能直接回答用户问题的段落，最多 4 个，最相关的放前面。只输出编号，用空格分隔，不要输出其他内容。")


def brief(p, words=40):
    head, _, body = p["text"].partition("\n")
    if not body:  # 没有“书名 › 章节”行的旧资料包
        head, body = p["title"], p["text"]
    w = body.split()
    return f"{head}：{' '.join(w[:words])}{'…' if len(w) > words else ''}"


def main():
    pack, n, qmode, model = sys.argv[1:5]
    N = int(sys.argv[5]) if len(sys.argv) > 5 else 20
    kws = {r["q"]: " ".join(r["answer"].split()) for r in map(json.loads, open(os.path.join(E.D, f"kw_{n}.jsonl"), encoding="utf-8"))}
    db = sqlite3.connect(pack)
    ids, M = RR.load_vecs(db)
    qv = RR.query_vecs(list(E.ALL) + list(kws.values()), M.shape[1])
    cands = {}
    for q in E.ALL:
        v = np.stack([qv[q], qv[kws[q]]]) if qmode == "zh+en" else qv[q][None]
        cands[q] = RR.search(db, RR.parse_keywords(kws[q], extra_stop=n != "old"), k=N, qvec=v, vecs=(ids, M))
    src = os.path.join(E.D, f"rr_in.jsonl")
    with open(src, "w", encoding="utf-8", newline="\n") as f:
        for q, ps in cands.items():
            user = "候选段落：\n" + "\n".join(f"{i + 1} {brief(p)}" for i, p in enumerate(ps)) + f"\n\n问题：{q}"
            f.write(json.dumps(dict(q=q, system=SYS_RR, user=user), ensure_ascii=False) + "\n")
    dst = os.path.join(E.D, f"rr_out.jsonl")
    gen(model, src, dst, 24)
    tag = os.path.basename(os.path.dirname(pack))
    cfg = f"{tag}/{n}/{qmode}+rr{N}"
    top, picks = {}, {}
    for r in map(json.loads, open(dst, encoding="utf-8")):
        ps = cands[r["q"]]
        pick = []
        for x in re.findall(r"\d+", r["answer"]):
            i = int(x) - 1
            if 0 <= i < len(ps) and i not in pick:
                pick.append(i)
        picks[r["q"]] = pick[:4]
        order = pick[:4] + [i for i in range(len(ps)) if i not in pick]  # 模型没选够就按原顺序补
        top[r["q"]] = [dict(id=ps[i]["id"], title=ps[i]["title"], text=ps[i]["text"]) for i in order[:4]]
    p = os.path.join(E.D, "top.json")
    old = json.load(open(p, encoding="utf-8"))
    old[cfg] = top
    json.dump(old, open(p, "w", encoding="utf-8"), ensure_ascii=False)
    json.dump(picks, open(os.path.join(E.D, f"rr_picks.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=0)
    print(cfg, "完成；模型平均选了", round(sum(map(len, picks.values())) / len(picks), 1), "段")


if __name__ == "__main__":
    main()
