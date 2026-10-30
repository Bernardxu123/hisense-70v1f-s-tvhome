# 70V1F-S · root 可行性复核（2026-10-28）

> 方式：全程**只读** adb 探测（getprop / cat / ls / dumpsys / pull），未改任何设置、未动任何分区。
> 探测时前台：`blbl.cat3399/.feature.player.PlayerActivity`（小孩在看电视），所以只做了读操作。
> 设备：海信 70V1F-S · NOVA72691 · Novatek n62020 · 192.168.31.234:5555

---

## 1. 结论

| 问题 | 答复 |
|---|---|
| 这台电视能不能 root？ | **软件层面它是"好打"的一台**，比一般 Android 手机/电视更松 |
| 能不能直接套用描述里那条链？ | **不能**。链的一半前提在本机不成立，照抄会白忙 |
| 缺的是什么？ | **缺"拿到 root 的那一下"本身**——没有 arm32 / 4.9.118 的公开可用 exploit |
| 值不值得？ | 只为换桌面：不值。想要真 root：先把离线部分做完再决定 |

一句话：**这台机器不是"防得住"，而是"没人给它写过"。** 门槛在工程量，不在防护。

---

## 2. 实测事实（2026-10-28 19:5x）

| 项 | 值 | 含义 |
|---|---|---|
| Android / 补丁 | 9 (API 28) / **2021-01-05** | 2021 之后的 LPE 全部未修 |
| 内核 | **4.9.118+**，armv7l，SMP PREEMPT，gcc 6.1.1 Linaro，2024-02-20 编译 | 老内核，生态漏洞多 |
| SELinux | `ro.boot.selinux=permissive`，`getenforce`=**Permissive** | 提权后**不需要**再破 SELinux |
| KASLR | `CONFIG_RANDOMIZE_BASE` 不存在 | 内核地址**固定**，符号可离线算 |
| 模块签名 | **`CONFIG_MODULE_SIG` 未开** | 拿到 root 就能 `insmod` 自编译模块 |
| 模块加载 | 已加载 20+ 个 `.ko`，`insmod/modprobe/lsmod` 都在，`modules_disabled=0` | 加载通道现成 |
| vermagic | `4.9.118+ SMP preempt mod_unload modversions ARMv7` | 自编模块必须逐字对齐，且 **CONFIG_MODVERSIONS=y**（要处理 `__versions` CRC） |
| SLUB | `CONFIG_SLAB_FREELIST_RANDOM` 未开、`SLAB_FREELIST_HARDENED` 不存在 | **堆喷/ grooming 可复现**，这对 UAF 利用是大礼包 |
| 其它加固 | `CONFIG_DEBUG_RODATA=y`、`STACKPROTECTOR_STRONG=y`、`HARDENED_USERCOPY=y`（无 PAGESPAN） | 只能改数据、不能改 .text；栈/拷贝有基础防护 |
| 崩溃策略 | `PANIC_ON_OOPS=0`，`kernel.panic=5` | oops 只杀进程；真 panic 则 **5 秒后重启**（黑屏） |
| 信息泄露 | `kptr_restrict=2`、`dmesg` 对 shell 输出 0 行、`/proc/cmdline` 拒读 | **没有白给的内核地址泄露**，泄露要在 exploit 里做 |
| 内存口 | `/dev/mem`、`/dev/kmem`、`/dev/port` 全无 | 无捷径 |
| 分区 | boot=`mmcblk0p8`（`brw------- root:root`）、system=`p29` 纯 ext4 ro（**无 dm-verity 挂载**）、data=`p31` rw | shell 读不到块设备；`/data` 未加密（可就地备份） |
| 网络栈 | `NETFILTER`/`XTABLES` 有，**`NF_TABLES` 没有**；`UNIX/PACKET/ASHMEM/FUSE` 有；**无 USER_NS、无 IO_URING** | nftables 系 CVE（2022-1015/2588 等）**不适用**；可打的是 unix/af_packet/binder 系 |
| adb root / su | `adbd cannot run as root`；全盘无 `su` | 官方路径、现成后门都没有 |

补充：`ro.secure=1`、`ro.debuggable=0`、`ro.build.tags=release-keys`；`ro.boot.verifiedbootstate` / `flash.locked` 为空（Novatek TV 不走 AVB 那套属性）。

---

## 3. 与那条「CVE-2026-64560」描述的差异

描述里的链是"跨缓存堆喷 → 写 tcp_mem 自证 → 扫 cred → 伪造 CAP_SYS_MODULE → finit_module 加载 KernelSU（每次开机按随机 kernel_base 重定位 217 个符号）"。在本机：

