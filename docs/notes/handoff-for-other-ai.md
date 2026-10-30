# 海信电视桌面替换项目 · 逆向资料交接文档

> 给接手的 AI/工程师。所有材料在 `D:\AI\杂\电视\`。本文件自包含全部关键结论与证据指针。

## 1. 目标与现状

- 目标：把海信电视默认桌面（聚好看）换成第三方桌面 ATV Launcher Pro（`ca.dstudio.atvlauncher`），要求 HOME 键 + 开机都进第三方桌面。
- 现状（2026-10-25 暂停）：免 root 与 root 路线已穷尽，**全部失败**。维持妥协方案：开机进聚好看，遥控器红键（`rc_red_key_action`）进 ATV。
- 红线：`com.jamdeo.tv.vod`（聚好看）**永远不禁用整包**——奇异果等内容的详情页/深链依赖它的其他 218 个组件。

## 2. 设备与固件

| 项 | 值 |
|---|---|
| 机型 | 海信 70V1F-S，VIDAA_TV，NOVA72691（固件号 72671） |
| SoC | Novatek n62020，ARM32，内核 4.9.118（2024-02-20 编译） |
| 真机系统 | Android 9 / SDK 28 / 04.02.60.03 / VIDAA 6.5，SELinux **Permissive**，**unencrypted**（无 FBE） |
| 镜像文件 | `His72671Upgrade.img`（2.0GB）= Android 8.0 / 04.02.60.01 / VIDAA 6.6（**比真机旧一个大版本**，结论迁移时注意） |
| adb | `192.168.31.234:5555`，网络 adb 可用（shell 权限），`adb root` 被拒（production build） |

## 3. 固件容器结构（已完全解析）

- 容器：`TIMG` 魔数 → 逐个 `PIMG` 条目（288 字节头：name/dev/size/guid）。分区表成品：`_fw/parts.json`（33 条目）。
- **注意**：条目 #15 是**误报**——`PIMG` 魔数撞上 uboot 分区 payload 里内置升级器代码的字符串（0x1227838 附近，伴随 `"%s invalid per image header !"`）。真实分区 32 个。
- 启动链：xboot → ddrcfg → uboot → ker0/ker1 + fdt0/fdt1 (A/B) + kerupm → tvos → system(1.74G) + vendor(207M)。
- `system.img` / `vendor.img` 是 **raw ext4**，可直接用 pytsk3 浏览（工具 `_fw/fs.py`）。vendor 无 APK。两个镜像已提取在 `_fw/img/`。

## 4. 开机→桌面机制（证据闭环）

代码位置：`system/framework/oat/arm/services.vdex` → `ActivityManagerService.startHomeActivityLocked()`（code_off 0x208c70）。

```
isStartPhysicalSource()：读 persist.sys.start_source（默认 2），为 0 改读 persist.sys.last_source；
                         命中 2562/2304/2305 任一 → true
真机：start_source=0、last_source=2562 → true → 走 AOSP 标准 category.HOME 解析
非物理源：海信硬编码显式组件 FTE(com.android.tv.settings，APK 不存在=死分支) /
          工厂(com.hisense.uifac) / 酒店(com.hisense.hotelmenu) / 默认(com.jamdeo.tv.livetv)
