# TvHome 接管方案（2026-10-29 · 看护进程版）

> 状态：**已生效并通过实机验证**
> 验证：用户在奇异果中按遥控器 HOME → 进入聚好看 → **立刻被拉回 TvHome**（用户实测，约 0.5 秒）

---

## 一、当前方案（已生效）

### 原理

两个组件配合：

1. **root 通道**：海信 `autorun` 服务（`user root`、`class main`、每次开机执行）会读 `/data/local/misc/Arun.zip`（tar 包），解压到 `/tmp` 并 `chmod 777 /tmp/run.sh` 后**以 root 执行**。`/data/local/misc` 是 `0777 shell:shell`，adb 可直接写。
2. **看护进程**：root 启动 `logcat -b events -s am_resume_activity`，监听系统活动事件；一旦发现 `com.jamdeo.tv.vod/com.ju.main.launcher.LauncherActivity` 被 resume，立即 `am start -n com.local.tvhome/.MainActivity`。

```sh
logcat -b events -s am_resume_activity | while read -r line; do
  case "$line" in
    *com.jamdeo.tv.vod/com.ju.main.launcher.LauncherActivity*)
      am start -n com.local.tvhome/.MainActivity ;;
  esac
done
```

事件驱动，无轮询；带 2 秒去抖，避免循环；外层 `while true` 保证 logcat 挂了自动重启。

### 生效范围

| 场景 | 行为 |
|---|---|
| 开机 | 聚好看闪一下 → 约 0.5–1 秒切到 TvHome |
| 应用内按 HOME | 聚好看闪一下 → 约 0.5 秒切回 TvHome |
| 待机唤醒 | 同上 |
| 聚好看本身 | 完全正常，随时可从"全部应用"打开 |

### 文件清单

