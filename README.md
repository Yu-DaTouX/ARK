# ⚠️ 本软件仅为个人爱好项目，请勿相信其中内容 ⚠️

> [!CAUTION]
> **方舟 ARK 不是医疗器械、不是急救培训、不是导航设备。** 它没有经过任何医疗、救援、航海或政府机构的审核或认证。
>
> - 资料多为几十年前的公开手册和网络内容，**部分做法已经过时甚至危险**；中文是 AI 机器翻译，可能译错。
> - AI 回答由手机上的小模型生成，**会编造、遗漏、给出错误或危险的建议**，剂量和数字尤其可能出错。
> - 急救步骤卡虽然参照现行指南编写，也**没有经过医学专业人员签认**，只是简化要点。
> - 地图、海图、指南针、天体定向、气压海拔、天气预报都只是估算，**不能用于导航或安全决策**。
>
> **有条件时，第一时间呼叫急救（中国 120，多数国家 112）或寻求专业帮助。**
> 使用、修改或分发本软件，即表示你理解并**自行承担全部风险**；作者和贡献者**不对任何后果负责**。完整声明见 [DISCLAIMER.md](DISCLAIMER.md)。

---

## 方舟 ARK — 断网断电时的离线求生助手（安卓）

没有网络、没有医生的时候，手机里还有一个能查资料、会回答问题、带地图的助手。

- **本地 AI 问答**：模型完全在手机上运行（LiteRT-LM，GPU 优先）。回答依据手机里的离线资料，每句标出处 `[1]`，点编号看原文；资料里没有的会直说。
- **离线资料**：急救医学、饮水、食物、野外求生、无线电、机械维修、能源，约 2.4 万段，中文显示（机器翻译），可切换英文原文对照。不开 AI 也能直接搜原文。
- **数字核对**：回答里的数字在出处原文中找不到就标红。
- **书签**：重要的回答连同出处原文一起保存。
- **离线地图**：地球仪视图 + NASA 卫星底图 + OpenStreetMap 矢量地图；GPS 定位不需要网络；屏幕中心坐标可复制。
- **地形图叠加**：澳洲官方 1:250 000 地形图（509 幅，按州下载）按 MGA 分带精确配准后作为半透明图层压在矢量图上——等高线、水系、水潭、土路都在，野外认地形比矢量图准；实测位置偏差约 70 m。
- **离线地名搜索与求助点**：内置全球 23.7 万个城镇（含 4.2 万个中文名），区域包再补小村、郊区，以及最近的医院、药店、警局、消防、取水点、营地（带距离和方位）。
- **求生工具**：指南针（真北/磁北）、天体定向（相机对准太阳月亮星星）、气压海拔与天气趋势、日出日落、单位换算、物资计算、饮水消毒、闪光灯 SOS。
- **急救步骤卡**：25 张一步一屏的抢救卡（心肺复苏、出血、窒息、烧伤、蛇咬、一氧化碳中毒等），零耗电；另有 CPR 节拍器和止血带计时。
- **不联网也能互通消息**：蓝牙低功耗短消息（不配对，三台以上自动接力）、近超声传信（17–19.5 kHz，听不见）、蓝牙求救信标（地图上显示附近的人和距离）、警报声。
- **省电**：黑色主题、息屏停传感器、推理只在需要时跑；一次问答整机约 90 焦耳。
- **导入自己的资料**：任何书、手册、网页都能做成资料包导入，见 [docs/DATA.md](docs/DATA.md)。

> 当前版本 0.3.0。未完成的已知问题见 [docs/HANDOFF.md](docs/HANDOFF.md)，代码结构与维护方式见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

## 下载

在 [Releases](../../releases) 下载 APK（约 200 MB，含核心资料和全球地图）。AI 模型在 App 的「资料」页下载：Gemma 4 E2B（2.4 GB，默认）或 E4B（3.4 GB），语义检索模型 EmbeddingGemma（165 MB，推荐）。

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
| `docs/` | [ARCHITECTURE.md](docs/ARCHITECTURE.md) 代码结构与模块说明；[DATA.md](docs/DATA.md) 导入资料与检索架构；[PLAN.md](docs/PLAN.md) 实测数据与决策记录 |

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
- 地图：OpenStreetMap 数据 © OpenStreetMap contributors，ODbL；NASA Blue Marble 为公有领域；地形图 © Commonwealth of Australia (Geoscience Australia) 2025，CC BY 4.0。
- 地名与求助点：GeoNames（CC BY 4.0）与 OpenStreetMap 提取，见 `data/maps/places/README.md`。
- 模型：Gemma 4、EmbeddingGemma 2 为 Apache-2.0，在 App 内由用户自行下载，不随 APK 分发。

**免责声明**：本软件仅为个人爱好项目，请勿相信其中内容。AI 回答和资料都可能出错或过时，作者不对任何使用后果负责。完整声明见 [DISCLAIMER.md](DISCLAIMER.md)。
