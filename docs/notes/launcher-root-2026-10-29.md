# TvHome 作为默认桌面 · 实施记录（2026-10-29）

> 设备：海信 70V1F-S（0008）· NOVA72691 · Android 9 · 04.02.60.03 · 192.168.31.234:5555
> 结论：**开机自动进 TvHome 已达成并验证 3 次**；**HOME 键仍归聚好看**（固件写死，未攻破）。

---

## 一、最终状态

| 项 | 状态 |
|---|---|
| 开机 | 聚好看 → 约 15 秒后自动切到 **TvHome**（root 钩子） |
| HOME 键 | 仍回**聚好看**（固件行为，未改） |
| 聚好看 | 包完好、组件启用、可正常打开 |
| TvHome | v1.1（`com.local.tvhome`），已注册 HOME 类别，是合法桌面候选 |
| ATV Launcher | 已 `disable-user`（避免与 TvHome 抢候选） |
| root | 通过海信自带 `autorun` 服务的钩子，开机自动生效 |

---

## 二、root 是怎么来的（重要，别丢）

海信 `/init.hisense.rc` 里有：

```
service autorun /system/bin/sh /system/bin/autorun.sh
    user root
    class main          # 未 disabled → 每次开机都跑
```

`autorun.sh` 末尾会读 `/data/local/misc/Arun.zip`（其实是 **tar** 包），解到 `/tmp` 并 `chmod 777 /tmp/run.sh && /tmp/run.sh`——以 **root** 执行。

- 触发方式：`setprop ctl.start autorun`（shell 即可，无需 root）
- 持久性：每次开机自动执行
- 落盘位置：`/data/local/misc/`（`0777 shell:shell`，adb 可直接写）

**当前 `run.sh`（Arun.zip 内）**：调用 `/data/local/misc/rc.sh` 并把输出写到 `rc.log`。
**当前 `rc.sh`**：开机流程 = 确保聚好看组件启用 → 等 `boot_completed` → 等 15 秒 → 反复把 TvHome 拉到前台（最多 8 次，命中即停）。

相关文件：

| 文件 | 作用 |
|---|---|
| `/data/local/misc/Arun.zip` | root 钩子载体（tar，内含 `run.sh`） |
| `/data/local/misc/rc.sh` | 开机实际执行的动作（可随时替换） |
| `/data/local/misc/rc.log` | 上次执行日志 |
| `D:\AI\杂\电视\_scripts\tv-recover-home.ps1` | PC 端一键恢复（拉回聚好看） |

---

## 三、关键发现（都吃过亏）

1. **禁用聚好看的 `LauncherActivity` 会让开机走不完。**
   没有界面被绘制 → 开机动画不退出 → `sys.boot_completed` 永远为空 → 卡 logo。
   此时 `pm` 仍可用（system_server 起着），手动 `pm enable` 立刻恢复。

2. **`cmd package set-home-activity` 在这台机器上无效。**
   返回 Success，但只写进一个 `always="false"` 的旧条目，HOME 解析仍选 priority=50 的聚好看。

3. **HOME 键被固件写死**：组件一禁用，按 HOME 什么都不发生（应用里按 HOME 会"卡住"）。
   注意：`input keyevent 3` 注入的 HOME 会被固件吞掉，**无法用 adb 远程验证按键效果**，只能用遥控器测。

4. **开机后固件还会再拉一次聚好看**，所以钩子必须等 `boot_completed` 之后再等约 15 秒抢占，否则会被顶回去。

5. 海信对第三方包的 intent-filter priority 一律归零，所以 TvHome 无法靠 priority 赢过聚好看（50）。

---

## 四、验证记录（3 次重启）

| 次数 | 条件 | 结果 |
|---|---|---|
| 1 | 组件启用，钩子用 boot-toggle | boot=1，前台 TvHome |
| 2 | **组件禁用态重启**，boot-toggle 先启用再禁用 | boot=1，前台 TvHome（但 HOME 键失效） |
| 3 | 组件启用 + 延后抢占（当前方案） | boot=1，**100 秒后仍是 TvHome** |

---

## 五、回滚

```powershell
# 1) 恢复聚好看为默认桌面（任何时候都能跑）
powershell -File D:\AI\杂\电视\_scripts\tv-recover-home.ps1

# 2) 彻底移除 root 钩子（开机不再执行任何自定义脚本）
adb shell "rm -f /data/local/misc/Arun.zip /data/local/misc/rc.sh"
```
- 恢复 ATV Launcher：`adb shell pm enable ca.dstudio.atvlauncher`
- 卸回旧版 TvHome：`adb install -r D:\AI\杂\电视\_backup\2026-10-29\tvhome-v1.0-installed.apk`（签名不同，需先卸载；旧版无 HOME 类别）

备份位置：`D:\AI\杂\电视\_backup\2026-10-29\`
（设备设置/包状态 XML、TvHome 应用数据、旧 APK、HOME 候选快照）

---

## 六、还没解决的：HOME 键

要真正让 HOME 键回 TvHome，只剩两条路：

| 方案 | 做法 | 风险/成本 |
|---|---|---|
| A. 输入层按键守护 | root 后台进程监听 `/dev/input` 的 HOME 键码，按下就 `am start tvhome` | 需用真实遥控器定键码；守护进程能否在 init 下存活待验证 |
| B. 改固件 | 改 `services` 里 `startHomeActivityLocked` 的显式组件，或替换聚好看桌面 Activity | 需 repack system / remount rw，变砖风险高 |

当前选择：**不做**。开机已进 TvHome，HOME 键维持原样（回聚好看），聚好看随时可用。
