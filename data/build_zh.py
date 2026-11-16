# -*- coding: utf-8 -*-
"""中文核心资料包：检索走英文（全文索引、向量都和 v4 完全一样），显示和交给模型的是中文译文。

  python build_zh.py [v4 包] [输出包]
  为什么不用中文检索：用中文正文建 trigram 索引 + 中文向量，108 题评测里硬核题、无线电机械题明显变差
  （能直接回答：硬核 22 → 18，电机 30–33 → 24，见 eval/retr_zh.py），所以只换“给人看、给模型读”的文字。

  结构（meta.lang 仍是 en，App 照常生成英文检索词；meta.display = zh）：
  - chunks.text      中文译文（App 显示、交给模型）
  - chunks.text_en   英文原文，zlib 压缩（App 用来算检索词覆盖数，阅读器“原文”按钮也读它）
  - chunks_fts       无内容 FTS5（porter），用英文原文建，和 v4 的分词一致
  - vec              直接沿用 v4 的英文段落向量
  - docs.title       中文书名（取译文第一行“书名 › 章节”），docs.title_en 原书名
  chunk / doc id、seq 和 v4 一样。
"""
import os, re, shutil, sqlite3, sys, time, zlib

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "zh"))
import check

SRC = sys.argv[1] if len(sys.argv) > 1 else f"{HERE}/packs/v4_256/ark-core.arkpack"
OUT = sys.argv[2] if len(sys.argv) > 2 else f"{HERE}/packs/zh/ark-core.arkpack"


def main():
    zh = {}
    for f in sorted(os.listdir(f"{HERE}/zh/out")):
        if re.fullmatch(r"b_\d{3}\.txt", f):
            zh.update(check.parse(f"{HERE}/zh/out/{f}"))
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    shutil.copyfile(SRC, OUT)
    db = sqlite3.connect(OUT)
    rows = db.execute("SELECT id, doc_id, seq, text, title FROM chunks ORDER BY doc_id, seq").fetchall()
    miss = sum(not zh.get(r[0]) for r in rows)
    print(f"段落 {len(rows)}，缺译文 {miss}（缺的显示英文）")

    db.executescript("""
        DROP TABLE IF EXISTS chunks_fts;
        CREATE VIRTUAL TABLE chunks_fts USING fts5(text, title, content='', tokenize='porter unicode61 remove_diacritics 2');
        ALTER TABLE chunks ADD COLUMN text_en BLOB;
        ALTER TABLE docs ADD COLUMN title_en TEXT;
        UPDATE docs SET title_en = title;""")
    # 先用英文建索引（和 v4 同样的列和分词，bm25 一致）
    db.executemany("INSERT INTO chunks_fts(rowid, text, title) VALUES(?, ?, ?)", [(r[0], r[3], r[4]) for r in rows])
    doc_title = {}
    for cid, did, seq, en, _ in rows:
        t = zh.get(cid) or en
        head = t.split("\n", 1)[0].strip()
        if zh.get(cid) and did not in doc_title and "›" in head and len(head) <= 200:
            doc_title[did] = head.split("›")[0].strip()
        db.execute("UPDATE chunks SET text = ?, text_en = ? WHERE id = ?", (t, zlib.compress(en.encode("utf-8"), 9), cid))
    db.executemany("UPDATE docs SET title = ? WHERE id = ?", [(v, k) for k, v in doc_title.items() if v])
    print(f"文档 {db.execute('SELECT count(*) FROM docs').fetchone()[0]}，换成中文书名 {len(doc_title)}")
    meta = dict(display="zh", text_en="zlib", version=time.strftime("%Y-%m-%d"),
                title="方舟核心资料·中文（急救·医学·饮水·食物·求生·无线电·维修·能源）",
                translation="英文原文由 DeepSeek V4.1 Flash 机器翻译；剂量、型号等以原文为准")
    db.executemany("INSERT OR REPLACE INTO meta(key, value) VALUES(?, ?)", meta.items())
    db.commit()
    db.execute("VACUUM")
    db.close()
    print(OUT, round(os.path.getsize(OUT) / 1e6, 1), "MB")


if __name__ == "__main__":
    main()
