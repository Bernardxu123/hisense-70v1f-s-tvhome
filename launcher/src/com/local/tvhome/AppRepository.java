package com.local.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 应用列表 + 偏好。纯框架 API，无 AndroidX。
 * 布局仿 tvhome-zh：第一行是 Dock（7 个固定常用），第二行起是应用网格。
 */
public class AppRepository {
    private static final String PREFS = "tvhome";
    private static final String KEY_HIDDEN = "hidden";
    private static final String KEY_CLEAN = "clean";
    private static final String KEY_LAST_CLEAN = "last_clean";
    private static final String KEY_DOCK = "dock";
    private static final String KEY_GRID_ORDER = "grid_order";

    /** Dock 默认顺序（与 tvhome-zh 的“常用”一致）。 */
    private static final String[] DEFAULT_DOCK = new String[]{
            "blbl.cat3399",             // 哔哩哔哩TV
            "com.ktcp.csvideo",         // 云视听极光
            "com.hisensecpa.tv",        // 奇异果TV
            "org.smarttube.stable",     // SmartTube
            "com.xiaobaifile.tv",       // 小白文件管理器
            "com.android.tv.settings",  // 电视设置
    };

    private static final String[] ORDER = new String[]{
            "blbl.cat3399", "com.ktcp.csvideo", "com.hisensecpa.tv",
            "org.smarttube.stable", "com.ju.miguvideoTV.cmcc",
            "com.xiaobaifile.tv", "com.android.tv.settings",
            "ca.dstudio.atvlauncher", "com.dangbeimarket",
            "com.oriontv", "com.chinasoul.bt", "com.huawei.himovceie",
            "com.speech.enchatroom"
    };

    private static final Map<String, String> LABEL_OVERRIDE =
            new java.util.HashMap<String, String>() {{
                put("blbl.cat3399", "哔哩哔哩TV");
                put("com.hisensecpa.tv", "奇异果TV");
                put("com.ktcp.csvideo", "云视听极光");
                put("com.ju.miguvideoTV.cmcc", "咪咕视频");
                put("org.smarttube.stable", "SmartTube");
                put("com.xiaobaifile.tv", "小白文件管理器");
                put("com.android.tv.settings", "电视设置");
                put("ca.dstudio.atvlauncher", "ATV桌面");
                put("com.dangbeimarket", "当贝市场");
                put("app.orchard.tv", "Aerial");
                put("com.oriontv", "猎豹TV");
                put("com.chinasoul.bt", "蓝牙助手");
                put("com.speech.enchatroom", "语音聊天");
                put("com.local.tvhome", "TvHome");
                put("com.local.tvmate", "TvMate 跳板");
            }};

    private final Context ctx;
    private final PackageManager pm;
    private final SharedPreferences sp;
    private final List<AppEntry> all = new ArrayList<>();
    private final Set<String> hidden = new HashSet<>();

    public AppRepository(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.pm = this.ctx.getPackageManager();
        this.sp = this.ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.hidden.addAll(sp.getStringSet(KEY_HIDDEN, new HashSet<String>()));
    }

