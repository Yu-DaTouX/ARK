#!/usr/bin/env bash
# 海图第一部分：GEBCO 2026 全球水深（公有领域，署名 GEBCO Compilation Group (2026) GEBCO 2026 Grid）
#   → depth.pmtiles   水深分层着色（栅格 webp，z0–7，陆地透明）
#   → contour.geojsons 等深线（矢量，d = 水深米数）：low 2′ 给 z0–5，mid 30″ 给 z6–10
# 在 WSL（Debian/Kali）里跑：apt install gdal-bin tippecanoe osmium-tool；pmtiles 命令行放在 PATH 里
#   bash bathy.sh <GEBCO geotiff 目录> <工作目录>
# 为什么分层用栅格：gdal_contour -p 生成全球分层多边形单线程要几个小时；着色栅格几分钟，z7 ≈ 1.2 km/像素，和 30″ 数据相当。
# GEBCO 近岸浅水精度有限，只供应急参考，不能代替正规航海图。
set -e
SRC=$1; W=$2; HERE=$(cd "$(dirname "$0")" && pwd)
mkdir -p $W && cd $W
[ -f gebco.vrt ] || gdalbuildvrt -q gebco.vrt $SRC/*.tif

# ---- 分层着色（栅格）----
if [ ! -f depth.pmtiles ]; then
  # 墨卡托 z7 分辨率，裁到 ±85.0511°
  gdalwarp -q -overwrite -t_srs EPSG:3857 -te -20037508.34 -20037508.34 20037508.34 20037508.34 \
    -tr 1222.9924525624949 1222.9924525624949 -r average -ot Int16 -co COMPRESS=DEFLATE -co TILED=YES -co BIGTIFF=YES \
    gebco.vrt depth3857.tif
  # 颜色表见 depth_colors.txt（分层取色，陆地透明）
  gdaldem color-relief -q -alpha depth3857.tif $HERE/depth_colors.txt depth_rgba.tif \
    -co COMPRESS=DEFLATE -co TILED=YES -co BIGTIFF=YES
  gdal_translate -q -of MBTILES -co TILE_FORMAT=WEBP -co QUALITY=75 -co ZOOM_LEVEL_STRATEGY=UPPER depth_rgba.tif depth.mbtiles
  gdaladdo -q -r average depth.mbtiles 2 4 8 16 32 64 128
  pmtiles convert depth.mbtiles depth.pmtiles
  rm -f depth3857.tif depth_rgba.tif
fi

# ---- 等深线（矢量）----
LOW="-6000 -5000 -4000 -3000 -2000 -1000 -200"
MID="-6000 -5000 -4000 -3000 -2000 -1000 -500 -200 -100 -50 -20 -10"
lines() { # 输入栅格 输出 等深线级别（从深到浅递增） 最小缩放 最大缩放
  gdal_contour -q -fl $3 -a elev $1 $2.gpkg
  ogr2ogr -f GeoJSONSeq $2.tmp $2.gpkg -dialect sqlite -sql "SELECT geom, CAST(-elev AS INTEGER) AS d FROM contour" -lco RS=NO
  # 每条线标上显示的缩放范围（tippecanoe 读 tippecanoe.minzoom / maxzoom）
  sed "s/^{ \"type\": \"Feature\"/{ \"type\": \"Feature\", \"tippecanoe\": { \"minzoom\": $4, \"maxzoom\": $5 }/" $2.tmp > $2
  rm -f $2.gpkg $2.tmp
}
[ -f low.geojsons ] || {
  gdalwarp -q -overwrite -tr 0.0333333333 0.0333333333 -r average -ot Int16 gebco.vrt low.tif
  lines low.tif low.geojsons "$LOW" 0 5
}
for t in $SRC/*.tif; do
  b=$(basename $t .tif)
  ( [ -f mid_$b.geojsons ] || {
      gdalwarp -q -overwrite -tr 0.0083333333 0.0083333333 -r average -ot Int16 $t mid_$b.tif
      lines mid_$b.tif mid_$b.geojsons "$MID" 6 10
      rm -f mid_$b.tif
    } ) &
done
wait
cat low.geojsons mid_*.geojsons > contour.geojsons
ls -la depth.pmtiles contour.geojsons
