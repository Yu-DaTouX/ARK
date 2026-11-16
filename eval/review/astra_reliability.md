[Inkstone note: server-side web search ran for this reply. The provider does not keep raw search results in history; this is the recorded summary.]
Queries: "BluetoothAdapter" "isEnabled" "BLUETOOTH_CONNECT" site:developer.android.com | "isEnabled" "RequiresNoPermission" "BluetoothAdapter"
已完成只读静态审查，未修改任何文件。

已通读指定目录及 Manifest；`F:/ARK/app/app/build.gradle.kts:14–15` 为 **minSdk 26、targetSdk 36**。以下路径相对 `F:/ARK/app/app/src/main/java/app/ark/`。未运行构建、真机或断电测试；原生库、机型相关后果注明“待确认”。

1. **高｜`comm/CommEngine.kt:122`；`ui/comm/BtChatTab.kt:71,78`；`ui/MapScreen.kt:523–524`**  
   **问题/触发：**申请蓝牙权限前就读取 `adapter.isEnabled`，没有异常处理；首次进入通讯页、拒绝权限后重进，以及首次开启信标均经过该路径。Android 12–15 上具体权限检查行为**待确认**，代码缺少防护已确认。  
   **改法：**先检查 `BLUETOOTH_CONNECT`，授权后再访问适配器；捕获 `SecurityException`，把“未授权”和“蓝牙关闭”分成不同状态，不能统一当作关闭。

2. **高｜`sensors/TrackService.kt:34–46,61–63`；`sensors/BeaconService.kt:69–81`；`comm/CommService.kt:40–47`**  
   **问题/触发：**先 `startForeground()`，后检查权限和设备条件，且前者在异常保护之外。定位关闭时仍以 `location` 类型启动；启动期间失去权限也无兜底。Android 14 起会检查类型的运行前提；异步授权回调后已进入后台时，还需考虑启动限制。  
   **改法：**启动入口和服务内部都检查权限、定位开关并捕获启动异常。信标无定位时仅使用 `CONNECTED_DEVICE`，不请求定位；轨迹功能显示失败并 `stopSelf()`。**Manifest 类型声明本身基本齐全，问题是运行条件。**  
   依据：`https://developer.android.com/develop/background-work/services/fgs/service-types`

3. **高｜`llm/LlmManager.kt:58–65,107–118,159–165`；`ui/AskScreen.kt:110–111`**  
   **问题/触发：**单线程 dispatcher 不等于整段协程互斥。生成流挂起时，用户仍能“释放模型”，使 `unload()` 与活跃 Conversation 交错；失败、原生崩溃或释放失败的具体表现**待确认**。  
   **改法：**用同一个 `Mutex` 包住初始化、完整生成及卸载；最好提供 `withModel { ... }`，保证选择模型到生成结束属于同一会话。卸载先取消并等待生成完成，再关闭引擎。  
   依据：`https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-coroutine-dispatcher/limited-parallelism.html`

4. **高｜`data/QaStore.kt:38–53`；`ui/AppViewModel.kt:81–85,224–226,244–247`**  
   **问题/触发：**启动时 `qa.reload()`、书签修改及翻页等根协程没有异常边界。数据库损坏、存储不可读或写满可导致未捕获异常；损坏数据库还可能使每次启动都失败。  
   **改法：**统一包装仓库操作：`catch (e: CancellationException) { throw e }`，再捕获数据库/IO 异常并显示可恢复状态。损坏库保留原文件及 WAL，提供导出/恢复入口，不直接删库重建。

5. **高｜`data/PackRepository.kt:270–277`**  
   **问题/触发：**解压循环只检查 `needsInput()`；遇到需要字典的 zlib 数据，`inflate()` 可持续返回 0，循环不退出。另无解压大小限制，异常路径也不执行 `inf.end()`。导入异常资料包后可永久占住检索锁、耗电或耗尽内存。  
   **改法：**`finally { inf.end() }`；`n == 0 && !finished()` 时区分缺字典、截断和无进展并报错；限制单段解压字节数，循环检查取消。

6. **高｜`comm/AcousticLink.kt:65–105,118–138,147–178`**  
   **问题/触发：**录音线程仅有 `finally`，播放仅捕获中断异常；设备初始化不完整、录音启动失败或音频路由变化时，`startRecording()/play()` 的运行异常会逃出裸线程。自测也缺少覆盖全流程的清理。  
   **改法：**在线程最外层捕获 `Exception`、发布错误；`finally` 释放音频对象并重置状态。自测的 `selfPayload=null`、必要时停止录音等也必须放入 `finally`。

