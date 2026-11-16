"""按批量测试的时间窗算能耗。
窗口：结束 = logcat 里 'batch done' 的时间；开始 = 结束 − 正式题耗时之和（输出文件里每题的 seconds，
热身题已去掉）。不依赖早期日志（长时间批量时 logcat 环形缓冲会把它们冲掉）。
基线：采样开始后 3–18 秒（脚本在这段时间只亮屏待机）的中位数。
  python pwr_window.py <pwr.csv> <marks 文件（logcat -v epoch）> <输出 jsonl>
"""
import csv, json, sys

path, marks, outj = sys.argv[1], sys.argv[2], sys.argv[3]
rows = [json.loads(l) for l in open(outj, encoding="utf-8") if l.strip()]
n = len(rows)
lines = [l for l in open(marks, encoding="utf-8", errors="replace") if l.strip()]
t1 = float(next(l for l in lines if "batch done" in l).split()[0])
start = t1 - sum(r["seconds"] for r in rows)
pts = []
for r in csv.DictReader(open(path)):
    try:
        pts.append((float(r["t"]), int(r["current_ua"]) * int(r["voltage_uv"]) / 1e12, int(r["cpu_max_mc"]) / 1000))
    except (ValueError, KeyError):
        continue
first = pts[0][0]
base = sorted(p for t, p, _ in pts if first + 3 < t < first + 18)
b = base[len(base) // 2]
win = [x for x in pts if start <= x[0] <= t1]
E = sum((win[i + 1][0] - win[i][0]) * (win[i][1] + win[i + 1][1]) / 2 for i in range(len(win) - 1))
dur = t1 - start
print(f"窗口 {dur:.0f} 秒，{n} 题；平均 {E / dur:.2f} W（亮屏待机基线 {b:.2f} W），最高 CPU {max(c for *_, c in win):.0f}°C")
print(f"每问：总能耗 {E / n:.0f} J，扣除基线 {(E - b * dur) / n:.0f} J，平均 {dur / n:.1f} 秒")
