#!/bin/bash
# 在手机上用指定模型批量生成（调试版 App，不经过检索，输入和电脑上 tools/embed gen 相同）。
#   bash batch.sh <models/ 下的模型文件名> <输入 jsonl（每行 system/user/q）> <输出 jsonl> [最长 token，默认 900]
#   环境变量 ARK_SERIAL（默认 8be26373）
export MSYS_NO_PATHCONV=1
S=${ARK_SERIAL:-8be26373}
A="adb -s $S"
name=$(basename "$2")
E=/sdcard/Android/data/app.ark.offline/files/eval
if $A shell "su -c true" >/dev/null 2>&1; then   # 有 root（12S Ultra）：经 /data/local/tmp 中转
  $A push "$2" /data/local/tmp/$name >/dev/null
  $A shell "su -c 'mkdir -p $E && cp /data/local/tmp/$name $E/ && rm -f $E/out_$name && chown -R \$(stat -c %u:%g $E/..) $E && chmod 770 $E && chmod 660 $E/$name'"
else
  $A shell "mkdir -p $E; rm -f $E/out_$name"
  $A push "$2" $E/$name >/dev/null
fi
$A shell input keyevent KEYCODE_WAKEUP; $A shell wm dismiss-keyguard
$A logcat -c
$A shell am start ${ARK_USER:+--user $ARK_USER} -n app.ark.offline/app.ark.MainActivity --es ark_model "$1" --es ark_batch "$name" --ei ark_max "${4:-900}" >/dev/null 2>&1
n=$(grep -c . "$2")
while true; do
  sleep 10
  $A shell input keyevent KEYCODE_WAKEUP; $A shell wm dismiss-keyguard
  last=$($A logcat -d -s ark:I | grep -E 'batch ' | tail -1 | sed 's/^.*ark     : //')
  echo "$(date +%H:%M:%S) $last" | cut -c1-140
  echo "$last" | grep -q 'batch done' && break
done
if $A shell "su -c true" >/dev/null 2>&1; then
  $A shell "su -c 'cp $E/out_$name /data/local/tmp/out_$name && chmod 644 /data/local/tmp/out_$name'"
  $A pull /data/local/tmp/out_$name "$3" >/dev/null
else
  $A pull $E/out_$name "$3" >/dev/null
fi
echo "取回 $(grep -c . "$3") / $n 条 → $3"