7. **高｜`ui/tools/BaroTool.kt:38–49,61`；`sensors/Sensors.kt:45`**  
   **问题/触发：**海拔输入只检查能否解析。输入 `50000` 后，负底数的非整数次幂产生 NaN，随后 `alt.roundToInt()` 抛异常，直接退出页面/进程。  
   **改法：**校准前检查 `alt.isFinite()` 及合理范围，例如 `-500f..10000f`；海平面气压计算结果也必须有限且在允许范围内，显示前再防御检查。  
   依据：`https://kotlinlang.org/api/core/kotlin-stdlib/kotlin.math/round-to-int.html`

8. **高｜`share/ShareServer.kt:30,58–60,93–98`**  
   **问题/触发：**HTTP 使用无界缓存线程池；每连接一线程，没有连接数、请求头总量或请求总时限。可达服务器的客户端不断连接、缓慢发送请求，即可占满线程/文件描述符并拖垮整款 App。  
   **改法：**使用有界 `ThreadPoolExecutor` 和连接上限；拒绝任务时立即关闭 socket。限制请求头如 16 KiB/64 行，并用单调时钟设置整个请求头的截止时间，而非仅逐次读超时。

9. **高｜`data/Storage.kt:57–60,93–94,129–133`；`data/Waypoints.kt:55–59`**  
   **问题/触发：**“临时文件→正式文件”之前先删除旧文件，不是安全替换；进程在两步之间死亡或重命名失败，会丢失原有模型、资料、地图或全部航点。航点还先更新 UI、吞掉保存错误。  
   **改法：**航点改用 `AtomicFile.startWrite()/finishWrite()/failWrite()`；大文件在同目录写临时文件并 `fd.sync()` 后以 `Os.rename()` 原子覆盖，**不要先删目标**。成功落盘后才发布新状态。

10. **高｜`data/Storage.kt:77,103–110,136–138`**  
    **问题/触发：**导入和 DownloadManager 共用 `<文件名>.part`，没有跨操作互斥。下载中导入同名文件、取消下载时正在导入，都能截断、删除或替换对方的临时文件。  
    **改法：**每个任务使用 UUID 临时文件名，保存其真实路径；最终安装按目标路径加锁。取消只能删除该任务拥有的临时文件。

11. **高｜`data/TrackStore.kt:36–40`；`sensors/TrackService.kt:37–48`**  
    **问题/触发：**写盘失败仍把点加入内存，界面继续显示正在记录；达到 50,000 点后静默丢弃后续点，定位服务仍耗电。断电重启后可能发现此前显示的轨迹不存在。  
    **改法：**写入成功才提交点和计数；失败发布错误并停止或明确进入“仅内存”状态。达到上限时分段滚动存储，或停止服务并通知，不能静默返回。

12. **高｜`comm/CommEngine.kt:339–345,404–415,424–431`**  
    **问题/触发：**`st >= 0` 把 `ST_BAD=2`、无 ACK 的 `99` 都当送达并永久跳过重试。等待 WRITE 时还会丢弃提前到达的 NOTI，进一步制造“假送达”。  
    **改法：**仅匹配消息 ID 的 `ST_NEW/ST_DUP` 确认送达；99 显示“写入完成、未确认”并保留重试。WRITE/NOTI 使用独立队列，或缓存暂时不匹配的事件。

13. **高｜`comm/CommEngine.kt:466,487–495`；`comm/CommStore.kt:58–66,80–97`**  
    **问题/触发：**接收消息仅排队写 JSON，马上回成功 ACK。接收进程在实际保存前被杀，发送方已停止重试，求救消息便永久丢失。  
    **改法：**接收处理放到串行 IO 协程；持久化事务提交成功后再回复 ACK。持久化失败不得回成功；JSON 写队列也应合并快照，避免积压大量旧版本。

14. **高｜`ui/tools/AidCardsTool.kt:56,73–79`**  
    **问题/触发：**急救计时器绑定当前步骤的 `remember`；点下一步、离开页面或进程重建就丢失。每次 `delay(1000)` 只减一秒，后台暂停/深睡会使真实已经过去的时间不计入。  
    **改法：**按卡片/事件保存开始时间、期限及暂停状态，以 `elapsedRealtime()` 计算剩余时间；持久化墙钟与开机标识供重启恢复。切换步骤不能销毁仍在进行的计时任务。

