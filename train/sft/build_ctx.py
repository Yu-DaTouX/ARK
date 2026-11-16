# -*- coding: utf-8 -*-
"""微调数据第 1 步：给每道训练问题配上 App 实际会检索到的 4 段资料。

  python build_ctx.py questions.jsonl ctx.jsonl

流程和 App 完全一样：Gemma 4 E4B（llama.cpp）把问题改写成英文检索词 → 关键词 + 语义向量混合检索（核心包 v2）→ 前 4 段。
这样训练时模型看到的就是真实的检索噪声（不相关段落、只部分相关的段落），学的是“在这种输入下怎么答”。
"""
import json, os, sqlite3, subprocess, sys

sys.path.insert(0, "F:/ARK/eval")
import rag_real as RR
import run_eval as R

PACK = "F:/ARK/data/packs/v2/ark-core.arkpack"
GGUF = "F:/ARK/models/gguf/gemma4-e4b.qat-Q4_0.gguf"


def main(src, dst):
    qs = [json.loads(l) for l in open(src, encoding="utf-8") if l.strip()]
    done = {json.loads(l)["q"] for l in open(dst, encoding="utf-8")} if os.path.exists(dst) else set()
    todo = [r for r in qs if r["q"] not in done]
    if not todo:
        return
    db = sqlite3.connect(PACK)
    vecs = RR.load_vecs(db)
    qv = RR.query_vecs([r["q"] for r in todo])
    log = open(dst + ".server.log", "w", encoding="utf-8")
    proc = subprocess.Popen([R.SERVER, "-m", GGUF, "--port", str(R.PORT), "-ngl", "99", "-c", "4096", "--jinja", "-np", "1",
                             "--no-webui"], stdout=log, stderr=subprocess.STDOUT)
    try:
        R.wait_ready(proc)
        with open(dst, "a", encoding="utf-8", newline="\n") as f:
            for i, r in enumerate(todo):
                kw, _, _ = RR.chat(R.SYS_KW, r["q"], 48)
                terms = RR.parse_keywords(kw) + [t for t in RR.parse_keywords(r["q"])]
                terms = list(dict.fromkeys(terms))
                ps = RR.search(db, terms, qvec=qv[r["q"]], vecs=vecs)
                f.write(json.dumps(dict(r, terms=terms, passages=ps), ensure_ascii=False) + "\n")
                if i % 50 == 0:
                    print(i, len(todo), flush=True)
    finally:
        proc.terminate(); proc.wait(30); log.close()


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
