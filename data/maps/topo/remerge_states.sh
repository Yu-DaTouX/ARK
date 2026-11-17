#!/usr/bin/env bash
# 用 alpha 叠加重新合并各州包（接缝处不再出现空白带）
set -u
cd "$(dirname "$0")"
for s in "$@"; do
  python merge_mbtiles.py "build/state-$s.mbtiles" $(python select_sheets.py "$s" --mb) || { echo "merge 失败 $s"; continue; }
  wsl -e bash -lc "cd /mnt/f/ARK/data/maps/topo && pmtiles convert build/state-$s.mbtiles topo-$s.pmtiles" || { echo "convert 失败 $s"; continue; }
  echo "done $s $(du -h topo-$s.pmtiles | cut -f1)"
done
