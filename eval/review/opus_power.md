节能修改已全部完成：12 个文件，每改一处都跑了 `compileDebugKotlin`，没有 `e:` 行，最后一次 exit=0。没有 git 提交，没动手机，`AcousticModem.kt`、FLOAT32 和采样参数都没改。下面的省电数值都是推算，还没在真机上测过。

**改了的（行号是改后的）**

1. **`ui/MapScreen.kt:298-300`，地图持续重绘**：按“我的位置”后，定位点每秒更新一次，罗盘每 0.5 秒更新一次（从 MapLibre 的 aar 里用 javap 查到 `COMPASS_UPDATE_RATE_MS=500`），都会触发平滑动画，跟随模式下整张地图几乎一直按屏幕刷新率（60–120 Hz）重绘。
   - 改法：加了 `lc.setMaxAnimationFps(15)`。
   - 预计：这时 GPU 重绘量降到原来的约 1/4 到 1/8；步行速度下看不出卡顿。
2. **`ui/MapScreen.kt:126`、`sensors/Sensors.kt:124-133`，地图页反复重组**：原来指南针 15 Hz、气压计 5 Hz 一更新，整个地图页就重组一次。
   - 改法：`rememberCompass` 加了 `stepDeg` 参数，地图页取整到 1°，并用 `derivedStateOf` 只在取整后的值变了才重组。
   - 交会定位记录方位时改读未取整的值（`:476`），精度不变。
   - 指南针工具、天体定向仍是原来的 15 Hz。
3. **`sensors/Sensors.kt:151-153`，气压计**：气压平均值变化不到 0.02 hPa（约 0.17 m）就不更新界面。界面只显示到 0.1 hPa / 1 m，看不出差别；`PressureLog` 照常每次都记。
4. **`sensors/MyFix.kt:46-68`，导航用的 3 秒一次 GPS**：原来只有离开页面才停。轨迹或信标前台服务在跑时，应用不受系统后台定位限流，锁屏后 GPS 会一直按 3 秒一次跑。
   - 改法：退到后台或锁屏（ON_STOP）就停，回到前台（ON_START）再开。
5. **`ui/comm/BtChatTab.kt:78-94`，加 `CommEngine.kt:81-82` 的只读 `uiActive`**：原来蓝牙消息页退到后台后，界面模式一直在跑：每 12 秒满功率（LOW_LATENCY）扫 5 秒，扫描占空比约 42%，外加 BALANCED 档广播。
   - 改法：退到后台 30 秒后放掉界面这一路。开了“保持在线”就退回每 30 秒扫 5 秒的低功耗档，没开就停；回到前台自动恢复。
   - 这是用户能感知的变化：没开“保持在线”时，锁屏 30 秒后就收不到消息了。理由是开关说明本来就写着“锁屏也收得到”只属于“保持在线”。留 30 秒宽限，是为了让正在发的消息发完。
6. **`ui/comm/AcousticTab.kt:64-75`，超声传信监听**：Android 9 起后台应用录到的只是静音，但解调线程还在以音频优先级空转。
   - 改法：ON_STOP 时关麦克风，回到前台自动恢复。Android 8 以下（API < 28）后台仍能录音，所以保持原行为。
7. **`comm/AlarmService.kt:71, 82-104`，“三短声”警报**：原来每 60 秒只响 3 秒，剩下 57 秒一直在往扬声器写静音，音频通路和功放一直开着。
   - 改法：响完就 `track.stop()`，睡到下一个周期再 `play()`。声音和节奏不变，唤醒锁照旧持有。
   - 预计：每小时省几十 mA·h（猜测是 20–60 mA × 95% 的时间），没测过。
8. **`llm/LlmManager.kt:117-128, 185`，问答流式输出**：原来每个 token 都对全文跑一次 `clean()` 正则，再触发一次重组和排版。CPU 后端时还会和解码抢核。
   - 改法：界面最多每 120 ms 刷新一次，结束时一定再刷一次完整文本。
   - 返回值和截断逻辑都没变，准确度不受影响。
9. **`ui/AskScreen.kt:176`，转圈动画**：转圈会逐帧重绘，还和模型抢 GPU。现在只在还没出字时转，开始出字后不再显示。界面上能看出这一点变化。
   - 第 8、9 两项预计每次问答省约 0.5–2 J，大约是扣掉基线后 39 J 里的 1–5%，没测过。
