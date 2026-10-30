#!/system/bin/sh
# 开机钩子（L3 看门狗版 + 实验开关）
# 标志文件（放在 /data/local/misc/，删掉即恢复默认）：
#   exp_novod   -> 不禁用前先恢复：让聚好看组件保持禁用（测试"开机直进 TvHome"）
#   exp_startup -> 写入 settings_startup_source_custom_pkg_key=com.local.tvhome
LOG=/data/local/misc/rc.log
if [ -f /data/local/misc/debug ]; then
  exec > "$LOG" 2>&1
  chmod 666 "$LOG"
else
  exec > /dev/null 2>&1
fi
if [ -f /data/local/misc/debug ]; then WLOG=/data/local/tmp/watchdog.log; else WLOG=/dev/null; fi
export WLOG
VODC=com.jamdeo.tv.vod/com.ju.main.launcher.LauncherActivity

echo "=== $(date) boot-hook uid=$(id -u) boot=$(getprop sys.boot_completed)"

# ---------- 1) 恢复安全态（实验模式除外）
if [ -f /data/local/misc/exp_novod ] || [ -f /data/local/misc/exp_fallback ]; then
  echo "step1: SKIP restore (experiment active)"
else
  pm enable --user 0 "$VODC" 2>&1
  echo "step1: vod component enabled"
fi

# ---------- 2) 收敛为单一 watcher
for p in $(ps -A -o PID,ARGS 2>/dev/null | grep -E "homewatcher\.sh" | grep -v grep | awk '{print $1}'); do
  kill -9 "$p" 2>/dev/null
done
for p in $(ps -A -o PID,ARGS 2>/dev/null | grep -E "logcat -b (events|system) -s" | grep -v grep | awk '{print $1}'); do
  kill -9 "$p" 2>/dev/null
done
sleep 1
if [ -f /data/local/misc/watcher.sh ]; then
  cp /data/local/misc/watcher.sh /data/local/tmp/homewatcher.sh
  chmod 755 /data/local/tmp/homewatcher.sh
  setsid /system/bin/sh /data/local/tmp/homewatcher.sh >/dev/null 2>&1 &
  echo "step2: watcher spawned"
else
  echo "step2: watcher.sh missing"
fi

# ---------- 2b) 守护循环：watcher 意外死掉时自动补活
SUPERVISOR='while true; do
  sleep 120
  C=$(ps -A -o PID,ARGS 2>/dev/null | grep -c "homewatcher\.sh")
  if [ "$C" -lt 1 ]; then
    echo "$(date) supervisor: watcher missing, respawn" >> "$WLOG"
    chmod 666 "$WLOG" 2>/dev/null
    setsid /system/bin/sh /data/local/tmp/homewatcher.sh >/dev/null 2>&1 &
  fi
done'
setsid /system/bin/sh -c "$SUPERVISOR" >/dev/null 2>&1 &
echo "step2b: supervisor spawned"

# ---------- 3) 实验改动区
if [ -f /data/local/misc/exp_startup ]; then
  settings put system settings_startup_source_custom_pkg_key com.local.tvhome 2>&1
  echo "step3a: exp_startup applied -> $(settings get system settings_startup_source_custom_pkg_key)"
fi
if [ -f /data/local/misc/exp_novod ]; then
  pm disable --user 0 "$VODC" 2>&1
  echo "step3b: exp_novod applied"
fi
if [ -f /data/local/misc/exp_fallback ]; then
  pm enable com.android.settings 2>&1
  pm disable --user 0 "$VODC" 2>&1
  echo "step3c: exp_fallback applied (settings enabled, vod component disabled)"
fi
[ -f /data/local/misc/exp_startup ] || [ -f /data/local/misc/exp_novod ] || echo "step3: experiment=none"

# ---------- 4) dead-man 看门狗：180 秒后若没启动完成或没有前台界面 → 回滚
WATCHDOG='sleep 180
B=$(getprop sys.boot_completed)
T=$(dumpsys activity activities 2>/dev/null | grep -m1 mResumedActivity)
if [ "$B" != "1" ] || [ -z "$T" ]; then
  echo "$(date) WATCHDOG TRIP boot=$B top=$T" >> "$WLOG"
  chmod 666 "$WLOG" 2>/dev/null
  pm enable --user 0 com.jamdeo.tv.vod/com.ju.main.launcher.LauncherActivity
  am start -a android.intent.action.MAIN -c android.intent.category.HOME
fi'
setsid /system/bin/sh -c "$WATCHDOG" >/dev/null 2>&1 &
echo "step4: watchdog armed"

# ---------- 5) 记录现场
sleep 2
echo "final: boot=$(getprop sys.boot_completed) top=$(dumpsys activity activities 2>/dev/null | grep -m1 mResumedActivity)"
echo "candidates:"
cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME 2>&1 | grep -E "/"
