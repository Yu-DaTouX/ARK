# 地形图叠加层（澳洲 1:250 000 官方图幅）

把 Geoscience Australia 的 1:250 000 地形图（GeoPDF，509 幅，6 GB）配准后切成栅格瓦片，
在 App 里作为**最上层的半透明叠加层**压在矢量地图上——矢量图负责城市街道和搜索，地形图负责野外地形。

## 精度

- 图幅是官方 2025-12 出的 1:250 000 产品，图框是 **MGA（GDA94 / UTM 南半球）格网**，不是经纬网：
  图框四条边就是东坐标/北坐标的等值线。所以整幅图在 MGA 坐标里是一个**正矩形**。
- 因此配准方式是：把栅格直接赋成对应带的 MGA 坐标（`EPSG:28350 + zone`），再做一次整体重投影到
  Web 墨卡托。**没有任何近似**。
- 反例（踩过的坑）：只用 GeoPDF 给的四角经纬度做一次多项式拟合，看起来四角很准，但幅面中部
  （尤其中线两侧）会偏出约 **386 m**——因为常北坐标线不是纬线，纬度沿图框边缘是弯的。
- 实测校验（Adelaide SI54-09）：栅格上的海岸线落在 **138.508°E**，真实 Glenelg 海滩约 138.509°E，
  偏差约 70 m，与 1:250 000 图幅本身的位置精度相当（纸面 0.3 mm ≈ 75 m）。

## 分辨率与体积

| 参数 | 值 |
|---|---|
| 渲染 | Ghostscript 200 dpi（21 m/像素，约为图面最小可辨要素 75 m 的 1/3） |
| 瓦片 | z9–12，WebP q80（z12 ≈ 31 m/像素，对 1:250k 已过采样） |
| 单幅 | ≈6 MB（PNG 要 34 MB） |
| 全澳 509 幅 | ≈3 GB，按 MGA 分带打包，单包 100–600 MB |

再往上加 z13 只增加体积，不增加精度：图面本身分辨不出更细的东西。

## 覆盖范围（重要）

- 手上的 509 幅覆盖**澳洲大陆**，最南到 -39.57°。GA 的免费 AUSTopo 250k 系列（eCAT 里 513 条记录）
  也不含塔斯马尼亚——索引里那 7 幅（Hobart Special SK55-11、Launceston、Burnie、Queenstown、
  Port Davey、King Island、Flinders Island）在仪表盘里点下载会跳到 Tasmap 商店（付费）。
- 塔州目前只有矢量图（OSM）和卫星图。若日后拿到 TASMAP 的 1:250 000 GeoPDF（他们卖的是 GeoPDF），
  直接丢进 `make_toposheet.sh` 就能用：脚本只依赖 GeoPDF 里的主图视口和四角经纬度。

## 用法

```bash
# WSL 环境：ghostscript + gdal(含 gdal2tiles.py) + pmtiles CLI + tile-join
# 单幅（调试）：
VPJSON=vp/Adelaide_SI54-09.json bash make_toposheet.sh Adelaide_SI54-09.pdf out.pmtiles 9 12 200
# 一个分带（并发 6，出 topo-zone54.pmtiles）：
bash build_zone.sh 54 9 12 200 6
# 全部分带：
bash build_all.sh
```

`vp/*.json` 是从每幅 GeoPDF 里抽出来的主图视口（`/VP` → `Main Map`）：图框 bbox、四角经纬度、
以及带号。抽取脚本用 pypdf（Windows 侧跑一次即可，509 幅约 1 分钟）：

```python
from pypdf import PdfReader
# 取 /VP 里名字含 "Main" 的视口，读 /Measure 的 /GPTS 与 /LPTS，见 make_toposheet.sh 里的实现
```

## App 里

- 文件名以 `topo` 开头的 `.pmtiles` 被当成地形图叠加层（`MapStyle.isTopo`）。
- 图层顺序：卫星图 → 区域详图 → 矢量面/线 → 海图水深/等深线 → **地形图** → 地名文字。
  压在道路之上、地名之下，这样等高线和土路看得清，地名还是矢量图自己的（更全、可搜索）。
- 地图页「更多 → 地形图叠加」开关，状态存在 `prefs: map_topo`。
- 包里没有地形图时打开会提示去「资料 → 地形图叠加」下载。