10. **`comm/CommService.kt:31-36`、`sensors/BeaconService.kt:113-121, 137, 140-143`，前台服务通知**：原来 CommService 每 15 秒、Beacon 每分钟两次无条件刷新通知，现在只在内容变了才发。
    - 顺带修了一个问题：`onDestroy` 里 `stopScan` 原来会再发一次通知，可能留下关不掉的残留通知，现在不会了。
11. **`ui/AppViewModel.kt:330-332`，下载轮询**：原来每秒写一次 SharedPreferences，现在只在下载任务列表变化时写。

**看了但没改的**
- **BLE 扫描模式（Comm 和信标都是 LOW_LATENCY）**：扫描窗口本来就短（5 s / 10 s），改成 BALANCED 会让 1 Hz 低功耗广播的发现率下降，信标是求救用的，所以不动。值得在真机上做 A/B 对比发现率后再决定。
- **广播功率 TX_POWER_HIGH**：发射距离就是这个功能本身，不动。
- **方位引擎的 SENSOR_DELAY_GAME（50 Hz）**：几个滤波系数是按每个事件定的，降采样率会改变磁干扰判定和收敛速度，影响方向准确度。
- **空闲卸载 10 分钟**：模型常驻内存基本不耗电，而重新加载要约 9 秒 GPU 初始化（约 40 J）。缩短反而更费电，维持不变。
- **`maxTokens` 默认 512**：只是上限，不生成就不耗电。调小会截断长回答，有损准确度。关键词那次调用是 48，已经很小。
- **其他**：
  - 急救卡页常亮：急救时腾不出手，是设计如此。
  - 天体相机叠加层 20 fps：只在主动瞄准时运行。
  - 信标 60 秒一次的 GPS 加 NETWORK：系统在间隔 ≥ 10 s 时会对 GNSS 做间歇开关。
  - 局域网分享服务器：空闲时不占 CPU。
- **AlarmTab 的耗电估算**：仍是“基础 70 mA”。改了第 7 项后，“三短声”的估算会偏高，等实测后再改文案。
- **一个顺带发现的功能问题（不属于省电，没改）**：CommEngine 和 BeaconService 的扫描周期用的是 `Handler.postDelayed`，没有唤醒锁，手机深睡时周期会停走。

**真机怎么测**
1. 准备：`adb shell dumpsys batterystats --reset`，再 `adb shell dumpsys battery unplug`，跑完一个场景后 `adb shell dumpsys batterystats app.ark.offline`。重点看 Bluetooth scan 时长、GPS、Sensor、Audio、Wake lock 这几项。
2. 各项对应的检查：
   - 第 4、6 项：锁屏 1 分钟后，`dumpsys location` 里不应再有 app.ark 的 3 秒请求；`dumpsys audio` 里不应再有录音会话。
   - 第 5 项：锁屏 1 分钟后，`dumpsys bluetooth_manager` 看扫描客户端，“保持在线”开和关两种情况都看一下。
   - 第 7 项：在“三短声”的静音期里，`dumpsys media.audio_flinger` 应该看不到处于活动状态的 track。
   - 第 1、9 项：`adb shell dumpsys gfxinfo app.ark.offline reset`，然后跑 60 秒地图跟随定位，或跑一次问答，再看 `Total frames rendered`。新旧 APK 对比。
3. 焦耳：用你们测 114 J 的同一方法，同一题、同一机器，新旧 APK 各跑 5 次，扣掉 75 J 基线后比较。
   - 地图：“定位跟随 5 分钟”对比。
   - 警报：“三短声 30 分钟”对比，看平均电流。

修改的文件（都在 `app/app/src/main/java/app/ark/` 下）：
- `llm/LlmManager.kt`
- `ui/AskScreen.kt`
- `ui/AppViewModel.kt`
- `ui/MapScreen.kt`
- `ui/comm/AcousticTab.kt`
- `ui/comm/BtChatTab.kt`
- `comm/AlarmService.kt`
- `comm/CommEngine.kt`
- `comm/CommService.kt`
- `sensors/BeaconService.kt`
- `sensors/MyFix.kt`
- `sensors/Sensors.kt`
