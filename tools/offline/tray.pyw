# -*- coding: utf-8 -*-
"""方舟 · 离线资料下载（托盘小窗）。

双击运行（pythonw，没有黑窗口）：后台启动 pull.py，屏幕右下角托盘出现图标。
  关掉或最小化窗口 = 缩到托盘，下载照常进行；只有“停止下载并退出”才会停（会先确认）。
  pull.py 意外退出时 1 分钟后自动重启（最多 20 次）；停掉或关机后再打开，会接着上次的进度下。
  同一时间只能开一个（再双击会提示已经在运行）。
  暂停 = 结束下载进程（腾出网络和硬盘），继续 = 重新启动，从断点接着下；暂停状态记在 F:/OfflineStage/paused，重开也保持暂停。
"""
import ctypes, json, os, queue, subprocess, sys, threading, time, tkinter as tk
from tkinter import ttk, messagebox

HERE = os.path.dirname(os.path.abspath(__file__))
STAGE = "F:/OfflineStage"
TITLE = "方舟 · 离线资料下载"
NOWIN = 0x08000000  # CREATE_NO_WINDOW：pull.py 和 aria2 都不弹黑窗口

k32 = ctypes.WinDLL("kernel32", use_last_error=True)
k32.CreateMutexW.restype = ctypes.c_void_p
_mutex = k32.CreateMutexW(None, False, "ArkOfflinePullTray")
if ctypes.get_last_error() == 183:  # ERROR_ALREADY_EXISTS
    ctypes.windll.user32.MessageBoxW(0, "离线资料下载已经在运行了。\n图标在屏幕右下角托盘里（可能收在 ^ 里面），单击图标打开进度窗口。", TITLE, 0x40)
    sys.exit()
try:
    ctypes.windll.shcore.SetProcessDpiAwareness(1)
except Exception:
    pass

import pystray
from PIL import Image, ImageDraw

events = queue.Queue()  # 托盘线程 / 监视线程 → 界面线程
GREEN, BLUE, RED, AMBER = "#1f7a4d", "#1f5fa8", "#a8452e", "#b8860b"
PAUSED = f"{STAGE}/paused"
FONT = "Microsoft YaHei UI"


def make_icon(color):
    im = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.ellipse((2, 2, 62, 62), fill=color)
    d.rectangle((27, 12, 37, 32), fill="white")
    d.polygon([(14, 30), (50, 30), (32, 50)], fill="white")
    d.rectangle((16, 52, 48, 55), fill="white")
    return im


class Runner:
    """管 pull.py 子进程：意外退出自动重启，停止时连同 aria2、压缩进程一起结束。"""

    def __init__(self):
        self.p, self.stopping, self.restarts, self.note = None, False, 0, ""

    def start(self):
        out = open(f"{STAGE}/pull_console.log", "a", encoding="utf-8")
        py = os.path.join(os.path.dirname(sys.executable), "python.exe")
        self.p = subprocess.Popen([py, "-u", "pull.py"], cwd=HERE, stdout=out, stderr=subprocess.STDOUT,
                                  env=dict(os.environ, PYTHONIOENCODING="utf-8"), creationflags=NOWIN)
        self.note = ""
        threading.Thread(target=self.watch, args=(self.p,), daemon=True).start()

    def watch(self, p):
        code = p.wait()
        if self.stopping or p is not self.p:  # 暂停 / 退出，或者已经换了新进程
            return
        if code != 0 and self.restarts < 20:
            self.restarts += 1
            self.note = f"下载程序意外退出（代码 {code}），1 分钟后自动重启（第 {self.restarts} 次）"
            time.sleep(60)
            if not self.stopping and p is self.p:
                self.start()
        else:
            self.note = "" if code == 0 else f"下载程序退出（代码 {code}），已不再自动重启，请看日志"
            events.put("finished")

    def alive(self):
        return self.p is not None and self.p.poll() is None

    def stop(self):
        self.stopping = True
        self.note = ""
        if self.alive():
            subprocess.run(["taskkill", "/F", "/T", "/PID", str(self.p.pid)], capture_output=True, creationflags=NOWIN)

    def resume(self):
        if self.alive():
            return
        self.stopping, self.restarts = False, 0
        self.start()


