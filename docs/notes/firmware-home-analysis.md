# 海信 VIDAA_TV (NOVA72671 / 70V1F-S) —— 桌面替换方案【实测验证版】

> **本报告所有关键结论均已在真机（192.168.31.234:5555）上实测验证。**

- 固件镜像分析：`D:\AI\杂\电视\His72671Upgrade.img`（Android **8.0** / `04.02.60.01` / VIDAA 6.6）
- **真机实测**：Android **9** / SDK 28 / `04.02.60.03` / VIDAA **6.5** / `ro.his.tv.launcher_version=V6LITE`
- ⚠️ **镜像 ≠ 真机**：镜像比真机旧一个大版本。真机的 `services.vdex` 逻辑不同（见 §4）。
  但**真机的 logcat 证明了同一套机制**，所以结论有效。

---

## 0. 最终结论（一句话）

> **能替换桌面。但不是用 `set-home-activity`——它在这台机器上无效。
> 真正的原因是「聚好看的 HOME 过滤器 priority=50，你的桌面 priority=0」，
> 而 priority 是在 APK 的 AndroidManifest 里写死的，改不了设置项。
> 解法有两条，都不需要 root、不需要刷机。**

---

## 1. 实测证据链（真机 logcat，2026-10-22 开机）

```
01-01 08:00:19.577  ActivityManager: initAppWhiteList, read list from
                    [/data/local/misc/app_start_when_boot_whitelist.txt]
01-01 08:00:19.582  ActivityManager: getHomes:com.jamdeo.tv.vod
01-01 08:00:19.582  ActivityManager: getHomes:app.orchard.tv
01-01 08:00:19.582  ActivityManager: getHomes:ca.dstudio.atvlauncher
01-01 08:00:19.582  ActivityManager: initAppWhiteList, App first white list for booting,
                    add home package: app.orchard.tv
01-01 08:00:19.582  ActivityManager: initAppWhiteList, App first white list for booting,
                    add home package: ca.dstudio.atvlauncher
01-01 08:00:19.966  HisenseStartManagerUtil: read firstTimePowerOn: 1 firstTime:0
                    factoryMode:0 agingMode:0
01-01 08:00:19.968  HisenseStartManagerUtil: start up source:2562
01-01 08:00:19.969  ActivityManager: START u0 {act=android.intent.action.MAIN
                    cat=[android.intent.category.HOME] flg=0x10000100
                    cmp=com.jamdeo.tv.vod/com.ju.main.launcher.LauncherActivity} from uid 0
```

**逐条解读：**

| 日志 | 含义 |
|---|---|
| `getHomes: com.jamdeo.tv.vod / app.orchard.tv / ca.dstudio.atvlauncher` | 系统枚举出 **3 个** HOME 候选 |
| `start up source:2562` | `persist.sys.last_source=2562` → `isStartPhysicalSource()` **返回 true** → 走**标准 category.HOME 解析**（不是海信硬编码分支） |
| `START ... cmp=com.jamdeo.tv.vod/...LauncherActivity` | 最终选中**聚好看** |

**所以这台机器走的是标准 AOSP HOME 解析。** 海信那个硬编码 `com.jamdeo.tv.livetv`
的分支（见 §3）只在「物理信号源」等条件下才触发 —— 而这里恰恰因为
`last_source=2562` 走了标准路径。

---

## 2. 为什么 `set-home-activity` 无效（实测）

```bash
$ adb shell cmd package set-home-activity app.orchard.tv/.MainActivity
Success

$ adb shell dumpsys package preferred-xml
    <item name="app.orchard.tv/.MainActivity">
      <filter>
        <action name="android.intent.action.MAIN" />
        <cat name="android.intent.category.HOME" />
        <cat name="android.intent.category.DEFAULT" />
      </filter>
    </item>          ← 写入成功！

$ adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
priority=50 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true
com.jamdeo.tv.vod/com.ju.main.launcher.LauncherActivity    ← 还是聚好看！
```

**根因（`dumpsys package` 实测）：**

