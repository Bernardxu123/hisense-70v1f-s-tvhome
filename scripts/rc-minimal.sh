#!/system/bin/sh
# 安静版开机钩子 v2
#
# 用户选择：开机就停在聚好看，手动进入 TvHome；进入 TvHome 后由 App 自己清理聚好看进程。
# 所以这个钩子【不切换界面、不杀前台应用】，只做三件事：
#   1. 确保聚好看桌面组件处于启用状态（历史上禁用它会导致开机卡 logo）
#   2. 首次运行时安装 TvHome 的特权权限白名单（让它能真正 force-stop 聚好看）
#   3. 清理旧版本遗留的看护进程和日志
# 默认不写日志；放一个 /data/local/misc/debug 文件才会写 rc.log

LOG=/data/local/misc/rc.log
if [ -f /data/local/misc/debug ]; then
  exec > "$LOG" 2>&1
  chmod 666 "$LOG"
else
  exec > /dev/null 2>&1
fi

VODC=com.jamdeo.tv.vod/com.ju.main.launcher.LauncherActivity
WL_SRC=/data/local/misc/privapp-tvhome.xml
WL_DST=/system/etc/permissions/privapp-permissions-tvhome.xml

echo "=== $(date) quiet-hook v2 start"

# 1) 聚好看桌面组件必须是启用状态，否则开机画不出界面
pm enable --user 0 "$VODC" 2>&1

# 2) 安装 TvHome 的特权权限白名单（装过就跳过）
if [ -f "$WL_SRC" ] && [ ! -f "$WL_DST" ]; then
  mount -o remount,rw / 2>&1
  cp "$WL_SRC" "$WL_DST"
  chown 0:0 "$WL_DST"
  chmod 644 "$WL_DST"
  restorecon "$WL_DST" 2>/dev/null
  sync
  mount -o remount,ro / 2>&1
  echo "privapp whitelist installed"
fi

# 3) 清理旧版本留下的看护进程/文件
for p in $(ps -A -o PID,ARGS 2>/dev/null | grep -E "homewatcher\.sh" | grep -v grep | awk '{print $1}'); do
  kill -9 "$p" 2>/dev/null
done
for p in $(ps -A -o PID,ARGS 2>/dev/null | grep -E "logcat -b system -s ActivityManager" | grep -v grep | awk '{print $1}'); do
  kill -9 "$p" 2>/dev/null
done
rm -f /data/local/tmp/homewatcher.sh /data/local/tmp/watcher.log /data/local/tmp/watchdog.log

echo "=== $(date) quiet-hook v2 done"

[ -f /data/local/misc/debug ] || rm -f "$LOG"
