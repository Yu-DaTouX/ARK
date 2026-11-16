# 取回已完成的“标准答案NN”子代理输出到 teacher/raw_NN.txt（已取过的跳过）
import json, os, re, subprocess

YAN = "C:/Users/YuDaTou/.pi/agent/yan/bin/yan.cmd"
HERE = os.path.dirname(os.path.abspath(__file__))
lst = json.loads(subprocess.run([YAN, "subagent", "list"], capture_output=True, text=True, encoding="utf-8").stdout)
runs = json.load(open(lst["resultFile"], encoding="utf-8"))
got, running = 0, []
for r in runs:
    title = r.get("title") or (r.get("brief") or {}).get("title") or ""
    m = re.match(r"标准答案(\d\d)$", title)
    if not m:
        continue
    dst = os.path.join(HERE, "teacher", f"raw_{m.group(1)}.txt")
    if r["status"] != "done":
        running.append(m.group(1))
        continue
    if os.path.exists(dst):
        continue
    out = subprocess.run([YAN, "subagent", "get", "--id", r["id"], "--final"], capture_output=True, text=True, encoding="utf-8").stdout
    open(dst, "w", encoding="utf-8").write(out)
    got += 1
print(f"新取回 {got} 批；还在跑：{sorted(running)}")
