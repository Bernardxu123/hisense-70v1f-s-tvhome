# 杀聚好看可行性分析（免 root / 不卸载 / 不阻断开机拉取）

> 任务：task-1「杀聚好看可行性：固件/组件级路径分析」
> 对象：海信 70V1F-S · VIDAA_TV / NOVA72691 / n62020 / ARM32 · Android 9 (SDK28) / 04.02.60.03 · 1.5GB RAM
> 镜像：`His72671Upgrade.img`（04.02.60.01，比真机旧一个大版本）· 真机 192.168.31.234:5555（**全程只读**）
> 标注约定：**[实测]** = 真机只读命令输出 / Lead 已实测事实 · **[静态证据]** = 镜像/APK/vdex 解析结果 · **[推断]** = 由证据推出的结论 · **[未验证]** = 需要真机实验才能定性
> 撰写时间：2026-10-25

---

## 0. 结论（先看这 5 条）

1. **不存在"免 root、普通 App 可调用、能杀 adj=200"的官方入口。**
   全镜像 97 个系统 APK 的 manifest 做了全量扫描（`_fw/killpath_tmp/apks_system.json`），持有 `FORCE_STOP_PACKAGES` 的只有 6 个包，全是 `sharedUserId=android.uid.system` 的特权应用；它们**没有任何一个把"按包名杀进程"暴露成 exported Service/Receiver/AIDL**。真机上这 6 个里只剩 2 个还在：[实测] `com.hisense.tv.inputs`(uid 10027) 和 `com.hisense.multiscreen.screenhelper`(uid 1000)，而且两者的组件里都找不到"killer"接口（唯一线索见 §3-P1）。

2. **`ActivityManager.killBackgroundProcesses()` 的天花板是 adj≥500（SERVICE_ADJ），杀不动 vod 主进程。**
   vod 主进程 adj=200（因为有前台服务 `KeepAliveService`），且 `killPackageProcessesLocked(..., evenPersistent=false)` 还会跳过 persistent 进程。它**能**杀掉的是 vod 的 cached 子进程（奇虎 RePlugin 的 `:p0` 插件进程等，adj≈900）——这是**今天就能做、零风险**的唯一收益。

3. **HisenseMemoryService（`com.hisense.android.hiRMService`）这条路是死的**：真机 `pm path` 为空 = **未安装**[实测]；即使按镜像分析，它的 service 是 `exported=false`，其"内存清理"实现只是 `ctl.start=hs_dropcaches`（drop_caches，清 page cache）而**不是杀进程**；`android.hisense.killapplications` 这个可疑广播在**整个 ROM 里没有任何接收者**。

4. **能真正杀掉 adj=200 主进程的现实路径只有 3 条**，按代价排序：
   (a) 一次性 adb：`am force-stop com.jamdeo.tv.vod`（shell 持 FORCE_STOP_PACKAGES）——**但会把包置 stopped=true，可能导致下次开机不投递 BOOT_COMPLETED，直接踩用户红线，必须先实测**；
   (b) 复用海信自家"加速/清理"UI（真机上唯一存在的是 `com.hisense.vidaaassistant`，但它**当前被禁用** `enabled=3`[实测]）；
   (c) 工厂模式/root（既有报告的最终方案，不在本任务范围）。

5. **第三方 App 在本 ROM 开机自启（BOOT_COMPLETED 后起 Activity）不可行**：`persist.vt.hi_freeze_mode=1`[实测] + `VtInterceptApp` 在 `boot_completed` 前拦截第三方进程（#40 logcat 证据）。所以"开机由我们的桌面接管"这条路不靠自启，只靠用户按遥控器/红键进入。

> **给用户的一句话建议**：现阶段不要追求"杀主进程"。先把 P0（killBackgroundProcesses 回收插件进程）做进桌面，**同时**用一次可回滚的真机实验验证"force-stop 是否影响下次开机拉取"。若验证通过，再做"进入桌面时 force-stop 聚好看 + 用户点聚好看时自动重新拉活"的开关式方案。

---

## 1. 结论速览表

| # | 路径 | 能杀 adj=200 主进程? | 需要什么权限 | 风险 | 验证方法 | 证据等级 |
|---|---|---|---|---|---|---|
| A | App 内 `killBackgroundProcesses("com.jamdeo.tv.vod")` | ❌ 只杀 adj≥500（cached 插件进程） | `KILL_BACKGROUND_PROCESSES`（normal，装包即得） | 零（无持久状态变更） | 桌面里调用前后 `dumpsys meminfo com.jamdeo.tv.vod` 对比 | [推断]（AOSP9 语义）/ 门槛待真机验证 |
| B | App 内 `startActivity(hisense.tv.assistant.ACCELERATE)` 拉起海信"加速" | ？取决于实现 | 无（exported activity） | 中（抢焦点弹 UI；且真机该包**被禁用**，需先 `pm enable`＝设备写） | `dumpsys package` 确认 enabled；真机实测 logcat | [实测]已禁用 / [静态证据]存在 / [未验证]行为 |
| C | App 内发官方"清理广播"（`android.hisense.killapplications` / `DEPTH_CLEAN` / `CACHE_CLEAN` / `DEVICE_RAM_LOW`） | ❌ 不存在可用接收者 | — | — | 97 APK manifest 全量扫描 + 真机 resolver table | [静态证据]+[实测] |
| D | App 内拉起 vod 自己的 `MemoryClearActivity` / `ClearMemoryAcitivity` | ❌ 只会清别人，不会杀自己 | 无（exported activity） | 低（弹 UI） | 真机 `dumpsys package com.jamdeo.tv.vod \| grep MemoryClear` | [静态证据]（镜像版）；真机版待核 |
| E | 让 vod "自己退出"（找 exported 退出/自杀接口） | ❌ **不存在** | — | — | `dumpsys package com.jamdeo.tv.vod` 的 receiver/service resolver table | [实测]+[静态证据] |
| F | 一次性 adb `am force-stop com.jamdeo.tv.vod` | ✅ 是 | shell 自带 `FORCE_STOP_PACKAGES` | **高**：`stopped=true` → 隐式广播（含 BOOT_COMPLETED）可能不再投递 → 破"开机拉取"红线 | 杀一次 → 重启 → `logcat \| grep vod` 看是否拉取；`dumpsys package` 看 stopped | [推断]（AOSP stopped 语义）/ 必须实测 |
| G | 一次性 adb `cmd appops set com.jamdeo.tv.vod RUN_ANY_IN_BACKGROUND ignore` | ⚠️ 不确定，且大概率杀不动前台服务进程 | shell | **高**：会连带限制后台广播/Job，破红线概率大 | 设置后 `dumpsys activity processes` 观察 | [未验证]（不推荐） |
| H | root / 工厂模式禁用 vod 的 LauncherActivity 组件 | ❌ 不解决内存（包仍活） | root | 高 | 既有报告 `launcher-replacement-runbook.md` | [历史结论] |
| I | `pm disable/enable` / `settings put` / `appops`（普通 App） | ❌ | 需要 `CHANGE_COMPONENT_ENABLED_STATE`/signature | 违反约束 | — | 本次任务明确禁止 |

