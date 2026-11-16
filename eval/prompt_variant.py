# -*- coding: utf-8 -*-
"""提示词对比实验：同一个模型、同一题库，换一套提示词，看准确率和回答长度（= 手机上的耗电）怎么变。

用法：python prompt_variant.py <变体名> <gguf路径> [端口=8092]
结果写到 results_variants/<变体名>.jsonl，并打印和 results/ 里同模型原提示词的对比。
"""
import json, os, statistics, subprocess, sys, time
import run_eval as R
import score as S

VARIANTS = {
    # 原提示词基础上加长度约束：手机上生成约 20 字/秒，少 100 个字就省约 5 秒、约 20 焦耳
    "short": dict(
        closed=R.SYS_CLOSED + "回答控制在 150 字以内，只写最关键的结论、数字和步骤，不要写背景知识和免责声明。",
        rag=R.SYS_RAG + "回答控制在 150 字以内，只写和问题直接相关的要点。",
    ),
}

name, gguf = sys.argv[1], sys.argv[2]
port = int(sys.argv[3]) if len(sys.argv) > 3 else 8092
variant = name.split("@")[0]
v = VARIANTS[variant]
R.SYS_CLOSED, R.SYS_RAG, R.PORT = v["closed"], v["rag"], port

os.makedirs(os.path.join(R.HERE, "results_variants"), exist_ok=True)
out = os.path.join(R.HERE, "results_variants", f"{name}.jsonl")
if not os.path.exists(out):
    log = open(out + ".server.log", "w", encoding="utf-8")
    proc = subprocess.Popen([R.SERVER, "-m", gguf, "--port", str(port), "-ngl", "99", "-c", "8192", "--jinja", "-np", "1",
                             "--no-webui"], stdout=log, stderr=subprocess.STDOUT)
    try:
        R.wait_ready(proc)
        with open(out, "w", encoding="utf-8", newline="\n") as f:
            for x in R.BANK:
                t = time.time()
                body = {"messages": R.messages(x), "temperature": 0, "top_k": 1, "max_tokens": 900,
                        "chat_template_kwargs": {"enable_thinking": False}}
                r = R.post("/v1/chat/completions", body)
                rec = dict(id=x["id"], answer=r["choices"][0]["message"].get("content") or "",
                           completion_tokens=r.get("usage", {}).get("completion_tokens"),
                           finish=r["choices"][0].get("finish_reason"), seconds=round(time.time() - t, 2))
                f.write(json.dumps(rec, ensure_ascii=False) + "\n")
    finally:
        proc.terminate(); proc.wait(30); log.close()


def summarize(path):
    recs = {r["id"]: r for r in map(json.loads, open(path, encoding="utf-8"))}
    sc = [S.score_one(S.BANK[i], recs[i]) for i in S.BANK]
    by = lambda f: [x for x in sc if f(x)]
    avg = lambda xs: round(100 * statistics.mean(x["score"] for x in xs), 1)
    return dict(闭卷=avg(by(lambda x: x["cat"] in ("med", "radio", "mech"))), 开卷=avg(by(lambda x: x["cat"] == "rag" and x["type"] == "fact")),
                承认不知道=avg(by(lambda x: x["type"] == "unans")), 求生必需=avg(by(lambda x: x["cat"] == "refusal")),
                闭卷平均token=round(statistics.mean(x["tokens"] for x in by(lambda x: x["cat"] in ("med", "radio", "mech")))),
                开卷平均token=round(statistics.mean(x["tokens"] for x in by(lambda x: x["cat"] == "rag"))))


base = os.path.join(R.HERE, "results", name.split("@")[1] + ".jsonl") if "@" in name else None
print("变体", name, summarize(out))
if base and os.path.exists(base):
    print("原版", os.path.basename(base), summarize(base))
