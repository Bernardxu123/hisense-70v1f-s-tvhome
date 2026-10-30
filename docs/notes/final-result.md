# 海信 70V1F-S（VIDAA 6.5 / Android 9）改造记录

时间：2026-10-22 19:43 ~ 22:10
设备：192.168.31.234:5555 · Hisense VIDAA_TV · device NOVA72691 · 芯片 Novatek n62020 · 内存 1.45G · 屏幕 1920×1080

---

## 一、结论

| 目标 | 结果 |
|---|---|
| 装一个好用的第三方桌面 | ✅ Aerial 1.30（`app.orchard.tv`） |
| 桌面入口 | ✅ **遥控器「直播键」直接进 Aerial**（按一次即可） |
| 开机自动进 Aerial | ⚠️ 不稳定（固件开机先拉聚好看，Aerial 靠自身开机接收器后手抢；时成时不成） |
| HOME 键进 Aerial | ❌ 固件写死 HOME→聚好看，未攻破（不做 root 无法改） |
| 屏保 | ✅ 按用户要求**关闭**（`screensaver_enabled=0`） |
| USB 媒体播放器 | ✅ 已重新启用 |
| 投屏 / 奇异果 / 直播电视 | ✅ 全部保持原状（投屏 4 件套启用、奇异果启用、直播电视禁用） |
| 聚好看 | ✅ 包完好、未禁用、未卸载 |

**红线（始终遵守）**：`com.jamdeo.tv.vod`（聚好看）只做 activity 级操作的可能，包本身永远 enabled。

---

## 二、内存实测（关键数据）

测试方法：`dumpsys meminfo <包名>` 取 TOTAL(PSS)，每次切换后等待 8–15 秒。

| 场景 | Aerial | ATV Launcher Pro |
|---|---|---|
| 冷启动耗时 | **1332 ms** | **643 ms** |
| 前台占用 | 103.2 MB | 39.7 MB |
| 后台占用（守护开启时） | **~70 MB 常驻不回收** | ~22 MB |
| 后台占用（守护关闭 + 收回权限后） | **~13 MB** | ~36 MB |

参考：聚好看自身常驻约 200 MB；改造后系统可用内存约 447 MB。

**为什么会差这么多**：Aerial 默认会把自己注册成「无障碍服务 + 通知监听」，靠这两个常驻服务保活/抢桌面。但海信固件**不向第三方无障碍服务派发窗口事件**（logcat 里一条 `AerialGuard` 都没有），所以这两个服务在海信上**完全无效，纯占内存**。

**处置**：已收回 Aerial 的 `WRITE_SECURE_SETTINGS` 并关闭无障碍，Aerial 无法再把守护塞回去。代价：Aerial 失去「被系统杀掉后自动复活 / 自动保持 ADB」能力（这两项在本机本来就不工作）。亮度调节走的是 `WRITE_SETTINGS` appop，未受影响。

---

## 三、做过的改动（全部可回滚）

| # | 改动 | 回滚 |
|---|---|---|
| 1 | 安装 Aerial 1.30（`app.orchard.tv`） | `adb uninstall app.orchard.tv` |
| 2 | 授予位置/写安全设置/悬浮窗/写设置/通知监听 | `pm revoke` 对应项 |
| 3 | AOT 编译 Aerial | 无需回滚 |
| 4 | 屏保：`screensaver_enabled=0`，组件改回原值 | `settings put secure screensaver_enabled 1` |
| 5 | 启用 `com.jamdeo.tv.mediacenter`（USB 播放器） | `pm disable-user --user 0 com.jamdeo.tv.mediacenter` |
| 6 | 固件启动指针 `settings_startup_source_custom_pkg_key` → `app.orchard.tv` | 改回 `com.jamdeo.tv.vod` |
| 7 | 收回 Aerial 的 WRITE_SECURE_SETTINGS + 关闭无障碍（如要恢复守护，反向执行） | `pm grant ...` + `settings put secure accessibility_enabled 1` |

一键还原脚本：`D:\AI\杂\电视\_scripts\restore-home.ps1`（卡 logo / 想全部恢复时跑它）

---

## 四、关键发现（供以后参考）

1. **开机卡 logo 的真因**：聚好看被禁用后，系统没有可用 HOME。固件里其实有 `com.android.settings/.FallbackHome`（AOSP 标准兜底），但它被同批 debloat 一起禁掉了（`pm disable-user com.android.settings`）。恢复兜底：`pm enable com.android.settings`（注意它会同时放出 priority=10 的 `.CryptKeeper`）。
2. **`settings_startup_source_custom_pkg_key` 的争议**：两份固件逆向报告结论相反——一份说它是「桌面指针」，一份说它只是工厂模式记账值、不驱动开机桌面。实机表现：改它之后有两次开机确实进了 Aerial，但第三次没有，**行为不稳定**。
3. **直播键 → Aerial 的机制**：固件在 `com.jamdeo.tv.livetv`（已被禁用）缺失时，退回启动「另一个可用桌面」，Aerial 正好是 HOME 候选里 priority=0 的第一个。
4. **Aerial 守护在海信上无效**：`keysPassThrough=true`（检测到 `com.hisense.vidaaassistant` / `com.jamdeo.tv.vod`）→ 不请求按键过滤；窗口事件也不派发 → 抢不回 HOME。
5. **HOME 键无法改**：固件用显式 component 启动聚好看（`cmp=com.jamdeo.tv.vod/...LauncherActivity from uid 0`），绕开 HOME 解析；`set-home-activity` 写入的 preferred-activity 是 `mAlways=false`，不生效。
6. **屏保改不了黑底时钟**：固件里的 DeskClock 是预编译的（APK 内无 classes.dex，装不上）；海信自带的是「拍信画册」，要联网加载 AI 插件且会带回开机图/广告服务，已重新禁用。

