#!/system/bin/sh
# 安静版开机钩子 v4
#
# 用户选择：开机后尽快进入 TvHome（越早越好），之后不再打扰；聚好看后台进程不强求清理。
# 本钩子【不切换界面以外的事】，只做：
#   1. 确保聚好看桌面组件处于启用状态（历史上禁用它会导致开机卡 logo）
#   2. 把 TvHome 的 APK 同步到 /system/priv-app（系统分区那份必须是新版，否则特权权限拿不到）
#   3. 首次运行时安装 TvHome 的特权权限白名单
#   4. **抢跑**：AMS 一就绪就反复 am start TvHome，直到它在前台（早于屏幕亮起）
#   5. 清理旧版本遗留的看护进程和日志
# 默认不写日志；放一个 /data/local/misc/debug 文件才会写 rc.log

LOG=/data/local/misc/rc.log
if [ -f /data/local/misc/debug ]; then
  exec > "$LOG" 2>&1
  chmod 666 "$LOG"
else
  exec > /dev/null 2>&1
fi

VODC=com.jamdeo.tv.vod/com.ju.main.launcher.LauncherActivity
TVH=com.local.tvhome/.MainActivity
WL_SRC=/data/local/misc/privapp-tvhome.xml
WL_DST=/system/etc/permissions/privapp-permissions-tvhome.xml
APK_SRC=/data/local/misc/TvHome.apk
APK_DIR=/system/priv-app/TvHome
APK_DST=$APK_DIR/com.local.tvhome.apk

echo "=== $(date) quiet-hook v4 start"

# 1) 聚好看桌面组件必须是启用状态
pm enable --user 0 "$VODC" 2>&1

# 2) 系统分区里的 TvHome APK 必须是新版
need_apk=0
if [ -f "$APK_SRC" ]; then
  if [ ! -f "$APK_DST" ]; then
    need_apk=1
  else
    a=$(md5sum "$APK_SRC" | awk '{print $1}')
    b=$(md5sum "$APK_DST" | awk '{print $1}')
    [ "$a" != "$b" ] && need_apk=1
  fi
fi

need_wl=0
[ -f "$WL_SRC" ] && [ ! -f "$WL_DST" ] && need_wl=1

if [ "$need_apk" = "1" ] || [ "$need_wl" = "1" ]; then
  mount -o remount,rw / 2>&1
  if [ "$need_apk" = "1" ]; then
    [ -f "$APK_DST" ] && cp "$APK_DST" /data/local/tmp/TvHome-system-old.apk
    mkdir -p "$APK_DIR"
    cp "$APK_SRC" "$APK_DST"
    chown -R 0:0 "$APK_DIR"
    chmod 755 "$APK_DIR"
    chmod 644 "$APK_DST"
    restorecon -R "$APK_DIR" 2>/dev/null
    echo "system apk updated"
  fi
  if [ "$need_wl" = "1" ]; then
    cp "$WL_SRC" "$WL_DST"
    chown 0:0 "$WL_DST"
    chmod 644 "$WL_DST"
    restorecon "$WL_DST" 2>/dev/null
    echo "privapp whitelist installed"
  fi
  sync
  mount -o remount,ro / 2>&1
fi

# 3) 抢跑：AMS 一就绪就把 TvHome 拉到前台（早于屏幕亮起，早于 BOOT_COMPLETED 广播队列）
#    只在真正的冷启动时做（uptime < 180 秒），避免手动触发 autorun 时误切换界面。
UP=$(awk '{print int($1)}' /proc/uptime)
if [ "$UP" -lt 180 ]; then
  (
    i=0
    # 等 AMS 可响应
    while [ $i -lt 240 ]; do
      if dumpsys activity activities 2>/dev/null | grep -q mResumedActivity; then
        break
      fi
      sleep 0.25
      i=$((i+1))
    done
    # 反复尝试，直到自己在前台（最多 ~40 秒）
    i=0
    while [ $i -lt 100 ]; do
      T=$(dumpsys activity activities 2>/dev/null | grep -m1 mResumedActivity)
      case "$T" in
        *com.local.tvhome*) break ;;
      esac
      am start -n "$TVH" >/dev/null 2>&1
      sleep 0.4
      i=$((i+1))
    done
    echo "race done after $i tries"
  ) &
fi

# 4) 清理旧版本留下的看护进程/文件
for p in $(ps -A -o PID,ARGS 2>/dev/null | grep -E "homewatcher\.sh" | grep -v grep | awk '{print $1}'); do
  kill -9 "$p" 2>/dev/null
done
for p in $(ps -A -o PID,ARGS 2>/dev/null | grep -E "logcat -b system -s ActivityManager" | grep -v grep | awk '{print $1}'); do
  kill -9 "$p" 2>/dev/null
done
rm -f /data/local/tmp/homewatcher.sh /data/local/tmp/watcher.log /data/local/tmp/watchdog.log

echo "=== $(date) quiet-hook v4 done"

[ -f /data/local/misc/debug ] || rm -f "$LOG"
