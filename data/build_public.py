# -*- coding: utf-8 -*-
"""公开发布版资料包：从中文核心包里去掉仍在版权期、不能公开再分发的书。

  python build_public.py [输入包] [输出包]
  去掉：Foxfire 1–3（Eliot Wigginton 等，1972–75）、The Ten Bushcraft Books（Richard Graves）。
  自用版（data/packs/zh）保留这些书；公开的 GitHub 仓库和 Release 只用这里生成的包。
"""
import os, shutil, sqlite3, sys, zlib

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = sys.argv[1] if len(sys.argv) > 1 else f"{HERE}/packs/zh/ark-core.arkpack"
OUT = sys.argv[2] if len(sys.argv) > 2 else f"{HERE}/packs/public/ark-core.arkpack"
EXCLUDE = ("Foxfire 1", "Foxfire 2", "Foxfire 3", "The Ten Bushcraft Books (Richard Graves)")


def main():
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    shutil.copyfile(SRC, OUT)
    db = sqlite3.connect(OUT)
    ids = [r[0] for r in db.execute(f"SELECT id FROM docs WHERE title_en IN ({','.join('?' * len(EXCLUDE))})", EXCLUDE)]
    assert len(ids) == len(EXCLUDE), ids
    q = ",".join(map(str, ids))
    n = db.execute(f"SELECT count(*) FROM chunks WHERE doc_id IN ({q})").fetchone()[0]
    db.execute(f"DELETE FROM vec WHERE chunk_id IN (SELECT id FROM chunks WHERE doc_id IN ({q}))")
    db.execute(f"DELETE FROM chunks WHERE doc_id IN ({q})")
    db.execute(f"DELETE FROM docs WHERE id IN ({q})")
    # 无内容 FTS5 不能按行删（要原文），直接用剩下段落的英文原文重建，分词和 build_zh.py 一致
    db.executescript("""
        DROP TABLE chunks_fts;
        CREATE VIRTUAL TABLE chunks_fts USING fts5(text, title, content='', tokenize='porter unicode61 remove_diacritics 2');""")
    rows = db.execute("SELECT id, text_en, title FROM chunks").fetchall()
    db.executemany("INSERT INTO chunks_fts(rowid, text, title) VALUES(?, ?, ?)",
                   [(i, zlib.decompress(t).decode("utf-8"), ti) for i, t, ti in rows])
    left = db.execute("SELECT count(*) FROM chunks").fetchone()[0]
    lic = db.execute("SELECT value FROM meta WHERE key='license'").fetchone()[0].replace(
        "zimgit 合集内各书版权归原作者", "zimgit 合集（Kiwix 收集的公开 PDF）内各书版权归原作者，权利人要求下架请提 Issue")
    db.executemany("INSERT OR REPLACE INTO meta(key, value) VALUES(?, ?)", [("chunks", str(left)), ("license", lic), ("edition", "public")])
    db.commit()
    db.execute("VACUUM")
    db.close()
    print(f"去掉 {len(ids)} 本书、{n} 段，剩 {left} 段 → {OUT} {os.path.getsize(OUT) / 1e6:.1f} MB")


if __name__ == "__main__":
    main()