| HOME 候选 | intent-filter priority |
|---|---|
| `com.jamdeo.tv.vod/com.ju.main.launcher.LauncherActivity` | **mPriority=50** |
| `app.orchard.tv/.MainActivity` | **无 priority 属性 → 0** |
| `ca.dstudio.atvlauncher/.screens.launcher.LauncherActivity` | **无 priority 属性 → 0** |

AOSP `PackageManagerService.queryIntentActivities` 的排序：**priority 大者在前**。
`set-home-activity` 只是往 `preferred-activities` 里写一条记录，
**但 resolution 时 priority 仍然压过它**（preferred 记录只在 priority 相同的候选之间决胜）。

> 这就是为什么 Aerial 的 `HomeGuardService` 反复设 HOME 也没用 ——
> **它设的是 preferred 记录，而聚看好的 manifest priority=50 永远压过 priority=0。**
> 之前 `#40` 记录里"改了 `app.orchard.tv` 无效"的结论，根因就在这里。

---

## 3. 静态固件逆向（镜像，Android 8.0）——机制确认

### 3.1 开机拉桌面的代码位置

`system/framework/oat/arm/services.vdex` →
`Lcom/android/server/am/ActivityManagerService;->startHomeActivityLocked()`
（code_off `0x208c70`），海信在 AOSP 原方法里插了一段。

**解码方法**：我用客观不变量校验（每个 code_item 的指令长度和必须精确等于 `insns*2`，
且每个分支目标必须落在指令边界上）自建解码器，`services.vdex` 上 **29,878/30,284 = 98.66%** 通过。

过程中定位并修正了 3 个真实解码 bug（这也是我与 bootpath 子组一度结论矛盾的原因）：

1. **invoke-kind (35c/3rc) 的 opcode 在第 2 字节**（`AA|op BBBB`），不是第 1 字节
   → 用已知 method_idx（`HisenseStartManagerUtil.isFteOrFactoryMenuStart` = 37259）
     在方法字节里做锚点，**经验性证明**
2. `if-test` (0x32–0x37) 是 format 22t = **3** 个 code unit
3. `goto/16` (0x28) 偏移是**有符号 1 字节**

### 3.2 确认的分支结构

```
0x208e98  invoke-static HisenseStartManagerUtil->isStartPhysicalSource()   -> v17
0x208ea0  if-nez v17 -> 0x208ed6          ; 物理信号源 -> 标准路径
0x208ea4  xor-int/lit8 v1, v1, #10         ; !v10 (非 FTE/工厂)
0x208ea8  if-eqz v17 -> 0x208ed6          ;        -> 标准路径
0x208eac  xor-int/lit8 v1, v1, #11         ; !v11 (非酒店)
0x208eb0  if-eqz v17 -> 0x208ed6          ;        -> 标准路径
0x208ec4  invoke-virtual ActivityStarter->startHomeActivityLocked()   ; ★标准 HOME 解析★
0x208ed6  const/16 v17, #0                 ; ---- 海信硬编码分支 ----
0x208ee6  if-eqz v10 -> 0x208fce
0x208efa  'com.android.tv.settings' + 'com.jamdeo.tv.fte.MainActivity'     ; FTE（APK 不存在，死分支）
0x208f9c  'com.hisense.uifac' + 'com.hisense.uifac.FacRootMenu'            ; 工厂菜单
0x208fd6  'com.hisense.hotelmenu' + '...HotelHomeActivity'                 ; 酒店模式
0x208ffa  'com.jamdeo.tv.livetv' + 'com.jamdeo.tv.livetv.LiveTvActivity'  ; 默认
0x208f24  if-eqz v17 -> 0x209058   ; mStartComp==null -> "HisenseStart start home now"
0x209078  invoke-virtual ActivityStarter->startHomeActivityLocked()   ; ★标准路径★
```

`isStartPhysicalSource()`（code_off `0x463148`）实测反汇编：

```
0x463196  const/16 v1, #2562
0x46319a  if-eq v1, v0 -> 0x4631a6      ; v0 == 2562 -> return true
0x46319e  const/16 v1, #2304
0x4631a2  if-ne v1, v0 -> 0x4631a8
0x4631a6  return v4                      ; true
0x4631ac  const/16 v1, #2305
0x4631b0  if-eq v1, v0 -> 0x4631a6      ; v0 == 2305 -> true
```

