# scripts · 脚本说明

所有脚本都是给 **这台海信 70V1F-S（NOVA72691 / Android 9）** 写的，路径是写死的（`/data/local/misc`、
`com.local.tvhome`）。换机型请先改路径和包名。

## 一览

| 文件 | 跑在哪 | 作用 |
|---|---|---|
| `run.sh` | 电视（root） | root 钩子载荷：`Arun.zip` 里的 `run.sh`，转去执行 `/data/local/misc/rc.sh` |
| `rc-minimal.sh` | 电视（root，开机） | **当前方案（安静版 v2）**：不切换界面、不杀前台；只确保聚好看组件可用、装一次特权白名单、清理旧看护进程 |
| `privapp-tvhome.xml` | 电视（root 复制到 `/system/etc/permissions/`） | 给 TvHome 授予 `FORCE_STOP_PACKAGES`，让它能真正停掉聚好看进程 |
| `rc-watchdog.sh` | 电视（root，开机） | 历史方案：开机钩子 + 180 秒 dead-man 看门狗 + 守护循环（会自动拉起 `homewatcher.sh`） |
| `homewatcher.sh` | 电视（root，常驻） | 历史方案：常驻看护，监听 `ActivityManager: START`，约 1 秒内切回 |
| `tv-recover-home.ps1` | 电脑 | 一键恢复：重新启用聚好看组件 + 拉起 HOME |

> **当前行为**：开机停在聚好看，TvHome 由用户手动进入；进入后由 App 自己清理聚好看进程
> （见 `launcher/README.md`）。`rc-minimal.sh` 现在**不再抢前台**——早期版本会在开机 90 秒内
> 自动切到 TvHome，导致用户手动进桌面后打开其他应用被拽回去，已废弃。

## 部署

```powershell
$TV = '192.168.1.50:5555'

# 1) 放 rc.sh（hook 的实际动作）
adb -s $TV push scripts\rc-minimal.sh /data/local/misc/rc.sh
adb -s $TV shell 'chmod 644 /data/local/misc/rc.sh'

# 2) 放特权白名单（开机钩子会把它复制到 /system/etc/permissions/）
adb -s $TV push scripts\privapp-tvhome.xml /data/local/misc/privapp-tvhome.xml
adb -s $TV shell 'chmod 644 /data/local/misc/privapp-tvhome.xml'

# 3) 打 root 钩子（Arun.zip 其实是 tar）
#    目录里需要有 run.sh
tar -cf Arun.zip run.sh
adb -s $TV push Arun.zip /data/local/misc/Arun.zip

# 4) 立即触发一次（不用重启）
adb -s $TV shell 'setprop ctl.start autorun'

# 5) 白名单写入后，需要重启一次（框架启动时才读 /system/etc/permissions）
adb -s $TV reboot
```

> `Arun.zip` 里的 `run.sh` 用本目录的 `scripts/run.sh`。
> 白名单只在首次生效：文件已存在就跳过，不会反复写 `/system`。

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
