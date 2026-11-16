#!/bin/bash
# 在手机上用指定模型问一个问题（调试版 App），打印检索、速度和回答。
#   bash ask.sh <models/ 下的模型文件名> "<问题>"
export MSYS_NO_PATHCONV=1
A="adb -s ${ARK_SERIAL:-8be26373}"
$A shell input keyevent KEYCODE_WAKEUP
$A logcat -c
b=$(printf '%s' "$2" | base64 -w0)
$A shell am start -n app.ark.offline/app.ark.MainActivity --es ark_model "$1" ${ARK_SPEC:+--es ark_spec $ARK_SPEC} --es ark_ask_b64 "$b" >/dev/null 2>&1
for i in $(seq 1 60); do
  sleep 5
  $A logcat -d -s ark:I | grep -q 'done model' && break
  $A shell input keyevent KEYCODE_WAKEUP; $A shell wm dismiss-keyguard
done
$A logcat -d -s ark:I | grep -E 'bench|done model' | sed 's/^.*ark     : //' | cut -c1-${ARK_CUT:-400}