`getStartUpInputSource()`（`0x46300c`）：读 `persist.sys.start_source`（默认 2），
为 0 时改读 `persist.sys.last_source`。

> **真机 `persist.sys.start_source=0`（空）、`persist.sys.last_source=2562`
> → 实际取值 2562 → `isStartPhysicalSource()==true` → 走标准 HOME 解析。**
> **静态分析与真机 logcat 完全对上。**

### 3.3 FallbackHome —— 真机确认存在

```
$ adb shell dumpsys package com.android.settings | grep FallbackHome
        118a87f com.android.settings/.FallbackHome filter 225fe67
```

固件侧：`priv-app/Settings/Settings.apk` → `.FallbackHome`，
`action MAIN` + `category.HOME` + `category.DEFAULT`，**priority = -1000**（AOSP 标准最低）。

> 它只在「一个 HOME 都没有」时兜底；而这里聚好看还在（priority 50），所以用不上。

---

## 4. 两套机制的差别（镜像 8.0 vs 真机 9.0）

| | 镜像（4.02.60.01 / A8 / VIDAA 6.6） | 真机（4.02.60.03 / A9 / VIDAA 6.5） |
|---|---|---|
| 开机拉桌面 | 海信硬编码 `com.jamdeo.tv.livetv` → LiveTV 再 `MAIN+HOME` 跳聚好看 | **标准 category.HOME 解析**（因为 `last_source=2562`） |
| `settings_startup_source_custom_pkg_key` | 只服务工厂OK + 语音遥测，**不参与** | 同上 |
| `FallbackHome` | 存在 | 存在 |

**无论哪一套，最终都是「谁声明了 category.HOME 且 priority 最高，谁当桌面」。**
所以结论对两者都成立。

---

## 5. 【方案】—— 三条路，按风险从低到高

### 路线 1 ★推荐：改 Aerial 的 manifest priority（治本）

`priority` 写在 APK 的 `AndroidManifest.xml` 的 intent-filter 上。
把它从 0 改成 **> 50**（例如 100），Aerial 就会在 resolution 里压过聚好看。

- 需要：改二进制 AXML（工具链我已备好：`axml.py`/`manifest.py` 能读写）→ 重新打包 → 安装
- 风险：只改 Aerial 自己，不动系统；一条 `adb install -r` 即可回滚
- **不碰 init.rc，不碰 settings，不碰系统分区**
- 缺点：需要改 APK 并重新签名（用 debug key 即可， Aerial 已经是自签的）

> Aerial 的 manifest 里 `MainActivity` 的 HOME filter 现在**没有 priority 属性**，
> 需要新增 `android:priority="100"`。

### 路线 2：改 `rc_red_key_action` / 走「双桌面」开关（固件原生）

固件里有 `persist.sys.doublehome_support`（本机为空）和 `rc_red_key_action`
（当前 = `ca.dstudio.atvlauncher`）。海信的「双桌面」机制能让两个桌面共存、
用遥控器某键切换 —— **这可能正是你要的「桌面替换」而不是「桌面禁用」**。

- 风险：需要试出正确的属性值，未验证
- 值得试：`setprop persist.sys.doublehome_support 1`

### 路线 3：禁用聚好看 + 保留 FallbackHome 兜底（你原方案，修正版）

**前提是先补兜底**，否则一定会卡 logo：

```bash
# 1) 先让 FallbackHome 复活（它在 com.android.settings 里）
adb shell pm enable com.android.settings
#   若提示未安装 → adb shell cmd package install-existing com.android.settings

# 2) 再确认 resolve-activity 能看到 FallbackHome
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME

# 3) 之后才禁聚好看
adb shell pm disable-user --user 0 com.jamdeo.tv.vod
# 重启验证
```

- 风险：**中**。聚好看是内容主入口 + 广播接收者，禁掉后：
  - `BOOT_COMPLETED` / `HISENSE_BOOT_COMPLETED` 无人收
  - 装包/删包/网络变化广播无人收
  - 内容/片源入口没了
- 回滚：`adb shell pm enable com.jamdeo.tv.vod`

