# -*- coding: utf-8 -*-
"""合并 Claude Opus 5.5 盲评结果：去盲 → 每个模型一个 jsonl → 汇总表。

  python merge_claude.py results_rag      # 第一轮：旧核心包 + 关键词检索
  python merge_claude.py results_rag_v2   # 第二轮：核心包 v2 + 混合检索
"""
import glob, json, os, re, statistics as S, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rag_real as RR

RUN = sys.argv[1] if len(sys.argv) > 1 else "results_rag"
HERE = os.path.dirname(os.path.abspath(__file__))
D = os.path.join(HERE, RUN, "claude_judge")
TAGS = ["retrieval_miss", "missed_answer", "off_topic", "hospital_only", "contradiction", "wrong_number",
        "hallucination", "bad_citation", "too_long", "source_outdated"]

key = json.load(open(os.path.join(D, "blind_key.json")))
rows = []
for f in glob.glob(os.path.join(D, "raw_sub-*.txt")):
    rows += [json.loads(l) for l in re.findall(r'^\s*(\{"q":.*\})\s*$', open(f, encoding="utf-8").read(), re.M)]
by = {}
for r in rows:
    r["model"] = key[str(r["q"])][r["label"]]
    r["question"] = RR.QUESTIONS[r["q"] - 1]
    by.setdefault(r["model"], []).append(r)
ideal = {RR.QUESTIONS[r["q"] - 1]: r["ideal"] for r in rows if r.get("ideal")}
json.dump(ideal, open(os.path.join(D, "ideal.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)

out = [f"{len(rows)} 条评分", "",
       "| 模型 | Opus 分 | 资料可答 | 可答题得分 | 危险 | 平均 token |", "|---|---|---|---|---|---|"]
for m in sorted(by):
    rs = sorted(by[m], key=lambda r: r["q"])
    with open(os.path.join(D, f"{m}.jsonl"), "w", encoding="utf-8", newline="\n") as f:
        for r in rs:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    toks = [json.loads(l)["tokens"] for l in open(os.path.join(HERE, RUN, f"{m}.jsonl"), encoding="utf-8")]
    ans = [r for r in rs if r["answerable"]]
    out.append(f"| {m} | {10 * S.mean(r['score'] for r in rs):.1f} | {len(ans)} | {10 * S.mean(r['score'] for r in ans):.1f} | "
               f"{sum(r['dangerous'] for r in rs)} | {S.mean(toks):.0f} |")
out += ["", "问题标签次数：", "| 模型 | " + " | ".join(TAGS) + " |", "|---" * (len(TAGS) + 1) + "|"]
for m in sorted(by):
    out.append(f"| {m} | " + " | ".join(str(sum(t in r["issues"] for r in by[m])) for t in TAGS) + " |")
out += ["", "危险题：" + "；".join(f"{m} {[r['q'] for r in sorted(by[m], key=lambda r: r['q']) if r['dangerous']]}" for m in sorted(by))]
open(os.path.join(D, "summary.md"), "w", encoding="utf-8", newline="\n").write("\n".join(out) + "\n")
print("\n".join(out))
