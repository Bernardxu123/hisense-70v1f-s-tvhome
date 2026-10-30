#!/system/bin/sh
# 极简开机钩子 v4（轮询 + 重试；最多工作 90 秒，之后退出）
# 默认不留日志（存在 /data/local/misc/debug 时写 rc.log）
LOG=/data/local/misc/rc.log
if [ -f /data/local/misc/debug ]; then
  exec > "$LOG" 2>&1
  chmod 666 "$LOG"
else
  exec > /dev/null 2>&1
fi
VODC=com.jamdeo.tv.vod/com.ju.main.launcher.LauncherActivity
VODPKG=com.jamdeo.tv.vod
TVH=com.local.tvhome/.MainActivity

# 清理历史遗留
for p in $(ps -A -o PID,ARGS 2>/dev/null | grep -E "homewatcher\.sh" | grep -v grep | awk '{print $1}'); do
  kill -9 "$p" 2>/dev/null
done
for p in $(ps -A -o PID,ARGS 2>/dev/null | grep -E "logcat -b system -s ActivityManager" | grep -v grep | awk '{print $1}'); do
  kill -9 "$p" 2>/dev/null
done
rm -f /data/local/tmp/homewatcher.sh /data/local/tmp/watcher.log /data/local/tmp/watchdog.log

echo "=== $(date) minimal-hook v4 start"

top_activity() {
  dumpsys activity activities 2>/dev/null | grep -m1 mResumedActivity
}

# 切换（带重试，避开启动早期的瞬时失败）
switch_with_retry() {
  i=0
  while [ $i -lt 6 ]; do
    i=$((i+1))
    am start -n "$TVH" 2>&1 | tail -1
    sleep 2
    case "$(top_activity)" in
      *tvhome*) echo "switched after retry=$i"; return 0 ;;
    esac
    echo "retry $i failed"
  done
  return 1
}

# 1) 保证聚好看组件启用（系统才能画出界面、走完启动）
pm enable --user 0 "$VODC" 2>&1

# 2) 逐秒盯 90 秒：聚好看一成为前台就切走
k=0
while [ $k -lt 90 ]; do
  k=$((k+1))
  case "$(top_activity)" in
    *com.jamdeo.tv.vod*)
      echo "t=${k}s top=vod -> switching"
      switch_with_retry
      sleep 1
      am force-stop "$VODPKG" >/dev/null 2>&1
      echo "clean vod done"
      break
      ;;
  esac
  sleep 1
done

echo "=== $(date) hook done"

# 3) 收尾：不管有没有切换，只要聚好看不在前台就把它的进程清掉
case "$(top_activity)" in
  *com.jamdeo.tv.vod*) : ;;
  *) am force-stop "$VODPKG" >/dev/null 2>&1 ;;
esac

# 4) 非 debug 不留日志
[ -f /data/local/misc/debug ] || rm -f "$LOG"
