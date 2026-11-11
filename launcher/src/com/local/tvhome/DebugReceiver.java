package com.local.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 调试用：不切换界面、只在后台跑一次清理，方便用 adb 验证权限是否够。
 *
 *   adb shell am broadcast -a com.local.tvhome.DEBUG_KILL -p com.local.tvhome
 *   adb logcat -s TvHomeKill
 *   adb shell ps -A | grep com.jamdeo.tv.vod     # 杀掉了就查不到
 */
public class DebugReceiver extends BroadcastReceiver {

    public static final String ACTION = "com.local.tvhome.DEBUG_KILL";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) {
            return;
        }
        Log.i("TvHomeKill", "debug kill requested");
        StockCleaner.clean(context);
    }
}
