#!/system/bin/sh
# 看护进程 v7：默认静默（仅在 /data/local/misc/debug 存在时写日志）
LOG=/data/local/tmp/watcher.log
if [ -f /data/local/misc/debug ]; then
  exec >> "$LOG" 2>&1
  chmod 666 "$LOG" 2>/dev/null
else
  exec > /dev/null 2>&1
fi
VOD=com.jamdeo.tv.vod/com.ju.main.launcher.LauncherActivity
VODPKG=com.jamdeo.tv.vod
TVH=com.local.tvhome/.MainActivity
echo "=== $(date) watcher v7 start pid=$$"

top_activity() {
  dumpsys activity activities 2>/dev/null | grep -m1 mResumedActivity
}

vod_pss() {
  dumpsys meminfo "$VODPKG" 2>/dev/null | grep -m1 "TOTAL" | tr -s ' ' | cut -d' ' -f3
}

clean_vod() {
  am force-stop "$VODPKG" >/dev/null 2>&1
}

switch_if_vod() {
  T=$(top_activity)
  case "$T" in
    *com.jamdeo.tv.vod*)
      echo "$(date) trigger top=vod"
      am start -n "$TVH" >/dev/null 2>&1
      sleep 2
      clean_vod
      echo "$(date) switched"
      ;;
  esac
}

watch_log() {
  # -T 1：只读最后 1 行再跟随，避免重放历史事件导致误切屏
  logcat -b system -s ActivityManager -T 1 2>/dev/null | while read -r line; do
    case "$line" in
      *"cmp=$VOD"*)
        echo "$(date) hit START-vod"
        am start -n "$TVH" >/dev/null 2>&1
        sleep 2
        am force-stop "$VODPKG" >/dev/null 2>&1
        ;;
    esac
  done
}

sleep 3
switch_if_vod

watch_log &
WPID=$!

n=0
while true; do
  sleep 10
  n=$((n+1))
  switch_if_vod
  if [ $((n % 6)) -eq 0 ]; then
    T=$(top_activity)
    case "$T" in
      *com.jamdeo.tv.vod*) : ;;
      *) [ -n "$(vod_pss)" ] && clean_vod ;;
    esac
  fi
  if ! kill -0 "$WPID" 2>/dev/null; then
    echo "$(date) logcat died, restart"
    watch_log &
    WPID=$!
  fi
done