---

## 2. 证据链

### 2.1 聚好看的开机时序：BOOT 广播与 HOME 拉起是两件事

**谁发 BOOT_COMPLETED** —— `system_server`，不是 app。`BOOTPATH.md:435-480` 给出 `UserController.finishUserUnlockedCompleted()`（`services.vdex` code_off=0x25830c）的完整字节码：

```
0x2584fc: const-string/jumbo v4, 'persist.boot.opt_switch'
0x258504: invoke-static  SystemProperties->getBoolean
0x258542: if-eqz v36, 258646          ; opt_switch==0 -> 走普通 BOOT_COMPLETED
0x258580: const-string/jumbo v4, 'android.intent.action.HISENSE_BOOT_COMPLETED'
0x258638: const-wide/16 v8, #30000     ; 先发 HISENSE_*，再 postDelayed 30s 发普通 BOOT
...
0x258646: 普通 BOOT_COMPLETED 分支（带 RECEIVE_BOOT_COMPLETED 权限）
```

真机 `persist.boot.opt_switch` **为空**[实测]（2026-10-25 `getprop` 无输出）→ 按镜像代码走 `opt_switch=false` 分支 → **普通 `BOOT_COMPLETED`，没有 30s 延迟，也没有 HISENSE 前置**[推断]。
（注：镜像 8.0 / 真机 9.0 的 services 不同，此条以属性为空的实测 + 镜像代码推断，真机抓一次 `logcat -b events | grep boot` 即可定论。）

**谁收** —— 固件里 **51 个** BOOT 接收者（`_fw/bootreceivers.txt`）。vod 的那个是：

```
priv-app/SuperApp/SuperApp.apk
    act=com.ju.vod.broadcast.StartReceiver  flags=['BOOT']
    actions=['android.intent.action.BOOT_COMPLETED', 'com.ju.main.app.restart',
             'android.intent.action.HISENSE_BOOT_COMPLETED']
```
真机版还多注册了 `CONNECTIVITY_CHANGE / CONNECTIVITY_CHANGE_IMMEDIATE / WIFI_STATE_CHANGED`，filter `mPriority=1000`（`_backup/dumpsys-vod.txt:197-211`）。

**StartReceiver 做什么**（`BOOTPATH.md:512-534`，`SuperApp` classes4.dex `onReceive` code_off=0x1ae634）：

```
'BootCompleteReceiver' / 'onReceive BootCompleteReceiver'   → Log
getAction() 对比 BOOT_COMPLETED / HISENSE_BOOT_COMPLETED
WatchDurationManage.getInstance().resetSingleRemain()
SystemProperties.set('vod_received_boot_completed', '1')
SuperApplicationtUtil.refreshTemplateAppList()
if (Configurations.isVidda4()) sendBroadcast(...)
```

**重点：它不 startActivity、不拉起桌面、不阻塞开机。**（`BOOTPATH.md:478-480` 也明确 `finishUserUnlockedCompleted()` 只广播，没有 `category.HOME`。）

**HOME 什么时候起** —— 独立的 `AMS.startHomeActivityLocked()`（`BOOTPATH.md:25-130`）。真机 logcat（`firmware-home-analysis.md:24-39`）：

```
08:00:19.577 ActivityManager: initAppWhiteList, read list from [/data/local/misc/app_start_when_boot_whitelist.txt]
08:00:19.582 ActivityManager: getHomes: com.jamdeo.tv.vod / app.orchard.tv / ca.dstudio.atvlauncher
08:00:19.582 ActivityManager: add home package: app.orchard.tv / ca.dstudio.atvlauncher
08:00:19.968 HisenseStartManagerUtil: start up source:2562        ← isStartPhysicalSource()==true
08:00:19.969 ActivityManager: START ... cmp=com.jamdeo.tv.vod/com.ju.main.launcher.LauncherActivity
```

⇒ **桌面的拉起发生在 BOOT_COMPLETED 投递之前/附近（同一秒内，早于 user-unlocked 广播）。**

**什么会导致卡 logo（历史闭环结论，见 `firmware-complete-reverse.md:67-76`、`firmware-home-analysis.md:151-161`）**：

| 触碰的东西 | 后果 | 证据 |
|---|---|---|
| vod 的 `LauncherActivity` 组件（HOME 候选，priority=50）被禁 | HOME 解析只剩 FallbackHome(-1000)，而它已被 `enabled=2` 系统级禁用且**不可逆** → `No home screen found` → 卡 logo | [实测] 历史实验 |
| `com.android.settings`（FallbackHome 宿主） | 同上；真机 `enabled=2` | [实测] |
| `com.jamdeo.tv.livetv` | 镜像的"海信硬编码默认分支"会指向它；但 60.03 真机走标准 HOME 解析，**当前它被禁用仍能正常开机**[实测 当前 disabled 列表 49 个 + 用户在正常看电视] | 历史报告曾怀疑它，本次证据弱化 |

**所以"只在开机完成后杀后台"的红线清单是**：不能 `pm disable/enable`、不能 `pm uninstall`、不能改 HOME priority、不能动 `com.android.settings` 的 enabled 状态、**不要长时间保持 stopped=true**（见 §2.4 第 4 条）。
**可以动**：BOOT 广播之后的进程生命周期（kill/force-stop 属于这一类，但 stopped 有副作用，见下）。

---

### 2.2 聚好看为什么杀不掉（保活结构实测）