| 描述中的步骤 | 本机实况 |
|---|---|
| 按每次启动随机的 `kernel_base` 重定位 217 个符号 | **没有 KASLR**，地址固定。符号表可离线从内核镜像一次算好，不需要运行期重定位 |
| 改 cred 的 uid/euid + 伪造 `CAP_SYS_MODULE` | 只用一半：SELinux 是 **permissive**，uid 0 就是全权，**不需要伪造能力位、不需要换 `u:r:ksu:*` 域** |
| `finit_module` 加载 KernelSU 模块 | **本机可行**（无模块签名）；但要解决 vermagic + `__versions` CRC，且 KernelSU 官方主线面向 GKI，4.9/arm32 属手工集成 |
| 堆喷用跨缓存占位 | 可行且**更稳**（无 freelist 随机），但仍要自己写类型混淆/泄露 |
| 「写 tcp_mem 当铁证」 | 等价物本机是 `/proc/sys/net/ipv4/tcp_mem`（需 init netns 的 CAP_NET_ADMIN）；可用，但只是仪式感 |

结论：**这条链描述的是"手机上打 GKI"的形态；本机形态更简单，但更"前现代"——靠自己写 2018 年式 ARM32 exploit。**

另外：联网检索工具本次不可用，`CVE-2026-64560` 这个编号没法核实。其原语（posix-cpu-timers UAF）与真实存在的 **CVE-2022-2585**（2022 年修，回填到 4.9.323 一线）高度重合；本机 4.9.118 + 2021-01 补丁，**理论上仍在影响范围**。但"理论上"不等于"有现成 exploit"。

---

## 4. 可行路线与成本

| 路线 | 前置 | 工作量 | 风险 | 可逆 |
|---|---|---|---|---|
| **A. 内核 LPE → insmod 自定义模块** | 一个能用的 arm32/4.9 exploit | **数周～数月**（写+调+绕泄露） | 中高：调试期 oops/panic → 黑屏重启；偶发文件系统损坏 | 是（不写分区，重启即清） |
| **B. 解包 OTA → 改 boot/system → U 盘升级刷入** | 升级包**不校验签名** + 有救砖手段 | 数小时～数天 | **高：变砖** | 否（依赖 U 盘救砖是否可用，未验证） |
| **C. 工厂模式（`tofac=M`）** | 重启进完整工厂模式 | 一次尝试 | 中：菜单含"母块清空"等毁灭项 | 否 |

路线 A 的现实分解（建议顺序）：

1. **离线（零风险，现在就能做）**
   - 从 `His72671Upgrade.img` 里取出 boot 分区与内核镜像，建立固定符号/偏移表（无 KASLR，一次到位）。
   - 搭 4.9.118 / armv7 / gcc-6.1 Linaro 交叉编译环境；先编一个**空模块**验证 vermagic 与 `__versions` 处理方式（这是后面一切的地基）。
   - 查海信/联咏是否公开该机型 GPL 内核源码——有源码的话，模块集成和 exploit 开发难度都降一个量级。
2. **真机单次试探（要挑没人看电视的时段）**
   - 先做"只读探针"：触发目标 bug 但只走泄露/校验路径，不写内核内存，用 oops 是否出现判断可达性。
3. **持久化**
   - 只有在 A 稳定后才谈 `insmod`；每次重启都要重打（符合"不写分区"的设定）。
   - 想真正持久，只能回到路线 B；那就必须**先验证 U 盘升级能救砖**。

---

## 5. 风险清单

- **家人体验**：调试期任何 panic = 电视黑屏 5 秒后重启；小孩正在看。
- **数据**：`/data` 未加密、ext4 rw，反复 panic 有丢数据/坏块风险；动手前 `adb pull` 备份关键目录。
- **变砖**：`hirecovery`(p24) / `recovery`(p9) 分区在，但能否被 U 盘升级主动写回**未验证**；在验证前不要碰 boot/system。
- **保修 + OTA**：任何持久化改动都会影响 OTA 与保修；海信 OTA 走 `com.hisense.usbupgrade` / `com.hisense.remoteupgrade`。
- **收益**：root 能拿到的、对"换桌面"有用的只有三件事——改 `startHomeActivityLocked` 的硬编码、恢复 `FallbackHome`、把桌面塞进 `/system/priv-app`。前两件用现有"直播键 + 红键"方案已能绕过大半。

---

## 6. 建议