15. **高｜`share/ShareService.kt:22–37`；`share/ShareServer.kt:65–67`**  
    **问题/触发：**分享服务是 `dataSync`，但没有 `onTimeout()`。持续传输会不断续期应用自己的空闲计时；Android 15 后台累计达到系统限额时，没有及时停止将触发进程异常。  
    **改法：**实现 `override fun onTimeout(startId: Int, fgsType: Int) { server?.stop(); stopSelf() }`，同时处理额度耗尽的启动异常，并提示用户传输中断。  
    依据：`https://developer.android.com/develop/background-work/services/fgs/timeout`

16. **中｜`ui/AppViewModel.kt:108–160,210`；`llm/LlmManager.kt:109,154–157`**  
    **问题/触发：**停止只取消“当前 Conversation”，不取消整次提问。加载/检索阶段点击无效；关键词生成阶段点击停止后，仍会继续检索及答案生成，下一次 `generate()` 又清除停止标记。  
    **改法：**保存 `askJob`；停止时取消 Job 并调用模型取消接口。阶段之间 `ensureActive()`；保留部分回答应走显式取消保存流程，不能继续后续生成。

17. **中｜`llm/LlmManager.kt:79–95`；`llm/Embedder.kt:32–49`**  
    **问题/触发：**初始化成功后才把新引擎赋给成员；初始化抛异常时，新对象不可达且没有调用 `close()`。GPU 失败后立即试 CPU，可能叠加残留资源；实际原生残留量**待确认**。  
    **改法：**保留局部 candidate，在失败分支 `runCatching { candidate.close() }`，成功后才转移所有权。取消异常直接重抛，不能作为普通加载失败继续尝试。

18. **中｜`ui/AppViewModel.kt:213–220`；`ArkApp.kt:19–20,29–30`；`data/PackRepository.kt:53,205–225`**  
    **问题/触发：**模型属于 Application，但释放倒计时属于 ViewModel；Activity 结束而进程因前台服务保留时，倒计时被取消，模型可能一直驻留。向量缓存也没有内存预算或压力回收。  
    **改法：**将空闲释放移到模型管理器的应用级作用域；根据活跃请求计数安全卸载。内存压力时清理闲置模型和向量缓存，为缓存设置总字节预算。

19. **中｜`share/ShareServer.kt:83–87,93,173–193`**  
    **问题/触发：**关闭服务只关闭监听 socket、打断线程池，没有关闭已接入 socket。客户端下载大文件后停止读取，可让线程卡在写操作；UI 已显示关闭，线程和文件仍未释放。`soTimeout` 只限制读操作。  
    **改法：**维护活动 socket 集合；`stop()` 逐个关闭，`finally` 移除。为无写入进展的连接设置 watchdog，超时直接关 socket。  
    依据：`https://developer.android.com/reference/java/net/Socket#setSoTimeout(int)`

20. **中｜`share/ShareServer.kt:53,100–108,202–207`**  
    **问题/触发：**地址过滤只影响显示，监听却绑定所有接口；无认证。因此不仅所显示的 Wi-Fi/热点接口，其他可达接口也能下载全部资料包和地图。实际远程可达范围取决于网络，**待确认**。  
    **改法：**绑定用户选择的局域网地址；每次开启生成随机访问令牌并写入二维码，所有下载路由检查令牌；网络变更时重新绑定或停止。

21. **中｜`comm/CommEngine.kt:196,324–331,364,415–416`**  
    **问题/触发：**停止发送线程的中断被底层 `Gatt.once()`/重试 sleep 捕获并吞掉。中断标志被清除后，旧 worker 可继续等待甚至消费新队列；重新开启又创建另一个 worker。  
    **改法：**中断异常向外传播，让最外层退出；或恢复中断标志并立即终止整个发送流程。使用每次启动独立的队列和取消令牌，确认旧 worker 结束后再创建新 worker。

22. **中｜`ui/comm/BtChatTab.kt:76–78`；`ui/comm/AcousticTab.kt:63–64`；`sensors/MyFix.kt:46–57`**  
    **问题/触发：**只在 Compose 销毁时释放资源，没有响应 `ON_STOP`。按 Home/锁屏通常不会销毁组合；未选择“后台保持在线”时，BLE 仍按界面模式运行，麦克风和界面定位也未主动停止。  
    **改法：**用生命周期事件或 `repeatOnLifecycle(STARTED)` 管理 UI 使用权；`ON_STOP` 执行 `release(false)`、停止录音及注销定位。后台 BLE 只由 CommService 持有。