| 事实 | 数值 | 来源 |
|---|---|---|
| 主进程 pid | 23280 | Lead [实测] |
| PSS | ≈173 MB | Lead [实测] |
| oom adj | **200** | Lead [实测] |
| adj=200 的原因 | 前台服务 `com.ju.middleware.app.service.KeepAliveService`（isForeground=true） | Lead [实测] |
| 第二保活 | `PlanPluginPitServiceGuard`（奇虎 RePlugin 的 ProcessPit 保活） | Lead [实测] |
| 插件进程 | `:p0` adj≈900 | Lead [实测] |
| 是否 persistent | **否**。`pkgFlags=[SYSTEM HAS_CODE ALLOW_CLEAR_USER_DATA UPDATED_SYSTEM_APP LARGE_HEAP]`，无 `PERSISTENT` | `_backup/dumpsys-vod.txt:483,500` [实测] |
| stopped 状态 | `stopped=false` | `_backup/dumpsys-vod.txt:612` [实测] |
| 它自己持有哪些"杀别人"权限 | `KILL_BACKGROUND_PROCESSES: granted=true`（还有一堆 signature 权限） | `_backup/dumpsys-package.txt` 权限块 [实测] |

**RePlugin 的进程坑证明它对第三方也是"可拉起"的**（`_backup/dumpsys-vod.txt:327-370`）：

```
Registered ContentProviders:
  com.jamdeo.tv.vod/com.qihoo360.replugin.component.process.ProcessPitProviderP0/P1/P2/UI/Persist
  com.jamdeo.tv.vod/com.qihoo360.replugin.component.process.ProcessPitProviderLoader0/Loader1
  com.jamdeo.tv.vod/com.qihoo360.svcmanager.mobilesafe.ServiceProvider  ← 奇虎 svcmanager（ContentProvider 调用桥）
```

⇒ 这些 provider 都是 exported（provider resolver 里能查到），**任何 App 调用它们都会把 vod 进程拉活**。所以"杀完再被别人拉起"的风险很高：桌面自己不要碰这些 provider、也不要去 `bindService` 它的 `PlatNativeEngineService/PlatRemoteEngineService`（`dumpsys-vod.txt:285-300`）。

**为什么 LMK 不会帮我们回收**（`_fw/out_boot/init.hisense.rc:392-396`，[静态证据]）：

```
on property:sys.boot_completed=1
    write /sys/module/lowmemorykiller/parameters/adj "0,58,117,176,700,800"
    write /sys/module/lowmemorykiller/parameters/minfree "16000,18000,20000,22000,30000,35000"
```

adj=200 落在 176 与 700 之间 ⇒ 只有内存压力顶到第 5 档（minfree 30000 pages ≈ 120MB 空闲）才会被杀。**正常情况下 LMK 永远不会碰它**，这就是"看电视越久内存越紧"的机制根因。

---

### 2.3 "官方清理入口"全量排查（本任务的重点）

#### 2.3.1 HisenseMemoryService（`app/HisenseMemoryService/HisenseMemoryService.apk`，9,547 字节）

**它是什么**（`_fw/killpath_tmp/HisenseMemoryService.apk` 的 AndroidManifest 全属性 dump，[静态证据]）：

```xml
<manifest package="com.hisense.android.hiRMService"
          sharedUserId="android.uid.system"
          versionCode="1" versionName="1.0"
          platformBuildVersionCode="24" platformBuildVersionName="7.0">
  <application persistent="true" directBootAware="true" ...>
    <receiver name=".HiRMBroadcastReceiver">
      <intent-filter priority="1000">
        <action name="android.intent.action.BOOT_COMPLETED"/>
        <action name="android.intent.action.ACTION_SHUTDOWN"/>
        <action name="android.intent.action.DEVICE_STORAGE_LOW"/>
      </intent-filter>
    </receiver>
    <service name="com.hisense.android.hiMemService.manager.MemMonitorService"/>   <!-- 无 exported/permission 属性 -->
  </application>
  <uses-sdk minSdkVersion="19" targetSdkVersion="24"/>
  <uses-permission name="android.permission.FORCE_STOP_PACKAGES"/>     <!-- 它自己有权 force-stop -->
  <uses-permission name="android.permission.KILL_BACKGROUND_PROCESSES"/>
  <uses-permission name="android.permission.WAKE_LOCK"/> <uses-permission .../> 共 8 个
</manifest>
```

**关键判定**：

- `targetSdkVersion=24` + service **没有 `android:exported`** ⇒ **默认 exported=false**（Android 3.0+/targetSdk≥17 规则）⇒ 第三方 App **不能** `startService`/`bindService` 它，`sendBroadcast` 也没有对应入口。
- 唯一静态 receiver `HiRMBroadcastReceiver` 只收 BOOT/SHUTDOWN/STORAGE_LOW，**这三个都不是第三方能拿来做"杀指定 App"的**（BOOT_COMPLETED 需要 `RECEIVE_BOOT_COMPLETED`，且只有系统能发；第三方发 BOOT_COMPLETED 会被 `ActivityManager` 拒绝/被忽略）。
- APK **没有 classes.dex**（系统 app 被 dexopt 剥壳，代码在 `oat/arm/HisenseMemoryService.vdex`，`vdex006` + quickened dex）。反汇编（本次自建解码器 `_fw/killpath_tmp/dis.py`，calibration 后 **59/59 方法对齐通过**）：

`MemMonitorService.onCreate`（code_off=0x19c0）：

```
new-instance ProcessObserver ; ActivityManagerNative.getDefault()
invoke-interface IActivityManager->registerProcessObserver          ← 系统级进程观察者
new LowMemManager(); new ProcessMonitorThread(...).start()
new HandlerThread("PolicyThread"); new PolicyHandler(...)
new IntentFilter()
const-string 'android.intent.action.ACTION_PREPARE_SHUTDOWN'
invoke-virtual/QUICK filter.addAction("ACTION_PREPARE_SHUTDOWN")     ← ★ 只有一个 action
const/16 #999 ; invoke-virtual/QUICK filter.setPriority(999)
iget-object-quick this.mAppStartReceiver
invoke-virtual/QUICK registerReceiver(mAppStartReceiver, filter)     ← ★ 3 参、无 permission 参数
```

`MemMonitorService$1.onReceive`（code_off=0x14ec，51 insns）：

```
getAction(); Log.d(TAG, "action:" + action)
if ("android.intent.action.ACTION_PREPARE_SHUTDOWN".equals(action)) { ... }   ← 只处理关机前清理
return-void
```

⇒ 这个"内存服务"的真实行为是：**注册进程观察者 + 一个线程轮询 `/proc/meminfo` + 按场景（Normal/Media/Test）做 reclaim**，而 reclaim 的实现（`NormalScene.reclaim()`，`dexref` 命中 const-string @0x1d68/0x1d6e）：

