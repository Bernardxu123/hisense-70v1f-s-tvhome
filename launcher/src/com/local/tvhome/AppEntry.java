package com.local.tvhome;

import android.content.ComponentName;
import android.content.Intent;

/** 一个可启动的应用条目；pkg == null 表示“全部应用”入口。 */
public class AppEntry {
    public final String pkg;
    public final String activity;
    public final String label;
    public final boolean allApps;

    public AppEntry(String pkg, String activity, String label) {
        this.pkg = pkg;
        this.activity = activity;
        this.label = label;
        this.allApps = false;
    }

    private AppEntry() {
        this.pkg = null;
        this.activity = null;
        this.label = "全部应用";
        this.allApps = true;
    }

    public static AppEntry allApps() {
        return new AppEntry();
    }

    public Intent launchIntent(android.content.Context ctx) {
        if (allApps) return null;
        if (activity != null && activity.length() > 0) {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setComponent(new ComponentName(pkg, activity));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            return i;
        }
        Intent i = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        }
        return i;
    }
}