---

## 五、彻底替换桌面还需要什么（未做）

只有 root 能做到以下任意一条：
- 修改 `services.vdex` 里 `startHomeActivityLocked()` 的硬编码 ComponentName；
- 或禁用聚好看桌面组件并补上系统级 FallbackHome；
- 或把 Aerial 装进 `/system/priv-app` 后改写固件配置。

社区对 2024 年这代海信（Android 9 / Novatek）root 成功案例极少，风险（变砖）明显大于收益，**建议停在现状**。

---

## 六、22:10 之后的追加实测：无 root 路径全部堵死

### 6.1 禁用聚好看「桌面组件」——被固件拒绝

```
pm disable-user --user 0 com.jamdeo.tv.vod/com.ju.main.launcher.LauncherActivity
→ SecurityException: Shell cannot change component state for
   com.jamdeo.tv.vod/com.ju.main.launcher.LauncherActivity to 3
```

`com.jamdeo.tv.vod` 在固件的受保护包里（priv-app + UPDATED_SYSTEM_APP + DIRECT_BOOT_AWARE + 桌面），
adb shell 对它的**任何状态变更**都被拒。**T1 方案作废。**

### 6.2 `com.android.settings`（FallbackHome）无法恢复

`enabled=2`（系统级禁用）。试过 5 种方式全部失败：

| 方式 | 结果 |
|---|---|
| `pm enable com.android.settings` | SecurityException |
| 先 `pm disable-user` 降级到状态 3 再 enable | SecurityException |
| `cmd package install-existing --user 0` | 返回「installed for user: 0」，状态仍是 2 |
| 组件级 `pm enable .../.FallbackHome` | SecurityException |
| `am start -n com.android.settings/.FallbackHome` | Activity does not exist |

对照组：`com.android.bluetooth`、`com.android.providers.userdictionary`（同样 enabled=2）也被拒；
而 `com.jamdeo.tv.mediacenter`、`com.jamdeo.tv.livetv`（enabled=3）可正常开关。
→ 这是 Android 通用规则（state 2 只有 root / 系统能改），**不是海信特调**。
→ `firmware-home-analysis.md` 的「方案 A」在本机不可行。

### 6.3 遥控器按键里没有 HOME 键

固件 `TileTransition.vdex` 里只有：`rc_red_key_action`、`rc_green_key_action`、`rc_blue_key_action`、
`rc_yellow_key_action`、`rc_favourate_key_action`。**没有 home 键映射项**，改键救不了 HOME。

### 6.4 Root 可行性只读探测（R1）

| 项 | 结果 | 含义 |
|---|---|---|
| `adb root` | ❌ `adbd cannot run as root in production builds` | 官方路径死 |
| `su` 二进制 | ❌ `/system/xbin`、`/system/bin`、`/sbin`、`/vendor/bin` 全无 | 无现成后门 |
| SELinux | ✅ **Permissive** | 提权后无需再突破 SELinux |
| 内核 | Linux **4.9.118+**，2024-02-20 编译 | 老内核 |
| 安全补丁 | **2021-01-05** | 2021~2022 漏洞理论未修 |
| KASLR | ❌ `CONFIG_RANDOMIZE_BASE` 不存在 | 内核地址固定 |
| `kptr_restrict` / `dmesg` | 2 / 读不到 | 拿不到内核符号，抵消部分优势 |
| `/dev/mem` | ❌ 不存在 | 无直接内核内存口 |
| 工厂菜单 | ✅ 可从 adb 直接拉起 `com.hisense.uifac/.FacRootMenu` | 省掉遥控器组合键 |

**结论**：root 只剩两条路——① 工厂模式（`tofac=M`，需重启 + 人在场；菜单含「母块清空」等危险项，不可盲操作）；
② 内核提权（ARM32 / 4.9，工程量大、panic 风险高）。**建议下次有空时只试工厂模式一次，不成即收尾。**

### 6.5 当日最终状态（关机前核对）

```
前台                     : com.hisensecpa.tv（奇异果，用户在看）
settings_startup_source_custom_pkg_key : app.orchard.tv
rc_red_key_action        : app.orchard.tv（红键 → Aerial）
screensaver_enabled      : 0
accessibility_enabled    : 0
HOME 候选                : 聚好看(50) / Aerial(0) / ATV Launcher(0)
禁用包数量               : 49（原 50，少了 USB 播放器）
com.jamdeo.tv.vod        : enabled=1（健康）
app.orchard.tv           : enabled=0（健康）
```

电视状态健康，可随时关机。下次继续时直接从 §6.4 的工厂模式开始。