livetv 被禁用时的回退逻辑会挑"另一个可用桌面"（曾实测选中 priority=0 的第三方）
```

- `settings_startup_source_custom_pkg_key` **不驱动开机桌面**（只被语音助手事件和工厂 OK 流程读写）。
- 聚好看三代：镜像 2020.6（`priv-app/SuperApp`，HOME priority=0）→ 真机系统 2023.7（`priv-app/SuperApp_YOUKU-red`，被覆盖）→ **生效版 2025.8**（OTA 到 `/data/app`，versionCode 1500591603，由 `com.jamdeo.updateservice` 装，HOME filter **mPriority=50**）。

## 5. 防线清单（为什么换不动）——全部实测

1. **第三方 priority 归零**（最关键发现）：ATV Launcher 的 manifest 自带 `android:priority="951"`（原始字节验证 TYPE_INT_DEC），但运行时被归零（dumpsys 无 mPriority 行=0；`query-activities` 显示 priority=0）；系统包的 50 保留。归零是 **60.01→60.03 之间新增的加固**：镜像 A8 框架 `PackageParser.parseIntent`（boot-framework.vdex，code_off 0x319240）解析链干净、setPriority 前无插桩。**归零代码在真机 A9 的 compact-dex 里，未定位**。
2. **preferred 无效**：`cmd package set-home-activity` 写入成功但 resolution 永远被 priority=50 压制（两次实测）。
3. **ProtectedPackages**（AOSP 9 机制，海信把 vod 列入）：shell 对 vod 组件/包状态变更抛 `SecurityException: Shell cannot change component state...`（字符串在镜像与真机 services.vdex 均存在）。
4. **enabled=2 系统级禁用**：`com.android.settings`（含 FallbackHome 兜底）5 种方式复活全败——Android 通用规则，仅 root 可逆。
5. **遥控器无 HOME 键映射**（TileTransition.vdex 只有红绿蓝黄/收藏键）。
6. **工厂菜单无提权项**：真机打开 `com.hisense.uifac/.FacRootMenu`（adb 可直接 am start，无需重启），工厂+设计两个标签页人工确认无 ADB/Root/Debug 开关；`tofac_mode=U`；tofac=M 重启进完整工厂模式**未试**（风险大）。
7. **/data/local 无后门**：框架只读 `/data/local/misc/app_start_when_boot_whitelist.txt`（开机启动白名单）和 `/data/local/misc/list_white_RestartBgSvc.txt`（后台服务白名单），无桌面选择器。
8. ATV 无 BOOT_COMPLETED receiver、`persistent=true` 对第三方无效 → 第三方桌面无法自启抢屏。

## 6. 仅剩的两条路（接手者从这里开始）

### 路线 A：挖出归零代码（工程量中，可能发现免 root 豁免）
- 真机 A9 的 vdex 是 **compact-dex**：`dev/boot-framework.vdex`（20.7MB）含 3 个 cdex，魔数 `cdex001\0` 位于 **0x30、0x19c03c、0x3225f8**；`dev/services.vdex` 同格式。vdex 头：`vdex 019\0 002\0`。
- 现有 `_fw/` 工具只认 A8 标准 dex（`dexfix.get_blobs` 找 `dex\n` 魔数，在 cdex 里找不到）。**需要写 cdex 解析器**（string_ids 等表在文件尾部 section，需加 datum 偏移换算；参考 AOSP `art/dex/compact_dex_file.h`）。
- 定位目标：真机 framework 的 `PackageParser.parseIntent`（或 PMS 扫描路径）里 setPriority 前的插桩，看判定条件是**系统标志位**（无解）还是**名单/属性**（有机会）。重点看 60.03 新增的 `persist.sys.*`（diff 结果在会话记录：新增 31 个，无一明显相关，但 `persist.sys.sub_plan_owner` 值得查）。
- 判定依据：dumpsys package 的 filter 行——mPriority 行不打印 = 值为 0（AOSP IntentFilter.dump 只在非零时打印）。

### 路线 B：定制固件 USB 刷机（工程量大，brick 风险高）
- 往 system.img 塞第三方桌面进 `/system/priv-app/`（成为特权系统应用 → priority 豁免归零，直接压过 vod 的 50），或直接改 services 去掉归零。
- 前置研究：升级器（eburner/uboot 里的代码）对 TIMG 的**签名校验机制**（每条目有 32 hex 的 guid 字段，是否 RSA/MD5 未知）；构建流程：改 ext4 → 重算分区大小 → 重打包 TIMG → U 盘强刷。
- 失败模式要预研：升级器拒刷（安全失败）vs 刷坏（变砖）。

## 7. 工具资产（都在 `D:\AI\杂\电视\_fw\`，Python 3 + pytsk3 + cryptography）

| 工具 | 用途 |
|---|---|
| `fs.py <img> list/find/cat` | pytsk3 浏览 ext4 镜像 |
| `axml2.py <apk> [member] [--filter=X]` | 二进制 AndroidManifest 解析（本固件专用修正：20 字节属性 stride、特殊 UTF-8 池 u8charLen/u8byteLen 布局） |
| `dalvik_final.py` | Dalvik 解码器（quickened vdex，客观不变量校验 98.66% 通过；坑：invoke-kind opcode 在第 2 字节、if-test 是 3 code unit、goto/16 偏移有符号 1 字节） |
| `methfind.py <file> <method-regex> [class-regex]` | 跨容器找方法（注意参数顺序：先方法后类） |
| `callers.py <file> <method> [class]` | 反查调用点 |
| `dexdis.py <file> <class> <method>` | 单方法反汇编（解析字符串/方法/字段引用） |
| `vdex019.py` | A8 标准 dex 的 vdex 提取器（对 A9 cdex 无效） |
| `_scripts/patch_aerial_v2.py` | APK AXML 插桩 + v1 重签名模板（可改造成任意 priority 补丁） |

## 8. 关键文件索引

| 文件 | 内容 |
|---|---|
| `_reports/firmware-complete-reverse.md` | 完整逆向汇总（分区/三代演进/机制/防线） |
| `_reports/launcher-replacement-runbook.md` | 执行手册（阶段/命令/回滚，R1 已作废标注） |
| `_reports/firmware-home-analysis.md` / `BOOTPATH.md` / `APK-INVENTORY.md` / `HOME-INVENTORY.txt` | 开机路径与 HOME 候选的深挖细节 |
| `_fw/parts.json` | 33 条目分区表 |
| `_fw/apks/SuperApp_image.apk`（150MB） | 镜像版聚好看 |
| `_fw/dev/services.vdex` / `dev/boot-framework.vdex` | 真机 pull 的 A9 框架（cdex001） |
| `_fw/out/flat_sys/*.vdex` | 镜像 A8 框架平铺（标准 dex，工具可用） |
| `_backup/dumpsys-package.txt` / `dumpsys-vod.txt` | 真机包状态快照（priority=50 铁证在 dumpsys-vod.txt） |
| `_apk/atvlauncher.apk` | 真机拉的 ATV Launcher 0.1.9 Pro（manifest 自带 951） |

## 9. 方法论可借鉴点（给别人复用）

1. **无 binwalk 时固件容器手工解析**：魔数扫描 → 头部字段试解 → 用条目间偏移一致性自校验；误报（如本例 #15）用 payload 内容判别（uboot 字符串特征）。
2. **AXML 解析的坑**：属性 stride 20 字节不是 19；attributeStart 相对 attrExt（chunk+16）不是 chunk+24；此固件字符串池是非标 UTF-8 变体——都用已知值（包名、组件名）锚定验证。
3. **vdex 里 Dalvik 反汇编的自证**：每方法 `insns*2 == 指令长度和`、分支目标必须落在指令边界——用不变量覆盖率（98.66%）证明解码器正确性，而不是肉眼比对。
4. **静态结论必须真机对齐**：镜像是 60.01/A8、真机 60.03/A9，本次最关键发现（归零）就存在于两者差异中——任何"镜像结论"先问设备版本是否一致。
5. **静默侦察纪律**：用户在看电视时只做 dumpsys/getprop/pull 类只读操作，状态变更集中到用户点头后的执行窗口，每步带回滚命令。