| 路径 | 作用 |
|---|---|
| `/data/local/misc/Arun.zip` | root 钩子载体（tar，含 `run.sh`） |
| `/data/local/misc/rc.sh` | 开机执行：清理旧实例 → 启动看护进程 |
| `/data/local/misc/watcher.sh` | 看护进程源码（复制到 `/data/local/tmp/homewatcher.sh` 运行） |
| `/data/local/tmp/watcher.log` | 看护日志（HIT 记录） |
| `/data/local/misc/rc.log` | 开机脚本日志 |
| `D:\AI\杂\电视\_scripts\tv-recover-home.ps1` | PC 端一键恢复 |
| `D:\AI\杂\电视\_backup\2026-10-29\` | 设备状态 / 旧 APK / 应用数据备份 |

### 已知小问题

- 早前重复触发留下过两份 watcher 实例（`ps -A -o PID,ARGS | grep homewatcher` 可查）。功能无害（重复 `am start` 幂等），下次重启会自然清理；也可手动 `kill -9`。

---

## 二、为什么不能"跳过聚好看、在 logo 前直接起 TvHome"

### 事实链

1. Android 的开机动画由 SurfaceFlinger 播放，**退出条件是"有应用窗口被绘制"**，不是时间到。
2. 因此"在 logo 之前调起 TvHome"在时序上不成立：logo 就是等待第一个窗口的那段时间。
3. 真正的目标应该是：**让第一个被绘制的窗口就是 TvHome**，这样连闪都没有。

### 卡点

- 把聚好看的 `LauncherActivity` 组件禁用后，开机没有任何窗口可画 → `sys.boot_completed` 永远为空（实测 4 分钟采样全空）→ 卡 logo。
- 说明固件在开机时**点名启动聚好看**（不是按优先级解析候选）。这也是 HOME 键的同一套逻辑。

### 由此得出的三条进阶路线

| 路线 | 做法 | 预期效果 | 风险 | 需要的实验 |
|---|---|---|---|---|
| **A. 改启动指针**（推荐先试） | `settings put system settings_startup_source_custom_pkg_key com.local.tvhome`，配合 `persist.sys.start_source` / `/data/local/misc/startup_source_is_custom` 标志 | 固件开机直接拉起 TvHome，完全无聚好看 | 低（设置可改回） | 1 次重启 |
| **B. 占位桌面（stub）** | 编译一个 20KB 的极简 HOME（黑屏即绘制），禁掉聚好看组件后由它完成"第一帧"，随即拉起 TvHome | 开机无聚好看，首屏黑 0.3s | 中（多一个常驻 stub；HOME 键仍会失效，需输入层兜底） | 1 次重启 + 装 APK |
| **C. 改固件** | 反编译 `services.vdex`（CompactDex）定位 `startHomeActivityLocked` / `HisenseStartManagerUtil`，把显式组件改成 TvHome；remount /system rw 回写 | 根治，无闪、HOME 也通 | 高（写分区、可能变砖、OTA 覆盖） | 多次重启 + 备用救砖验证 |

### A 路线的依据

- 设备上存在 `settings_startup_source_custom_pkg_key`（system 命名空间），当前值 = `com.jamdeo.tv.vod`。
- `/data/local/misc/statupstart_source_is_custom` 一类标志文件存在（`startup_source_is_custom`，system 所有）。
- 历史尝试把它改成 `app.orchard.tv` 后，"有两次开机确实进了 Aerial，第三次没有"——**行为不稳定但方向正确**，说明固件确实读这个键。
- 需要同时确认 `persist.sys.last_source` / `isStartPhysicalSource()` 的分支条件，避免只改一半。

---

## 三、下一步实验清单（都需要重启，等电视空闲）

### 实验 1（A 路线，约 3 分钟）

```bash
adb shell settings put system settings_startup_source_custom_pkg_key com.local.tvhome
adb reboot
# 观察：是否直接进 TvHome、有无聚好看闪屏、boot_completed 是否正常
```
回滚：`settings put system settings_startup_source_custom_pkg_key com.jamdeo.tv.vod`

判读：
- 直接进 TvHome → 收工，甚至可以撤掉看护进程
- 仍进聚好看但被看护切走 → 说明该键只影响别的分支，转 B
- 开机异常 → 立刻用 `tv-recover-home.ps1` 恢复

### 实验 2（B 路线，如果 A 无效）

1. 编译 stub 桌面（`com.local.stubhome`，只显示黑底 + 立即 `startActivity(TvHome)`）
2. 装 stub → 禁聚好看组件 → 重启
3. 判读：`boot_completed` 是否正常、首屏是否 stub→TvHome

### 实验 3（诊断，任何路线前都值得做）

带完整 stderr 复现"组件禁用 + 开机"，抓 `logcat -b all`：
- 确认 `am start` 失败的真实原因（之前的实验把 stderr 丢了，这是唯一未解释的点）
- 若发现是"系统在 boot 阶段拒绝启动第三方 Activity"，则 B/C 都要调整

---

## 四、回滚（任何时候）

```powershell
# 拉回聚好看
powershell -File D:\AI\杂\电视\_scripts\tv-recover-home.ps1

