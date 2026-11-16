# -*- coding: utf-8 -*-
"""比较检索方案：关键词（App 现状）/ 纯向量 / 混合（RRF）/ 向量重排关键词候选。

  python hybrid_test.py [资料包(含 vec 表)] [检索词来源模型]

命中标准：前 4 段里有一段同时匹配该题的全部正则（“好段落”应有的内容）。
关键词检索用 rag_real 里该模型真实生成的检索词（App 的流程），向量检索直接用中文问题（不需要模型生成检索词）。
查询向量由 tools/embed（与手机同一个 .litertlm）算出。
"""
import json, os, re, sqlite3, subprocess, sys
import numpy as np
import rag_real as RR

HERE = os.path.dirname(os.path.abspath(__file__))
PACK = sys.argv[1] if len(sys.argv) > 1 else "F:/ARK/data/packs/ark-core.vec.arkpack"
KW_MODEL = sys.argv[2] if len(sys.argv) > 2 else "gemma4-e4b"
EMBED = "F:/ARK/tools/embed/build/install/ark-embed/bin/ark-embed.bat"
EMODEL = "F:/ARK/models/embed/embeddinggemma-2-text-270m.litertlm"
K = 4

# 每题：好段落必须同时匹配的正则（英文原文）
GOLD = {
    "被蛇咬了怎么急救": [r"snake", r"(immobili|keep .{0,20}still|splint|pressure (bandage|immobili)|do not (cut|suck)|below (the )?(heart|level))"],
    "怎么用漂白剂消毒饮用水": [r"bleach", r"drops?"],
    "伤口感染有哪些迹象": [r"infect", r"(pus|red streak|swollen|swelling|warm|fever|red)"],
    "严重腹泻怎么防止脱水": [r"(diarrh|dehydrat)", r"(rehydration|ORS|salt.{0,60}sugar|sugar.{0,60}salt)"],
    "怎么给骨折的腿上夹板": [r"splint", r"(joints? above and below|above and below|pad)"],
    "失温的人怎么取暖": [r"hypotherm", r"(wet cloth|dry cloth|sleeping bag|skin.to.skin|warm (drink|liquid|fluid)|insulat|rewarm)"],
    "中暑了怎么处理": [r"heat ?stroke", r"(cool|shade|fan|wet)"],
    "烧伤后怎么处理": [r"burn", r"(cool|cold) (running |clean )?water"],
    "大出血怎么止血": [r"bleed", r"(direct pressure|tourniquet)"],
    "怎么做心肺复苏": [r"(CPR|resuscitat|chest compression)", r"(compressions?|30\s*(:|to)\s*2|100|push hard|breaths?)"],
    "怎么判断一个人是不是休克了，怎么处理": [r"shock", r"(elevat|raise|legs|keep .{0,20}warm|lie|lay)"],
    "被蜱虫咬了怎么取出来": [r"\bticks?\b", r"(tweezers|forceps|pull|grasp)"],
    "煮沸消毒饮用水要煮多久": [r"boil", r"minute"],
    "没有冰箱怎么长期保存肉": [r"(meat|fish)", r"(drying|dried|smok|salt|jerky|cur)"],
    "自己做罐头怎么避免肉毒杆菌中毒": [r"botulism", r"(pressure can|canner|acid|boil)"],
    "怎么用太阳晒瓶子消毒水": [r"(sodis|solar)", r"bottle", r"(hour|sun)"],
    "怎么挖一个简易厕所": [r"(latrine|toilet|pit)", r"(dig|deep|depth|met(er|re)s?|feet)"],
    "洪水过后家里的食物还能吃吗": [r"flood", r"food"],
    "怎么打一个称人结": [r"bowline"],
    "怎么判断野生蘑菇能不能吃": [r"mushroom", r"(identif|poison|edible)"],
    "婴儿腹泻脱水有什么表现": [r"dehydrat", r"(sunken|fontanel|soft spot|dry mouth|no tears|little urine|urinat)"],
    "牙痛没有医生怎么办": [r"(tooth|teeth|dental)", r"(pain|ache|clove|abscess)"],
    "眼睛进了异物怎么处理": [r"\beyes?\b", r"(foreign|speck|dirt|sand|rinse|flush|irrigat)"],
    "怎么处理冻伤": [r"frostbite", r"(rewarm|warm water|thaw|rub)"],
    "被狗咬了怎么处理，要担心狂犬病吗": [r"(rabies|dog bite|animal bite)", r"(wash|soap|clean)"],
    "怎么清洗和包扎伤口": [r"wound", r"(clean|wash|irrigat)", r"(dressing|bandage|cover)"],
    "关节脱臼了可以自己复位吗": [r"dislocat", r"(reduc|pull|traction|back into (place|position)|relocat|splint)"],
    "一个人一天至少需要多少水": [r"water", r"((lit(er|re)s?|quarts?|gallons?).{0,40}(a|per|each) day|daily)"],
    "怎么判断水源是否安全": [r"water (source|supply|supplies)", r"(contaminat|safe|pollut|sewage|latrine|animal)"],
    "灾后怎么防止霍乱传播": [r"cholera", r"(water|wash|latrine|sanitation|hand)"],
    "怎么搭建一个临时避难所": [r"shelter", r"(build|construct|lean.to|tarp|branch|poncho)"],
    "没有火柴怎么生火": [r"fire", r"(flint|bow drill|friction|spark|magnif|lens|tinder)"],
    "一氧化碳中毒有什么症状": [r"carbon monoxide", r"(headache|dizz|nausea|symptom|weak|confus)"],
    "怎么识别中风": [r"(?<!heat )(?<!heat-)\bstroke", r"(face|arm|speech|numb|weakness|droop|one side)"],
    "孕妇快生了但没有医生怎么办": [r"(birth|labou?r|deliver)", r"(cord|placenta|push)"],
    "怎么给发烧的孩子降温": [r"fever", r"(sponge|lukewarm|cool|undress|paracetamol|acetaminophen)"],
    "哮喘发作没有药怎么办": [r"asthma", r"(sit|breath|calm|steam|inhal)"],
    "被蜜蜂蜇了怎么处理": [r"(bee|wasp|sting)", r"(scrape|stinger|remove the sting|ice|cold|allerg)"],
    "地震后被困在废墟里该怎么做": [r"(earthquake|collapse|rubble|trapped|debris)", r"(tap|whistle|signal|cover your mouth|shout|dust)"],
    "怎么储存饮用水才不会变质": [r"water", r"stor", r"(container|bottle|cool|dark|replace|months?|clean)"],
}


