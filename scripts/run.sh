#!/system/bin/sh
# root 钩子载荷：由 /data/local/misc/Arun.zip 解出到 /tmp/run.sh 后以 root 执行
# 作用：把实际动作转交给 /data/local/misc/rc.sh，这样改动作不用重新打包 Arun.zip
R=/data/local/misc/rc.sh
if [ -f "$R" ]; then
  chmod 755 "$R"
  /system/bin/sh "$R" > /data/local/misc/rc.log 2>&1
  chmod 666 /data/local/misc/rc.log
fi
