# 交接：方舟 ARK（2026-11-16 凌晨）

离线求生 Android App（包名 `app.ark.offline`，开源）：本地 AI 按离线资料回答并标出处、回答可加书签、离线地图 + 资料包（必需的内置，其余按需下载）。
进度和实测数据总表见 `docs/PLAN.md`；这份文档记录**当前停在哪、哪些结论已经实测、回来先做什么**。

## 1. 当前状态一览

| 部分 | 状态 |
|---|---|
| App | 内置**中文版核心资料包**（检索走英文、显示和交给模型的是译文，106.6 MB，APK 225 MB），阅读器可切“原文”；12S Ultra 实测通过；模型官方 Gemma 4 E2B。见第 3b 节 |
| 核心资料包中文翻译 | 全部译完（DeepSeek V4.1 Flash），`data/zh/out/`，已做进 App |
| 离线资料库下载 | **全部完成**（11-16 15:56）：1680 个、505.6 GB，失败 0；G 盘剩 1516 GB |
| 微调 | 暂停（第 3 版质量下降不上线），细节见 git 历史里旧版 HANDOFF 和 PLAN.md |
| Git | 最新提交见 `git log`；本轮最后一次提交在写这份文档之后 |

## 2. 环境与常用命令

- 项目 `F:/ARK`（固态）；`G:` USB 机械盘（离线资料库 `G:/Offline`）；下载暂存 `F:/OfflineStage`。
- 测试机：小米 12S Ultra，adb `8be26373`。编译：`cd /f/ARK/app && /f/ARK/tools/gradle-9.9.1/bin/gradle -q assembleDebug`（JAVA_HOME 用 Android Studio 的 jbr）。手机日志 `adb -s 8be26373 logcat -d -s ark`。
- 资料包：`bash data/build_v4.sh` 生成；向量 `tools/embed/.../ark-embed.bat pack <模型> <arkpack> 256`（要设 JAVA_HOME 和 `JAVA_OPTS=-Djava.library.path=F:/ARK/tools/embed/native`）。
- 提交要带作者：`git -c user.name=ARK -c user.email=ark@localhost commit ...`（没配全局身份）。
- 批量调模型：砚工作流里的 `model()`（不派子代理），`yan workflow run --file <脚本> --model-concurrency 32`。**`resume` 带 `--model-concurrency` 不生效**（停在原并发），要改并发就停掉重开，脚本自己跳过已完成的。

## 3. 核心资料包中文翻译（已译完，待接入）

- 输入：`data/zh/in/b_000…b_099.txt`（v4 全部 25282 段，`### 段落id` 分隔）；译文：`data/zh/out/` 同名文件、同格式（34 MB）。
- 过程：Sonnet 子代理（约 1200 段，中途放弃）→ DeepSeek V4.1 Flash 子代理（约 5000 段）→ **`model()` 直接调用**（其余 1.9 万段，32 并发约 1 小时，单文件约 0.02 美元）。
- 工具：`chunk.py split`（把没译的段切成约 2 万字符的小文件 `in_c/`）、`translate_api.js`（每个小文件调一次模型写 `out_c/`，已有的跳过）、`chunk.py merge`（并回 `out/`）、`check.py`（核对，问题段写 `bad.json`）。补译流程：`split` → 把 `chunks.json` 里同名的旧 `out_c` 文件挪走 → 跑 `translate_api.js` → `merge` → `check.py`。
- 核对结果（`check.py`）：25282 段，缺段 0；通过 21211；“没翻成中文” 57（多是参考文献列表，本该保留原文）；“译文过短” 4；**“数字缺失” 4029**——抽查主要是 OCR 页眉页码（如 “Where There Is No Doctor 2011”）、脚注编号、参考文献年份被合理略掉，少数是 13,000 写成“1.3 万”。还没逐条分清哪些是真错。
- **接下来要做**：
  1. 给“数字缺失”分类：只剩下原文正文里的数字（剂量、尺寸、频率）缺失的才重翻；可让脚本排除页码/年份/脚注后再看剩多少。
  2. 定资料包形式：中文段落替换英文，还是中英并存（同一 chunk 两种文本，检索两边都查、显示中文、出处带原文）。并存更稳（剂量等可对照原文），包会变大约一倍。
  3. 生成中文/双语资料包，重算 256 维向量，用 `eval/retr_eval.py` + `retr_judge.py`（108 题）对比 v4，确认检索不变差再换进 App。
