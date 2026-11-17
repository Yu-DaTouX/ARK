#!/usr/bin/env bash
# 把一批 1:250,000 图幅直成一个大瓦片包：按 MGA 分带（Zone 49–56）打包。
# 在 WSL 里跑：bash build_zone.sh <zone> [zmin] [zmax] [dpi] [并发]
# 输出 topo-zone<zone>.pmtiles（WebP q80，含全部图幅，可整包导入手机）。
set -euo pipefail
ZONE="$1"; ZMIN="${2:-9}"; ZMAX="${3:-12}"; DPI="${4:-200}"; JOBS="${5:-4}"
cd "$(dirname "$0")"
WORK="$PWD/build/zone$ZONE"; mkdir -p "$WORK/mb"
export WEBP_QUALITY=80 TILEDRIVER=WEBP NOPMTILES=1

one() {   # $1 = 图幅 json
  local j="$1" name out
  name="$(basename "$j" .json)"
  out="$WORK/mb/$name.mbtiles"
  [ -s "$out" ] && return 0
  if VPJSON="$j" bash make_toposheet.sh "/mnt/g/Offline/01_Maps/AUSTopo_250k/$name.pdf" "$out.tmp" "$ZMIN" "$ZMAX" "$DPI" \
       > "$WORK/mb/$name.log" 2>&1; then mv "$out.tmp" "$out"; echo "ok $name"
  else echo "FAIL $name"; tail -3 "$WORK/mb/$name.log"; fi
}
export -f one; export WORK ZMIN ZMAX DPI

ls vp/*.json | while read -r j; do
  grep -q "Zone $ZONE " "$j" && echo "$j" || true
done > "$WORK/jobs.txt"
echo "zone $ZONE: $(wc -l < "$WORK/jobs.txt") 幅，并发 $JOBS"
xargs -a "$WORK/jobs.txt" -P "$JOBS" -I{} bash -c 'one "$@"' _ {}

ls "$WORK"/mb/*.mbtiles > "$WORK/list.txt" 2>/dev/null || true
if [ ! -s "$WORK/list.txt" ]; then echo "没有成功产出的图幅，退出"; exit 1; fi
echo "合并 $(wc -l < "$WORK/list.txt") 幅…"
python3 merge_mbtiles.py "$WORK/zone$ZONE.mbtiles" $(cat "$WORK/list.txt")
pmtiles convert "$WORK/zone$ZONE.mbtiles" "topo-zone$ZONE.pmtiles"
echo "→ topo-zone$ZONE.pmtiles $(du -h "topo-zone$ZONE.pmtiles" | cut -f1)"
