# -*- coding: utf-8 -*-
"""核对译文：段数齐不齐、原文数字在译文里有没有都出现、有没有整段没翻成中文。
  python check.py [批名…]   （默认 out/ 里的全部批次）→ 汇总 + bad.json（要重翻的段落 id，按批分组）
"""
import json, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
SEG = re.compile(r"(?m)^### (\d+)\s*$")


def parse(path):
    t = open(path, encoding="utf-8").read()
    parts = SEG.split(t)
    return {int(parts[i]): parts[i + 1].strip() for i in range(1, len(parts) - 1, 2)}


def nums(s):
    s = re.sub(r"(?<=\d),(?=\d{3}\b)", "", s)  # 1,000 → 1000
    return re.findall(r"\d+(?:\.\d+)?", s)


def problems(en, zh):
    out = []
    cjk = len(re.findall(r"[\u4e00-\u9fff]", zh))
    if cjk < 0.25 * len(en.split()):
        out.append("没翻成中文")
    if len(zh) < 0.5 * len(en.split()):
        out.append("译文过短")
    # “3.2 million”→“320 万”、“1900s”→“20 世纪” 这类量级和年代换算不算缺失
    conv = set(re.findall(r"(\d+(?:\.\d+)?)(?:\s*(?:million|billion|thousand|trillion)\b|0?s\b)", re.sub(r"(?<=\d),(?=\d{3}\b)", "", en), re.I))
    miss = [n for n in set(nums(en)) - conv if n not in set(nums(zh))]
    if miss:
        out.append("数字缺失 " + " ".join(sorted(miss)[:6]))
    return out


def main():
    names = sys.argv[1:] or sorted(f[:-4] for f in os.listdir(os.path.join(HERE, "out")) if f.endswith(".txt"))
    bad, tot, ok_n, issues = {}, 0, 0, {"缺段": 0, "没翻成中文": 0, "译文过短": 0, "数字缺失": 0}
    for nm in names:
        src = parse(os.path.join(HERE, "in", nm + ".txt"))
        dst = parse(os.path.join(HERE, "out", nm + ".txt")) if os.path.exists(os.path.join(HERE, "out", nm + ".txt")) else {}
        b = []
        for i, en in src.items():
            tot += 1
            if i not in dst or not dst[i]:
                b.append((i, ["缺段"])); issues["缺段"] += 1; continue
            p = problems(en, dst[i])
            for x in p: issues[x.split(" ")[0]] += 1
            if p: b.append((i, p))
            else: ok_n += 1
        if b: bad[nm] = b
    print(f"{len(names)} 批，{tot} 段：通过 {ok_n}，" + "，".join(f"{k} {v}" for k, v in issues.items()))
    json.dump(bad, open(os.path.join(HERE, "bad.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=0)
    for nm, b in list(bad.items())[:5]:
        print(nm, b[:4])


if __name__ == "__main__":
    main()
