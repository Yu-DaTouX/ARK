# -*- coding: utf-8 -*-
"""把 NASA Blue Marble（等经纬度投影的整张地球照片，公有领域）切成 Web 墨卡托瓦片，打包成 PMTiles。

用法：python make_satellite.py <源图.jpg> <输出.pmtiles> [最大缩放级别=6] [WebP 质量=78]
源图 21600×10800（每度 60 像素），256 像素瓦片到 z6 正好不放大。
x 方向是线性的，先把整图缩放到该级别的宽度；y 方向按墨卡托公式逐行取样（行间线性插值）。
"""
import io, math, sys
import numpy as np
from PIL import Image
from pmtiles.tile import zxy_to_tileid, TileType, Compression
from pmtiles.writer import Writer

Image.MAX_IMAGE_PIXELS = None
SRC, OUT = sys.argv[1], sys.argv[2]
MAXZ = int(sys.argv[3]) if len(sys.argv) > 3 else 6
Q = int(sys.argv[4]) if len(sys.argv) > 4 else 78

src = Image.open(SRC).convert("RGB")
tiles = {}
for z in range(MAXZ + 1):
    n = 256 << z
    # 竖向留足分辨率：墨卡托在高纬度会拉伸，按 n 行等经纬度取样就够
    img = np.asarray(src.resize((n, max(n // 2, 256)), Image.LANCZOS), dtype=np.float32)
    h = img.shape[0]
    for ty in range(1 << z):
        ys = (ty * 256 + np.arange(256) + 0.5) / n
        lat = np.degrees(np.arctan(np.sinh(math.pi * (1 - 2 * ys))))
        sy = np.clip((90 - lat) / 180 * h - 0.5, 0, h - 1)
        y0 = np.floor(sy).astype(int); y1 = np.minimum(y0 + 1, h - 1); f = (sy - y0)[:, None, None]
        band = img[y0] * (1 - f) + img[y1] * f            # 256 × n × 3
        band = np.clip(band + 0.5, 0, 255).astype(np.uint8)
        for tx in range(1 << z):
            buf = io.BytesIO()
            Image.fromarray(band[:, tx * 256:(tx + 1) * 256]).save(buf, "WEBP", quality=Q, method=6)
            tiles[zxy_to_tileid(z, tx, ty)] = buf.getvalue()
    print(f"z{z}: {1 << (2 * z)} 张，累计 {sum(map(len, tiles.values())) / 1e6:.1f} MB", flush=True)

with open(OUT, "wb") as f:
    w = Writer(f)
    for tid in sorted(tiles):
        w.write_tile(tid, tiles[tid])
    w.finalize(
        {
            "tile_type": TileType.WEBP, "tile_compression": Compression.NONE,
            "min_zoom": 0, "max_zoom": MAXZ,
            "min_lon_e7": -1800000000, "min_lat_e7": -850511287, "max_lon_e7": 1800000000, "max_lat_e7": 850511287,
            "center_zoom": 2, "center_lon_e7": 1340000000, "center_lat_e7": -250000000,
        },
        {
            "name": "Blue Marble 地形与海底地形",
            "attribution": "NASA Earth Observatory Blue Marble Next Generation（公有领域）",
            "type": "baselayer", "format": "webp", "tileSize": 256,
        },
    )
print("完成", OUT)
