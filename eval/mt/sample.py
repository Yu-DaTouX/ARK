# -*- coding: utf-8 -*-
"""翻译试点抽样：从 v4 资料包按类别分层抽 200 段，其中约一半挑含数字的段落（专门考数字、剂量、单位有没有翻错）。
  python sample.py [资料包] → sample.jsonl（id, src, cat, text）
"""
import json, os, random, re, sqlite3, sys

HERE = os.path.dirname(os.path.abspath(__file__))
PACK = sys.argv[1] if len(sys.argv) > 1 else "F:/ARK/data/packs/v4/ark-core.arkpack"
CATS = [  # 类别, 份数, 来源匹配
    ("医学", 60, r"mdwiki|No_Doctor|No Doctor|zimgit-medicine|ST 31-91B|Environmental Health"),
    ("求生", 50, r"post-disaster|water|food-prep|knots|FEMA|FM 3-25|Deadfalls|Steel Traps|Woodcraft|Shelters|Camp Life|Fish Pond"),
    ("无线电", 30, r"NEETS Module (10|17)|FM 24-18|ham\.stack"),
    ("机械电力", 40, r"TM 9-8000|Construction Mechanic|NEETS Module [125] |restarters|mechanics\.stack|energypedia"),
    ("其他", 20, r"wikiciv|scoutwiki|outdoors\.stack|^Wikipedia"),
]


def main():
    db = sqlite3.connect(PACK)
    rows = db.execute("SELECT c.id, d.src, c.text FROM chunks c JOIN docs d ON d.id = c.doc_id").fetchall()
    random.seed(20261115)
    out = []
    for cat, n, pat in CATS:
        pool = [r for r in rows if re.search(pat, r[1]) and 120 <= len(r[2].split()) <= 380]
        num = [r for r in pool if len(re.findall(r"\d+(?:\.\d+)?\s?(?:mg|ml|g|kg|°|%|cm|mm|m|ft|in|v|volts?|amps?|a|w|watts?|mhz|khz|ohms?|hours?|minutes?|days?|psi|lb)\b", r[2], re.I)) >= 2]
        pick = random.sample(num, min(len(num), n // 2))
        rest = [r for r in pool if r not in pick]
        pick += random.sample(rest, n - len(pick))
        out += [dict(id=i, src=s, cat=cat, text=t) for i, s, t in pick]
        print(cat, len(pool), "段可选，抽", len(pick), "（含数字", min(len(num), n // 2), "）")
    random.shuffle(out)
    with open(os.path.join(HERE, "sample.jsonl"), "w", encoding="utf-8", newline="\n") as f:
        for r in out:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")
    print("共", len(out), "段，", sum(len(r["text"].split()) for r in out), "词")


if __name__ == "__main__":
    main()