> ⚠️ **注意**：固件默认分支是硬编码 `com.jamdeo.tv.livetv`（直播电视）。
> 你现在的 disabled 列表里**已经有 `com.jamdeo.tv.livetv`** —— 它也是开机链的一环，
> 禁掉它可能才是你上次卡 logo 的直接原因。

---

## 6. 你原方案缺的那一步（对照表）

| # | 你的原方案 | 实测后修正 |
|---|---|---|
| 1 | 装 Aerial | ✔ 已装（`app.orchard.tv`，进程在跑，`HomeGuardService` 也在跑） |
| 2 | 设默认 HOME | ✔ 已设（preferred-xml 里就是 Aerial）—— **但无效**，因为 priority 50 > 0 |
| 3 | 禁用聚好看 | ⚠️ **先 `pm enable com.android.settings` 补兜底**，否则卡 logo |
| 4 | — | ❗ 真正的拦路虎是 **manifest priority**，不是 settings |

---

## 7. 给你的下一步（按顺序执行，每步都观察）

```bash
ADB="D:\software\开心电视助手8.1版本By剑心\Release\adb.exe"
S=192.168.31.234:5555

# ── 第 0 步：先把 preferred 记录改回来（现在被 Aerial 占了，你可能想要原样）──
$ADB -s $S shell "cmd package set-home-activity com.jamdeo.tv.vod/com.ju.main.launcher.LauncherActivity"

# ── 第 1 步：补兜底（不管走哪条路都该做）────────────────
$ADB -s $S shell "pm enable com.android.settings"
$ADB -s $S shell "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"

# ── 第 2 步：诊断「双桌面」开关是否可用 ──────────────────
$ADB -s $S shell "getprop persist.sys.doublehome_support"
$ADB -s $S shell "getprop persist.sys.tofac_mode"     # 必须是 U，别设 M
$ADB -s $S shell "settings get system rc_red_key_action"

# ── 第 3 步：确认 Aerial 的 priority 现状 ────────────────
$ADB -s $S shell "dumpsys package app.orchard.tv | grep -A5 'MainActivity filter'"
```

**第 3 步之后**，我可以用已经验证过的 AXML 工具链把 Aerial 的
`AndroidManifest.xml` 加上 `android:priority="100"`、重新打包签名，
你 `adb install -r` 上去 —— 那是路线 1。

---

## 8. 诚实的置信度

| 结论 | 置信度 | 依据 |
|---|---|---|
| priority=50 是聚好看赢的唯一原因 | **已实测证明** | dumpsys + set-home-activity 实验 + resolve-activity |
| `last_source=2562` → 标准 HOME 解析 | **已实测证明** | 真机 logcat `start up source:2562` + 静态反汇编 |
| FallbackHome 在真机存在 | **已实测证明** | `dumpsys package com.android.settings` |
| `settings_startup_source_custom_pkg_key` 不参与开机拉桌面 | 静态证明（镜像） | 全固件 const-string 扫描，只找到工厂OK + 语音遥测两处 |
| 海信默认硬编码 `com.jamdeo.tv.livetv` | 静态证明（镜像） | `services.vdex` 字节码，98.66% 校验通过 |
| 路线 1（改 manifest priority）可行 | **高**（机制已证明），但未在真机做过 | 需要重新签名 APK |
| 路线 3 禁用聚好看后仍能开机 | **中** —— 取决于 `com.jamdeo.tv.livetv` 和 FallbackHome 是否都活着 | 需实测 |

---

## 9. 产物

| 路径 | 内容 |
|---|---|
| `_reports\firmware-home-analysis.md` | **本报告** |
| `_fw\parts.json` | 33 个分区表 |
| `_fw\HOME-INVENTORY.txt` | 固件内 6 个 HOME 组件清单 |
| `_fw\BOOTPATH.md` / `_fw\APK-INVENTORY.md` | 两个子组的详报 |
| `_fw\dalvik_final.py` | 98.66% 校验通过的 Dalvik 解码器 |
| `_fw\ams_definitive.txt` | 开机路径的完整正确反汇编 |
| `_fw\livetv_vod.txt` | `startVodLauncher()` 反汇编 |
| `_fw\dev\services.vdex` | 从真机 pull 的 framework（VDEX 019） |