def hit(text, pats):
    return all(re.search(p, text, re.I | re.S) for p in pats)


def embed_queries(qs, tag):
    cache = os.path.join(HERE, "results_rag", f"qvec_{tag}.jsonl")
    if not os.path.exists(cache):
        tmp = os.path.join(HERE, "results_rag", f"q_{tag}.txt")
        open(tmp, "w", encoding="utf-8", newline="\n").write("\n".join(qs) + "\n")
        subprocess.run([EMBED, "query", EMODEL, tmp, cache, "256"], check=True, capture_output=True)
    return np.array([json.loads(l) for l in open(cache)], dtype=np.float32)


def load_vecs(db):
    ids, mats = [], []
    for cid, blob in db.execute("SELECT chunk_id, v FROM vec ORDER BY chunk_id"):
        scale = np.frombuffer(blob[:4], "<f4")[0]
        ids.append(cid); mats.append(np.frombuffer(blob[4:], np.int8).astype(np.float32) * scale)
    return np.array(ids), np.stack(mats)


def main():
    db = sqlite3.connect(PACK)
    text = dict(db.execute("SELECT id, text FROM chunks"))
    ids, M = load_vecs(db)
    print(f"{len(ids)} 条段落向量，{M.shape[1]} 维")
    recs = {r["q"]: r for r in map(json.loads, open(os.path.join(HERE, "results_rag", f"{KW_MODEL}.jsonl"), encoding="utf-8"))}
    qs = RR.QUESTIONS
    Q = embed_queries(qs, "zh")

    def dedupe(cands, k=K):
        seen, out = set(), []
        for cid in cands:
            key = re.sub(r"\W", "", text[cid][:200].lower())
            if key in seen:
                continue
            seen.add(key); out.append(cid)
            if len(out) == k:
                break
        return out

    def bm25_list(terms):  # App 现状：FTS5 OR → 覆盖度重排（返回全部候选，按顺序）
        if not terms:
            return []
        m = " OR ".join(f'"{t}"' for t in terms[:10])
        rows = db.execute("SELECT c.id, c.text, bm25(chunks_fts,1.0,0.0) FROM chunks_fts JOIN chunks c ON c.id=chunks_fts.rowid "
                          "WHERE chunks_fts MATCH ? ORDER BY 3 LIMIT 50", (m,)).fetchall()
        stems = [t.lower()[:5] for t in terms if len(t) >= 3]
        rows.sort(key=lambda r: (-sum(s in r[1].lower() for s in stems), r[2]))
        return [r[0] for r in rows]

    def rrf(*lists, k=60):
        sc = {}
        for L in lists:
            for rank, cid in enumerate(L):
                sc[cid] = sc.get(cid, 0) + 1 / (k + rank + 1)
        return sorted(sc, key=lambda c: -sc[c])

    methods = {"关键词（现状）": [], "纯向量": [], "混合 RRF": [], "向量重排关键词候选": []}
    rows = []
    for qi, q in enumerate(qs):
        sims = M @ Q[qi]
        vec = [int(ids[i]) for i in np.argsort(-sims)[:50]]
        kw = bm25_list(recs[q]["terms"])
        pos = {int(c): i for i, c in enumerate(ids)}
        rer = sorted(kw, key=lambda c: -sims[pos[c]])
        res = {"关键词（现状）": dedupe(kw), "纯向量": dedupe(vec), "混合 RRF": dedupe(rrf(kw, vec)), "向量重排关键词候选": dedupe(rer)}
        line = [q[:12]]
        for name, top in res.items():
            h = next((i + 1 for i, c in enumerate(top) if hit(text[c], GOLD[q])), 0)
            methods[name].append(h)
            line.append(f"{name[:2]}:{h or '✗'}")
        rows.append("  ".join(line))
    print("\n".join(rows))
    print(f"\n命中（前 {K} 段内有好段落），检索词来自 {KW_MODEL}：")
    for name, hs in methods.items():
        print(f"  {name:12s} {sum(1 for h in hs if h)}/{len(hs)}   其中排第 1：{sum(1 for h in hs if h == 1)}")


if __name__ == "__main__":
    main()
