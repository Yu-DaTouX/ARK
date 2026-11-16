# -*- coding: utf-8 -*-
"""真实场景评测：完整模拟 App 的问答流程，用内置资料包里真实检索到的段落。

  中文问题 → 模型生成英文检索词 → 在 .arkpack 里检索（和 App 同一套算法）→ 前 4 段 → 模型按资料作答

用法：
  python rag_real.py run   <模型名> <gguf路径>     # 用 llama.cpp 跑（GPU），结果写 results_rag/<模型名>.jsonl
  python rag_real.py judge <模型名> [...]          # 用 NInfer 上的大模型裁判
  python rag_real.py report                        # 汇总表
裁判看：问题、4 段资料、回答。打分只看“是否忠实于资料、是否有用”，不看模型自身知识；
另外判断“资料里到底有没有答案”，把检索失败和回答失败分开。
确定性指标：原文里找不到的数字个数（和 App 的数字核对同一规则）、回答 token 数（≈ 手机耗电）。
"""
import glob, json, os, re, sqlite3, statistics, subprocess, sys, time, urllib.request
import run_eval as R

HERE = os.path.dirname(os.path.abspath(__file__))
PACK = os.environ.get("ARK_PACK", "F:/ARK/data/packs/ark-core.arkpack")
OUT = os.path.join(HERE, os.environ.get("ARK_OUT", "results_rag"))
K = 4
HYBRID = os.environ.get("ARK_HYBRID", "1") == "1"  # 关键词 + 语义向量（RRF），与 App 的 PackRepository.search 一致
EMBED = "F:/ARK/tools/embed/build/install/ark-embed/bin/ark-embed.bat"
EMODEL = "F:/ARK/models/embed/embeddinggemma-2-text-270m.litertlm"

QUESTIONS = [
    "被蛇咬了怎么急救", "怎么用漂白剂消毒饮用水", "伤口感染有哪些迹象", "严重腹泻怎么防止脱水", "怎么给骨折的腿上夹板",
    "失温的人怎么取暖", "中暑了怎么处理", "烧伤后怎么处理", "大出血怎么止血", "怎么做心肺复苏",
    "怎么判断一个人是不是休克了，怎么处理", "被蜱虫咬了怎么取出来", "煮沸消毒饮用水要煮多久", "没有冰箱怎么长期保存肉",
    "自己做罐头怎么避免肉毒杆菌中毒", "怎么用太阳晒瓶子消毒水", "怎么挖一个简易厕所", "洪水过后家里的食物还能吃吗",
    "怎么打一个称人结", "怎么判断野生蘑菇能不能吃", "婴儿腹泻脱水有什么表现", "牙痛没有医生怎么办", "眼睛进了异物怎么处理",
    "怎么处理冻伤", "被狗咬了怎么处理，要担心狂犬病吗", "怎么清洗和包扎伤口", "关节脱臼了可以自己复位吗",
    "一个人一天至少需要多少水", "怎么判断水源是否安全", "灾后怎么防止霍乱传播", "怎么搭建一个临时避难所", "没有火柴怎么生火",
    "一氧化碳中毒有什么症状", "怎么识别中风", "孕妇快生了但没有医生怎么办", "怎么给发烧的孩子降温", "哮喘发作没有药怎么办",
    "被蜜蜂蜇了怎么处理", "地震后被困在废墟里该怎么做", "怎么储存饮用水才不会变质",
]

STOP = set("the and or of for to in on a an how what with is are do does can why when which from by at as be it use using keywords".split())
# 泛词：几乎每段求生资料都有，只会把泛泛的段落顶上来
STOP2 = set("safety safe emergency tips guide guidelines information methods method techniques technique diy procedures "
            "procedure survival basic basics simple overview steps ways practices handling".split())


def parse_keywords(raw, extra_stop=False):  # 与 App 的 Prompts.parseKeywords 一致
    out = []
    for w in re.findall(r"[A-Za-z][A-Za-z0-9-]*", raw):
        w = w.lower().strip("-")
        if len(w) >= 2 and w not in STOP and not (extra_stop and w in STOP2) and w not in out:
            out.append(w)
    return out[:8]


