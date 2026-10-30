# scripts · 脚本说明

所有脚本都是给 **这台海信 70V1F-S（NOVA72691 / Android 9）** 写的，路径是写死的（`/data/local/misc`、
`com.local.tvhome`）。换机型请先改路径和包名。

## 一览

| 文件 | 跑在哪 | 作用 |
|---|---|---|
| `run.sh` | 电视（root） | root 钩子载荷：`Arun.zip` 里的 `run.sh`，转去执行 `/data/local/misc/rc.sh` |
| `rc-minimal.sh` | 电视（root，开机） | **默认方案**：开机后 90 秒内盯前台，聚好看一上来就切到 TvHome 并清掉它 |
| `rc-watchdog.sh` | 电视（root，开机） | 调试用：开机钩子 + 180 秒 dead-man 看门狗 + 守护循环（会自动拉起 `homewatcher.sh`） |
| `homewatcher.sh` | 电视（root，常驻） | 常驻看护：监听 `ActivityManager: START`，约 1 秒内切回，配合 `rc-watchdog.sh` 使用 |
| `tv-recover-home.ps1` | 电脑 | 一键恢复：重新启用聚好看组件 + 拉起 HOME |

## 部署

```powershell
$TV = '192.168.1.50:5555'

# 1) 放 rc.sh（hook 的实际动作）
adb -s $TV push scripts\rc-minimal.sh /data/local/misc/rc.sh
adb -s $TV shell 'chmod 644 /data/local/misc/rc.sh'

# 2) 打 root 钩子（Arun.zip 其实是 tar）
#    目录里需要有 run.sh
tar -cf Arun.zip run.sh
adb -s $TV push Arun.zip /data/local/misc/Arun.zip

# 3) 立即触发一次（不用重启）
adb -s $TV shell 'setprop ctl.start autorun'
```

> `Arun.zip` 里的 `run.sh` 用本目录的 `scripts/run.sh`。

## 切到"常驻看护"方案

```powershell
adb push scripts\rc-watchdog.sh /data/local/misc/rc.sh
adb push scripts\homewatcher.sh /data/local/misc/watcher.sh
adb shell 'chmod 644 /data/local/misc/rc.sh /data/local/misc/watcher.sh'
adb reboot
```

切回来就重新推 `rc-minimal.sh` 并重启。

## 日志

默认全静默。要看日志：

```console
adb shell 'touch /data/local/misc/debug'
# 复现后
adb shell 'cat /data/local/misc/rc.log'          # rc.sh / rc-watchdog.sh 的输出
adb shell 'cat /data/local/tmp/watcher.log'      # homewatcher.sh 的输出（仅看护方案）
adb shell 'rm -f /data/local/misc/debug /data/local/misc/rc.log /data/local/tmp/watcher.log'
```

## 注意

- `tv-recover-home.ps1` 里的 `pm enable` **必须由 root 执行**（脚本是通过 `setprop ctl.start autorun`
  让钩子去跑的），直接用 `adb shell pm enable` 会抛 `SecurityException`。
- 所有涉及 `pm disable/enable`、`/system` 写入的操作都需要 root 通道，见
  [../docs/03-根权限通道.md](../docs/03-根权限通道.md)。