- 已知问题：E2B 检索到公式也不会推算（“7.1 MHz 偶极天线多长”答“资料中没有”），可考虑在提示词里引导它用资料里的公式计算。

## 3b. 中文版资料包（已上 App，11-16）

- 生成：`python data/build_zh.py`（输入 v4_256 包 + `data/zh/out`）→ `data/packs/zh/ark-core.arkpack`，再拷到 `app/app/src/main/assets/packs/`。
- 结构：`chunks.text` 是译文；`chunks.text_en` 是 zlib 压缩的英文原文；`chunks_fts` 是用英文建的无内容 FTS5（porter，和 v4 一致）；`vec` 沿用 v4 英文向量；meta `lang=en`、`display=zh`。所以检索结果和 v4 一样（关键词排序已核对一致），只是显示 / 交给模型的是中文。
- 为什么不用中文检索：试过纯中文包（trigram 索引 + 中文向量，`data/packs/zh_trigram`，`eval/retr_zh.py`），Opus 评 108 题：硬核题能直接回答 22→18、无线电机械 30–33→24，明显变差。
- App 改动：`PackRepository` 对 display=zh 的包用英文原文算检索词覆盖数、`original()` 读原文；`ReaderDialog` 加“原文 / 译文”切换和“机器翻译，剂量等以原文为准”提示；`Prompts.RAG`（及 `eval/run_eval.py` SYS_RAG）去掉“英文”二字。中文包分支 `zhMatch` 也支持了英文词（给以后纯中文包用）。
- 手机实测：蛇咬伤、偶极天线、搭电启动等都用中文资料作答、出处显示中文书名，答题 10–14 秒；偶极天线仍不会自己代公式算。还没做：用中文资料重跑 40 题 Opus 盲评和功耗测试，对比英文资料时的 54.2 分 / 114 J。
- 可压缩的方向：不带英文原文可再省约 15 MB（但覆盖排序要改、少了对照原文）。

## 4. 离线资料库下载（已完成）

- 程序：`tools/offline/pull.py`（aria2 下到 F 盘 → 处理 → 顺序写 G 盘 + 读回 SHA-1 校验 → `G:/Offline/_lists/manifest.tsv`）；`tray.pyw` 托盘窗口（暂停/继续、停止；意外退出 1 分钟后自动重启）；快捷方式已放进 Windows 启动文件夹，开机自启。
- 本轮改动：最多 **3 批并行**，大文件单独一批不挡小文件（总速度仍约 8–9 MB/s，瓶颈是宽带）；修了续传大文件时重复计算暂存空间导致一直等待的问题。
- 监控 `tools/offline/watch.py 30`：只在砚开着时有效（砚重启后要重新挂，11-16 03:00 已重挂）。托盘不在就拉起；进度 5 分钟不更新、30 分钟无写入、新失败文件时退出报警。
- 清单 `F:/OfflineStage/inventory.json`（1680 个）；本轮新增 `G:/Offline/_lists/phase5_zh.txt`：古登堡中文全集、中文维基导游/学院/语录、Hesperian 中文《环境健康社区指南》《草根良医》（PDF 分章）、互联网哲学百科（IEP）、中文维基文库（24 GB，大部分是法院判决书，但有严复译《群己权界论》《天演论》《原富》《法意》等）。新增条目后要重启 pull.py 才生效（杀掉 pull.py，托盘 1 分钟后自动重启；注意别打断正在写 G 盘的大文件）。
- 用户要求：**所有书优先找中文版**。已查：现成合法中文版只有上面这些；西方哲学现代中译本都在版权期，不从 Z-Library 等盗版站下载。可做未做：从 archive.org 找民国时期已过版权期的译本（扫描件，要 OCR）。
- 哲学：古登堡 LCC B 已在清单（4131 本英文，其中纯哲学约 690 本，其余多为宗教）；哲学问答站（0.22 GB）用户未确认，没加。

## 4b. 11-16 09:05 G 盘掉线，下载已暂停