def load_vecs(db):
    import numpy as np
    if not db.execute("SELECT 1 FROM sqlite_master WHERE name='vec'").fetchone():
        return None
    ids, mats = [], []
    for cid, blob in db.execute("SELECT chunk_id, v FROM vec ORDER BY chunk_id"):
        ids.append(cid); mats.append(np.frombuffer(blob[4:], np.int8).astype(np.float32) * np.frombuffer(blob[:4], "<f4")[0])
    return np.array(ids), np.stack(mats)


def query_vecs(qs, dim=256):
    """问题向量（和手机同一个 .litertlm），按问题文本缓存。"""
    import numpy as np
    cache = os.path.join(HERE, "results_rag", "qvec_cache.jsonl" if dim == 256 else f"qvec_cache_{dim}.jsonl")
    have = {}
    if os.path.exists(cache):
        for l in open(cache, encoding="utf-8"):
            r = json.loads(l); have[r["q"]] = r["v"]
    todo = [q for q in qs if q not in have]
    if todo:
        tmp_in, tmp_out = cache + ".in.txt", cache + ".out.jsonl"
        open(tmp_in, "w", encoding="utf-8", newline="\n").write("\n".join(todo) + "\n")
        env = dict(os.environ, JAVA_OPTS="-Djava.library.path=F:/ARK/tools/embed/native",
                   JAVA_HOME="C:/Program Files/Android/Android Studio/jbr")
        subprocess.run([EMBED, "query", EMODEL, tmp_in, tmp_out, str(dim)], check=True, capture_output=True, env=env)
        with open(cache, "a", encoding="utf-8", newline="\n") as f:
            for q, l in zip(todo, open(tmp_out)):
                have[q] = json.loads(l); f.write(json.dumps(dict(q=q, v=have[q]), ensure_ascii=False) + "\n")
    return {q: np.array(have[q], dtype=np.float32) for q in qs}


def search(db, terms, k=K, cand=50, qvec=None, vecs=None):  # 与 App 的 PackRepository.search 一致
    import numpy as np
    kw = []
    if terms:
        m = " OR ".join(f'"{t}"' for t in terms[:10])
        rows = db.execute(
            "SELECT c.id, bm25(chunks_fts,1.0,0.0), c.text FROM chunks_fts JOIN chunks c ON c.id=chunks_fts.rowid "
            "WHERE chunks_fts MATCH ? ORDER BY 2 LIMIT ?", (m, cand)).fetchall()
        stems = [t.lower()[:5] for t in terms if len(t) >= 3]
        rows.sort(key=lambda r: (-sum(s in r[2].lower() for s in stems), r[1]))
        kw = [r[0] for r in rows]
    order = kw
    if qvec is not None and vecs is not None:
        ids, M = vecs
        qs = qvec if getattr(qvec, "ndim", 1) == 2 else [qvec]  # 可以传多个查询向量（中文问题 + 英文检索词），每个一路
        sems = [[int(ids[i]) for i in np.argsort(-(M @ v))[:cand]] for v in qs]
        sc = {}
        for L in [kw] + sems:
            for i, c in enumerate(L):
                sc[c] = sc.get(c, 0) + 1 / (60 + i + 1)
        order = sorted(sc, key=lambda c: -sc[c])
    seen, out = set(), []
    for cid in order:
        title, text = db.execute("SELECT coalesce(d.title, c.title, ''), c.text FROM chunks c LEFT JOIN docs d ON d.id=c.doc_id "
                                 "WHERE c.id=?", (cid,)).fetchone()
        key = re.sub(r"\W", "", text[:200].lower())
        if key in seen:
            continue
        seen.add(key)
        out.append(dict(id=cid, title=title, text=text))
        if len(out) == k:
            break
    return out


def rag_user(passages, q):  # 与 App 的 Prompts.ragUser 一致
    return "资料：\n" + "\n\n".join(f"[{i + 1}] {p['text']}" for i, p in enumerate(passages)) + f"\n\n问题：{q}"


NUM = re.compile(r"\d+(?:[.,]\d+)?")


WORDS = {w: str(i) for i, w in enumerate("zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen "
                                         "fifteen sixteen seventeen eighteen nineteen twenty".split())}