# 彻底移除 root 钩子与看护
adb shell "rm -f /data/local/misc/Arun.zip /data/local/misc/rc.sh /data/local/misc/watcher.sh"
adb shell "kill -9 \$(ps -A -o PID,ARGS | grep homewatcher | grep -v grep | awk '{print \$1}')"
```

---

## 五、关于 `His72671Upgrade.img`

**不需要逆向，对本机无用**：它是 NOVA72671 / Android 8.0 / 2020-05-18 / 内核 #21；
本机是 NOVA72691 / Android 9 / 2024-02-20 / 内核 #1。

要深入分析 HOME 逻辑，应使用**本机**的：

- `_fw/dev/services.vdex`（9.86 MB，`cdex001` CompactDex，头部已解）
- 需要时用 root 钩子直接 pull 本机 `/system/priv-app` 下的聚好看 APK
- `build-tools\34.0.0\dexdump.exe` 已就绪

---

## 六、部署状态（2026-10-29 10:35）
| 项 | 状态 |
|---|---|
| 看护进程（watcher） | ✅ 已在电视上运行并通过实机验证（按 HOME 约 0.5 秒回 TvHome） |
| 设备可达性 | ❌ 电视已关机（`ping` 不通、`adb` 连不上），无法继续部署 |
| L3 看门狗版钩子 | 📦 已写好并归档：`_scripts\rc-watchdog.sh`、`_scripts\homewatcher.sh`，**尚未推送到设备** |

### 下次电视开机后的部署步骤

```powershell
$adb = 'D:\software\开心电视助手8.1版本By剑心\Release\adb.exe'
& $adb connect 192.168.31.234:5555
& $adb push 'D:\AI\杂\电视\_scripts\rc-watchdog.sh' /data/local/misc/rc.sh
& $adb push 'D:\AI\杂\电视\_scripts\homewatcher.sh' /data/local/misc/watcher.sh
& $adb shell 'chmod 644 /data/local/misc/rc.sh /data/local/misc/watcher.sh'
# 不要 setprop ctl.start（会切画面）；新钩子在下次开机/下次触发时生效
```

### 当前设备上的钩子行为（未升级前）

- `/data/local/misc/rc.sh` = 「启动 watcher」版本
- 下次开机：聚好看正常启动（组件启用）→ watcher 自动拉起 → 聚好看被切走
- 安全性：**不依赖任何会破坏开机的状态**，与现在观察到的一致

### 升级到 L3 后的行为

1. 无条件先 `pm enable` 聚好看组件（幂等，保证画得出界面）
2. 收敛为单一 watcher
3. 实验改动区（默认空）
4. 武装 180 秒 dead-man：若 `boot_completed != 1` 或没有前台界面 → 自动回滚并拉起 HOME

---

## 七、10:30–10:50 实机执行记录

### 已部署并验证

| 项 | 结果 |
|---|---|
| L3 看门狗钩子 | ✅ 已推送并在开机时正常武装 |
| 看门狗实战 | ✅ 触发一次 `WATCHDOG TRIP` 并自动恢复（见实验 B 记录） |
| watcher v3 | ✅ 从 `am_resume_activity` 改为 `ActivityManager: START ... cmp=` 触发 + 10 秒兜底轮询 |
| watcher 反应速度 | ✅ 手动拉起聚好看后 **1 秒内**回 TvHome |

**v3 换信号的背景**：重启后 `am_resume_activity` 事件在 events 缓冲区里 0 条（`logcat -b events -d | grep -c am_resume` = 0），v2 因此完全不触发、前台停在聚好看。system 缓冲区的 `ActivityManager: START u0 {...}` 则稳定存在。

### 实验 A（改启动指针）——失败，已回退

1. 第一次：钩子开机时写入 `settings_startup_source_custom_pkg_key=com.local.tvhome` → 开机 START 仍 `cmp=com.jamdeo.tv.vod`
2. 第二次（指针预置、重启后生效）：仍 `cmp=com.jamdeo.tv.vod`
3. 附带查清：`/data/local/misc/startup_source_is_custom` **原本就是 `1`**（自定义启动源一直是开的），改包名依然无效

结论：该键在本固件上不控制开机桌面。已回退为 `com.jamdeo.tv.vod`。

### 实验 B（禁用聚好看组件后开机）——失败，但信息量最大

过程：组件禁用状态下重启 → 钩子跳过 restore → 180 秒内 `boot_completed` 一直为空 → **看门狗自动回滚**（启用组件 + 拉 HOME）→ 系统恢复。

关键日志：

```
08:00:18 boot-hook ... step1: SKIP restore / step3b: exp_novod applied
08:00:18 final: boot= top=            ← 什么都没有
10:45:29 WATCHDOG TRIP boot= top=
10:45:30 START u0 {act=MAIN cat=[HOME] flg=0x10000000 cmp=com.jamdeo.tv.vod/...}  ← 看门狗发起
```

**正常开机时的对照**（组件启用）：

```
08:00:20 START u0 {act=android.intent.action.MAIN cat=[android.intent.category.HOME]
                   flg=0x10000100 cmp=com.jamdeo.tv.vod/com.ju.main.launcher.LauncherActivity} from uid 0
