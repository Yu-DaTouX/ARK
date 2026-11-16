# -*- coding: utf-8 -*-
"""检索命中率：40 道评测题 + 28 道硬核求生题，比较资料包（v2 / v3）、检索词提示词（旧 / 新）、查询向量（中文 / 中文+英文）。

  python retr_eval.py kw  <名 old|new> <模型.litertlm>     → retr/kw_<名>.jsonl（模型按该提示词生成的检索词）
  python retr_eval.py hit <资料包>[@256] [...]              → 每题命中名次 + 汇总（每个包 × 每套检索词 × 查询方式）
两种判定：
  规则：前 4 段里有一段同时匹配该题的全部正则（粗筛，会把只沾边的段落算进去）。
  评分：retr/labels.jsonl 里 Opus 给每个“问题 + 段落”的分（2 能直接回答 / 1 部分有用 / 0 无关），见 retr_judge.py。
每种方案的前 4 段存进 retr/top.json，供 retr_judge.py 出评分任务。
"""
import hashlib, json, os, re, sqlite3, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rag_real as RR
import run_eval as R
from hybrid_test import GOLD
from probe_hard import gen

HERE = os.path.dirname(os.path.abspath(__file__))
D = os.path.join(HERE, "retr")

HARD = {  # 每题要同时出现“做法”的关键词，只沾边（如皮毛定价、疥癣）不算
    "怎么处理和食用野生动物": [r"(entrails|gut(ting|ted|s)?\b|field.dress|eviscerat|butcher)", r"(skin|hide|carcass)"],
    "打到一只兔子怎么剥皮和去内脏": [r"(rabbit|small game|small mammal|hare)", r"(entrails|gut(ting|s)?\b|viscera|eviscerat)"],
    "野外抓到的蛇能吃吗，怎么处理": [r"snakes?\b", r"(cut off the head|remove the head|skin(ning)? (the )?snake|snakes? .{0,60}(edible|eat|cook|roast))"],
    "哪些昆虫可以吃，怎么吃": [r"(grasshopper|termite|grub|larva|locust|cricket)", r"(eat|edible|cook|roast)"],
    "怎么做一个简单的陷阱抓小动物": [r"(snare|deadfall|figure.4|twitch.up)", r"(trigger|noose|loop|bait)"],
    "野生动物的肉要怎么煮才不会得寄生虫病": [r"(parasit|trichin|tapeworm)", r"(cook|boil)", r"(meat|game|pork|animal)"],
    "抓到的鱼怎么处理和保存": [r"fish", r"(gut|scale|fillet|clean)", r"(salt|smok|dry|preserv|cook)"],
    "没有冰箱怎么把肉做成肉干": [r"(jerky|dried meat|drying meat|dry the meat|smok)", r"(strip|slice|thin)"],
    "饿极了能吃老鼠吗": [r"(\brats?\b|rodent|mice)", r"(edible|eat)"],
    "路边发现死了的动物能吃吗": [r"(carrion|dead animal|found dead|road.?kill|scaveng|spoil)", r"(eat|edible|meat)"],
    "能喝自己的尿或者海水吗": [r"urine", r"sea ?water"],
    "怎么判断野果和野菜能不能吃": [r"(edibility test|universal edibility|taste test|test .{0,40}plant|plant .{0,40}test)", r"(lip|tongue|skin|mouth)"],
    "怎么做一根打猎用的矛": [r"spears?\b", r"(sharpen|fire.harden|point)", r"(shaft|pole|stick|wood|sapling)"],
    "怎么做简易弓箭": [r"\bbows?\b", r"arrows?", r"(stave|bowstring|string|wood)"],
    "怎么做投石索或者弹弓打猎": [r"(sling|slingshot)", r"(stone|rock|pouch)"],
    "没有刀怎么做一把石刀": [r"(flint|obsidian|chert|stone)", r"(knife|blade|flake)", r"(strik|percuss|chip|flak|knap)"],
    "晚上营地怎么防止野兽靠近": [r"(animal|bear|predator|wolf|wolves)", r"camp", r"(food|hang|fire|away)"],
    "遇到熊或者野狗怎么自保": [r"(bear|dog)", r"(attack|charge)", r"(back away|fight|defend|eye contact|stand|retreat|play dead)"],
    "灾后有人来抢物资怎么保护自己和家人": [r"(loot|intruder|crime|defend|security)", r"(home|house|supplies|family)"],
    "被人持刀威胁怎么脱身": [r"knife", r"(attacker|assailant|defen|disarm|threat)"],
    "怎么做一个简易防尘口罩": [r"(mask|respirator)", r"(cloth|towel|fabric|handkerchief)"],
    "核爆后怎么躲避辐射尘": [r"fallout", r"(shelter|shield)"],
    "怎么安全地使用和保养猎枪": [r"(firearm|rifle|shotgun|\bguns?\b)", r"(muzzle|unload|clean|safety)"],
    "怎么给营地设置警戒和报警装置": [r"(alarm|guard|sentry|sentries|trip ?wire|early warning)", r"(camp|perimeter|noise|cans)"],
    "怎么做便携指南针": [r"compass", r"needle", r"(magnet|stroke|silk|battery)"],
    "没有指南针怎么辨别方向": [r"(shadow|stick|sun|stars?|polaris|southern cross)", r"(north|south)", r"(east|west)"],
    "怎么用镜子或者火堆发求救信号": [r"(mirror|signal fire)", r"signal"],
    "没有火柴和打火机怎么生火": [r"(bow drill|hand drill|fire plough|fire saw|flint and steel|ferro|lens|magnif|steel wool)", r"(tinder|spark|ember)"],
}
RM = {r["q"]: None for r in map(json.loads, open(os.path.join(HERE, "bank.jsonl"), encoding="utf-8"))
      if r["cat"] in ("radio", "mech")}  # 题库里的无线电 / 机械 40 题：没有规则，只看 Opus 评分
