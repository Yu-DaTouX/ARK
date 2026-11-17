#!/usr/bin/env bash
# 单幅澳洲 1:250,000 地形图（GeoPDF）→ 配准好的栅格瓦片 pmtiles。
# 在 WSL 里跑（需要 ghostscript、gdal、gdal2tiles.py、pmtiles；不要求 pypdf）。
#
#   bash make_toposheet.sh <GeoPDF> <输出.pmtiles> [最低级] [最高级] [DPI]
#   VPJSON=/path/vp.json bash make_toposheet.sh ...   # 四角由外部先算好（WSL 里没 pypdf 时用这个）
#
# 配准原理：这些图幅的图框是 MGA（GDA94 / UTM 南半球）分带格网格线，不是经纬网格——
# 图框四边就是东坐标/北坐标的等值线。所以把栅格直接赋成对应带的 MGA 坐标（矩形，零近似），
# 再整体重投影到 Web 墨卡托。如果改用四角经纬度做一次多项式拟合，幅面中部会偏出约 400 m。
set -euo pipefail
PDF="$1"; OUT="$2"; ZMIN="${3:-8}"; ZMAX="${4:-12}"; DPI="${5:-200}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

if [ -n "${VPJSON:-}" ]; then
  cp "$VPJSON" "$WORK/vp.json"
else
  python3 - "$PDF" "$WORK/vp.json" <<'PYVP'
import json, sys
from pypdf import PdfReader
r = PdfReader(sys.argv[1]); p = r.pages[0]
H = float(p.mediabox.height)
best = None
for v in p['/VP']:
    v = v.get_object()
    if 'Main' not in str(v.get('/Name') or ''):
        continue
    m = v['/Measure'].get_object()
    best = dict(name=str(v.get('/Name')), bbox=[float(x) for x in v['/BBox']],
                gpts=[float(x) for x in m['/GPTS']],
                lpts=[float(x) for x in m['/LPTS']], page_h=H)
if not best:
    sys.exit('no main map viewport in ' + sys.argv[1])
json.dump(best, open(sys.argv[2], 'w'))
PYVP
fi

gs -q -dNOPAUSE -dBATCH -sDEVICE=png16m -r"$DPI" -dTextAlphaBits=4 -dGraphicsAlphaBits=4 \
   -sOutputFile="$WORK/page.png" "$PDF"

# 裁出主图图框，直接赋 MGA 坐标（EPSG:28<zone>）。WSL 里没有 pyproj，用 gdaltransform 换算四角。
python3 - "$WORK/vp.json" "$DPI" "$WORK/pts.txt" <<'PYGEO'
import json, re, sys
vp = json.load(open(sys.argv[1])); dpi = float(sys.argv[2]); s = dpi / 72.0
x0, y0, x1, y1 = vp['bbox']; H = vp['page_h']; g = vp['gpts']
latlon = [(g[2 * i], g[2 * i + 1]) for i in range(4)]      # (lat, lon)：左下、左上、右上、右下
m = re.search(r'Zone\s*(\d+)', vp.get('name', ''))
zone = int(m.group(1)) if m else int((sum(p[1] for p in latlon) / 4 + 180) // 6) + 1
win = (round(x0 * s), round((H - y1) * s), round((x1 - x0) * s), round((y1 - y0) * s))
with open(sys.argv[3], 'w') as f:
    f.write(f'{zone} {win[0]} {win[1]} {win[2]} {win[3]}\n')
    for lat, lon in latlon:
        f.write(f'{lon:.7f} {lat:.7f}\n')
PYGEO
read -r ZONE SX SY SW SH < "$WORK/pts.txt"
EPSG=$((28300 + ZONE))
tail -n +2 "$WORK/pts.txt" | gdaltransform -s_srs EPSG:4326 -t_srs "EPSG:$EPSG" | awk '
  { E[NR]=$1; N[NR]=$2; if(NR==1||$1<emin)emin=$1; if(NR==1||$1>emax)emax=$1;
    if(NR==1||$2<nmin)nmin=$2; if(NR==1||$2>nmax)nmax=$2 }
  END { printf "%d %d %d %d %d %.3f %.3f %.3f %.3f\n", 0,0,0,0,0, emin, nmax, emax, nmin }' > "$WORK/en.txt"
read -r _ _ _ _ _ EL NT ER NB < "$WORK/en.txt"
echo "zone $ZONE epsg $EPSG 框 $(awk -v a=$EL -v b=$ER 'BEGIN{printf "%.0f", b-a}')×$(awk -v a=$NB -v b=$NT 'BEGIN{printf "%.0f", b-a}') m"
gdal_translate -q -of GTiff -a_srs "EPSG:$EPSG" -a_ullr "$EL" "$NT" "$ER" "$NB" \
  -srcwin "$SX" "$SY" "$SW" "$SH" "$WORK/page.png" "$WORK/raw.tif"
gdalwarp -q -t_srs EPSG:3857 -r cubic -dstalpha -overwrite "$WORK/raw.tif" "$WORK/warp.tif"

# 瓦片格式：默认 WebP（有损 q80），体积约为 PNG 的 1/3，肉眼无差别（地形图是线划图，压不坏）
DRIVER="${TILEDRIVER:-WEBP}"; EXT=$(echo "$DRIVER" | tr 'A-Z' 'a-z')
gdal2tiles.py -q -p mercator -z "$ZMIN-$ZMAX" -r cubic --xyz --tiledriver="$DRIVER" \
  ${WEBP_QUALITY:+--webp-quality=$WEBP_QUALITY} "$WORK/warp.tif" "$WORK/tiles" >/dev/null

EXT="$EXT" python3 - "$WORK/tiles" "$WORK/out.mbtiles" <<'PYMBT'
import os, sqlite3, sys
root, out = sys.argv[1], sys.argv[2]; ext = os.environ.get('EXT', 'png')
if os.path.exists(out):
    os.remove(out)
db = sqlite3.connect(out)
db.executescript('''CREATE TABLE metadata (name TEXT, value TEXT);
  CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB);
  CREATE UNIQUE INDEX tile_index ON tiles (zoom_level, tile_column, tile_row);''')
n = 0
for z in sorted(os.listdir(root)):
    zp = os.path.join(root, z)
    if not (z.isdigit() and os.path.isdir(zp)):
        continue
    zz = int(z)
    for x in os.listdir(zp):
        xp = os.path.join(zp, x)
        if not (x.isdigit() and os.path.isdir(xp)):
            continue
        for f in os.listdir(xp):
            if not f.endswith('.' + ext):
                continue
            y = int(f.split('.')[0])
            db.execute('INSERT INTO tiles VALUES (?,?,?,?)',
                       (zz, int(x), (1 << zz) - 1 - y, open(os.path.join(xp, f), 'rb').read()))
            n += 1
db.executemany('INSERT INTO metadata VALUES (?,?)',
               [('name', os.path.basename(out)), ('format', 'png'), ('type', 'basemap'), ('version', '1')])
db.commit(); db.close()
print(f'{n} tiles')
PYMBT

if [ -n "${NOPMTILES:-}" ]; then
  cp "$WORK/out.mbtiles" "$OUT"; echo "→ $OUT  $(du -h "$OUT" | cut -f1)"
else
  pmtiles convert "$WORK/out.mbtiles" "$OUT"
  echo "→ $OUT  $(du -h "$OUT" | cut -f1)"
fi
