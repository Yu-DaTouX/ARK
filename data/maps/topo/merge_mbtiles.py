#!/usr/bin/env python3
"""把多幅栅格 mbtiles 合并成一个（tile-join 只吃矢量瓦片，栅格会报 PBF decoding error）。

    python3 merge_mbtiles.py 输出.mbtiles 输入1.mbtiles 输入2.mbtiles ...

相邻图幅在墨卡托里是"斜"的，接缝常常一刀切在同一个瓦片里：如果简单地整块覆盖，
后写的图幅会把自己的空白边盖到前一幅的内容上，图上就出现一条几公里宽的空白带。
所以重复的瓦片要做 alpha 叠加（下层的透明处保留下层，半透明处叠加上去）。
"""
import io
import os
import sqlite3
import sys

from PIL import Image


def load(data):
    im = Image.open(io.BytesIO(data))
    im.load()
    return im.convert("RGBA")


def main():
    out, srcs = sys.argv[1], [s.strip() for s in sys.argv[2:] if os.path.getsize(s.strip()) > 4096]
    if os.path.exists(out):
        os.remove(out)
    db = sqlite3.connect(out)
    db.executescript("""
      CREATE TABLE metadata (name TEXT, value TEXT);
      CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB);
      CREATE UNIQUE INDEX tile_index ON tiles (zoom_level, tile_column, tile_row);
    """)
    n = dup = 0
    for src in srcs:
        s = sqlite3.connect(src)
        for z, x, y, data in s.execute("SELECT zoom_level, tile_column, tile_row, tile_data FROM tiles"):
            old = db.execute("SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
                             (z, x, y)).fetchone()
            if old:
                dup += 1
                top, bottom = load(data), load(old[0])
                comp = bottom.copy()
                comp.alpha_composite(top)
                buf = io.BytesIO()
                comp.save(buf, "WEBP", quality=80)
                data = buf.getvalue()
            db.execute("INSERT OR REPLACE INTO tiles VALUES (?,?,?,?)", (z, x, y, data))
            n += 1
        s.close()
    db.executemany("INSERT INTO metadata VALUES (?,?)", [
        ("name", os.path.basename(out)), ("format", "webp"), ("type", "basemap"), ("version", "1"),
    ])
    db.commit()
    db.close()
    print(f"合并 {len(srcs)} 幅，{n} 张瓦片（其中 {dup} 张做了叠加）")


if __name__ == "__main__":
    main()