23. **中｜`sensors/MyFix.kt:46–54`；`ui/MapScreen.kt:164,407–410`；`sensors/BeaconService.kt:89–97`**  
    **问题/触发：**定位 effect 只依赖 `enabled`。先打开返回导航、再授权时，授权不会触发重新注册；启动时定位提供者关闭，也没有监听开启事件重新注册。界面可能一直“等待定位”。  
    **改法：**把权限版本、生命周期和定位开关状态加入注册条件；授权成功及提供者开启后重新订阅，停止时注销。不要仅依赖第一次进入页面的状态。

24. **中｜`sensors/MyFix.kt:19–23`；`ui/MapScreen.kt:268–280`；`sensors/NavUi.kt:141–173`**  
    **问题/触发：**导航直接使用无限期缓存的 `MyFix.last`，没有年龄、精度检查。GPS 关闭或失去信号后，仍按旧位置显示距离和“直走/转向”，没有说明定位已过期。  
    **改法：**根据 `elapsedRealtimeNanos` 计算定位年龄，结合精度判断有效性；过期时停止方向指令并显示“最后位置、时间、精度”，不要伪装成实时导航。

25. **中｜`sensors/BeaconService.kt:73–83,99–129`**  
    **问题/触发：**没有蓝牙状态监听。运行期间关闭蓝牙后，状态仍为开启；再次打开时不重新取得 scanner/advertiser，广播/扫描异常部分被静默吞掉。  
    **改法：**监听 `ACTION_STATE_CHANGED`；OFF 时停止扫描、清空句柄并显示离线，ON 时重新检查权限、获取句柄并恢复。扫描失败要更新状态，不能继续显示正常工作。

26. **中｜`ui/MapScreen.kt:138,218`；`sensors/TrackService.kt:34–46`；`data/TrackStore.kt:27,40`；`data/Waypoints.kt:36,59`**  
    **问题/触发：**进入地图同步读取全部轨迹/航点，定位回调在主线程追加文件，航点保存也由点击回调同步执行；地图样式构建还同步读取 PMTiles 文件头。慢存储或长轨迹会卡 UI，ANR 耗时阈值需实测。  
    **改法：**仓库改成 `suspend` API，通过串行 IO 执行器读写；服务先及时升前台，再异步加载历史；样式字符串在 IO/Default 构建，只有 `setStyle()` 回主线程。

27. **中｜`ui/MapScreen.kt:126,268,547–557`**  
    **问题/触发：**返回目标计算位于组合函数主体，每次指南针/气压状态重组都遍历全部轨迹并计算三角函数。长轨迹下即使位置没变，也会反复执行，持续耗电、掉帧。  
    **改法：**先用 `remember(trackPts, meLoc)` 避免无关重算；更好是在 Default dispatcher 按位置变化计算并节流，预计算累计路程，最近点查询使用空间索引。

28. **中｜`ui/AppViewModel.kt:283–294,314–331`**  
    **问题/触发：**轮询读取旧下载列表，经过挂起 IO 后执行 `downloads.value = next`。期间用户新增下载会被覆盖掉，取消的下载可能被重新加入；随后错误列表又持久化。  
    **改法：**按下载 ID 合并结果，例如 `downloads.update { cur -> cur.filterNot { it.id in finishedIds }.map { progress[it.id] ?: it } }`；不要用旧快照覆盖当前列表，并为每项 IO 设置异常边界。

29. **中｜`ui/tools/TorchService.kt:56–63`；`ui/tools/SignalTool.kt:57`**  
    **问题/触发：**声称关屏继续发送，但时序只依赖主线程协程 `delay()`，没有维持 CPU 唤醒的机制。设备深睡时摩尔斯节奏可能暂停或严重拉长；各机型表现**待确认**。  
    **改法：**仅在明确启用后台闪光信号期间持有有超时的 PARTIAL_WAKE_LOCK，所有退出路径释放；用单调时钟计算节拍，不在恢复后补发过期脉冲。  
    依据：`https://developer.android.com/develop/background-work/background-tasks/awake`

30. **中｜`comm/AlarmService.kt:47–55,67–78,81–84`**  
    **问题/触发：**播放器构建失败只退出线程，写失败也只退出循环，服务、`active` 和最长 12 小时的唤醒锁仍保留，形成“没声音却耗电”。原闹钟音量只保存在字段里，进程被杀后也无法恢复。  
    **改法：**播放线程异常或非预期结束时，通知主线程 `stopSelf()`，释放锁并清除状态；用播放会话 ID 避免旧线程停止新播放。调整音量前持久化恢复信息，正常恢复后清除。

总体评价：正常路径已有资源清理和省电意识，但异常恢复、数据持久化及并发取消存在多处实质缺口，尚不适合作为低端手机上长期无人照看的应急工具。
