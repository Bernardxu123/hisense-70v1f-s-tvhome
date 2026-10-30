# HOME 键归属 · 方案研究（2026-10-29，静默阶段）

> 本轮**未改动电视任何状态**：只做了只读查询、被动抓日志、本地文件分析、外网资料检索。

## 一、已确认的事实

| # | 事实 | 证据 |
|---|---|---|
| 1 | 开机 → 聚好看 → 约 15s 后 TvHome | boot-toggle 钩子，2 种启动态均验证 |
| 2 | `user_setup_complete=1`、`device_provisioned=1`、`tv_user_setup_complete` 未设置 | `settings get` |
| 3 | 日志中无 `Not starting activity because user setup is in progress` | logcat 全量 grep，命中 0 |
| 4 | `set-home-activity` 只写入 `always="false"`，HOME 仍选 priority=50 的聚好看 | `package-restrictions.xml` |
| 5 | 禁用聚好看 LauncherActivity → 无界面绘制 → `boot_completed` 永远为空（卡 logo） | 4 分钟采样 + 两次复现 |
| 6 | boot-toggle（开机启用、启动完成后再禁用）可正常开机 | 2 次重启验证 |
| 7 | **未验证**：组件禁用后真实遥控器按 HOME 的落点 | 之前用 `input keyevent 3` 测试无效（注入键被固件吞） |
| 8 | 待机唤醒会直接 resume 聚好看 LauncherActivity，并发 `MAIN` intent（flags 0x10000100） | 09:53 事件日志 |

## 二、根因的两种可能（尚未区分）

- **H1｜HOME 走标准解析**：那么把聚好看从候选里摘掉（禁用其 LauncherActivity），HOME 自然落到 TvHome。boot-toggle 已解决开机问题 → 直接可用。
- **H2｜HOME 走固件显式组件**：那么禁用组件后 HOME 无反应（应用内按 HOME 会"卡住"），必须加看护进程。

区分成本：**一次按键 + 1 分钟**。

## 三、判别实验（等电视空闲，1 分钟）

1. 钩子切到 boot-toggle（`_tmp\payload\rc.sh` 已写好，含"开机启用→完成→禁用→拉 TvHome"）
2. 触发（或重启，开机安全已有验证）
3. 从奇异果里按一次遥控器 **HOME**
4. 判读：
   - 落在 TvHome → **H1 成立**，方案 1 直接收工
   - 无反应 / 回聚好看 → **H2 成立**，走方案 2

## 四、方案清单

| # | 方案 | 前置 | 风险 | 收益 |
|---|---|---|---|---|
| 1 | boot-toggle + 标准解析 | 无（已实现） | 低，开机已验证 | 若 H1 成立即完全达成 |
| 2 | root 看护进程（事件驱动） | 需要 H2 | 中：需验证 init 不杀子进程 | HOME→TvHome，约 0.3–0.8s 闪烁 |
| 3 | 无障碍守护（55D30S 同代方案） | 不需要 root | 低 | 大概率无效（本机固件不派发窗口事件，已实测） |
| 4 | 键位映射（`settings_channel_key` / `rc_*_key_action`） | 需先定位本机存放位置（本机为 null） | 低 | 只解决"红键/多功能键→TvHome"，不改 HOME |
| 5 | 改固件（改 `startHomeActivityLocked` 的组件） | 需解 CompactDex + remount rw | 高 | 真正根治，不推荐 |

### 方案 2 设计要点

```sh
# 由开机钩子 setsid 后台拉起，避免 init 收尾时被回收
logcat -b events -s am_resume_activity | while read line; do
  case "$line" in
    *com.jamdeo.tv.vod/com.ju.main.launcher.LauncherActivity*)
      am start -n com.local.tvhome/.MainActivity ;;
  esac
done
```
- 事件驱动，无轮询开销
- 聚好看组件保持启用 → 开机永远安全
- 失败时最坏结果：HOME 仍回聚好看（等价于现状），不会黑屏

## 五、关于 `His72671Upgrade.img`（回答"要不要逆向"）

**不用，对本机没用。**

| | 该镜像 | 你的电视 |
|---|---|---|
| device | NOVA72671 | NOVA72691 |
| Android | 8.0.0 | 9 |
| 版本 | 04.02.60.01 | **04.02.60.03** |
| 内核 | 4.9.118+ #21（2020-05-18） | 4.9.118+ #1（2024-02-20） |
| 平台 | n00010 | n62020 |

真正值得分析的是**本机自己的**文件，已经拉到本地：

- `/system/framework/oat/arm/services.vdex` → `_fw/dev/services.vdex`（9.86 MB，**CompactDex/cdex001** 格式，头部已解析）
- 本机厂商 APK（`com.jamdeo.tv.vod` 等）可用 root 钩子直接 pull
- 本机 `build.prop`、`init.rc`、`init.hisense.rc`（已取）

若要查清 HOME 的显式组件逻辑，路径是：vdex → 提取 cdex → dexdump（build-tools 34 已就绪）→ 定位 `PhoneWindowManager` / `HisenseStartManagerUtil` / `startHomeActivityLocked`。属方案 5 的前置工作，优先级低于"一分钟实验"。

## 六、外网资料要点（ego 浏览器检索）

1. **zhouzt0121/HisenseTV55D30S**（VIDAA V6LITE / Android 9，同代）：
   - 结论：VIDAA 在 PhoneWindowManager 层当 `mUserSetupComplete=false` 时跳过标准 HOME；`set-home-activity` 写的 `mMatch=0x100000` 与 HOME 的 `MATCH_DEFAULT_ONLY` 不匹配 → 永不生效；第三方 HOME priority 被钳为 0
   - 解法：无障碍守护 + 广播 `LETV_SCREENON`（不能直接 startActivity，Android 9 后台启动限制会静默丢弃）
   - 我们的差异：本机 `user_setup_complete=1`，且已有 root，可直接 `am start`，不需要绕后台启动限制
2. **nanosan0/vidaa-tv-restore-kit**（MTK9632 / Android 9）：用 `cmd package set-home-activity` + `input keyevent HOME` 声称接管成功 → 机型固件不同，本机实测无效
3. **XDA TV Box Tools**：确认 `set-home-activity` 在 Android 10+ 才是 HOME role；老版本只是写默认值 → 与本机现象一致
4. **CSDN**：HOME 失效常因 `USER_SETUP_COMPLETE`/`TV_USER_SETUP_COMPLETE` 未置 1 → 本机不适用（已验证）

## 七、下次执行清单

1. 电视空闲 → 切 boot-toggle 钩子 → 触发/重启（约 60s）
2. 你用遥控器按一次 HOME → 报结果
3. H1：收尾（更新文档、可选精简钩子）
   H2：上方案 2，验证看护进程在开机后存活，再验 HOME
4. 全程可回滚：`_scripts\tv-recover-home.ps1` + 删除 `/data/local/misc/Arun.zip`
