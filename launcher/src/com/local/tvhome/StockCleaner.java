package com.local.tvhome;

import android.app.ActivityManager;
import android.content.Context;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * 进入 TvHome 时清理聚好看进程（只杀进程，不禁用、不卸载，包保持完整）。
 *
 * 为什么不用 root：用户希望「手动进 TvHome 才清理」，事件发生在应用内，应用自己处理最自然，
 * 不需要常驻脚本、不需要开机钩子参与。
 *
 * 三级策略（按代价从低到高，逐个尝试并记录到 logcat：TvHomeKill）：
 *   1. killBackgroundProcesses —— 普通应用权限，能杀掉纯后台进程；
 *   2. 反射 forceStopPackage —— 需要 FORCE_STOP_PACKAGES，系统应用可能被授予；
 *   3. exec "am force-stop" —— 用应用自身 uid 调用 am，权限够就生效。
 * 只要其中一级成功，聚好看就没了；都不行只会打日志，不会影响使用。
 */
public final class StockCleaner {

    private static final String TAG = "TvHomeKill";
    private static final String VOD = "com.jamdeo.tv.vod";

    /** 防止频繁触发（例如快速来回切应用） */
    private static long sLastRunMs = 0L;
    private static final long MIN_INTERVAL_MS = 3000L;

    private StockCleaner() {
    }

    public static void clean(Context ctx) {
        long now = System.currentTimeMillis();
        if (now - sLastRunMs < MIN_INTERVAL_MS) {
            return;
        }
        sLastRunMs = now;

        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) {
            return;
        }

        // 1) 普通权限：杀后台进程
        try {
            am.killBackgroundProcesses(VOD);
            Log.i(TAG, "killBackgroundProcesses ok");
        } catch (Throwable t) {
            Log.i(TAG, "killBackgroundProcesses failed: " + t);
        }

        // 2) 反射 forceStopPackage（隐藏 API；Android 9 对 target 28 的浅灰名单通常放行）
        try {
            Method m = ActivityManager.class.getMethod("forceStopPackage", String.class);
            m.invoke(am, VOD);
            Log.i(TAG, "forceStopPackage ok");
        } catch (Throwable t) {
            Log.i(TAG, "forceStopPackage failed: " + t);
        }

        // 3) 兜底：用自身 uid 调 am
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", "am force-stop " + VOD});
            int code = p.waitFor();
            Log.i(TAG, "exec am force-stop exit=" + code);
        } catch (Throwable t) {
            Log.i(TAG, "exec am force-stop failed: " + t);
        }
    }
}