```
SystemProperties.set('ctl.start', 'hs_dropcaches')     → init 服务 /vendor/bin/hs_dropcaches.sh（init.hisense.rc:489）
```

**它清的是 page cache（drop_caches），不是杀 app 进程。** 配置项（`LowMemManager.initLowMemConfig`，code_off=0x1380）：

```
'ro.config.low_ram'                 → 真机 = false  [实测]（低内存分支不启用）
'persist.sys.low_ram.white_list'    → 真机 = 空     [实测]
'persist.sys.lm.bservice_enable'    → 真机 = 空     [实测]
'persist.sys.lm.pstcached' / 'persist.sys.lm.pstcachedKBytes'(=18432) → 真机 = 空 [实测]
'initLowMemConfig', '/system/etc/white_app.xml', 阈值 '10240' / '18432' / '5120'
```

**那个看起来最像"官方杀进程"的东西**：字符串池里有 `KILL_APP_ACTION` / `START_APP_ACTION` 两个静态常量，对应的值就是

```
android.hisense.killapplications
android.hisense.startapplications
```

**但是**：
- 它们在dex里是**静态常量值**（`static_values`），`dexref` 扫 const-string 扫不到 ⇒ 没有任何方法把它们当字符串用；
- **97 个系统 APK 的 manifest 全量扫描后，没有任何 receiver 声明这两个 action**（`_fw/killpath_tmp/apks_system.json`，grep 结果：0 命中）；
- `MemMonitorService` 的动态 filter 也只 add 了 `ACTION_PREPARE_SHUTDOWN`，**没有 add 这两个 action**。

⇒ **`android.hisense.killapplications` 在本镜像里"有名字、没接收者"**，是一条死线索。

**最后一击 [实测]**：

```
$ adb -s 192.168.31.234:5555 shell pm path com.hisense.android.hiRMService
（无输出 = 未安装）
$ adb ... shell pm list packages | grep -i hiRM
（无输出）
$ adb ... shell dumpsys activity services com.hisense.android.hiRMService
  (nothing)
$ adb ... shell getprop persist.sys.lm.memcontrol / bservice_enable / pstcached
（全为空）
```

⇒ **HisenseMemoryService 在 60.03 真机上根本不存在**（60.01 镜像里还在）。**这条路彻底作废**，不需要再研究。

> 附：init 里还有 3 个"清理"服务（`_fw/out_boot/init.hisense.rc`）：`cleandata`(L177)、`clear_d_n_c`(L272)、`hs_dropcaches`(L489)。前者是恢复出厂用的 root/oneshot 服务（`seclabel u:r:hsrecovery:s0`，`disabled`），第三个就是上面的 drop-caches。**三者都不是第三方可触发的**（`ctl.start` 属性写入需要 init/system 权限，普通 App 的 `SystemProperties.set` 会被 SELinux/属性权限拒绝）。

#### 2.3.2 系统里所有"清理/加速"组件的全量清单

扫描方法：`_fw/killpath_tmp/apkscan.py`（pytsk3 随机读 zip → 取 `AndroidManifest.xml` → `axml2.py` 解析），覆盖 `app/` + `priv-app/` 共 **97 个 APK，97/97 解析成功**，结果 `_fw/killpath_tmp/apks_system.json`。

**A. 持有 `FORCE_STOP_PACKAGES` 的包（镜像 6 个）**

| 包 | 路径 | sharedUserId | 与"杀进程"相关组件 |
|---|---|---|---|
| com.hisense.vidaaassistant | app/VidaaAssistant | android.uid.system | `AccelerateActivity`(action `hisense.tv.assistant.ACCELERATE`)、`clearmemory.NulDelActivity`(`RUBBISH_CLEAR`)、`DepthCleanReceiver`(`CACHE_CLEAN`/`DEPTH_CLEAN`)、`AccelerateReceiver`(`DEVICE_RAM_LOW`) |
| com.hisense.multiscreen.screenhelper | app/MsScreenHelper | android.uid.system | 只有 `FileTransferService`/`ReportService`，**无 killer 接口** |
| com.hisense.android.hiRMService | app/HisenseMemoryService | android.uid.system | 见 §2.3.1（真机未安装） |
| com.hisense.electronictutorial | app/HisenseElectronicinstruction_V6Art | android.uid.system | `CommonReceiver`（一堆语音/TVBOX 广播），无 kill 语义 |
| com.keylab.speech.core.vidaa | app/aiCore | android.uid.system | `CoreStartup`、`CoreService`，无 kill 语义 |
| com.hisense.voicecontrol | app/VoiceControl | android.uid.system | `StartReceiver`（TVCONTROL/SYSTEM_KEY…），无 kill 语义 |

**B. 真机上仍带 `FORCE_STOP_PACKAGES: granted=true` 的包（2 个）**[实测 `dumpsys package`]

| 包 | uid | enabled | 相关组件（resolver table 里能看到 = exported） |
|---|---|---|---|
| `com.hisense.tv.inputs` | **10027**（非 system uid，priv-app 特权） | 0=default | `background.receiver.AppUninstallReceiver`（PACKAGE_REMOVED）；**`background.receiver.AppSwitchReceiver`**：`BOOT_COMPLETED` / **`com.jamdeo.launcher.appswitch`** / `ACTION_PANEL_ON` / `EXTERNAL_APPLICATIONS_*` / `HISENSE_BOOT_COMPLETED` / `APP_UPGRADE_FINISHED` |
| `com.hisense.multiscreen.screenhelper` | 1000 | — | 无 killer 接口 |

> **`com.hisense.tv.inputs` 的 `AppSwitchReceiver` 是本次唯一还没排掉的"官方 kill"线索**：它监听 `com.jamdeo.launcher.appswitch`（桌面切换应用时发），又持有 FORCE_STOP_PACKAGES，**存在"切换应用时 force-stop 上一个应用以省内存"的可能**。该 APK 不在 system.img 的 app/priv-app 里（镜像与真机版本不一致），需要单独取 APK 或抓 logcat 才能定性——列为 §4 待验证第 1 项。

**C. 全 ROM 里带 clean/kill/clear/boost/accel/trim 语义 action 的组件（12 个，全清单）**

