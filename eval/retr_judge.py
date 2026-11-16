# -*- coding: utf-8 -*-
"""给检索结果打相关性分（Opus 子代理），结果缓存在 retr/labels.jsonl，供 retr_eval.py 计分。

  python retr_judge.py tasks [批数，默认 8]   → 把 retr/top.json 里还没评分的“问题 + 段落”写成 retr/jt_<i>.md 和请求 retr/jreq_<i>.json
  python retr_judge.py collect <raw.txt> ...  → 解析子代理回答里的 “key 分数” 行，追加进 retr/labels.jsonl
"""
import json, os, re, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import retr_eval as E

D = E.D
RULES = """你在评估一个离线求生 App 的检索质量。每条是一个用户的中文问题和检索到的一段英文资料（第一行可能是“书名 › 章节”）。
判断这段资料对回答该问题有多大用处，只看资料本身，不用你自己的知识补充：
  2 = 资料里有直接回答这个问题的具体做法或事实，用户照着能做、或能得到问题要的答案（覆盖问题的主要方面之一即可）。
  1 = 相关但只是沾边：讲到了这个主题或相近情况，有一点有用信息，但没给出问题要的做法或答案。
  0 = 无关，或者几乎没用。
这是检索评测，不是答案质量评测：资料里的做法即使过时（比如蛇咬用止血带），只要正是在回答这个问题，也按 2 或 1 计。
同一个问题下面通常有好几段，请逐段独立判断。

输出格式：每条一行，`key 分数`，例如 `3fa2c19d0b7e4a51 2`。所有条目都要给分，不要输出任何其他内容（不要解释、不要表格）。
"""


def pending():
    top = json.load(open(os.path.join(D, "top.json"), encoding="utf-8"))
    lab = E.labels()
    out = {}
    for d in top.values():
        for q, ps in d.items():
            for p in ps:
                k = E.key(q, p["text"])
                if k not in lab:
                    out[k] = (q, p["text"])
    return out


def tasks(n):
    items = sorted(pending().items(), key=lambda kv: kv[1][0])  # 同一问题的段落放在一起
    if not items:
        print("全部已评分"); return
    size = -(-len(items) // n)
    for i in range(n):
        part = items[i * size:(i + 1) * size]
        if not part: break
        md = os.path.join(D, f"jt_{i}.md")
        with open(md, "w", encoding="utf-8", newline="\n") as f:
            f.write(RULES + f"\n共 {len(part)} 条。\n")
            last = None
            for k, (q, text) in part:
                if q != last:
                    f.write(f"\n\n======== 问题：{q}\n"); last = q
                f.write(f"\n--- key: {k}\n{text}\n")
        req = dict(task=f"只读任务。请完整阅读 {md.replace(os.sep, '/')}，按文件开头的规则给其中每一条打分，"
                        f"最终回答只输出 `key 分数` 行（共 {len(part)} 行），不要输出其他内容。",
                   title=f"检索相关性评分 {i}", model="anthropic/claude-opus-5-5", thinkingLevel="high", readOnly=True,
                   brief=dict(goal="给检索段落打相关性分", deliverables=[f"{len(part)} 行 key 分数"]))
        json.dump(req, open(os.path.join(D, f"jreq_{i}.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
        print(md, len(part), "条", sum(len(t.split()) for _, (_, t) in part), "词")


def collect(files):
    pend = pending()
    got = {}
    for fn in files:
        for k, s in re.findall(r"\b([0-9a-f]{16})\b\D{0,5}([012])\b", open(fn, encoding="utf-8").read()):
            if k in pend:
                got[k] = int(s)
    with open(os.path.join(D, "labels.jsonl"), "a", encoding="utf-8", newline="\n") as f:
        for k, s in got.items():
            f.write(json.dumps(dict(key=k, q=pend[k][0], score=s), ensure_ascii=False) + "\n")
    print(f"收回 {len(got)} 条，还剩 {len(pend) - len(got)} 条没评分")


def score():
    """按 Opus 评分汇总 top.json 里的每种方案：前 4 段里有没有能直接回答的（2 分）、至少部分有用的（1 分），以及 4 段里平均几段有用。"""
    top = json.load(open(os.path.join(D, "top.json"), encoding="utf-8"))
    lab = E.labels()
    print("方案".ljust(28) + "评测40:直接  部分  有用段   硬核28:直接  部分  有用段   电机40:直接  部分  有用段   未评")
    for cfg, d in top.items():
        row, miss = cfg.ljust(28), 0
        for qs in (E.GOLD, E.HARD, E.RM):
            sc = [[lab.get(E.key(q, p["text"]), -1) for p in d.get(q, [])] for q in qs]
            miss += sum(s == -1 for ss in sc for s in ss)
            row += f"{sum(2 in ss for ss in sc):>10}{sum(max(ss, default=-1) >= 1 for ss in sc):>6}{sum(s >= 1 for ss in sc for s in ss) / len(qs):>8.2f}   "
        print(row + f"{miss:>5}")


if __name__ == "__main__":
    if sys.argv[1] == "tasks":
        tasks(int(sys.argv[2]) if len(sys.argv) > 2 else 8)
    elif sys.argv[1] == "score":
        score()
    else:
        collect(sys.argv[2:])