WORDS.update(thirty="30", forty="40", fifty="50", sixty="60", seventy="70", eighty="80", ninety="90", hundred="100",
             thousand="1000", half="0.5", once="1", twice="2", dozen="12", single="1", double="2", triple="3")
ZH = dict(zip("零一二两三四五六七八九十", ["0", "1", "2", "2", "3", "4", "5", "6", "7", "8", "9", "10"]))


def zh_numbers(t):  # 问题里的中文数字：二十 → 20，三 → 3（只处理一百以内）
    out = []
    for m in re.finditer(r"[零一二两三四五六七八九十]+", t):
        s = m.group()
        if "十" in s:
            a, _, b = s.partition("十")
            out.append(str((int(ZH.get(a, "1")) if a else 1) * 10 + (int(ZH[b]) if b else 0)))
        elif len(s) == 1:
            out.append(ZH[s])
    return out


def unverified(answer, passages, q):  # 与 App 的 unverifiedNumbers 一致
    norm = lambda s: (s.replace(",", "").rstrip("0").rstrip(".") if "." in s.replace(",", "") else s.replace(",", ""))
    texts = [p["text"] for p in passages] + [q]
    known = {norm(m) for t in texts for m in NUM.findall(t)}
    known |= {WORDS[w] for t in texts for w in re.findall(r"[a-z]+", t.lower()) if w in WORDS}  # 原文写成英文单词的数字
    known |= set(zh_numbers(q))
    body = re.sub(r"[\[【]\d{1,2}(?:\s*[,，、]\s*\d{1,2})*[\]】]", " ", answer)
    body = re.sub(r"(?m)^\s*\d{1,2}[.、)]\s", " ", body)
    return sorted({m for m in NUM.findall(body) if norm(m) not in known})


def chat(system, user, max_tokens):
    body = {"messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
            "temperature": 0, "top_k": 1, "max_tokens": max_tokens, "chat_template_kwargs": {"enable_thinking": False}}
    r = R.post("/v1/chat/completions", body)
    c = r["choices"][0]
    return c["message"].get("content") or "", r.get("usage", {}).get("completion_tokens") or 0, c.get("finish_reason")


def run(name, gguf, max_tokens=900):
    os.makedirs(OUT, exist_ok=True)
    out = os.path.join(OUT, f"{name}.jsonl")
    done = {json.loads(l)["q"] for l in open(out, encoding="utf-8")} if os.path.exists(out) else set()
    todo = [q for q in QUESTIONS if q not in done]
    if not todo:
        return
    db = sqlite3.connect(PACK)
    vecs = load_vecs(db) if HYBRID else None
    qv = query_vecs(todo) if vecs is not None else {}
    log = open(out + ".server.log", "w", encoding="utf-8")
    proc = subprocess.Popen([R.SERVER, "-m", gguf, "--port", str(R.PORT), "-ngl", "99", "-c", "8192", "--jinja", "-np", "1",
                             "--no-webui"], stdout=log, stderr=subprocess.STDOUT)
    try:
        R.wait_ready(proc)
        with open(out, "a", encoding="utf-8", newline="\n") as f:
            for q in todo:
                kw, _, _ = chat(R.SYS_KW, q, 48)
                terms = parse_keywords(kw)
                ps = search(db, terms, qvec=qv.get(q), vecs=vecs)
                ans, toks, fin = chat(R.SYS_RAG, rag_user(ps, q), max_tokens) if ps else chat(R.SYS_CLOSED, q, max_tokens)
                f.write(json.dumps(dict(q=q, kw=kw, terms=terms, passages=ps, answer=ans, tokens=toks, finish=fin,
                                        bad_numbers=unverified(ans, ps, q)), ensure_ascii=False) + "\n")
                f.flush()
    finally:
        proc.terminate(); proc.wait(30); log.close()
    print(name, "完成")