```
com.hisense.vidaaassistant  activity .accelerate.AccelerateActivity            hisense.tv.assistant.ACCELERATE
com.hisense.vidaaassistant  activity .clearmemory.NulDelActivity               hisense.tv.assistant.RUBBISH_CLEAR
com.hisense.vidaaassistant  receiver ...receiver.DepthCleanReceiver            com.hisense.vidaaassistant.CACHE_CLEAN / DEPTH_CLEAN
com.android.gallery3d       activity app.TrimVideo                            com.android.camera.action.TRIM      （视频裁剪，无关）
com.jamdeo.updateservice    receiver UpdateReceiver                           ...action.CLEAR_DATA                （升级清数据，无关）
com.jamdeo.tv.vod           activity com.ju.main.apppage.manager.clean.MemoryClearActivity  同名 action
com.jamdeo.tv.vod           activity com.hisense.base.activity.ActivityAppAccelerated      com.ju.store.ui.ActivityAppAccelerated
com.jamdeo.tv.vod           activity com.hisense.base.activity.ClearMemoryAcitivity        com.ju.store.ui.ClearMemoryAcitivity
com.jamdeo.tv.vod           activity com.hisense.base.activity.ClearStorageDialogAcitivity com.ju.hisense.base.clearstorage
com.jamdeo.tv.vod           activity com.ju.main.appspage.clearspace.ClearSpaceDialogActivity 同名 action
com.android.documentsui     receiver .PackageReceiver                         PACKAGE_DATA_CLEARED               （无关）
```

**D. 真机核对（关键差异，全部 [实测]）**

| 组件 | 镜像(60.01) | 真机(60.03) |
|---|---|---|
| `hisense.tv.assistant.ACCELERATE` → `AccelerateActivity` | 有 | **resolver table 里还在**（`dumpsys package com.hisense.vidaaassistant` 能查到），但：①`cmd package resolve-activity -a ...` = **No activity found**；②包 `enabled=3`（DISABLED_USER）；③`pm list packages` 有，`disabled-20261022` 列表里也有 |
| `hisense.tv.assistant.RUBBISH_CLEAR` → `NulDelActivity` | 有 | 同上（在 resolver table，但包被禁用） |
| `com.hisense.vidaaassistant.CACHE_CLEAN` / `DEPTH_CLEAN` / `hisense.intent.action.DEVICE_RAM_LOW` | 有 | **grep 无命中 → 真机版本里没有这些接收器** |
| `android.hisense.killapplications` | 无接收者 | 同（且宿主 app 未安装） |
| `com.ju.receiver.CLEAN` → vod `SpaceCleanReceiver` | 有 | **在**（`_backup/dumpsys-package.txt:1913`） |

> 结论：**真机上现在没有任何"能被普通 App 触发、且带 FORCE_STOP 能力"的 exported 组件**——唯一候选的宿主包全被禁用或不存在。

#### 2.3.3 聚好看自己对外暴露的"清理/退出"组件（能不能让它自己退？）

[实测 `dumpsys package com.jamdeo.tv.vod` 的 Receiver/Service Resolver Table] + [静态证据 镜像 manifest]：

| 组件 | action | exported | 行为推断 |
|---|---|---|---|
| `com.ju.middleware.app.receiver.SpaceCleanReceiver` | **`com.ju.receiver.CLEAN`** | 是（在 resolver table） | 空间清理（通常弹通知/清缓存），不会杀自己 |
| `com.ju.juui.receiver.StartReceiver` | `com.ju.main.app.restart`（+BOOT/WIFI） | 是 | **让 vod 重启**（反向需求，但可做"杀后复活"的补救） |
| `com.ju.plat.businessframe.imp.channel.PlatNativeEngineService` | `com.ju.lib.business.api.plat.native.service.action` | 是（无 permission） | 业务引擎（**bind 它会拉活进程**） |
| `PlatRemoteEngineService` | `...plat.remote.service.action` | 是 | 同上 |
| `WidgetService` / `WidgetBusiness` | `com.ju.widget.intent.action.WIDGET_*` | 是 + `com.ju.widget.permission.WIDGET_SERVICE` | 桌面小部件（**拉住不放**） |
| `com.hisense.base.activity.ActivityMemoryClear`（无 filter） | — | **否**（无 filter 且无 exported 声明） | 内部内存清理页 |
| `com.qihoo360.mobilesafe.svcmanager.ServiceProvider` | — | 是（provider） | 奇虎 svcmanager 跨进程调用桥 |

⇒ **vod 没有任何"退出/自杀"接口**；能把它的进程拉起来/留住的接口反而有一堆。**"让聚好看自己退出"这条路不成立。**

#### 2.3.4 shell 级（=免 root 但需一次性 adb）的"官方清理"

| 命令 | 能否越过 adj=200 | 说明 |
|---|---|---|
| `am kill <pkg>` / `am kill-all` | ❌ | 与 `killBackgroundProcesses` 同源，只杀 background（≥SERVICE_ADJ） |
| `am force-stop <pkg>` | ✅ | 真正停包；**会把 stopped 置 true** |
| `am stop-service` | ❌ | 只停指定 service，KeepAliveService(START_STICKY) 会重启 |
| `am send-trim-memory <pid> <level>` | ⚠️ | 只发 `onTrimMemory` 回调，App 自愿释放；vod 不会退 |
| `kill -9 <pid>` | ❌ | `adb shell` 是 uid 2000，无 CAP_KILL，杀不了 uid 1000/system 进程（SELinux Permissive 也救不了 capability 检查） |
| `cmd appops set ... RUN_ANY_IN_BACKGROUND ignore` | ⚠️ | 见 §2.4；会连带限制后台广播/Job |

---

### 2.4 权限与机制核查

**① `killBackgroundProcesses` 的确切门槛**

AOSP 9 `ActivityManagerService.killBackgroundProcesses()` 的实现是（`services/core/java/com/android/server/am/ActivityManagerService.java`）：

```java
enforceCallingPermission(android.Manifest.permission.KILL_BACKGROUND_PROCESSES, null);
...
killPackageProcessesLocked(packageName, appId, userId,
        ProcessList.SERVICE_ADJ,   // ← minOomAdj = 500
        false /*callerWillRestart*/, true /*allowRestart*/, true /*doit*/,
        false /*evenPersistent*/, "killBackgroundProcesses");
```

而 `killPackageProcessesLocked` 里对每个进程：

```java
if (proc.pid == MY_PID) continue;
if (proc.isPersistent() && !evenPersistent) continue;   // persistent 进程一律跳过
final int adj = proc.curAdj;
if (adj < minOomAdj) continue;                          // adj 必须 >= 500 才杀
```

