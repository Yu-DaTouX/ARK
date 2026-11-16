#!/system/bin/sh
# 每 0.5 秒记录：时间 电流(uA，本机放电为正) 电压(uV) 电池温度(0.1C) 最热 CPU 温度(mC)
OUT=${1:-/data/local/tmp/ark/pwr.csv}
Z=""; for z in /sys/class/thermal/thermal_zone*; do case $(cat $z/type 2>/dev/null) in cpu-1-*|cpuss-1*|cpu-0-*) Z="$Z $z/temp";; esac; done
echo "t,current_ua,voltage_uv,batt_temp_dc,cpu_max_mc" > $OUT
# 充电器偶尔会自己恢复充电（11-16 实测：暂停约 45 秒后电流变负），每次采样前都重新暂停
while true; do
  [ "$(cat /sys/class/qcom-battery/input_suspend)" = 1 ] || echo 1 > /sys/class/qcom-battery/input_suspend
  m=0; for f in $Z; do t=$(cat $f 2>/dev/null); [ -n "$t" ] && [ "$t" -gt "$m" ] && m=$t; done
  echo "$(date +%s.%N | cut -c1-14),$(cat /sys/class/power_supply/battery/current_now),$(cat /sys/class/power_supply/battery/voltage_now),$(cat /sys/class/power_supply/battery/temp),$m" >> $OUT
  sleep 0.4
done
