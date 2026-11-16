# -*- coding: utf-8 -*-
"""把还没译的段落切成小文件，让子代理只做“读一个小文件 → 写一个小文件”，检查和合并交给脚本。
  python chunk.py split   → in_c/<批>_<序号>.txt（每个约 2 万字符）+ chunks.json；已有完整译文的小文件跳过
  python chunk.py merge   → 把 out_c/ 的译文并回 out/<批>.txt（按原文顺序），之后跑 check.py
"""
import json, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__)).replace(os.sep, "/")
SEG = re.compile(r"(?m)^### (\d+)\s*$")
LIMIT = 20000


def parse(path):
    if not os.path.exists(path):
        return {}
    parts = SEG.split(open(path, encoding="utf-8").read())
    return {int(parts[i]): parts[i + 1].strip() for i in range(1, len(parts) - 1, 2)}


def batches():
    return [m["name"] for m in json.load(open(f"{HERE}/batches.json", encoding="utf-8"))]


def split():
    os.makedirs(f"{HERE}/in_c", exist_ok=True); os.makedirs(f"{HERE}/out_c", exist_ok=True)
    plan, n_seg = {}, 0
    for b in batches():
        en = parse(f"{HERE}/in/{b}.txt")
        done = parse(f"{HERE}/out/{b}.txt")
        todo = [i for i in en if not done.get(i)]
        groups, cur, size = [], [], 0
        for i in todo:
            if cur and size + len(en[i]) > LIMIT:
                groups.append(cur); cur, size = [], 0
            cur.append(i); size += len(en[i])
        if cur: groups.append(cur)
        names = []
        for k, g in enumerate(groups):
            nm = f"{b}_{k:02d}"
            if all(parse(f"{HERE}/out_c/{nm}.txt").get(i) for i in g) and os.path.exists(f"{HERE}/in_c/{nm}.txt") \
                    and list(parse(f"{HERE}/in_c/{nm}.txt")) == g:
                continue  # 这个小文件上次已经译完
            open(f"{HERE}/in_c/{nm}.txt", "w", encoding="utf-8", newline="\n").write(
                "\n\n".join(f"### {i}\n{en[i]}" for i in g) + "\n")
            names.append(dict(name=nm, n=len(g))); n_seg += len(g)
        if names: plan[b] = names
    json.dump(plan, open(f"{HERE}/chunks.json", "w", encoding="utf-8"), ensure_ascii=False, indent=0)
    print(f"{len(plan)} 批还有没译的，{sum(len(v) for v in plan.values())} 个小文件，{n_seg} 段")


def merge():
    n = 0
    for b in batches():
        parts = sorted(f for f in os.listdir(f"{HERE}/out_c") if f.startswith(b + "_"))
        if not parts: continue
        en = parse(f"{HERE}/in/{b}.txt")
        zh = parse(f"{HERE}/out/{b}.txt")
        for f in parts:
            zh.update({k: v for k, v in parse(f"{HERE}/out_c/{f}").items() if v and k in en})
        open(f"{HERE}/out/{b}.txt", "w", encoding="utf-8", newline="\n").write(
            "\n\n".join(f"### {i}\n{zh[i]}" for i in en if zh.get(i)) + "\n")
        n += 1
    print("合并", n, "批")


if __name__ == "__main__":
    {"split": split, "merge": merge}[sys.argv[1]]()