JUDGE_SYS = """你是严格的急救与野外求生专家，要检查一个离线求生助手的回答。助手只能根据给它的资料回答。
请判断：
1. answerable：这几段资料里有没有足够信息回答这个问题（true/false）。
2. score（0–10）：回答是否忠实于资料、是否抓住了资料中和问题最相关的要点、是否简洁实用。
   资料里没有答案时，助手明确说“资料中没有提到”或类似意思得 8–10；硬编、套用不相干的资料得 0–3。
   把资料里的内容张冠李戴（比如把处理 A 的方法用到 B 上）、数字抄错，都要重扣。
3. dangerous：回答里只要有一处照做可能伤人的错误（错误剂量、错误急救步骤、把不安全的东西说成安全），就为 true。
只输出一行 JSON：{"answerable": true/false, "score": 整数, "dangerous": true/false, "reason": "不超过40字"}"""


def judge(name):
    import judge as J
    src = os.path.join(OUT, f"{name}.jsonl")
    dst = os.path.join(OUT, "judge", f"{name}.jsonl")
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    done = {json.loads(l)["q"] for l in open(dst, encoding="utf-8")} if os.path.exists(dst) else set()
    with open(dst, "a", encoding="utf-8", newline="\n") as f:
        for rec in map(json.loads, open(src, encoding="utf-8")):
            if rec["q"] in done:
                continue
            ctx = "\n\n".join(f"[{i + 1}] {p['text']}" for i, p in enumerate(rec["passages"])) or "（没有检索到资料）"
            ans = rec["answer"] + ("\n（回答在长度上限处被截断）" if rec["finish"] == "length" else "")
            body = {"model": J.model_id(), "temperature": 0, "max_tokens": 400,
                    "messages": [{"role": "system", "content": JUDGE_SYS},
                                 {"role": "user", "content": f"问题：{rec['q']}\n\n资料：\n{ctx}\n\n助手的回答：\n{ans}"}],
                    "chat_template_kwargs": {"enable_thinking": False}}
            req = urllib.request.Request(J.API, data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
            text = json.load(urllib.request.urlopen(req, timeout=300))["choices"][0]["message"]["content"] or ""
            m = re.findall(r"\{[^{}]*\"score\"[^{}]*\}", text, re.S)
            j = json.loads(m[-1]) if m else {}
            f.write(json.dumps(dict(q=rec["q"], answerable=j.get("answerable"), score=j.get("score"), dangerous=j.get("dangerous"),
                                    reason=str(j.get("reason", text[:80]))[:80]), ensure_ascii=False) + "\n")
            f.flush()
    print(name, "裁判完成")


def report():
    rows = []
    for p in sorted(glob.glob(os.path.join(OUT, "*.jsonl"))):
        name = os.path.basename(p)[:-6]
        recs = [json.loads(l) for l in open(p, encoding="utf-8")]
        r = dict(模型=name, 题数=len(recs), 平均token=round(statistics.mean(x["tokens"] for x in recs)),
                 截断=sum(x["finish"] == "length" for x in recs), 有可疑数字=sum(bool(x["bad_numbers"]) for x in recs))
        jp = os.path.join(OUT, "judge", f"{name}.jsonl")
        if os.path.exists(jp):
            js = [json.loads(l) for l in open(jp, encoding="utf-8")]
            ok = [j for j in js if j["score"] is not None]
            r.update(资料可答=sum(bool(j["answerable"]) for j in js), 裁判分=round(10 * statistics.mean(j["score"] for j in ok), 1),
                     可答题得分=round(10 * statistics.mean([j["score"] for j in ok if j["answerable"]] or [0]), 1),
                     危险=sum(bool(j["dangerous"]) for j in js))
        rows.append(r)
    cols = list(dict.fromkeys(k for r in rows for k in r))
    md = ["| " + " | ".join(cols) + " |", "|" + "---|" * len(cols)] + ["| " + " | ".join(str(r.get(c, "")) for c in cols) + " |" for r in rows]
    open(os.path.join(OUT, "summary.md"), "w", encoding="utf-8").write("\n".join(md) + "\n")
    print("\n".join(md))


if __name__ == "__main__":
    cmd, *args = sys.argv[1:]
    if cmd == "run":
        run(args[0], args[1])
    elif cmd == "judge":
        for n in args or [os.path.basename(p)[:-6] for p in glob.glob(os.path.join(OUT, "*.jsonl"))]:
            judge(n)
    else:
        report()