ALL = {**GOLD, **HARD, **RM}

KW = {
    "old": R.SYS_KW,
    "new": R.SYS_KW2,
}


def hit(text, pats):
    return bool(pats) and all(re.search(p, text, re.I) for p in pats)


def key(q, text):
    return hashlib.sha1((q + "\n" + " ".join(text.split())[:600]).encode("utf-8")).hexdigest()[:16]


def labels():
    p = os.path.join(D, "labels.jsonl")
    return {r["key"]: r["score"] for r in map(json.loads, open(p, encoding="utf-8"))} if os.path.exists(p) else {}


def main():
    os.makedirs(D, exist_ok=True)
    mode = sys.argv[1]
    if mode == "kw":
        name, model = sys.argv[2], sys.argv[3]
        src = os.path.join(D, f"kw_in_{name}.jsonl")
        with open(src, "w", encoding="utf-8", newline="\n") as f:
            for q in ALL:
                f.write(json.dumps(dict(q=q, system=KW[name], user=q), ensure_ascii=False) + "\n")
        gen(model, src, os.path.join(D, f"kw_{name}.jsonl"), 48)
        return
    import numpy as np
    sets = {n: {r["q"]: " ".join(r["answer"].split()) for r in map(json.loads, open(os.path.join(D, f"kw_{n}.jsonl"), encoding="utf-8"))}
            for n in KW if os.path.exists(os.path.join(D, f"kw_{n}.jsonl"))}
    norm = lambda X: X / np.linalg.norm(X, axis=-1, keepdims=True)
    res, tops = {}, {}
    for spec in sys.argv[2:]:  # 资料包路径，可加 @256 把向量截成 256 维（先截再归一化，和模型的 outputSize 一样）
        pack, _, cut = spec.partition("@")
        db = sqlite3.connect(pack)
        ids, M = RR.load_vecs(db)
        dim = int(cut or M.shape[1])
        full = M.shape[1]
        M = norm(M[:, :dim])
        tag = os.path.basename(os.path.dirname(pack)) + (f"@{dim}" if cut else "")
        for n, kws in sets.items():
            qv = RR.query_vecs(list(ALL) + [kws[q] for q in ALL], full)
            for qmode in (("zh",) if n == "old" else ("zh", "zh+en")):
                cfg = f"{tag}/{n}/{qmode}"
                col, top = res.setdefault(cfg, {}), tops.setdefault(cfg, {})
                for q, pats in ALL.items():
                    terms = RR.parse_keywords(kws[q], extra_stop=n != "old")
                    v = np.stack([qv[q], qv[kws[q]]]) if qmode == "zh+en" else qv[q][None]
                    ps = RR.search(db, terms, qvec=norm(v[:, :dim]), vecs=(ids, M))
                    col[q] = next((i + 1 for i, p in enumerate(ps) if hit(p["text"], pats)), 0)
                    top[q] = [dict(id=p["id"], title=p["title"], text=p["text"]) for p in ps]
    cols = list(res)
    lab = labels()
    print("  ".join(f"[{i}] {c}" for i, c in enumerate(cols)))
    print("题目（规则命中名次）".ljust(18) + "".join(f"[{i}]".rjust(5) for i in range(len(cols))))
    for q in ALL:
        print(q[:10] + "　" * (11 - len(q[:10])) + "".join(str(res[c][q] or "-").rjust(5) for c in cols))
    for name, qs in (("40 道评测题", GOLD), ("28 道硬核题", HARD), ("40 道无线电机械", RM)):
        print(f"规则 {name}" + "　" * (9 - len(name) // 2) + "".join(f"{sum(bool(res[c][q]) for q in qs)}".rjust(5) for c in cols))
        if lab:
            best = {c: [max((lab.get(key(q, p["text"]), -1) for p in tops[c][q]), default=-1) for q in qs] for c in cols}
            print(f"评分 {name} 有能直接回答的段" + "".join(f"{sum(b == 2 for b in best[c])}".rjust(5) for c in cols))
            print(f"评分 {name} 至少部分有用　" + "".join(f"{sum(b >= 1 for b in best[c])}".rjust(5) for c in cols))
            miss = sum(lab.get(key(q, p["text"])) is None for c in cols for q in qs for p in tops[c][q])
            if miss: print(f"  （还有 {miss} 段没评分）")
    json.dump(res, open(os.path.join(D, "hits.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    p = os.path.join(D, "top.json")
    old = json.load(open(p, encoding="utf-8")) if os.path.exists(p) else {}
    old.update(tops)
    json.dump(old, open(p, "w", encoding="utf-8"), ensure_ascii=False)


if __name__ == "__main__":
    main()
