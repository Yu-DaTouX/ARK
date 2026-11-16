# -*- coding: utf-8 -*-
"""“硬核求生”问题（处理和吃野生动物、陷阱、昆虫、极端情况）按 App 的完整流程逐步排查：
  模型生成英文检索词 → 混合检索（v2 资料包）→ 模型作答。每一步都存下来，看问题卡在哪一环。

  python probe_hard.py kw     <模型.litertlm>     → probe/kw_in.jsonl，跑 gen 后得到检索词
  python probe_hard.py search                     → 检索，写 probe/ans_in.jsonl（给 gen 作答用）
  python probe_hard.py show   <答案.jsonl>        → 打印检索词、资料标题、回答
"""
import json, os, sqlite3, subprocess, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import rag_real as RR
import run_eval as R

HERE = os.path.dirname(os.path.abspath(__file__))
SET = os.environ.get("ARK_SET", "1")  # 1：野生动物与食物；2：武器、防护与自保
D = os.path.join(HERE, "probe" if SET == "1" else f"probe{SET}")
PACK = "F:/ARK/data/packs/v2/ark-core.arkpack"
QS2 = [
    "怎么做一根打猎用的矛", "怎么做简易弓箭", "怎么做投石索或者弹弓打猎", "没有刀怎么做一把石刀",
    "晚上营地怎么防止野兽靠近", "遇到熊或者野狗怎么自保", "灾后有人来抢物资怎么保护自己和家人",
    "被人持刀威胁怎么脱身", "怎么做一个简易防尘口罩", "核爆后怎么躲避辐射尘", "怎么安全地使用和保养猎枪",
    "怎么给营地设置警戒和报警装置",
]
QS3 = ["怎么做便携指南针", "没有指南针怎么辨别方向", "怎么用镜子或者火堆发求救信号", "没有火柴和打火机怎么生火"]
QS = QS2 if SET == "2" else QS3 if SET == "3" else [
    "怎么处理和食用野生动物", "打到一只兔子怎么剥皮和去内脏", "野外抓到的蛇能吃吗，怎么处理", "哪些昆虫可以吃，怎么吃",
    "怎么做一个简单的陷阱抓小动物", "野生动物的肉要怎么煮才不会得寄生虫病", "抓到的鱼怎么处理和保存", "没有冰箱怎么把肉做成肉干",
    "饿极了能吃老鼠吗", "路边发现死了的动物能吃吗", "能喝自己的尿或者海水吗", "怎么判断野果和野菜能不能吃",
]


def gen(model, src, dst, max_tokens):
    env = dict(os.environ, JAVA_HOME="C:/Program Files/Android/Android Studio/jbr",
               JAVA_OPTS="-Djava.library.path=F:/ARK/tools/embed/native", ARK_EMBED_CPU="1")
    subprocess.run(["F:/ARK/tools/embed/build/install/ark-embed/bin/ark-embed.bat", "gen", model, src, dst, str(max_tokens)],
                   check=True, env=env, capture_output=True)


def main():
    os.makedirs(D, exist_ok=True)
    mode = sys.argv[1]
    if mode == "kw":
        with open(os.path.join(D, "kw_in.jsonl"), "w", encoding="utf-8", newline="\n") as f:
            for q in QS:
                f.write(json.dumps(dict(q=q, system=R.SYS_KW, user=q), ensure_ascii=False) + "\n")
        gen(sys.argv[2], os.path.join(D, "kw_in.jsonl"), os.path.join(D, "kw_out.jsonl"), 48)
    elif mode == "search":
        db = sqlite3.connect(PACK)
        vecs = RR.load_vecs(db)
        qv = RR.query_vecs(QS)
        kw = {r["q"]: r["answer"] for r in map(json.loads, open(os.path.join(D, "kw_out.jsonl"), encoding="utf-8"))}
        with open(os.path.join(D, "ans_in.jsonl"), "w", encoding="utf-8", newline="\n") as f:
            for q in QS:
                terms = RR.parse_keywords(kw[q])
                ps = RR.search(db, terms, qvec=qv[q], vecs=vecs)
                f.write(json.dumps(dict(q=q, kw=kw[q], terms=terms, passages=ps, system=R.SYS_RAG, user=RR.rag_user(ps, q)),
                                   ensure_ascii=False) + "\n")
    elif mode == "closed":  # 不给资料（App 检索不到时走这条路），看模型本身会不会拒答
        with open(os.path.join(D, "closed_in.jsonl"), "w", encoding="utf-8", newline="\n") as f:
            for q in QS:
                f.write(json.dumps(dict(q=q, system=R.SYS_CLOSED, user=q), ensure_ascii=False) + "\n")
        gen(sys.argv[2], os.path.join(D, "closed_in.jsonl"), os.path.join(D, "closed_out.jsonl"), 600)
        for r in map(json.loads, open(os.path.join(D, "closed_out.jsonl"), encoding="utf-8")):
            print(f"\n===== {r['q']}\n" + r["answer"][:300].replace("\n", " / "))
    elif mode == "show":
        src = {r["q"]: r for r in map(json.loads, open(os.path.join(D, "ans_in.jsonl"), encoding="utf-8"))}
        for r in map(json.loads, open(sys.argv[2], encoding="utf-8")):
            s = src[r["q"]]
            print(f"\n===== {r['q']}\n检索词：{s['terms']}")
            for i, p in enumerate(s["passages"], 1):
                print(f"  [{i}] {p['title'][:40]}｜{p['text'][:110].replace(chr(10), ' ')}")
            print("回答：" + r["answer"][:500].replace("\n", " / "))


if __name__ == "__main__":
    main()
