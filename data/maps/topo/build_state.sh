#!/usr/bin/env bash
# 按州打包（复用已渲染的单幅产物，不重新出图）。
#   bash build_state.sh sa          # → topo-sa.pmtiles
# 图幅按"与州界范围相交"归属，边界图幅会在相邻州重复出现，代价是几百 MB 冗余，换来边界不断图。
set -euo pipefail
cd "$(dirname "$0")"
ST="$1"
OUT="topo-$ST.pmtiles"
LIST="build/list-$ST.txt"
python3 select_sheets.py "$ST" --mb > "$LIST"
N=$(wc -l < "$LIST")
[ "$N" -gt 0 ] || { echo "$ST 没有已渲染的图幅"; exit 1; }
echo "$ST: $N 幅 → $OUT"
python3 merge_mbtiles.py "build/state-$ST.mbtiles" $(cat "$LIST")
pmtiles convert "build/state-$ST.mbtiles" "$OUT"
echo "→ $OUT $(du -h "$OUT" | cut -f1)"