1. **现在**：只做离线部分（解包 OTA、拿符号、搭工具链、验 vermagic），完全不碰电视。
2. **改桌面**：停在现有方案，不需要 root（见 `final-result.md` §一）。
3. **真要 root**：把"U 盘救砖是否可用"当成**前置门槛**先验证；过不了就别进路线 B，走路线 A 的慢工。
4. 那条描述里的具体数字（217 符号、随机 kernel_base）**在本机没有意义**，不要按它准备环境。

---

相关：`final-result.md` §6.4（2026-10-22 的 root 只读探测）、`桌面替换可行性与路线评估.md`（路线对比）、`_fw/out_boot/`（已解出的 boot ramdisk 与 init 脚本）。

---

## 7. 2026-10-28 晚追加：手上镜像 ≠ 本机固件（关键）

### 7.1 两份固件的对照

| 项 | 本地 `His72671Upgrade.img` | 电视在跑的 |
|---|---|---|
| device | **NOVA72671** | **NOVA72691** |
| Android | 8.0.0 / OPR6.170623.013 | 9 / PPR2.180905.006.A1 |
| 版本号 | 04.02.60.01 | **04.02.60.03** |
| 构建日期 | 2020-05-18 | 2024-02-20 |
| 内核 | 4.9.118+ **#21**，2020-05-18 14:12:13 | 4.9.118+ **#1**，2024-02-20 15:53:46 |
| board.platform | n00010 | **n62020** |
| 安全补丁 | 2019-03-05 | 2021-01-05 |

- 镜像里的 kernel 已解出（`_fw/out_kern/ker0.kernel` = zImage，gzip 偏移 0x4333 → `ker0.vmlinux` 12,403,168 B），banner 为 `#21 … Mon May 18 14:12:13 CST 2020`。
- 本机 `uname -a` 是 `#1 … Tue Feb 20 15:53:46 CST 2024`。
- **结论：内核不是同一个构建。** 因此从这份镜像推算出来的符号、偏移、gadget、`__versions` CRC 全部对真机无效。
- 但配置大体同族；**本机权威配置已拉取**：`_tmp/config-20261028.txt`（4170 行，来自 `/proc/config.gz`，世界可读）。
- 正确的包名来自本机属性：`ro.tv.upgrade_file = His72691Upgrade.img`。

### 7.2 cleanyourtv 里的一条反证

`docs/devices/hisense-amlogict963-android9.md`（Hisense VIDAA_TV / Android 9 / 32 位 / `ro.adb.secure=1`，build 07.02.70.02）记录：

- 聚好看 `com.jamdeo.tv.vod` **保持启用**，第三方桌面被设为 Home；
- 重启后仍进第三方桌面，**按物理 HOME 键也不退回聚好看**；
- 验证方法只有两条只读命令（`pm list packages -e com.jamdeo.tv.vod`、`cmd package resolve-activity --brief -a MAIN -c HOME`）+ 重启 + 物理 HOME 实测。

这与本机笔记「HOME 被 `startHomeActivityLocked` 硬编码」的结论冲突。两台机器 build 分支不同（07.02.70.02 vs 04.02.60.03），值得按同样方法复测一次；失败才可确认本机确实写死。

### 7.3 usbupgrade.apk 初步结论

`com.hisense.firmware.upgrade`（`UsbUpgradeHandler`、`DEFAULT_USB_UPGRADE_FILE_LIST`、`EXTRA_USB_PATH`）在 dex 里**看不到哈希/签名校验**；它的动作是 `writeBootWhiteList` → 设 power ctl 属性 → `reboot device for usb upgrade`。真正的写入在 bootloader/recovery（`hirecovery`/`nvt`）。所以"能不能刷改过的包"取决于引导层校验；`ro.nvt.secure_check=0` 是乐观信号，但未证实。

### 7.4 下一步（按收益排序）

1. 拿 `<某个 NT72691 固件包>` 或 `His72691Upgrade.img`（04.02.60.03）→ 解 ker0/ker1 → 恢复 kallsyms（无 KASLR + `KALLSYMS_ALL`）→ 得到真机全符号表。zndsrom 上有 70V1F-S 的 NT72691 条目（20264 BOM06、20263 BOM07、21171/21186 BOM04），按 BOM 区分。
2. 先读出本机 BOM/机型（`/tvconfig`、`deviceinfo`），确保拿对包。
3. 按 7.2 的方法复测 HOME（只读两条 + 重启 + 物理键），把"必须 root"这个前提再确认一次。
4. REA 插件要能用，需补：`GHIDRA_INSTALL_DIR`（12.1.x）、`REA_JADX_MCP_JAR`（jadx-headless-mcp 0.7.1）、`REA_ADB_PATH`（指向本机 adb.exe）。
