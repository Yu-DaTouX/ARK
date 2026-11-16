# -*- coding: utf-8 -*-
"""数字抄错统计：回答里的数字（≥3 位）在资料+问题里找不到，且和资料里某个数字只差一位（多一位、少一位或改一位）→ 记为“抄错”。
  python num_check.py <out.jsonl> ...
另外统计“资料里找不到的数字”总数（含合理的换算结果，只作参考）和平均用时、解码速度。
"""
import json, re, sys

IN = "ab/in_num.jsonl"

NUM = re.compile(r"\d[\d,]*(?:\.\d+)?")


def nums(s):
    return {n.replace(",", "").rstrip(".") for n in NUM.findall(s)}


def near(a, b):
    """编辑距离为 1（插入、删除、替换一个字符）。"""
    if a == b or abs(len(a) - len(b)) > 1:
        return False
    if len(a) == len(b):
        return sum(x != y for x, y in zip(a, b)) == 1
    if len(a) > len(b):
        a, b = b, a
    return any(b[:i] + b[i + 1:] == a for i in range(len(b)))


def check(path):
    rs = [json.loads(l) for l in open(path, encoding="utf-8")]
    ins = {r["q"]: r["user"] for r in map(json.loads, open(IN, encoding="utf-8"))}  # 手机输出不带 user，按题目找回
    bad, unk, ex = 0, 0, []
    for r in rs:
        src = nums(r.get("user") or ins[r["q"]])
        for n in nums(r["answer"]):
            d = n.replace(".", "")
            if len(d) < 3 or n in src:
                continue
            unk += 1
            hit = [s for s in src if len(s.replace(".", "")) >= 3 and near(n, s)]
            if hit:
                bad += 1
                ex.append(f"{hit[0]}→{n}")
    sec = sum(r.get("seconds", 0) for r in rs) / len(rs)
    print(f"{path}: {len(rs)} 题，抄错 {bad}，资料里找不到的数字 {unk}，平均 {sec:.1f} 秒  例：{' '.join(ex[:8])}")


if __name__ == "__main__":
    for p in sys.argv[1:]:
        check(p)
