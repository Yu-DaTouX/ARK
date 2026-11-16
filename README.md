# 方舟 ARK — 断网断电时的离线求生助手（安卓）

没有网络、没有医生的时候，手机里还有一个能查资料、会回答问题、带地图的助手。

- **本地 AI 问答**：模型完全在手机上运行（LiteRT-LM，GPU 优先）。回答依据手机里的离线资料，每句标出处 `[1]`，点编号看原文；资料里没有的会直说。
- **离线资料**：急救医学、饮水、食物、野外求生、无线电、机械维修、能源，约 2.4 万段，中文显示（机器翻译），可切换英文原文对照。不开 AI 也能直接搜原文。
- **数字核对**：回答里的数字在出处原文中找不到就标红。
- **书签**：重要的回答连同出处原文一起保存。
- **离线地图**：地球仪视图 + NASA 卫星底图 + OpenStreetMap 矢量地图；GPS 定位不需要网络；屏幕中心坐标可复制。
- **导入自己的资料**：任何书、手册、网页都能做成资料包导入，见 [docs/DATA.md](docs/DATA.md)。

## 下载

在 [Releases](../../releases) 下载 APK（约 180 MB，含核心资料和全球地图）。AI 模型在 App 的「资料」页下载：Gemma 4 E2B（2.4 GB，默认）或 E4B（3.4 GB），语义检索模型 EmbeddingGemma（165 MB，推荐）。

实测（Gemma 4 E2B，GPU）：小米 12S Ultra 和三星 Fold6 上一个问题约 15–25 秒，整机每问约 90–120 J。

## 目录

| 目录 | 内容 |
|---|---|
| `app/` | 安卓 App（Kotlin + Jetpack Compose，minSdk 26） |
| `data/` | 资料处理：`extract_chunks.py`（ZIM / PDF / HTML / TXT → 段落）、`build_pack.py`（段落 → `.arkpack`）、`build_zh.py`（中文显示版）、`build_public.py`（公开发布版）、`maps/` |
| `tools/embed/` | 电脑端工具：给资料包算语义向量、用和手机相同的运行时批量生成回答 |
| `eval/` | 检索与回答评测脚本 |
| `bench/` | 手机端测速与功耗脚本 |
| `train/` | 微调实验（未上线） |
| `docs/` | [DATA.md](docs/DATA.md) 导入资料与检索架构；[PLAN.md](docs/PLAN.md) 实测数据与决策记录 |

## 构建

需要 Android SDK（compileSdk 37）、JDK 21。资料包和地图体积大，不进 Git，需要先生成（或从 Release 里的 APK 解出 `assets/`）：

```bash
# 1. 核心资料包：抽取段落 → 打包 → 算向量（详见 docs/DATA.md）
python data/extract_chunks.py <ZIM 或 书籍目录> ...
bash data/build_v4.sh
bash tools/embed/setup.sh && (cd tools/embed && gradle installDist)
tools/embed/build/install/ark-embed/bin/ark-embed pack models/embed/embeddinggemma-2-text-270m.litertlm data/packs/v4/ark-core.arkpack 256
# 2. 中文显示版（需要 data/zh/out 里的译文）和公开发布版（去掉不能再分发的书）
python data/build_zh.py data/packs/v4/ark-core.arkpack
python data/build_public.py
cp data/packs/public/ark-core.arkpack app/app/src/main/assets/packs/

# 3. 地图：卫星底图（NASA Blue Marble，公有领域）+ 全球矢量概略图（Protomaps，ODbL）
python data/maps/make_satellite.py world.topo.bathy.200407.3x21600x10800.jpg world-satellite.pmtiles 6
pmtiles extract https://build.protomaps.com/<日期>.pmtiles world-z5.pmtiles --maxzoom=5
cp world-satellite.pmtiles world-z5.pmtiles app/app/src/main/assets/maps/

# 4. 构建
cd app && ./gradlew assembleRelease
```

## 许可

- 代码：Apache-2.0（见 [LICENSE](LICENSE)）。
- 内置资料包：各书许可不同，App 资料页和包内 `meta.license` 有说明。美军、美国海军、FEMA、和平队手册及古登堡书籍为公有领域；MDWiki、维基百科、Stack Exchange、WikiCiv、energypedia、Restarters、Scoutwiki 为 CC BY-SA（中文译文同样按 CC BY-SA 分发）；Hesperian 的书仅限非营利免费分发；Kiwix zimgit 合集内各书版权归原作者。仍在版权期、不允许再分发的书（Foxfire、Richard Graves 等）不在公开版里。权利人如要求下架，请提 Issue。
- 地图：OpenStreetMap 数据 © OpenStreetMap contributors，ODbL；NASA Blue Marble 为公有领域。
- 模型：Gemma 4、EmbeddingGemma 2 为 Apache-2.0，在 App 内由用户自行下载，不随 APK 分发。

**免责声明**：AI 回答可能出错。用药和急救请以资料原文为准，有条件时尽快联系医生或急救。
