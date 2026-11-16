# -*- coding: utf-8 -*-
"""第 3 版数据：取回“重写拒答NN”子代理的输出，核对后合成 train3.jsonl。

  python collect_fix.py

核对同 collect.py：出处编号只在 [1]–[4]、数字都能在原文里找到。不合格的保留旧答案并列出来。
合成规则同第 2 版：资料覆盖的话题里，仍然是一句话拒答（没有任何要点）的去掉。
"""
import json, os, re, subprocess, sys

sys.path.insert(0, "F:/ARK/eval")
import rag_real as RR

YAN = "C:/Users/YuDaTou/.pi/agent/yan/bin/yan.cmd"
HERE = os.path.dirname(os.path.abspath(__file__))
lst = json.loads(subprocess.run([YAN, "subagent", "list"], capture_output=True, text=True, encoding="utf-8").stdout)
runs = json.load(open(lst["resultFile"], encoding="utf-8"))
for r in runs:
    title = r.get("title") or (r.get("brief") or {}).get("title") or ""
    m = re.match(r"重写拒答(\d\d)$", title)
    dst = os.path.join(HERE, "teacher", f"fix_raw_{m.group(1)}.txt") if m else None
    if m and r["status"] == "done" and not os.path.exists(dst):
        out = subprocess.run([YAN, "subagent", "get", "--id", r["id"], "--final"], capture_output=True, text=True, encoding="utf-8").stdout
        open(dst, "w", encoding="utf-8").write(out)

ctx = {}
for name in ("ctx.jsonl", "ctx2.jsonl"):
    for l in open(os.path.join(HERE, name), encoding="utf-8"):
        ctx[len(ctx)] = json.loads(l)
rows = {r["id"]: r for r in map(json.loads, open(os.path.join(HERE, "train_all.jsonl"), encoding="utf-8"))}
fix = {}
for f in sorted(os.listdir(os.path.join(HERE, "teacher"))):
    if f.startswith("fix_raw_"):
        for l in re.findall(r'^\s*(\{"id".*\})\s*$', open(os.path.join(HERE, "teacher", f), encoding="utf-8").read(), re.M):
            r = json.loads(l); fix[int(r["id"])] = r
todo = [i for i, r in rows.items() if r["covered"] and not r["answerable"] and r["answer"].lstrip().startswith("资料中没有")]
print(f"收到 {len(fix)} / {len(todo)}；缺 {[i for i in todo if i not in fix][:20]}")

bad, modes = [], {"answer": 0, "refuse": 0}
for i, r in fix.items():
    text = r["answer"].replace("\\n", "\n").strip()
    c = ctx[i]
    cites = {int(n) for grp in re.findall(r"[\[【](\d{1,2}(?:\s*[,，、]\s*\d{1,2})*)[\]】]", text) for n in re.findall(r"\d+", grp)}
    probs = []
    if cites - {1, 2, 3, 4}:
        probs.append(f"出处越界 {sorted(cites - {1, 2, 3, 4})}")
    nums = RR.unverified(text, c["passages"], c["q"])
    if nums:
        probs.append(f"原文找不到的数字 {nums}")
    if probs:
        bad.append((i, c["q"], probs)); continue
    modes[r.get("mode", "answer")] = modes.get(r.get("mode", "answer"), 0) + 1
    rows[i] = dict(rows[i], answer=text, answerable=r.get("mode") == "answer", problems=[])
print("重写结果", modes, "；不合格（保留旧答案）", len(bad))
for i, q, p in bad:
    print(f"  {i:3d} {q[:24]:26s} {'；'.join(p)}")

out, drop = [], 0
for i, r in sorted(rows.items()):
    one_line = r["answer"].lstrip().startswith("资料中没有") and "\n-" not in r["answer"] and "\n" not in r["answer"].strip()
    if r["covered"] and one_line:
        drop += 1; continue
    out.append(dict(system=r["system"], user=r["user"], answer=r["answer"]))
with open(os.path.join(HERE, "train3.jsonl"), "w", encoding="utf-8", newline="\n") as f:
    for r in out:
        f.write(json.dumps(r, ensure_ascii=False) + "\n")
ref = sum(r["answer"].lstrip().startswith("资料中没有") for r in out)
print(f"train3.jsonl {len(out)} 条（去掉一句话拒答 {drop}）；以“资料中没有”开头 {ref} 条（{ref / len(out):.0%}）")