    /** 重新扫描：MAIN+LAUNCHER 与 LEANBACK_LAUNCHER 合并去重。 */
    public void reload() {
        all.clear();
        Map<String, AppEntry> map = new LinkedHashMap<>();
        collect(map, Intent.CATEGORY_LAUNCHER);
        collect(map, Intent.CATEGORY_LEANBACK_LAUNCHER);

        List<AppEntry> list = new ArrayList<>(map.values());
        final Collator collator = Collator.getInstance(Locale.CHINA);
        Collections.sort(list, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                int ia = orderIndex(a.pkg), ib = orderIndex(b.pkg);
                if (ia != ib) return ia - ib;
                return collator.compare(a.label, b.label);
            }
        });
        all.addAll(list);
    }

    private void collect(Map<String, AppEntry> map, String category) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(category);
        List<ResolveInfo> ris = pm.queryIntentActivities(intent, 0);
        if (ris == null) return;
        for (ResolveInfo ri : ris) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg == null) continue;
            if (pkg.equals(ctx.getPackageName())) continue;
            if (pkg.equals("com.jamdeo.tv.vod")) continue;
            if (map.containsKey(pkg)) continue;
            CharSequence sysLabel = ri.loadLabel(pm);
            String label = sysLabel == null ? pkg : sysLabel.toString().trim();
            String override = LABEL_OVERRIDE.get(pkg);
            if (override != null) label = override;
            map.put(pkg, new AppEntry(pkg, ri.activityInfo.name, label));
        }
    }

    private int orderIndex(String pkg) {
        for (int i = 0; i < ORDER.length; i++) if (ORDER[i].equals(pkg)) return i;
        return ORDER.length + 1;
    }

    public List<AppEntry> visibleApps() {
        List<AppEntry> out = new ArrayList<>();
        for (AppEntry e : all) if (!hidden.contains(e.pkg)) out.add(e);
        return out;
    }

    public List<AppEntry> hiddenApps() {
        List<AppEntry> out = new ArrayList<>();
        for (AppEntry e : all) if (hidden.contains(e.pkg)) out.add(e);
        return out;
    }

    /** 全部应用（含隐藏），供移动模式按包名查条目。 */
    public List<AppEntry> allApps() {
        return all;
    }

    /** Dock 应用（按用户编辑或默认顺序），最多 count 个。 */
    public List<AppEntry> dockApps(int count) {
        Set<String> dock = dockSet();
        List<AppEntry> out = new ArrayList<>();
        for (String pkg : dock) {
            AppEntry e = find(pkg);
            if (e != null && !hidden.contains(pkg)) out.add(e);
            if (out.size() >= count) break;
        }
        // 不够时用可见应用补齐
        if (out.size() < count) {
            for (AppEntry e : all) {
                if (out.size() >= count) break;
                if (hidden.contains(e.pkg) || contains(out, e.pkg)) continue;
                out.add(e);
            }
        }
        return out;
    }

    /** 网格应用：先按用户排序（grid_order），再追加未列出的可见应用（非 Dock）。 */
    public List<AppEntry> gridApps(int dockCount) {
        Set<String> dock = new HashSet<>();
        for (AppEntry e : dockApps(dockCount)) dock.add(e.pkg);

        List<AppEntry> out = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (String pkg : gridOrder()) {
            AppEntry e = find(pkg);
            if (e == null || hidden.contains(pkg) || dock.contains(pkg) || used.contains(pkg)) continue;
            out.add(e);
            used.add(pkg);
        }
        for (AppEntry e : all) {
            if (hidden.contains(e.pkg) || dock.contains(e.pkg) || used.contains(e.pkg)) continue;
            out.add(e);
            used.add(e.pkg);
        }
        return out;
    }

    // ------------------------------------------------------------ 排序持久化

    public List<String> gridOrder() {
        String raw = sp.getString(KEY_GRID_ORDER, "");
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String s : raw.split(",")) {
            if (!s.isEmpty() && !out.contains(s)) out.add(s);
        }
        return out;
    }

    public void setGridOrder(List<String> order) {
        sp.edit().putString(KEY_GRID_ORDER, join(order)).apply();
    }

    /** Dock 内移动：from/to 为 Dock 内的位置（0-based）。 */
    public void moveInDock(int from, int to) {
        List<String> order = new ArrayList<>();
        for (AppEntry e : dockApps(DOCK_LIMIT)) order.add(e.pkg);
        move(order, from, to);
        // Dock 顺序与 dock 集合保持同步
        sp.edit().putString(KEY_DOCK + "_order", join(order)).apply();
        Set<String> set = new LinkedHashSet<>(order);
        sp.edit().putStringSet(KEY_DOCK, set).apply();
    }

    /** 应用页内移动：from/to 为网格内的位置（0-based）。 */
    public void moveInGrid(int from, int to) {
        List<String> order = new ArrayList<>();
        for (AppEntry e : gridApps(DOCK_LIMIT)) order.add(e.pkg);
        move(order, from, to);
        setGridOrder(order);
    }

    private void move(List<String> list, int from, int to) {
        if (from < 0 || to < 0 || from >= list.size() || to >= list.size() || from == to) return;
        String pkg = list.remove(from);
        list.add(to, pkg);
    }

    /** Dock 上限（与 UI 的 5 个槽位一致）。 */
    public static final int DOCK_LIMIT = 5;

    private boolean contains(List<AppEntry> list, String pkg) {
        for (AppEntry e : list) if (e.pkg.equals(pkg)) return true;
        return false;
    }

    private AppEntry find(String pkg) {
        for (AppEntry e : all) if (e.pkg.equals(pkg)) return e;
        return null;
    }

    public Set<String> dockSet() {
        Set<String> saved = sp.getStringSet(KEY_DOCK, null);
        if (saved != null && !saved.isEmpty()) {
            // 保序：按保存时的列表顺序
            String joined = sp.getString(KEY_DOCK + "_order", "");
            if (!joined.isEmpty()) {
                Set<String> ordered = new java.util.LinkedHashSet<>();
                for (String s : joined.split(",")) if (saved.contains(s)) ordered.add(s);
                if (ordered.size() == saved.size()) return ordered;
            }
            return new java.util.LinkedHashSet<>(saved);
        }
        Set<String> def = new java.util.LinkedHashSet<>();
        Collections.addAll(def, DEFAULT_DOCK);
        return def;
    }

    public void setDock(List<String> pkgs) {
        Set<String> set = new java.util.LinkedHashSet<>(pkgs);
        sp.edit().putStringSet(KEY_DOCK, set)
                .putString(KEY_DOCK + "_order", join(pkgs)).apply();
    }

    public void toggleDock(String pkg) {
        List<String> list = new ArrayList<>(dockSet());
        if (list.contains(pkg)) list.remove(pkg);
        else list.add(pkg);
        setDock(list);
    }

    private String join(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(list.get(i));
        }
        return sb.toString();
    }

    public void setHidden(String pkg, boolean hide) {
        if (hide) hidden.add(pkg); else hidden.remove(pkg);
        sp.edit().putStringSet(KEY_HIDDEN, new HashSet<>(hidden)).apply();
    }

    public boolean cleanEnabled() {
        return sp.getBoolean(KEY_CLEAN, true);
    }

    public void setCleanEnabled(boolean v) {
        sp.edit().putBoolean(KEY_CLEAN, v).apply();
    }

    public long lastClean() {
        return sp.getLong(KEY_LAST_CLEAN, 0L);
    }

    public void setLastClean(long v) {
        sp.edit().putLong(KEY_LAST_CLEAN, v).apply();
    }
}