class App:
    def __init__(self):
        self.runner = Runner()
        self.told = False
        r = self.root = tk.Tk()
        r.title(TITLE)
        r.geometry("820x560")
        r.minsize(560, 380)
        self.head = tk.Label(r, font=(FONT, 14, "bold"), anchor="w")
        self.head.pack(fill="x", padx=14, pady=(12, 4))
        self.bar = ttk.Progressbar(r, maximum=1000)
        self.bar.pack(fill="x", padx=14)
        self.line = tk.Label(r, font=(FONT, 10), anchor="w", justify="left")
        self.line.pack(fill="x", padx=14, pady=(6, 6))
        tk.Label(r, text="关掉这个窗口不会停止下载，它会缩到屏幕右下角的托盘里；单击托盘图标可以再打开。",
                 font=(FONT, 9), fg="#666", anchor="w").pack(side="bottom", fill="x", padx=14, pady=(0, 10))
        bf = tk.Frame(r)
        bf.pack(side="bottom", fill="x", padx=14, pady=8)
        self.text = tk.Text(r, font=(FONT, 9), wrap="none", relief="flat", bg="#f4f4f4", padx=8, pady=6, height=10)
        self.text.pack(fill="both", expand=True, padx=14)
        for t, f in (("缩到托盘", self.hide), ("打开 G 盘资料库", lambda: os.startfile("G:\\Offline")),
                     ("打开日志", lambda: os.startfile(os.path.normpath(f"{STAGE}/pull.log")))):
            ttk.Button(bf, text=t, command=f).pack(side="left", padx=(0, 8))
        ttk.Button(bf, text="停止下载并退出", command=self.quit).pack(side="right")
        self.pbtn = ttk.Button(bf, text="暂停下载", command=self.toggle_pause)
        self.pbtn.pack(side="right", padx=(0, 8))
        r.protocol("WM_DELETE_WINDOW", self.hide)
        r.bind("<Unmap>", lambda e: self.hide() if e.widget is r and r.state() == "iconic" else None)

        menu = pystray.Menu(pystray.MenuItem("显示进度", lambda: events.put("show"), default=True),
                            pystray.MenuItem(lambda _: "继续下载" if self.paused else "暂停下载", lambda: events.put("pause")),
                            pystray.MenuItem("打开 G 盘资料库", lambda: events.put("open_g")),
                            pystray.Menu.SEPARATOR,
                            pystray.MenuItem("停止下载并退出", lambda: events.put("quit")))
        self.icon = pystray.Icon("ark_offline", make_icon(GREEN), TITLE, menu)
        threading.Thread(target=self.icon.run, daemon=True).start()
        if os.path.exists(f"{STAGE}/quit"): os.remove(f"{STAGE}/quit")
        self.paused = os.path.exists(PAUSED)
        if self.paused:
            self.runner.stopping = True
            self.pbtn["text"] = "继续下载"
        else:
            self.runner.start()
        r.after(300, self.poll)
        r.after(800, self.refresh)

    # ---- 窗口 / 托盘 ----
    def hide(self):
        self.root.withdraw()
        if not self.told:
            self.told = True
            try:
                self.icon.notify("下载在后台继续。单击托盘图标可以再打开进度窗口。", "已缩到托盘")
            except Exception:
                pass

    def show(self):
        self.root.deiconify()
        self.root.lift()
        self.root.focus_force()

    def toggle_pause(self):
        if self.paused:
            self.paused = False
            if os.path.exists(PAUSED): os.remove(PAUSED)
            self.runner.resume()
            self.pbtn["text"] = "暂停下载"
        else:
            self.paused = True
            open(PAUSED, "w").write(time.strftime("%F %T"))
            self.runner.stop()
            self.pbtn["text"] = "继续下载"
        self.icon.update_menu()
        self.refresh(once=True)

    def quit(self):
        if self.runner.alive():
            self.show()
            if not messagebox.askyesno(TITLE, "确定停止下载吗？\n\n已经下好的文件都会保留，下次打开会接着下。", parent=self.root):
                return
        open(f"{STAGE}/quit", "w").write(time.strftime("%F %T"))  # 告诉 watch.py：是用户主动退出，不要拉起
        self.runner.stop()
        self.icon.stop()
        self.root.destroy()

    def poll(self):
        while not events.empty():
            e = events.get()
            if e == "show":
                self.show()
            elif e == "pause":
                self.toggle_pause()
            elif e == "open_g":
                os.startfile("G:\\Offline")
            elif e == "quit":
                self.quit()
                return
            elif e == "finished":
                self.refresh(once=True)
                self.show()
                try:
                    self.icon.notify(self.head["text"], TITLE)
                except Exception:
                    pass
        self.root.after(300, self.poll)

    # ---- 进度 ----
    def refresh(self, once=False):
        try:
            st = json.load(open(f"{STAGE}/status.json", encoding="utf-8"))
        except Exception:
            st = None
        alive = self.runner.alive()
        if st:
            frac = st["b_done"] / max(1, st["b_total"])
            self.bar["value"] = frac * 1000
            remain, rate = st["b_total"] - st["b_done"], st["rate"]
            eta = f"；按目前速度约还要 {remain / rate / 3600:.0f} 小时" if alive and rate > 2e5 and remain > 0 else ""
            phase = "已暂停（点“继续下载”从断点接着下）" if self.paused else (st["phase"] if alive or st["phase"] != "下载中" else "已停止")
            self.head["text"] = f"{phase}　{frac * 100:.1f}%"
            self.line["text"] = (f"已完成 {st['n_done']} / {st['n_total']} 个文件，{st['b_done'] / 1e9:.1f} / {st['b_total'] / 1e9:.1f} GB{eta}")
            info = [f"正在下载：{st['dl_batch'] or '—'}",
                    f"正在写入 G 盘：{st['moving'] or '空闲'}；等待处理 {st['pending']} 个，等待写入 {st['queued']} 个",
                    f"固态暂存区 {st['staged'] / 1e9:.1f} GB（上限 200）；F 盘剩 {st['free_f'] / 1e9:.0f} GB；G 盘剩 {st['free_g'] / 1e9:.0f} GB",
                    f"失败 {len(st['failed'])} 个" + (f"：{', '.join(st['failed'][-5:])}" if st["failed"] else ""),
                    f"进度更新于 {st['updated']}"]
            pct = f"{frac * 100:.1f}%（{st['n_done']}/{st['n_total']}）"
        else:
            self.head["text"] = "准备中…"
            info, pct = [], "准备中"
        if self.runner.note:
            self.head["text"] = self.runner.note
        try:
            with open(f"{STAGE}/pull.log", "rb") as f:
                f.seek(0, 2); f.seek(max(0, f.tell() - 6000))
                tail = f.read().decode("utf-8", "ignore").splitlines()[-14:]
        except Exception:
            tail = []
        self.text.configure(state="normal")
        self.text.delete("1.0", "end")
        self.text.insert("end", "\n".join(info) + "\n\n—— 最近日志 ——\n" + "\n".join(tail))
        self.text.configure(state="disabled")
        color = AMBER if self.paused else GREEN if alive else (BLUE if st and st["phase"] == "全部完成" else RED)
        if getattr(self, "_color", None) != color:
            self._color = color
            self.icon.icon = make_icon(color)
        self.icon.title = (f"方舟离线资料下载 {pct}" + ("（已暂停）" if self.paused else ""))[:120]
        if not once:
            self.root.after(3000, self.refresh)


if __name__ == "__main__":
    App().root.mainloop()
