# -*- coding: utf-8 -*-
"""翻译试点的盲评：把几份译文匿名打乱成 A/B/C/D，交给 Opus 子代理逐段打分，再按模型汇总。

  python judge.py tasks <批数> <名1>=<译文1.jsonl> <名2>=<译文2.jsonl> ...   → judge_<i>.md / jreq_<i>.json / key.json
  python judge.py score <子代理回答.txt> ...                                  → 各模型汇总
"""
import json, os, random, re, sys
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
RULES = """你在评审一个离线求生 App 的资料翻译（英文 → 中文）。用户断网后会照着中文译文去急救、找水、修车、架天线，译错可能出人命。
每条给出英文原文和几份匿名译文（A、B、C…，顺序随机）。逐份对照原文判断：
  准确度 0–3：3 = 意思完整准确；2 = 有小瑕疵（个别用词不当、轻微漏译）但不影响照做；1 = 有影响理解或操作的错译 / 漏译；0 = 大段漏译、乱译，或者没翻成中文。
  危险 0/1：译文里有照着做可能造成伤害的错误，比如数字、剂量、单位、时间、频率、步骤顺序、"要 / 不要"翻反。只要有一处就记 1。
  流畅度 1–3：3 = 自然的中文；2 = 能读懂但生硬；1 = 很难读。
不要因为译文比原文更短或更长而扣分，只看意思对不对；第一行“书名 › 章节”的书名译不译都可以。
输出格式：每份译文一行：`条目号 字母 准确度 危险 流畅度 问题`，问题用几个字概括（没有就写 -），例如
`17 B 1 1 2 把 30 分钟译成 3 分钟`
所有条目的所有译文都要打分，不要输出其他内容。
"""


def tasks(n, specs):
    names = [s.split("=", 1)[0] for s in specs]
    tr = {nm: {r["id"]: r["zh"] for r in map(json.loads, open(p, encoding="utf-8"))} for nm, p in (s.split("=", 1) for s in specs)}
    src = [r for r in map(json.loads, open(os.path.join(HERE, "sample.jsonl"), encoding="utf-8")) if all(r["id"] in tr[nm] for nm in names)]
    random.seed(7)
    key = {}
    size = -(-len(src) // n)
    for i in range(n):
        part = src[i * size:(i + 1) * size]
        if not part: break
        md = os.path.join(HERE, f"judge_{i}.md")
        with open(md, "w", encoding="utf-8", newline="\n") as f:
            f.write(RULES + f"\n共 {len(part)} 条，每条 {len(names)} 份译文。\n")
            for r in part:
                order = names[:]; random.shuffle(order)
                key[str(r["id"])] = {chr(65 + j): nm for j, nm in enumerate(order)}
                f.write(f"\n\n======== 条目 {r['id']}\n【原文】\n{r['text']}\n")
                for j, nm in enumerate(order):
                    f.write(f"\n【{chr(65 + j)}】\n{tr[nm][r['id']]}\n")
        req = dict(task=f"只读任务。请完整阅读 {md.replace(os.sep, '/')}（较长，可分段读），按开头的规则给每条的每份译文打分，"
                        f"最终回答只输出评分行（共 {len(part) * len(names)} 行），不要输出其他内容。",
                   title=f"翻译盲评 {i}", model="anthropic/claude-opus-5-5", thinkingLevel="high", readOnly=True,
                   brief=dict(goal="盲评英译中质量，重点找危险错误", deliverables=[f"{len(part) * len(names)} 行评分"], sources=[md.replace(os.sep, "/")]))
        json.dump(req, open(os.path.join(HERE, f"jreq_{i}.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
        print(md, len(part), "条")
    json.dump(key, open(os.path.join(HERE, "key.json"), "w", encoding="utf-8"), ensure_ascii=False)


def score(files):
    key = json.load(open(os.path.join(HERE, "key.json"), encoding="utf-8"))
    cat = {str(r["id"]): r["cat"] for r in map(json.loads, open(os.path.join(HERE, "sample.jsonl"), encoding="utf-8"))}
    agg, bycat, issues, seen = defaultdict(list), defaultdict(list), defaultdict(list), set()
    for fn in files:
        for m in re.finditer(r"^\s*`?(\d+)\s+([A-H])\s+([0-3])\s+([01])\s+([1-3])\s*(.*?)`?\s*$", open(fn, encoding="utf-8").read(), re.M):
            i, L, acc, dang, flu, why = m.groups()
            if i not in key or L not in key[i] or (i, L) in seen: continue
            seen.add((i, L)); nm = key[i][L]
            agg[nm].append((int(acc), int(dang), int(flu)))
            bycat[(nm, cat[i])].append(int(acc))
            if int(dang) or int(acc) <= 1: issues[nm].append(f"{i}({cat[i]}) 准确{acc} 危险{dang}：{why.strip()}")
    print("模型".ljust(10), "条数  准确度  准确≥2   危险  流畅度   " + "  ".join(c for c in ["医学", "求生", "无线电", "机械电力", "其他"]))
    for nm, xs in sorted(agg.items(), key=lambda kv: -sum(x[0] for x in kv[1]) / len(kv[1])):
        n = len(xs)
        per = "  ".join(f"{sum(bycat[(nm, c)]) / max(1, len(bycat[(nm, c)])):.2f}".rjust(len(c) * 2) for c in ["医学", "求生", "无线电", "机械电力", "其他"])
        print(nm.ljust(10), f"{n:4d}  {sum(x[0] for x in xs) / n:6.2f}  {sum(x[0] >= 2 for x in xs) / n * 100:5.0f}%  {sum(x[1] for x in xs):5d}  {sum(x[2] for x in xs) / n:6.2f}   {per}")
    json.dump(issues, open(os.path.join(HERE, "issues.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)


if __name__ == "__main__":
    if sys.argv[1] == "tasks":
        tasks(int(sys.argv[2]), sys.argv[3:])
    else:
        score(sys.argv[2:])