| 断言 | 结论 | 置信度 |
|---|---|---|
| 门槛 = `ProcessList.SERVICE_ADJ` = **500**（不是 900） | 只要 adj≥500 就会被杀（CACHED=900、SERVICE=500 边界） | [推断]（AOSP 源码语义）；**本 ROM 未逐字节复核**（AOSP 站点本机不可达，见 §5 限制） |
| vod 主进程 adj=200 < 500 ⇒ **一定不被杀** | ✅ 与 Lead 的实测预期一致 | [推断]（数学上确定，前提是门槛 500） |
| `evenPersistent=false` ⇒ persistent 进程跳过 | vod **非** persistent ⇒ 不享受这条豁免；但它也**不需要**这条豁免（adj 已经低于门槛） | [实测]（pkgFlags 无 PERSISTENT） |
| 对 foreground service 进程 | 一定不杀（adj=200） | [推断] |
| 对 vod 的 `:p0` 插件进程（adj≈900） | **会被杀** | [推断] |
| 调用方需要什么权限 | `android.permission.KILL_BACKGROUND_PROCESSES`，**normal 级、安装即授予**（连 `com.dangbeimarket`/`com.xiaobaifile.tv` 这种三方应用都有） | [实测]（`_backup/dumpsys-package.txt` 权限块） |

> **验证方法（只读，不用改 TV）**：把 `killBackgroundProcesses("com.jamdeo.tv.vod")` 放进我们的桌面里，在桌面启动时执行，然后 `adb shell dumpsys meminfo com.jamdeo.tv.vod` 前后对比、`adb shell dumpsys activity processes | grep -A3 vod` 看 `:p0` 是否消失。

**② ProtectedPackages 与 force-stop**

- 历史结论（`firmware-complete-reverse.md:72`）：`ProtectedPackages` / `isPackageStateProtected` 在镜像与真机 `services.vdex` 里都存在，真机 `pm disable` 实测被拒（`SecurityException: Shell cannot change component state…`）。
- AOSP 9 中 `ProtectedPackages.isPackageStateProtected()` 的**已知调用点只有三处**：`setEnabledSetting`、`setApplicationHiddenSettingAsUser`、`setPackagesSuspendedAsUser`（与历史证据一致）。
- **`forceStopPackage` 路径在 AOSP 9 上不查 ProtectedPackages** ⇒ 理论上 `am force-stop com.jamdeo.tv.vod` 不会被这层拦。
- **但本 ROM 是否在 `forceStopPackage` 里加了料，未验证**（services.vdex 是 `vdex019` + **compact-dex**，现有工具链只能解析到"方法/字符串"级，见 §5 限制）。
- 旁证：系统里已有多个 app 被授予 `FORCE_STOP_PACKAGES`（vidaaassistant / screenhelper / tv.inputs / electronictutorial / voicecontrol / aiCore），说明这条 API 面在 ROM 里是**活跃**的，没被整体阉割。

**③ `RUN_ANY_IN_BACKGROUND` appop 在 Android 9 的语义（会破红线）**

| 问题 | 结论 | 置信度 |
|---|---|---|
| 普通 App 能否改**别的包**的 appops？ | **不能**。改他人 appops 需要 `MANAGE_APP_OPS_MODES`（signature）或系统身份。只有 shell/root 能做 | [推断] AOSP 语义 |
| `ignore` 之后"运行中的前台服务进程"会怎样？ | 前台服务进程（adj=200）**不会被这条 appop 直接杀**；"后台限制"限制的是后台启动 service/job/alarm/隐式广播的能力，而不是杀掉已在跑的 FGS | [未验证]（Android 9 行为，需真机验证） |
| 会不会阻断开机 `BOOT_COMPLETED` 拉取？ | **很可能会**。被后台限制的包在后台禁止启动组件/接收隐式广播；即使 BOOT_COMPLETED 有豁免（`FLAG_RECEIVER_INCLUDE_BACKGROUND`），被限制包的行为也不保证 | [未验证]（**红线风险源**） |
| 结论 | **不推荐**。收益不确定、红线风险最大 | — |

**④ `stopped` 状态：本任务最大的隐藏地雷**

- `am force-stop` 之后，包被置 `stopped=true`；AOSP 的广播投递会跳过 stopped 包，**除非 intent 带 `FLAG_RECEIVER_INCLUDE_STOPPED_PACKAGES`**。
- `BOOT_COMPLETED` 是**隐式广播**。stopped 包**能否**收到它，是本方案成/败的唯一关键点，必须真机实测（§4 第 2 项）。
- 补救：**`startActivity` 一次即可清除 stopped 状态**（用户点一下聚好看就恢复）。所以"用户点开 → 恢复正常"这条兜底是可靠的；不确定的只有"下次开机自动拉取"。
- 现状基线：`stopped=false`（`dumpsys-vod.txt:612`），说明历史上尚未对它做过 force-stop。

---

### 2.5 第三方 App 在本 ROM 开机自启（BOOT_COMPLETED 后起 Activity）——不可行

| 证据 | 内容 | 来源 |
|---|---|---|
| VtInterceptApp 拦截 | `[VtInterceptApp] intercept 3rd app.processName: app.orchard.tv(service) before boot_completed` | #40 logcat（转引 `aerial-tv-analysis.md:84,382`） |
| 冻结/拦截开关 | `persist.vt.hi_freeze_mode=1`（本次实测确认仍为 1） | [实测] 2026-10-25 |
| 启动白名单 | `AMS.initAppWhiteList()` 读 `/data/local/misc/app_start_when_boot_whitelist.txt`（`1:` 第一白名单 / `2:` 第二白名单）；目录 777 但文件 `system:system 600` ⇒ shell 能 rename 不能写 | `桌面替换可行性与路线评估.md:315-322` |
| 60.03 的 priority 归零 | 第三方包的 HOME intent-filter priority 在运行时被归零（ATV 的 951 → 0） | `firmware-complete-reverse.md:79-88` |

⇒ **不要指望"我们的桌面开机自启"**。可行的形态是：**用户按遥控器红键/桌面图标进入我们的桌面** → 我们在 `onStart` 里做清理动作。这也正好符合"不要阻止开机时聚好看拉取"的前提（此时开机拉取早已完成）。

---

## 3. 推荐的实现与降级顺序

### P0（现在就能做，零风险，确定收益）：桌面启动时 `killBackgroundProcesses`

```kotlin
// 在我们的桌面 LauncherActivity.onStart() 里
val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
am.killBackgroundProcesses("com.jamdeo.tv.vod")     // KILL_BACKGROUND_PROCESSES 是 normal 权限
```