- 09:05 起 G 盘（USB 机械盘）从系统里消失（盘符和磁盘都不在），pull.py 写进度报“找不到路径”，监控 exit 3 报警。
- 处理：写了 `F:/OfflineStage/paused` 并结束托盘进程树；没有任何文件写失败（pull.log 无“搬运失败”），暂存区 40 GB 已下好的保留。
- 当时进度 1598 / 1680 个、439 / 506 GB，剩古登堡几个大分类（lcc-n、lcc-t、lcc-u 等）。
- 恢复：重新插好 G 盘、确认 `G:/Offline` 能打开 → 双击“离线资料下载”快捷方式 → 点“继续下载”（会从断点接着下）→ 在砚里重挂 `python -u watch.py 30`。建议检查 USB 线 / 供电，并关掉该盘的 USB 选择性暂停。

## 4c. 公开发布（11-16）

- 仓库 https://github.com/Yu-DaTouX/ARK （公开），Release v0.1.0（`ARK-0.1.0.apk`，176 MB，发布密钥签名，内置**公开版**资料包）。
- 公开版资料包：`python data/build_public.py` 去掉 Foxfire 1–3、Richard Graves（1305 段），剩 23977 段。自用版（`data/packs/zh`，App assets 里那份）保留。编译 release 时临时把 assets 里的包换成公开版，编完换回。
- 公开仓库没有旧历史：本地 `main` 是完整历史，`public` 分支是快照，`bash tools/publish.sh "说明"` 同步（排除 `docs/public_exclude.txt` 里 36 个含版权原文的评测 / 训练数据文件）。
- 发布密钥在仓库外 `F:/ARK-keys/`（`ark-release.jks` + `keystore.properties`），编译时设 `ARK_KEYSTORE_PROPS=F:/ARK-keys/keystore.properties`。**丢了就再也发不了能覆盖安装的更新，要另外备份。**
- 测试手机上装的是调试签名版，和 release 版签名不同，不能互相覆盖（卸载会删掉已下载的模型）。
- GPU 激活精度默认 FLOAT32（数字抄错 6–11 → 0），调试开关 `--es ark_act DEFAULT`。功耗同条件复测（12S，扣基线）：DEFAULT 66 J/问、18.3 s；FLOAT32 75 J/问、24.2 s；按 token 算都约 0.2 J，继续用 FLOAT32。

## 4d. 11-16 晚：通讯、安全、急救卡、计算器

- **通讯**（工具页「通讯」卡，`comm/`、`ui/comm/`）：蓝牙消息（BLE 广播，不配对，3 跳接力）、超声传信（17–19.7 kHz 16-MFSK + RS，只留不可闻模式）、警报声、地图页「更多」里的 BLE 求救信标。12S ↔ Fold6 双机实测都通过：蓝牙消息“已送达 1 台”；超声 52 字节 0 纠错；信标两边互相看到“求救 / 平安”。警报声（满音量）还没测。
- 超声同步修过：门限 0.5、追到峰值后 8 步再锁定、假同步后用环形缓冲补扫。复盘工具 `ark-embed rxfile <pcm> ultra`（先把 app 的 comm 三个文件拷到 `tools/embed/src/main/kotlin/modem/`，不入库）；debug 包停止监听时写 `acoustic_rx.pcm`。
- 安全弹窗 `ui/SafetyNotice.kt`（5 秒倒计时，首次和升级后弹）；公开仓库 README 顶部大字免责 + `DISCLAIMER.md`。
- 急救卡按 GPT-6.1 Sol 和 DeepSeek 两份审查重写为 25 张（`eval/medreview/`），勘误 65 条 87 个 id。
- 计算器：`llm/ArkTools.kt`（与 `tools/embed/.../ArkTools.kt` 同一份，认单位），`LlmManager.generate(calc=true)` 才挂上，**App 里不开**：108 题 Opus 盲评（计算器真正可用的那轮）总体 38 胜 33 负 37 平，计算类题 6 胜 8 负，危险回答 4 比 1（换提示词后回答变长、混用资料）。评测 `eval/calc_set.py`、`prompt_ab.py judge 2 g_tool g_plain`，结果 `eval/ab/jres2_*.txt`、key `key_gtool2.json`。
- 答非所问保护：回答像“请提供您的问题”（`Prompts.lostQuestion`）时，把问题挪到资料前再问一次。

## 4e. v0.3.0 发布（11-17 凌晨）

