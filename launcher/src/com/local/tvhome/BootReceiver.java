package com.local.tvhome;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.List;

/**
 * 开机后把自己切到前台（v1.8）。
 *
 * 冷启动的主力其实是 root 侧的 rc.sh「抢跑」（AMS 一就绪就 am start，早于屏幕亮起）。
 * 这个接收器是**框架软重启**（待机唤醒后系统重启）时的兜底：那种情况下 init 不会重跑 rc.sh。
 *
 * 实测：BOOT_COMPLETED 广播本身没问题，但这台机器的广播队列在开机时会积压，
 * 我们的进程可能要等十几秒才被拉起，所以这里不再用 AlarmManager（开机阶段它还会额外延迟几秒），
 * 改成进程内 Handler 每 400ms 重试，直到自己在前台。
 *
 * 只监听 BOOT_COMPLETED，不监听 MY_PACKAGE_REPLACED（后者会在用户看片时触发）。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "TvHomeBoot";
    private static final long STEP_MS = 400L;
    private static final int MAX_ATTEMPTS = 50;   // ~20 秒

    private Handler handler;
    private int attempts;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        Log.i(TAG, "BOOT_COMPLETED t=" + SystemClock.elapsedRealtime());
        final Context app = context.getApplicationContext();
        handler = new Handler(Looper.getMainLooper());
        attempts = 0;
        tick(app);
    }

    private void tick(final Context ctx) {
        if (attempts >= MAX_ATTEMPTS) {
            Log.i(TAG, "give up after " + attempts);
            return;
        }
        if (isSelfForeground(ctx)) {
            Log.i(TAG, "foreground after " + attempts + " tries");
            return;
        }
        rise(ctx);
        attempts++;
        if (handler != null) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    tick(ctx);
                }
            }, STEP_MS);
        }
    }

    private void rise(Context ctx) {
        try {
            Intent i = new Intent(ctx, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            ctx.startActivity(i);
        } catch (Throwable ignored) {
        }
    }

    /** 只能判断「自己」是否在前台：本机实测可靠 */
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
