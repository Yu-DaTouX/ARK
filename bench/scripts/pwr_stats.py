"""分析功耗采样 CSV（pwr.sh 的输出）。

用法：python pwr_stats.py <csv> <开始时间戳>
基线 = 开始时间之前样本的中位数；负载段 = 开始之后功率高于“基线 + 0.8 W”的最长连续区间
（中间允许最多 4 个样本回落，用来跨过两轮测试之间的短暂间隙）。
"""
import csv, sys

path, t0 = sys.argv[1], float(sys.argv[2])
pts = []
for r in csv.DictReader(open(path)):
    try:
        pts.append((float(r["t"]), int(r["current_ua"]) * int(r["voltage_uv"]) / 1e12,
                    int(r["cpu_max_mc"]) / 1000, int(r["batt_temp_dc"]) / 10))
    except ValueError:
        continue
base = sorted(p for t, p, *_ in pts if t < t0)
b = base[len(base) // 2] if base else min(p for _, p, *_ in pts)

segs, cur, gap = [], [], 0
for x in (x for x in pts if x[0] >= t0):
    if x[1] > b + 0.8:
        cur.append(x); gap = 0
    elif cur:
        gap += 1
        if gap > 4:
            segs.append(cur); cur, gap = [], 0
if cur:
    segs.append(cur)
if not segs:
    sys.exit("没有找到负载段")
seg = max(segs, key=lambda s: s[-1][0] - s[0][0])
ts, te = seg[0][0], seg[-1][0]
win = [x for x in pts if ts <= x[0] <= te]
avg = sum(x[1] for x in win) / len(win)
print(f"基线 {b:.2f} W | 负载 {te - ts:.0f} 秒，平均 {avg:.2f} W（比基线多 {avg - b:.2f} W），峰值 {max(x[1] for x in win):.2f} W"
      f" | CPU 最高 {max(x[2] for x in win):.1f}°C | 电池 {win[0][3]:.1f}→{max(x[3] for x in win):.1f}°C | 能耗 {avg * (te - ts):.0f} J")
