# -*- coding: utf-8 -*-
"""微调数据第 3 步：收集老师（Claude Opus 5.5 子代理）写的标准答案，核对后生成 train.jsonl。

  python collect.py            # 读 teacher/raw_*.txt（yan subagent get --final 的输出）

核对：每批 25 条齐全；出处编号只在 [1]–[4]；数字都能在原文里找到（与 App 的数字核对同一规则）。
不合格的打印出来，人工看过再决定丢弃还是保留。
"""
import glob, json, os, re, sys

sys.path.insert(0, "F:/ARK/eval")
import rag_real as RR
import run_eval as R

HERE = os.path.dirname(os.path.abspath(__file__))
# ctx.jsonl：第 1 批 600 题（id 0–599）；ctx2.jsonl：补写的 200 道宽泛常见问题（id 600–799）
ctx = {}
for name in ("ctx.jsonl", "ctx2.jsonl"):
    p = os.path.join(HERE, name)
    if os.path.exists(p):
        for l in open(p, encoding="utf-8"):
            ctx[len(ctx)] = json.loads(l)
ans = {}
for f in sorted(glob.glob(os.path.join(HERE, "teacher", "raw_*.txt"))):
    for l in re.findall(r'^\s*(\{"id".*\})\s*$', open(f, encoding="utf-8").read(), re.M):
        r = json.loads(l)
        ans[int(r["id"])] = r
print(f"收到 {len(ans)} / {len(ctx)} 条；缺：{[i for i in ctx if i not in ans][:30]}")

bad, rows = [], []
for i, a in sorted(ans.items()):
    c = ctx[i]
    text = a["answer"].replace("\\n", "\n").strip()
    cites = {int(n) for grp in re.findall(r"[\[【](\d{1,2}(?:\s*[,，、]\s*\d{1,2})*)[\]】]", text) for n in re.findall(r"\d+", grp)}
    probs = []
    if cites - {1, 2, 3, 4}:
        probs.append(f"出处越界 {sorted(cites - {1, 2, 3, 4})}")
    nums = RR.unverified(text, c["passages"], c["q"])
    if nums:
        probs.append(f"原文找不到的数字 {nums}")
    if probs:
        bad.append((i, c["q"], probs))
    rows.append(dict(id=i, q=c["q"], cat=c.get("cat"), covered=c.get("covered"), answerable=a.get("answerable"),
                     system=R.SYS_RAG, user=RR.rag_user(c["passages"], c["q"]), answer=text, problems=probs))

print(f"有问题的 {len(bad)} 条：")
for i, q, p in bad:
    print(f"  {i:3d} {q[:24]:26s} {'；'.join(p)}")
with open(os.path.join(HERE, "train_all.jsonl"), "w", encoding="utf-8", newline="\n") as f:
    for r in rows:
        f.write(json.dumps(r, ensure_ascii=False) + "\n")
lens = [len(r["answer"]) for r in rows]
print(f"答案平均 {sum(lens) / len(lens):.0f} 字，资料可答 {sum(bool(r['answerable']) for r in rows)} 条")
