#!/usr/bin/env bash
# 依次出完所有分带（跳过已存在的包）。后台跑：nohup bash build_all.sh > /tmp/topo_all.log 2>&1 &
set -u
cd "$(dirname "$0")"
for z in 49 50 51 52 53 54 55 56; do
  [ -s "topo-zone$z.pmtiles" ] && { echo "跳过 zone $z（已存在）"; continue; }
  echo "=== zone $z $(date +%H:%M:%S)"
  bash build_zone.sh "$z" 9 12 200 6 || echo "zone $z 失败"
done
echo "全部完成 $(date +%H:%M:%S)"
