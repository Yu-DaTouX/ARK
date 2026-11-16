# -*- coding: utf-8 -*-
"""把 chunks/*.jsonl 打包成 App 用的 .arkpack（SQLite + FTS5 全文索引）。

用法：python build_pack.py <包id> <标题> <语言 en|zh> <许可证> <输出目录> <chunks.jsonl> [...]
表结构（App 端 PackRepository 按这个读）：
  meta(key, value)                    id/title/lang/license/version/description/chunks/docs
  docs(id, path, title, src)          文档
  chunks(id, doc_id, seq, text)       段落，seq 是在文档内的顺序
  chunks_fts(text, title)             FTS5，外部内容表指向 chunks；英文用 porter，中文用 trigram
"""
import json, os, re, sqlite3, sys, time

MAXW = int(os.environ.get("ARK_MAXW", "0"))  # >0 时把长段切成不超过 MAXW 词；实测切短反而降低召回（上下文被切散），默认不切
DUP = 0.6  # 一段里超过 60% 的 5 词片段已经出现过，就当作重复内容丢掉（同一本书常被多个合集收录）
QMIN = float(os.environ.get("ARK_QMIN", "0"))  # >0 时丢掉 OCR 乱码段：正文里常见英文词（chunks/_vocab.json）占比低于 QMIN
VOCAB = set(json.load(open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "chunks", "_vocab.json")))) if QMIN else set()


def readable(text):
    ws = re.findall(r"[A-Za-z][A-Za-z'^]*", text.split("\n", 1)[-1])
    return sum(w.lower().strip("'") in VOCAB for w in ws) >= QMIN * max(1, len(ws))


def split(text, maxw=MAXW):
    """长段按句子边界切成不超过 maxw 词的小段；太短的尾巴并到前一段。"""
    if not maxw or len(text.split()) <= maxw * 1.2:
        return [text]
    sents = re.split(r"(?<=[.!?;:])\s+(?=[A-Z0-9•(\-–])|\s(?=[•♦]\s)", text)
    out, buf, n = [], [], 0
    for s in sents:
        w = len(s.split())
        if buf and n + w > maxw:
            out.append(" ".join(buf)); buf, n = [], 0
        buf.append(s); n += w
    if buf:
        if out and n < maxw // 3:
            out[-1] += " " + " ".join(buf)
        else:
            out.append(" ".join(buf))
    return out


# 维基条目里的脚注号 [12]、[3–5]、[citation needed]、[note 1]：和 App 的出处编号 [n] 冲突（模型会照抄，界面会把它当成第 n 段），一律去掉
FOOTNOTE = re.compile(r"\[(?:\d+(?:\s*[-–,]\s*\d+)*|[a-z]|note \d+|citation needed|clarification needed|when\?|who\?|failed verification)\]", re.I)


def clean(text):
    return re.sub(r"[ \t]{2,}", " ", FOOTNOTE.sub("", text)).replace(" .", ".").replace(" ,", ",")


def shingles(text):
    w = re.findall(r"[a-z0-9]+|[\u4e00-\u9fff]", text.lower())
    return {hash(" ".join(w[i:i + 5])) for i in range(max(1, len(w) - 4))}


def build(pack_id, title, lang, license_, out_dir, sources):
    os.makedirs(out_dir, exist_ok=True)
    path = os.path.join(out_dir, pack_id + ".arkpack")
    if os.path.exists(path):
        os.remove(path)
    db = sqlite3.connect(path)
    tok = "trigram" if lang == "zh" else "porter unicode61 remove_diacritics 2"
    db.executescript(f"""
        PRAGMA page_size=4096; PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF;
        CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT);
        CREATE TABLE docs(id INTEGER PRIMARY KEY, path TEXT, title TEXT, src TEXT);
        CREATE TABLE chunks(id INTEGER PRIMARY KEY, doc_id INTEGER, seq INTEGER, text TEXT, title TEXT);
        CREATE INDEX chunks_doc ON chunks(doc_id, seq);
        CREATE VIRTUAL TABLE chunks_fts USING fts5(text, title, content='chunks', content_rowid='id', tokenize='{tok}');
    """)
    docs, nchunks, seen, dropped, garbled = {}, 0, set(), 0, 0
    pieces = (dict(c0, text=p) for src in sources for c0 in map(json.loads, open(src, encoding="utf-8"))
              for p in split(clean(c0["text"])))
    for c in pieces:
        if QMIN and not readable(c["text"]):
            garbled += 1
            continue
        sh = shingles(c["text"])
        if len(sh & seen) > DUP * len(sh):
            dropped += 1
            continue
        seen |= sh
        key = (c["src"], c["doc"])
        if key not in docs:
            docs[key] = [len(docs) + 1, 0]
            db.execute("INSERT INTO docs VALUES(?,?,?,?)", (docs[key][0], c["doc"], c["title"], c["src"]))
        d = docs[key]
        d[1] += 1
        db.execute("INSERT INTO chunks(doc_id, seq, text, title) VALUES(?,?,?,?)", (d[0], d[1], c["text"], c["title"]))
        nchunks += 1
    db.execute("INSERT INTO chunks_fts(chunks_fts) VALUES('rebuild')")
    db.execute("INSERT INTO chunks_fts(chunks_fts) VALUES('optimize')")
    meta = dict(id=pack_id, title=title, lang=lang, license=license_, version=time.strftime("%Y-%m-%d"),
                chunks=str(nchunks), docs=str(len(docs)), format="1")
    db.executemany("INSERT INTO meta VALUES(?,?)", meta.items())
    db.commit(); db.execute("VACUUM"); db.close()
    print(f"{path}: {len(docs)} 篇, {nchunks} 段（去重丢掉 {dropped} 段，乱码丢掉 {garbled} 段）, {os.path.getsize(path) / 1e6:.1f} MB")


if __name__ == "__main__":
    a = sys.argv[1:]
    build(a[0], a[1], a[2], a[3], a[4], a[5:])
