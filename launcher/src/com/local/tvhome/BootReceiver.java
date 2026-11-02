package com.local.tvhome;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

import java.util.List;

/**
 * 开机（含海信的「框架软重启」）后把 TvHome 带回前台。
 *
 * 背景：
 *   海信的「开机」经常只是 Android 框架重启（内核不重启，uptime 继续增长），init 不会重跑
 *   autorun 服务，于是 /data/local/misc/rc.sh 的开机钩子不执行，系统会把上次的桌面（聚好看）
 *   恢复到前台，用户看到的就是「开机进聚好看」。
 *
 * 为什么不做「检测到聚好看才动手」：
 *   本机实测（v1.3.2 自检广播）`getRunningAppProcesses()` 只能看到自己的进程，top=null。
 *   非平台签名的系统应用拿不到别的进程信息，无法判断前台是谁。
 *   于是改成：开机完成后在几个时间点检查「自己是否已在前台」，不在就把自己带到前台。
 *
 * 安全约束（来自一次真实事故）：
 *   1. **只处理 BOOT_COMPLETED**，绝不处理 MY_PACKAGE_REPLACED —— 后者会在用户看片时触发，
 *      把桌面糊到用户脸上。
 *   2. 只在开机后的 ~60 秒窗口内动作，窗口过了就彻底不管。
 *   3. 自己已在前台就立刻收工。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String ACTION_CHECK = "com.local.tvhome.CHECK_FRONT";

    /** 检查点延迟（毫秒），覆盖「聚好看先画 / 我们的包稍后才可启动」的窗口，总计约 60 秒 */
    private static final long[] DELAYS = {8000L, 6000L, 6000L, 6000L, 6000L, 6000L, 6000L, 6000L, 6000L, 6000L};

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_CHECK.equals(action)) {
            checkAndRise(context, intent.getIntExtra("attempt", 0));
        } else if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            schedule(context, 0);
        }
    }

    private void schedule(Context ctx, int attempt) {
        if (attempt >= DELAYS.length) {
            return;
        }
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        Intent i = new Intent(ctx, BootReceiver.class)
                .setAction(ACTION_CHECK)
                .putExtra("attempt", attempt);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, attempt, i, PendingIntent.FLAG_UPDATE_CURRENT);
        am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + DELAYS[attempt], pi);
    }

    private void checkAndRise(Context ctx, int attempt) {
        if (isSelfForeground(ctx)) {
            return;                      // 已经在我们自己的桌面上，收工
        }
        rise(ctx);                       // 不在前台 → 带上来
        schedule(ctx, attempt + 1);      // 聚好看通常还会再抢一次，继续盯几个检查点
    }

    private void rise(Context ctx) {
        try {
            Intent i = new Intent(ctx, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            ctx.startActivity(i);
        } catch (Throwable ignored) {
        }
    }

    /** 只能判断「自己」是否在前台：本机实测这个能力可靠（别人的信息看不到） */
    private boolean isSelfForeground(Context ctx) {
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) {
                return false;
            }
            List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
            if (procs == null) {
                return false;
            }
            for (ActivityManager.RunningAppProcessInfo p : procs) {
                if (p.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                    int colon = p.processName.indexOf(':');
                    String pkg = colon > 0 ? p.processName.substring(0, colon) : p.processName;
                    return ctx.getPackageName().equals(pkg);
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }
}

