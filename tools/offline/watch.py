# -*- coding: utf-8 -*-
"""离线下载监控：每分钟看一次，发现问题就退出并打印原因（给砚的后台命令用，退出即通知）。

  python watch.py [卡住判定分钟=30]
  - 托盘程序不在、也不是用户主动暂停 / 退出 → 自动用快捷方式拉起来（10 分钟内最多一次），连续拉 3 次还不在就报警退出
  - status.json 超过 5 分钟没更新（pull.py 挂了或卡死）→ 报警退出
  - 暂存区和 G 盘清单超过 N 分钟没有任何写入（下载卡住）→ 报警退出
  - 出现新的失败文件 → 报警退出
  - 全部完成 → 退出
  每 30 分钟往 F:/OfflineStage/watch.log 记一行进度。
"""
import ctypes, json, os, subprocess, sys, time

STAGE = "F:/OfflineStage"
HERE = os.path.dirname(os.path.abspath(__file__))
LNK = os.path.join(HERE, "离线资料下载.lnk")
STALL = int(sys.argv[1]) * 60 if len(sys.argv) > 1 else 30 * 60
k32 = ctypes.WinDLL("kernel32", use_last_error=True)
k32.OpenMutexW.restype = ctypes.c_void_p


def tray_running():
    h = k32.OpenMutexW(0x00100000, False, "ArkOfflinePullTray")  # SYNCHRONIZE；打不开 = 托盘没在跑
    if h:
        k32.CloseHandle(ctypes.c_void_p(h)); return True
    return False


def newest_write():
    t = 0
    for d in (f"{STAGE}/dl", f"{STAGE}/ready"):
        for root, _, files in os.walk(d):
            for f in files:
                try: t = max(t, os.path.getmtime(os.path.join(root, f)))
                except OSError: pass
    for p in ("G:/Offline/_lists/manifest.tsv",):
        try: t = max(t, os.path.getmtime(p))
        except OSError: pass
    return t


def log(s):
    line = time.strftime("[%m-%d %H:%M] ") + s
    print(line, flush=True)
    open(f"{STAGE}/watch.log", "a", encoding="utf-8").write(line + "\n")


def main():
    launches, last_launch, last_beat = 0, 0, 0
    st0 = json.load(open(f"{STAGE}/status.json", encoding="utf-8"))
    fail0 = len(st0["failed"])
    log(f"监控开始：{st0['n_done']}/{st0['n_total']} 个，{st0['b_done'] / 1e9:.1f}/{st0['b_total'] / 1e9:.1f} GB，卡住判定 {STALL // 60} 分钟")
    while True:
        time.sleep(60)
        now = time.time()
        if os.path.exists(f"{STAGE}/paused") or os.path.exists(f"{STAGE}/quit"):
            last_beat = last_beat or now
            continue  # 用户主动暂停或退出：不管
        if not tray_running():
            if launches >= 3:
                log("报警：托盘程序拉起 3 次都没起来"); sys.exit(2)
            if now - last_launch > 600:
                launches += 1; last_launch = now
                log(f"托盘程序不在，自动拉起（第 {launches} 次）")
                subprocess.Popen(["explorer.exe", LNK])
            continue
        launches = 0
        try:
            st = json.load(open(f"{STAGE}/status.json", encoding="utf-8"))
        except Exception:
            continue
        age = now - os.path.getmtime(f"{STAGE}/status.json")
        if st["phase"] == "全部完成" or st["phase"].startswith("结束"):
            log(f"下载结束：{st['phase']}，{st['n_done']}/{st['n_total']} 个"); sys.exit(0)
        if age > 300 and now - last_launch > 300:
            log(f"报警：进度 {age / 60:.0f} 分钟没更新（pull.py 可能挂了或卡死）"); sys.exit(3)
        idle = now - newest_write()
        if idle > STALL and now - last_launch > STALL:
            log(f"报警：{idle / 60:.0f} 分钟没有任何写入，下载可能卡住；正在下：{st['dl_batch']}"); sys.exit(4)
        if len(st["failed"]) > fail0:
            log(f"报警：新增失败 {st['failed'][fail0:]}"); sys.exit(5)
        if now - last_beat > 1800:
            last_beat = now
            log(f"进度 {st['n_done']}/{st['n_total']} 个，{st['b_done'] / 1e9:.1f}/{st['b_total'] / 1e9:.1f} GB；正在下：{st['dl_batch']}")


if __name__ == "__main__":
    main()