- **收益**：杀掉 vod 的 cached 进程（奇虎 RePlugin 的 `:p0` 插件进程、`ProcessPitProvider*` 拉起的 loader 进程等，adj≈900），预计回收 **数十 MB**（需实测，见 §4 第 3 项）。
- **不动的**：主进程（adj=200）活着，`KeepAliveService` 不断 → **绝不违反红线**（无组件禁用、无 stopped、无设置变更，且下次开机 BOOT 拉取完全不受影响）。
- **风险**：几乎为零。唯一副作用是 vod 的插件进程下次用到时会重启（用户点开聚好看无感）。
- **实现注意**：只调用一次（`onStart` 里做去抖，如 5 分钟内不重复），避免频繁打断 vod 的插件下载/播放准备。

### P0.5（可选，同样是零风险）：把"回收"做成用户可见开关

桌面设置里加一项「进入桌面时释放后台内存」（默认开）。这样用户能自己关掉、也能感知行为，避免"偷偷杀后台"带来的信任问题。

### P1（一次只读实验，决定后续）：`com.jamdeo.tv.inputs` 的 `com.jamdeo.launcher.appswitch`

- 事实：该包 `FORCE_STOP_PACKAGES: granted=true` 且 `AppSwitchReceiver` 监听 `com.jamdeo.launcher.appswitch`（[实测] resolver table）。
- 要做的事：真机抓 `logcat`，由用户正常"从聚好看切到别的应用"一次，看该广播到 `AppSwitchReceiver` 之后**是否出现 `Force stopping ... ` / `Killing ...` 日志**。若确实会 force-stop 上一个前台应用 → 我们只需在桌面启动时发这条广播（**这不是杀进程本身，而是复用海信自己的切换逻辑**）。
- 风险：中。发广播本身是设备写操作，需要用户同意后做一次；且若该逻辑会 force-stop **我们的桌面**就尴尬（需要看 extras/条件）。

### P2（需用户同意的一次性实验）：`am force-stop` + stopped 验证

```bash
# 步骤（每一行都要观察）
adb -s 192.168.31.234:5555 shell dumpsys package com.jamdeo.tv.vod | grep -E "stopped=|enabled="   # 基线
adb -s 192.168.31.234:5555 shell am force-stop com.jamdeo.tv.vod
adb -s 192.168.31.234:5555 shell dumpsys package com.jamdeo.tv.vod | grep -E "stopped=|enabled="   # 期望 stopped=true
adb -s 192.168.31.234:5555 shell dumpsys meminfo | grep -i vod                                  # 期望进程消失
# ★ 然后重启电视，观察：聚好看是否仍会在开机后拉取内容、首页是否正常、是否卡 logo
# ★ 若拉取失效：说明 stopped 真的挡 BOOT 广播 → P2 方案作废（或必须配合"用户点开一次"）
```
- 若验证通过，才做"开关式"整合：**进入我们桌面 → force-stop vod；用户点聚好看 → 正常 startActivity（顺带清 stopped）**。
- **绝不**自动化成"每次开机都 force-stop"——那样即使 BOOT 能被投递，也会让 vod 每次开机都处于 stopped 态，行为不可预期。

### P3（最后手段）：工厂模式 / root

沿用既有结论（`firmware-complete-reverse.md:124-128`）：工厂模式 `tofac=M` 拿 root → 禁用 vod 的 `LauncherActivity` 组件（包保持 enabled）。**注意：这解决的是"桌面归属"，不是"省内存"**——vod 进程该活还是活。

### 明确不推荐

| 方案 | 原因 |
|---|---|
| `cmd appops set ... RUN_ANY_IN_BACKGROUND ignore` | 收益不确定 + 破红线概率最大（可能挡 BOOT 广播） |
| `pm disable/enable`、`settings put`、`am start`（作为"官方入口"） | 现状禁止；且触发 ProtectedPackages/SecurityException |
| 让 App 调 `forceStopPackage` 反射 | 非系统 App 无 `FORCE_STOP_PACKAGES`，`ActivityManagerNative` 路径会抛 `SecurityException` |
| 去 bind vod 的 `PlatNativeEngineService` / 调 `ProcessPitProvider*` | 会把 vod 进程**拉活**（反向） |
| 杀 `KeepAliveService` 本身 | 只能 `am stop-service`，START_STICKY 会重启；且需要 shell |

---

## 4. 待验证清单

| # | 待验证项 | 只读验证方法 | 需要谁 | 风险 |
|---|---|---|---|---|
| 1 | `com.hisense.tv.inputs/.background.receiver.AppSwitchReceiver` 收到 `com.jamdeo.launcher.appswitch` 后是否 force-stop 应用 | 用户正常切换一次应用，抓 `logcat \| grep -iE "appswitch\|Force stopping\|Killing"` | 用户操作 + 只读 logcat | 低（只观察） |
| 2 | **`am force-stop` 后 `stopped=true` 是否导致下次开机不投递 BOOT_COMPLETED**（本任务成败关键） | P2 步骤：force-stop → 重启 → `logcat \| grep -iE "vod_received_boot_completed\|jamdeo"`、看首页是否正常 | 用户同意后执行（**设备写**） | 中高（可能一次开机不拉取，点开一次即恢复） |
| 3 | P0 的实际收益（能回收多少 MB） | `dumpsys meminfo com.jamdeo.tv.vod` / `dumpsys activity processes` 前后对比；`cat /proc/meminfo` | 只读 | 无 |
| 4 | vod 主进程的真实 `oom adj` 与 `KeepAliveService` 前台状态（Lead 已给，建议留一份可复现快照） | `dumpsys activity processes \| grep -B2 -A6 vod`、`dumpsys activity services com.jamdeo.tv.vod` | 只读 | 无 |
| 5 | `persist.boot.opt_switch` 为空时真机到底走哪条 BOOT 分支（有没有 30s 延迟 / 有没有 HISENSE_BOOT_COMPLETED） | 重启前后抓 `logcat -b events \| grep -i boot_completed` | 用户配合一次重启 | 低 |
| 6 | 本 ROM 的 `killBackgroundProcesses` 门槛是否真的 = SERVICE_ADJ(500) | 找一个 adj 已知的包做实验（例如 500–900 之间的进程），观察是否被杀 | 需设备写（调用） | 低 |
| 7 | 真机版 `com.jamdeo.tv.vod` 是否仍有 `MemoryClearActivity` / `ClearMemoryAcitivity`（镜像版有） | `dumpsys package com.jamdeo.tv.vod \| grep -i memoryclear` | 只读 | 无 |
| 8 | 真机是否真的没有 `com.hisense.android.hiRMService`（换 `cmd package list packages` 再确认一次） | `cmd package list packages \| grep -i mem` / `ls /system/app \| grep -i mem` | 只读 | 无 |

