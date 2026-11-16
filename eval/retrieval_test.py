# -*- coding: utf-8 -*-
"""检索质量测试：模拟 App 的检索逻辑（FTS5 OR 查询取 50 个候选 → 按覆盖检索词个数重排 → 去重 → 取前 4）。

用法：python retrieval_test.py <资料包.arkpack> [...]
每题给出模型大概会生成的英文检索词，和“好段落”应该含有的正则；前 4 段里有一段命中就算找到。
"""
import re, sqlite3, sys

TESTS = [
    ("bleach drinking water disinfection", r"(2|two|8|eight|16)\s*drops?.{0,60}(quart|gallon|liter|litre)"),
    ("snake bite first aid", r"(immobili|keep .{0,20}still|splint|pressure (bandage|immobili))"),
    ("leg fracture splint", r"(joints? above and below|above and below the)"),
    ("hypothermia treatment rewarming", r"(remove wet cloth|dry cloth|warm.{0,30}(drink|liquid)|skin.to.skin|sleeping bag)"),
    ("diarrhea dehydration oral rehydration solution", r"(teaspoons?|handful).{0,60}(salt|sugar)"),
    ("wound infection signs", r"(pus|red streak|swollen|swelling)"),
    ("burn first aid cool water", r"(cool|cold) water"),
    ("bleeding direct pressure tourniquet", r"direct pressure"),
    ("heat stroke cooling", r"(cool|shade|fan|wet).{0,60}(body|skin|cloth)"),
    ("CPR chest compressions", r"(compressions?|30\s*(:|to)\s*2|100)"),
    ("boil water purification minute", r"boil.{0,80}(minute|min)"),
    ("tick removal", r"(tweezers|forceps|pull.{0,20}(steady|straight|slowly))"),
    ("anaphylaxis epinephrine", r"(epinephrine|adrenaline)"),
    ("knot bowline tie", r"bowline"),
    ("food storage canning botulism", r"botulism"),
    ("solar water disinfection SODIS bottle", r"(sodis|bottles?.{0,60}(sun|hours))"),
]


def search(db, terms, k=4, cand=50):
    m = " OR ".join(f'"{t}"' for t in terms)
    rows = db.execute(
        "SELECT c.id, d.title, c.text, bm25(chunks_fts,1.0,0.0) FROM chunks_fts JOIN chunks c ON c.id=chunks_fts.rowid "
        "LEFT JOIN docs d ON d.id=c.doc_id WHERE chunks_fts MATCH ? ORDER BY 4 LIMIT ?", (m, cand)).fetchall()
    stems = [t.lower()[:5] for t in terms if len(t) >= 3]
    rows.sort(key=lambda r: (-sum(s in r[2].lower() for s in stems), r[3]))
    seen, out = set(), []
    for r in rows:
        key = re.sub(r"\W", "", r[2][:200].lower())
        if key in seen:
            continue
        seen.add(key); out.append(r)
        if len(out) == k:
            break
    return out


for path in sys.argv[1:]:
    db = sqlite3.connect(path)
    ok, words = 0, []
    for q, pat in TESTS:
        res = search(db, q.split())
        words += [len(r[2].split()) for r in res]
        hit = next((i for i, r in enumerate(res) if re.search(pat, r[2], re.I | re.S)), None)
        ok += hit is not None
        print(f"  {'✓' if hit is not None else '✗'} {q:45s} {'' if hit is None else '第%d段' % (hit + 1)}")
    print(f"{path}: 找到 {ok}/{len(TESTS)}，平均每段 {sum(words) / max(1, len(words)):.0f} 词\n")
