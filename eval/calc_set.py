# -*- coding: utf-8 -*-
"""计算题评测集：资料里有公式/数据、需要代入计算的题。和 App 一样检索（关键词 + 语义），中文资料包。
  python calc_set.py prep   → ab/in_calc_plain.jsonl（App 现行提示词，无工具）、ab/in_calc_tool.jsonl（带计算器）
答案（ref）是人工算好的参考值，用来判对错。"""
import json, os, sqlite3, sys
import numpy as np
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rag_real as RR

D = os.path.join(os.path.dirname(os.path.abspath(__file__)), "ab")
EN = "F:/ARK/data/packs/v4_256/ark-core.arkpack"
ZH = "F:/ARK/data/packs/zh/ark-core.arkpack"
QS = [
    ("146 MHz 信号的波长大约是多少？", "frequency wavelength radio MHz meters", "约 2.05 m"),
    ("做一副工作在 7.1 MHz 的半波偶极天线，总长度大约多少米？", "half-wave dipole antenna length formula MHz", "约 20 m（468/f=65.9 ft≈20.1 m；理论 21.1 m）"),
    ("做一根 146 MHz 的四分之一波长垂直天线，振子大约多长？", "quarter-wave vertical antenna length formula", "约 49–51 cm（234/146=1.6 ft）"),
    ("100 牛·米的扭矩大约等于多少磅·英尺？", "torque newton meters foot pounds conversion", "约 73.8 lbf·ft"),
    ("轮胎气压 32 psi 大约等于多少 bar？", "tire pressure psi bar conversion", "约 2.2 bar"),
    ("用家用漂白水（5% 次氯酸钠）消毒 20 升水，要加多少滴？", "bleach drops disinfect water liter chlorine", "约 40 滴（2 滴/升）"),
    ("18 公斤的孩子发烧，对乙酰氨基酚（扑热息痛）一次给多少毫克？", "paracetamol acetaminophen dose child mg per kg", "约 180–270 mg（10–15 mg/kg）"),
    ("自己配 3 升口服补液盐，要放多少糖和多少盐？", "oral rehydration solution sugar salt liter teaspoon", "糖 18 平茶匙、盐 1.5 平茶匙（每升 6 + 1/2）"),
    ("4 个人准备 7 天的饮用水，一共要储备多少？", "emergency water storage per person per day gallon", "28 加仑≈106 L（1 加仑/人/天）"),
    ("体温 40°C 等于多少华氏度？", "fever temperature fahrenheit celsius", "104 °F"),
    ("看到闪电后 9 秒听到雷声，雷暴离我大约多远？", "lightning thunder seconds distance", "约 3 km（3 秒/km，或 5 秒/英里≈1.8 英里）"),
    ("60 公斤的成人中度脱水，按资料的补液量 4 小时内大约要喝多少？", "dehydration oral rehydration ml per kg 4 hours", "约 4.5 L（75 mL/kg）"),
    ("一块 5000 mAh、3.85 V 的手机电池大约存了多少瓦时的电？", "battery capacity watt hours amp hours voltage", "19.25 Wh"),
    ("30 米长的绳子从 10 米高的地方下降，对折后用双绳下降够不够？", "rappel rope length double rope descent", "对折后 15 m > 10 m，够（需留余量）"),
]
TOOL_SYS = ("你是一个离线求生助手。下面给出从离线资料库检索到的资料。请根据资料，用简体中文简洁回答，并在用到资料的句子后标注出处编号，如 [1]。"
            "资料往往不会逐字回答问题：如果资料讲了相关的原理、做法、公式或数字，就把它们整理成能照着做的步骤；"
            "需要计算时，调用 calculate 工具，把数字连同单位写进算式（如 3e8 m/s / 146 MHz to m），不要心算；回答里写出公式、代入的数字和工具算出的结果。工具报错就改写算式再试；实在算不出，也要照常按资料回答，不能因为算不出就说资料中没有。"
            "只有资料里确实没有相关内容时，才回答“资料中没有提到”。不要用资料以外的知识补充数字、剂量或药名。")

def prep():
    sys.path.insert(0, "F:/ARK/app")
    plain = open("F:/ARK/app/app/src/main/java/app/ark/llm/Prompts.kt", encoding="utf-8").read()
    import re
    blk = plain[plain.index("const val RAG"):plain.index("const val KEYWORDS")]
    rag = "".join(re.findall(r'"([^"]*)"', blk))
    en, zh = sqlite3.connect(EN), sqlite3.connect(ZH)
    ids, M = RR.load_vecs(en)
    M = M / np.linalg.norm(M, axis=1, keepdims=True)
    qv = RR.query_vecs([q for q, _, _ in QS], 256)
    rows = []
    for q, kw, ref in QS:
        terms = list(dict.fromkeys(RR.parse_keywords(kw) + RR.parse_keywords(q)))
        ps = RR.search(en, terms, qvec=(qv[q] / np.linalg.norm(qv[q]))[None], vecs=(ids, M))
        texts = [zh.execute("SELECT text FROM chunks WHERE id=?", (p["id"],)).fetchone()[0] for p in ps]
        rows.append(dict(q=q, ref=ref, ids=[p["id"] for p in ps], user=RR.rag_user([dict(text=t) for t in texts], q)))
    for name, sysp, tools in (("plain", rag, False), ("tool", TOOL_SYS, True)):
        with open(os.path.join(D, f"in_calc_{name}.jsonl"), "w", encoding="utf-8", newline="\n") as f:
            for r in rows:
                f.write(json.dumps(dict(q=r["q"], ref=r["ref"], ids=r["ids"], system=sysp, user=r["user"], tools=tools), ensure_ascii=False) + "\n")
    print(len(rows), "题；RAG 提示词：", rag[:40])

if __name__ == "__main__":
    prep()