- 内容：通讯（蓝牙消息 / 超声传信 / 求救信标 / 警报声）、安全弹窗与免责、急救卡 25 张、求生工具、省电优化（地图 15 fps、退后台停传感器、三短声静音期关音频、流式限流）、可靠性修复 29 条（Astra 审查）、全代码注释 + `docs/ARCHITECTURE.md`。
- 构建：versionCode 3 / 0.3.0；`python data/build_public.py` 后的公开包换进 `assets/packs`，`ARK_KEYSTORE_PROPS=F:/ARK-keys/keystore.properties gradle assembleRelease`，编完拷回自用包并 `cmp` 核对（已做）。APK 约 200 MB，签名 `CN=ARK Offline`。
- 公开仓库历史：作者统一改成 `Yu-DaTouX <174885674+Yu-DaTouX@users.noreply.github.com>`（commit-tree 重写，内容和时间不变，force push；旧分支备份 `public-bak`，本地 main 未动）。`publish.sh` 已不再写死 ARK 身份。

## 4f. 地图：离线地名搜索与求助点（11-17）

- 数据：`data/maps/places/`。内置 `assets/places.db`（GeoNames 全球 23.7 万居民点，4.2 万有中文名，30 MB）；区域包 `australia.db`（OSM 提取：30,439 地名 + 29,179 求助点，8 MB，Release `places-2026-11`）。
- App：`data/Places.kt`（搜索 + 附近求助点，含距离/方位）、`ui/MapSearchDialogs.kt`、地图页「更多 → 搜地名 / 附近求助点」、资料页「地名与求助点」管理。
- 12S 实测：搜 Sydney 命中全球库和区域库两类结果，点选飞到悉尼；附近求助点列出 1.7 km 的医院并标「有急诊」。
- 和 Lastbook 的互通：`tools/compat/lastbook_bridge.py` 双向转换地名/求助点（往返 30,439/29,179 全对上，中文名不丢）；底图格式不同（PMTiles vs 自家 feat 表），不互通。
- 给对方提了 PR：Immortal-Protocols/lastbook#63（`tools/build_places_region.py` + 测试 + MAPS.md 小节），用 Yu-DaTouX 账号；澳洲数据未提交（对方的 derived/ 不入库）。

## 4g. 地形图叠加层（11-17 晚）

- 需求：离线便携导入的前提下做到最高精度。结论：**官方 1:250 000 图幅更准**（GA 数据、有等高线/水系/土路），矢量图只在城市街道和 POI 上更全。
- 管线 `data/maps/topo/`：GeoPDF → 裁主图框 → 赋 MGA 坐标 → gdalwarp 到墨卡托 → gdal2tiles(z9–12) → WebP q80 → mbtiles → pmtiles。单幅约 3–6 MB（PNG 要 34 MB）。
- **配准**：图框是 MGA 分带格网矩形，直接把栅格赋 `EPSG:283xx` 再整体重投影，零近似。若只用四角经纬度做一次多项式，幅面中部偏 386 m（算过），已废弃。
- 校验：Adelaide 幅的海岸线落在 138.508°E，真实 Glenelg 海滩 138.509°E，差约 70 m（1:250k 图面 0.3 mm 的水平）。
- App：文件名 `topo*` 的 pmtiles 当叠加层，图层顺序在矢量线之上、地名文字之下，透明度 0.82；地图页「更多 → 地形图叠加」，状态存 `prefs: map_topo`；资料页新增「地形图叠加（1:25 万）」分区。
- 打包：按**州**（不用 MGA 分带——图幅虽按带命名但覆盖跨带，按带打包会在带界出现整条空白）。`build_state.sh <state>` 复用 `build/zone*/mb/*.mbtiles` 的缓存，边界图幅在相邻州重复。
- 缺口：509 幅只覆盖大陆，塔斯马尼亚那 7 幅在 GA 免费系列里没有（点下载跳 Tasmap 商店，付费）。

## 5. 待用户决定

- 中文资料包：替换还是中英并存（第 3 节第 2 条）。
- 是否从 archive.org 收民国旧译本。
- 局域网分享是否加随机口令认证（现在同一网络内谁都能下载公开文件）。
- 12S 上遗留的 e2b-ft2i、e2b-ft3 两个模型要不要删。
- zimgit 里在版权期的书（Foxfire 1–3、Richard Graves 等）随开源 App 分发前要确认。
