#!/bin/bash
# 12S Ultra（root）：暂停充电 → 采样电流电压 → 批量生成 → 算每问能耗。
#   bash pwr_batch.sh <模型文件名> <输入 jsonl> <输出 jsonl> [最长 token，默认 900]
# 输入第一行会再复制一份放在最前面当热身（含模型加载），能耗只算第 1 题开始到结束这段。
export MSYS_NO_PATHCONV=1
S=${ARK_SERIAL:-8be26373}
A="adb -s $S"
tag=$(basename "$3" .jsonl)
tmp="${3%.jsonl}_in.jsonl"; { head -1 "$2"; cat "$2"; } > "$tmp"
# 不管正常结束还是中途被打断（Ctrl+C、超时、adb 断开后重连），都恢复充电，免得手机一直不充电直到没电关机
trap "$A shell \"su -c 'pkill -f ark/pw[r].sh; echo 0 > /sys/class/qcom-battery/input_suspend'\"" EXIT
$A shell "su -c 'echo 1 > /sys/class/qcom-battery/input_suspend'"
$A shell "su -c 'nohup sh /data/local/tmp/ark/pwr.sh /data/local/tmp/ark/pwr_$tag.csv >/dev/null 2>&1 &'"
$A shell input keyevent KEYCODE_WAKEUP; $A shell wm dismiss-keyguard
sleep 20                                   # 亮屏待机基线
bash "$(dirname "$0")/batch.sh" "$1" "$tmp" "$3.tmp" "${4:-900}"
$A logcat -d -v epoch -s ark:I | grep -E 'batch (2/|done)' > "$3.marks"
$A shell "su -c 'pkill -f ark/pw[r].sh; echo 0 > /sys/class/qcom-battery/input_suspend'"
$A pull /data/local/tmp/ark/pwr_$tag.csv "${3%.jsonl}_pwr.csv" >/dev/null
tail -n +2 "$3.tmp" > "$3"; rm -f "$3.tmp" "$tmp"   # 去掉热身那一条
python "$(dirname "$0")/pwr_window.py" "${3%.jsonl}_pwr.csv" "$3.marks" "$3"