---

## 5. 限制与不确定点（务必连同结论一起读）

1. **镜像 ≠ 真机**：镜像是 04.02.60.01（Android 8.0），真机是 04.02.60.03（Android 9）。本次已实测到**三处硬差异**：HisenseMemoryService 未安装、vidaaassistant 被禁用且清理接收器消失、第三方 HOME priority 被归零。所有"镜像有"的结论都必须先在真机上 `dumpsys` 复核，**本文已逐条标注**。
2. **真机 framework 是 `vdex019` + compact-dex**，本次未实现对它的字节码级解析（现有工具只到方法名/字符串级）。因此：
   - `killBackgroundProcesses` 的门槛 500 是 **AOSP 9 源码语义**，不是本 ROM 的反汇编证据；
   - `forceStopPackage` 是否被 ROM 加料（ProtectedPackages）**未验证**。
   - 补：`_reports/桌面替换可行性与路线评估.md:324-340` 已记录 cdex 头布局（`+0x38 str_ids / +0x58 method_ids / +0x60 class_defs`），若后续需要，可在此之上继续。
3. **本机 AOSP 站点不可达**（`android.googlesource.com` 解析到非公网 IP），所以本文的 AOSP 引用只能给"文件+方法+代码形状"，无法贴出可点开的源码链接做逐行核对。
4. **本任务对电视全程只读**：没有做任何 `pm/settings/am/install/reboot/force-stop`。§3 的 P1/P2 与 §4 第 2/6 项都需要用户同意后的设备写操作。
5. quickened-dex 反汇编：本次为 `vdex006`（Android 8）写了带 opcode 长度自校准的解码器（`_fw/killpath_tmp/dis.py` + `calib2.py`），在 HisenseMemoryService 上 **59/59 方法对齐通过**；但 ART 9 的 quickened 编码不同，不能直接套用。

---

## 附录 A：本次新增的分析产物（都在 `_fw/killpath_tmp/`）

| 文件 | 用途 |
|---|---|
| `HisenseMemoryService.apk/.vdex/.odex` | 从 system.img 提取的原文（9,547B / 19,892B / 20,960B） |
| `AndroidManifest.axml`、`axmlfull.py` | 该 APK 的 manifest 原文与全属性 dump（含 sharedUserId/persistent） |
| `dump.py` / `apkx.py` | 用 pytsk3 随机读从 `system.img` 抽文件 / 列 APK 条目 / 抽单个成员 |
| `apkscan.py` → `apks_system.json` | **97 个系统 APK 的 manifest 全量扫描**（package / sharedUserId / 权限 / 组件 / exported / intent-filter / priority） |
| `q1.py` | 基于上面 JSON 的查询：持 FORCE_STOP 的包、clean 语义组件、world-callable 服务 |
| `dis.py` + `calib2.py` | 自建 Dalvik 反汇编器（quickened opcode 长度自校准，带 span/分支目标双不变量校验） |
| `vdexscan.py` / `opfreq.py` | vdex 标识符扫描 / opcode 频率（用于判断"是否被 quicken"） |
| `superapp_mf.py` | 镜像版 SuperApp（150MB）manifest 的关键组件导出 |
| `qk.py`、`dbg*.py`、`amprobe.py` | 探索性脚本（vdex quickening 表、cdex 头探测，保留以便后续继续） |

## 附录 B：关键复现命令

```powershell
# 1) 看某个系统 app 的 manifest（含 exported / sharedUserId）
python _fw\killpath_tmp\apkx.py pull /app/HisenseMemoryService/HisenseMemoryService.apk out.apk
python _fw\axml2.py out.apk

# 2) 全量重扫 97 个 APK 的组件表（约 1 分钟）
python _fw\killpath_tmp\apkscan.py _fw\img\system.img _fw\killpath_tmp\apks_system.json app priv-app
python _fw\killpath_tmp\q1.py

# 3) 反汇编某个 vdex 里的某个类（自动校准 quickened opcode 长度）
python _fw\killpath_tmp\dis.py <x.vdex> "manager/MemMonitorService;" "onCreate"

# 4) 把字符串定位到方法（const-string 映射，quickening 不影响）
python _fw\dexref.py <x.vdex> "android.hisense.killapplications"
```

## 附录 C：真机只读核对记录（2026-10-25）

```
$ getprop persist.boot.opt_switch        → (空)
$ getprop ro.build.version.sdk           → 28
$ getprop persist.vt.hi_freeze_mode      → 1
$ getprop ro.config.low_ram              → false
$ getprop persist.sys.lm.memcontrol      → (空)
$ getprop persist.sys.lm.bservice_enable → (空)
$ getprop persist.sys.doublehome_support → (空)
$ getprop ro.build.type / tags           → user / release-keys

$ pm path com.hisense.android.hiRMService → (无输出 = 未安装)
$ pm list packages | grep -i hiRM         → (无输出)
$ dumpsys activity services com.hisense.android.hiRMService → (nothing)

$ pm list packages | grep -i vidaaassistant → package:com.hisense.vidaaassistant
$ dumpsys package com.hisense.vidaaassistant
    uid=1000
    android.permission.FORCE_STOP_PACKAGES: granted=true
    android.permission.KILL_BACKGROUND_PROCESSES: granted=true
    User 0: installed=true ... enabled=3            ← DISABLED_USER
    Activity Resolver Table:
        hisense.tv.assistant.ACCELERATE:  com.hisense.vidaaassistant/.accelerate.AccelerateActivity
        hisense.tv.assistant.RUBBISH_CLEAR: com.hisense.vidaaassistant/.clearmemory.NulDelActivity
    (无 CACHE_CLEAN / DEPTH_CLEAN / DEVICE_RAM_LOW 接收器)
$ cmd package resolve-activity -a hisense.tv.assistant.ACCELERATE → No activity found

$ dumpsys package com.hisense.tv.inputs
    uid=10027   enabled=0
    android.permission.FORCE_STOP_PACKAGES: granted=true
    Receiver: ...background.receiver.AppSwitchReceiver
        actions: BOOT_COMPLETED / com.jamdeo.launcher.appswitch / ACTION_PANEL_ON /
                 EXTERNAL_APPLICATIONS_* / HISENSE_BOOT_COMPLETED / APP_UPGRADE_FINISHED
```