```

推论：开机这条 HOME 启动**不是通用解析**——组件一禁用，系统连 START 都不发出。可能是包名约束，或解析时带了 `MATCH_SYSTEM_ONLY` 之类只匹配系统应用的标志（TvHome 是用户应用，因此被排除）。

### 下一步候选（按性价比）

| 方案 | 做法 | 风险 |
|---|---|---|
| **D. 把 TvHome 装成系统应用** | `mount -o remount,rw /system` → 复制 APK 到 `/system/priv-app/` → 重启，验证开机 START 是否解析到 TvHome | 中：写分区；失败需恢复（有 OTA/恢复出厂兜底） |
| E. 改固件里 `startHomeActivityLocked` | 解 CompactDex + 定位解析标志，改成本地应用可匹配 | 高 |
| 维持现状 | watcher 方案，开机/HOME 各闪 0.5–1 秒 | 无 |

方案 D 的验证判据：重启后 `logcat -b system -d | grep "START u0.*cat=\[android.intent.category.HOME\]"` 的 `cmp=` 是否为 `com.local.tvhome/.MainActivity`。

---

## 八、11:10–11:30 方案 D 执行记录（把 TvHome 装成系统应用）

### 做了什么

1. **remount 验证**：`mount -o remount,rw /` → `touch` 探针 → `mount -o remount,ro /`，rc=0，可读写且可还原
2. **备份**：`tar -cf /data/local/tmp/priv-app-backup.tar -C /system priv-app`（581,916,672 B，195 条目）
3. **重建 TvHome v1.2**：HOME filter 加 `android:priority="100"`，versionCode 3
4. **写入系统分区**：`/system/priv-app/TvHome/com.local.tvhome.apk`（0644 root:root，`u:object_r:system_file:s0`），md5 与源一致
5. **卸载 /data 版本**，重启让系统扫描

### 踩的坑

- 第一次放成 `TvHome.apk` → `PackageManager: Failed to parse /system/priv-app/TvHome: No packages found in split`
  原因：系统应用目录里的 base APK **必须按包名命名**（`com.local.tvhome.apk`）。
- 目录权限被 root umask 收成 0700 → 一并改成 0755。

### 结果

| 项 | 结果 |
|---|---|
| 系统应用 | ✅ `pm path` → `/system/priv-app/TvHome/com.local.tvhome.apk`，`flags=[SYSTEM HAS_CODE ALLOW_CLEAR_USER_DATA]` |
| 优先级 | ✅ TvHome `priority=100`、聚好看 `priority=50`（系统应用后不再被钳制为 0） |
| `cmd package resolve-activity -c HOME` | ✅ 现在返回 **TvHome**（改造前返回聚好看） |
| **开机 START** | ❌ 仍是 `cmp=com.jamdeo.tv.vod/...`，**没有**变成 TvHome |
| 看门狗 | ✅ 本次实验又触发 1 次并自动恢复（累计 3 次验证通过） |
| 最终状态 | boot=1，前台 TvHome，候选 = TvHome(100) + 聚好看(50) |

### 由此确认的机制

- 开机那段 HOME 启动**确实是隐式 intent**（不是硬编码组件）：禁用聚好看后它会退到 FallbackHome
- 但它**不按 manifest priority 选**，也**不认** `settings_startup_source_custom_pkg_key`
- 注意 `flags` 里**没有 `PRIVILEGED`**（虽然位于 priv-app）——若开机解析只接受平台签名的特权应用，这正好解释"系统应用也不被选"

### 可能的下一步（未做）

1. 用平台签名重签 TvHome（设备上没有平台私钥，需从固件/泄露签名获取 → 不现实）
2. 解 `services.vdex`（CompactDex）定位开机解析的过滤标志，改成本地应用可匹配（高风险写分区）
3. 维持现状：开机/HOME 各闪约 1 秒，其余全部正常

### 回滚（如需撤掉系统应用）

```bash
# 删除系统应用副本并装回普通应用
adb shell "su 0 mount -o remount,rw / ; rm -rf /system/priv-app/TvHome ; mount -o remount,ro /"   # 需经 root 钩子执行
adb install -r D:\tvbuild\out\tvhome\TvHome.apk
```
（实际执行方式：把上述内容写进 `/data/local/misc/rc.sh` 再 `setprop ctl.start autorun`）

---

## 九、最终配置（2026-10-29 11:36 定稿）

### 结论：完美版（零闪烁）暂时做不到，实用版已达成

| 目标 | 状态 |
|---|---|
| 开机进 TvHome | ✅ 达成（闪约 1 秒） |
| 按 HOME 回 TvHome | ✅ 达成（闪约 1 秒） |
| 聚好看可用 | ✅ 包/组件完好，可从"全部应用"冷启动 |
| 聚好看常驻内存 | ✅ 已消除（`force-stop`，实测 179–217 MB → 0） |
| 零闪烁（开机/按键直接进 TvHome） | ❌ 暂时无解，原因见下 |

### 为什么"零闪烁"暂时无解

1. **开机那条 HOME 启动不按 manifest priority 选**：TvHome 已是 `priority=100` 的系统应用、`resolve-activity` 也返回 TvHome，但开机仍选 `priority=50` 的聚好看。
2. **不认 `settings_startup_source_custom_pkg_key`**：两次预置重启验证无效（该键配 `startup_source_is_custom=1` 也没用）。
3. 我们的系统应用 `flags` 缺 `PRIVILEGED`；若开机解析只接受**平台签名的特权应用**，这就是卡点 —— 但平台私钥拿不到。
4. 改 `services.vdex`（CompactDex）需要写分区，而本机没有可用的 `His72691Upgrade.img` 救砖包，风险不可接受。
5. 输入层拦截 HOME 键（`getevent`）理论可行，但 shell 读不到输入设备、需真实遥控器配合抓键码，且最多只是省掉闪烁。

### 最终运行态

| 项 | 值 |
|---|---|
| TvHome | `/system/priv-app/TvHome/com.local.tvhome.apk`，`flags=[SYSTEM ...]`，`priority=100`，v1.2 (versionCode 3) |
| root 钩子 | `/data/local/misc/Arun.zip` + `rc.sh`（含 180s 看门狗）+ `watcher.sh`（v5） |
| 看护逻辑 | 监听 `ActivityManager: START ... cmp=聚好看` + 3 秒自检 + 10 秒轮询 → `am start TvHome` → 2 秒后 `am force-stop 聚好看` |
| 内存 | 实测聚好看 PSS 179,097 → 6,893 → gone；`MemAvailable` 稳定约 820 MB |
| 开机 | 聚好看闪约 1 秒 → TvHome |
| HOME 键 | 聚好看闪约 1 秒 → TvHome |
| 看门狗 | 实测触发 3 次均自动恢复 |

### 注意事项

- 聚好看被 `force-stop` 成"已停止"状态，**不影响开机由系统拉起**（已验证：重启后系统仍会启动它，随后被切走并再次清理）
- 用户从"全部应用"打开聚好看时是冷启动（几秒），属正常
- 若某天想彻底恢复原状：删除 `/system/priv-app/TvHome` + 删除 `/data/local/misc/Arun.zip`，重启即可

---

## 十、12:00 定稿：改为极简钩子（无常驻进程、无日志）

用户反馈 HOME 键已直接进 TvHome，看护进程不再必要，遂改为**只在开机时工作 90 秒**的钩子。

### 最终结构

| 组件 | 内容 |
|---|---|
| `/data/local/misc/Arun.zip` | root 钩子载体（tar → run.sh） |
| `/data/local/misc/rc.sh` | 极简开机钩子（1.9 KB） |
| 常驻进程 | **无** |
| 日志 | **默认不写**（放 `/data/local/misc/debug` 才写 `rc.log`） |

### rc.sh 逻辑

1. 清理历史遗留（旧 watcher / logcat 进程与文件）
2. `pm enable` 聚好看组件（保证系统画得出界面、启动能走完）
3. 逐秒轮询 90 秒：一旦前台是聚好看 → `am start TvHome`（带 6 次重试，每次 2 秒）
4. 切换成功后 `am force-stop com.jamdeo.tv.vod`
5. 收尾再判断一次：前台不是聚好看就把它的进程清掉
6. 非 debug 模式删除 `rc.log`

### 实测结果

| 项 | 结果 |
|---|---|
| HOME 键 | ✅ 直接进 TvHome（系统应用 priority=100 生效，不经过聚好看） |
| 开机 | 聚好看显示约 8–10 秒 → 切到 TvHome（启动早期包尚不可启动，需重试到 ~8 秒） |
| 常驻进程 | ✅ 0 |
| 日志文件 | ✅ 无 |
| 聚好看进程 | ✅ 已 `force-stop`，20 秒内未自启；`MemAvailable` 由 ~678 MB 回升到 **834 MB** |
| 聚好看可用性 | ✅ 包与组件完好，可从"全部应用"冷启动 |

### 已知取舍

- 极简版开机有 8–10 秒聚好看；之前的常驻看护版约 1 秒，但有常驻进程 + logcat + 日志。
- 如果以后觉得开机这段太长，恢复常驻版即可：把 `_scripts/homewatcher.sh` 与 `rc-watchdog.sh` 推回 `/data/local/misc/` 并触发一次 `setprop ctl.start autorun`。





