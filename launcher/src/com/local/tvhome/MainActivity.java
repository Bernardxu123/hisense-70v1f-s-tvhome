package com.local.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * TvHome 4.0：Aerial 式逻辑，但把“两页”做成一个 Activity 内的两个状态。
 *
 * 状态 1（首页 / 收起）：底部 4 个 Dock 卡片 + 大标题 + 环境光。选中卡片放大 1.10，
 *                      Dock 容器预留了放大余量，不会被裁切。
 * 状态 2（应用页 / 展开）：Dock 隐藏，整块区域换成应用网格；网格卡片与 Dock 同比例（16:9）。
 *                      返回 / ↑ 回到首页。
 *
 * 性能：网格只在第一次进入时构建；图标/banner 下一帧加载并缓存；环境光只采样一次。
 */
public class MainActivity extends Activity implements View.OnClickListener,
        View.OnFocusChangeListener, View.OnLongClickListener {

    private static final String TAG = "TvHome";
    private static final String KILL_PKG = "com.jamdeo.tv.vod";
    private static final int COLS = 5;
    private static final int DOCK_SLOTS = 5;

    private static final int BG = 0xFF000000;
    private static final int TILE = 0xFF1C1C1E;
    private static final int TEXT_MAIN = 0xFFFFFFFF;
    private static final int TEXT_DIM = 0xB3FFFFFF;
    private static final int RADIUS_DP = 16;

    /** 卡片设计比例 16:9（与 Dock、网格、全部应用页统一）。 */
    private static final float CARD_RATIO = 9f / 16f;
    private static final float FOCUS_SCALE = 1.10f;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<String, Integer> ambientCache = new HashMap<>();

    private AppRepository repo;
    private IconLoader icons;

    private TextView clock, date, greeting, heroName, statusDot;
    private FrameLayout contentArea;
    private LinearLayout dockRow, homeBox;
    private FrameLayout gridRoot;
    private LinearLayout gridColumn;
    private View ambient;

    private int cardW, cardH, dockCardW, dockCardH, iconPx, inner;
    private final List<View> dockTiles = new ArrayList<>();
    private final List<View> gridTiles = new ArrayList<>();
    private boolean gridBuilt = false;
    private boolean appsOpen = false;

    // ---------------- 移动模式（调整图标位置） ----------------
    private boolean moveMode = false;
    private boolean moveInDock = true;      // true: 在 Dock 内移动；false: 在应用页内移动
    private int moveFrom = -1;              // 原位置
    private int moveTo = -1;                // 当前位置
    private List<String> moveOrder;         // 当前预览顺序
    private LinearLayout movePanel;         // 移动面板
    private TextView moveTitle, moveKeys, moveActions;
    private int movePrevFocus = -1;

    private final SimpleDateFormat dateFmt = new SimpleDateFormat("M月d日 EEEE", Locale.CHINA);
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm", Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 真·全屏沉浸：铺满整块面板，不留系统装饰与边距
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        getWindow().setFlags(
                android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
                android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
        repo = new AppRepository(this);
        icons = new IconLoader(this);

        int screenW = getResources().getDisplayMetrics().widthPixels;
        // 内容边距缩小到 40dp，让 5 列卡片更“满”，减少边界感
        inner = screenW - dp(40) * 2;
        cardW = (inner - dp(12) * COLS) / COLS;
        cardH = Math.round(cardW * CARD_RATIO);
        // Dock 与网格同尺寸，保证列对齐；5 个槽位，不满时左对齐
        dockCardW = cardW;
        dockCardH = cardH;
        iconPx = Math.round(Math.min(dockCardW, dockCardH) * 0.52f);

        buildUi();
        repo.reload();
        buildDock();
        doCleanIfNeeded();
        updateClock();
        ui.postDelayed(clockTick, 30_000L);
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        ambient = new View(this);
        ambient.setAlpha(0f);
        root.addView(ambient, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(32), dp(18), dp(32), dp(10));
        root.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 顶部
        FrameLayout top = new FrameLayout(this);
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);

        greeting = new TextView(this);
        greeting.setTextColor(TEXT_DIM);
        greeting.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        greeting.setLetterSpacing(0.22f);
        greeting.setAllCaps(true);
        greeting.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        left.addView(greeting);

        LinearLayout clockRow = new LinearLayout(this);
        clockRow.setOrientation(LinearLayout.HORIZONTAL);
        clockRow.setGravity(Gravity.BOTTOM);

        clock = new TextView(this);
        clock.setTextColor(TEXT_MAIN);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 50);
        clock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clock.setIncludeFontPadding(false);
        clock.setLetterSpacing(-0.04f);
        clockRow.addView(clock);

        View divider = new View(this);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(1), dp(24));
        dlp.setMargins(dp(14), 0, dp(14), dp(3));
        dlp.gravity = Gravity.BOTTOM;
        divider.setLayoutParams(dlp);
        divider.setBackgroundColor(0x33FFFFFF);
        clockRow.addView(divider);

        date = new TextView(this);
        date.setTextColor(TEXT_DIM);
        date.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        date.setLetterSpacing(0.12f);
        date.setPadding(0, 0, 0, dp(4));
        clockRow.addView(date);
        left.addView(clockRow);
        top.addView(left);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.END | Gravity.BOTTOM;
        top.addView(buttons, blp);
        buttons.addView(pillButton("桌面设置", R.id.btn_settings, this));
        buttons.addView(pillButton("全部应用", R.id.btn_all, this));

        statusDot = new TextView(this);
        statusDot.setText("●");
        statusDot.setTextColor(0x8CFFFFFF);
        statusDot.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        statusDot.setPadding(dp(12), 0, 0, dp(6));
        buttons.addView(statusDot);

        col.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 内容区（权重 1）：首页态放「大标题 + Dock」；应用页态放网格
        contentArea = new FrameLayout(this);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        clp.topMargin = dp(8);
        col.addView(contentArea, clp);

        // ---------- 首页态 ----------
        homeBox = new LinearLayout(this);
        homeBox.setOrientation(LinearLayout.VERTICAL);
        homeBox.setGravity(Gravity.BOTTOM);
        contentArea.addView(homeBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        heroName = new TextView(this);
        heroName.setTextColor(TEXT_MAIN);
        heroName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        heroName.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        heroName.setLetterSpacing(-0.01f);
        heroName.setSingleLine(true);
        heroName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        heroName.setAlpha(0.92f);
        heroName.setPadding(dp(2), 0, 0, dp(10));
        homeBox.addView(heroName, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Dock：5 个固定槽位，左对齐；外层留白容纳 1.10 放大
        LinearLayout dockFrame = new LinearLayout(this);
        dockFrame.setOrientation(LinearLayout.HORIZONTAL);
        dockFrame.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        dockFrame.setClipChildren(false);
        dockFrame.setClipToPadding(false);
        dockFrame.setPadding(dp(2), dp(14), dp(2), dp(14));
        GradientDrawable dockBg = new GradientDrawable();
        dockBg.setColor(0x661A1A1C);
        dockBg.setCornerRadius(dp(24));
        dockBg.setStroke(dp(1), 0x1AFFFFFF);
        dockFrame.setBackground(dockBg);
        homeBox.addView(dockFrame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dockRow = new LinearLayout(this);
        dockRow.setOrientation(LinearLayout.HORIZONTAL);
        dockRow.setGravity(Gravity.START);
        dockRow.setClipChildren(false);
        dockFrame.addView(dockRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---------- 应用页态 ----------
        gridRoot = new FrameLayout(this);
        gridRoot.setVisibility(View.GONE);
        gridRoot.setClipChildren(false);
        gridRoot.setClipToPadding(false);
        contentArea.addView(gridRoot, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(dp(10), dp(10), dp(10), dp(10));
        scroll.setId(R.id.grid_scroll);
        gridRoot.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        gridColumn = new LinearLayout(this);
        gridColumn.setOrientation(LinearLayout.VERTICAL);
        gridColumn.setPadding(dp(6), dp(14), dp(6), dp(14));
        scroll.addView(gridColumn, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 底部渐隐已移除：它会在应用页盖住边缘，造成"被截断"的观感

        setContentView(root);
    }

    private TextView pillButton(String text, int id, View.OnClickListener l) {
        TextView tv = new TextView(this);
        tv.setId(id);
        tv.setText(text);
        tv.setTextColor(TEXT_DIM);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(14), dp(7), dp(14), dp(7));
        tv.setFocusable(true);
        tv.setClickable(true);
        tv.setOnClickListener(l);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x14FFFFFF);
        bg.setCornerRadius(dp(18));
        tv.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(8), 0, 0, 0);
        tv.setLayoutParams(lp);
        tv.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                GradientDrawable g = new GradientDrawable();
                g.setColor(hasFocus ? 0xFFFFFFFF : 0x14FFFFFF);
                g.setCornerRadius(dp(18));
                v.setBackground(g);
                ((TextView) v).setTextColor(hasFocus ? 0xFF000000 : TEXT_DIM);
            }
        });
        return tv;
    }

    // ---------------------------------------------------------------- Dock

    private void buildDock() {
        buildDock(null);
    }

    /** order 非空时按该顺序构建（移动模式用）；否则用仓库里的顺序。 */
    private void buildDock(List<String> order) {
        dockRow.removeAllViews();
        dockTiles.clear();
        List<AppEntry> dock;
        if (order == null) {
            dock = repo.dockApps(DOCK_SLOTS);
        } else {
            dock = new ArrayList<>();
            for (String pkg : order) {
                for (AppEntry e : repo.allApps()) {
                    if (e.pkg.equals(pkg)) { dock.add(e); break; }
                }
            }
            if (dock.size() > DOCK_SLOTS) dock = dock.subList(0, DOCK_SLOTS);
        }
        // 5 个固定槽位：有应用就放卡片，没有就放等宽透明占位 —— 保证与网格列严格对齐、左对齐
        for (int i = 0; i < DOCK_SLOTS; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dockCardH, 1f);
            lp.setMargins(dp(6), 0, dp(6), 0);
            if (i < dock.size()) {
                View tile = makeTile(dock.get(i), true, dockCardW, dockCardH);
                dockRow.addView(tile, lp);
                dockTiles.add(tile);
            } else {
                View placeholder = new View(this);
                placeholder.setFocusable(false);
                placeholder.setClickable(false);
                dockRow.addView(placeholder, lp);
            }
        }
        if (!dockTiles.isEmpty() && !moveMode) dockTiles.get(0).requestFocus();
    }

    // ---------------------------------------------------------------- 应用页网格

    private void buildGrid() {
        buildGrid(null);
    }

    /** order 非空时按该顺序构建（移动模式用）；否则用仓库顺序。 */
    private void buildGrid(List<String> order) {
        gridColumn.removeAllViews();
        gridTiles.clear();
        List<AppEntry> grid;
        if (order == null) {
            grid = repo.gridApps(DOCK_SLOTS);
        } else {
            grid = new ArrayList<>();
            for (String pkg : order) {
                for (AppEntry e : repo.allApps()) {
                    if (e.pkg.equals(pkg)) {
                        grid.add(e);
                        break;
                    }
                }
            }
        }
        if (grid.isEmpty()) {
            gridBuilt = true;
            return;
        }

        // 应用页卡片略小于首页（0.9），保证 3 行完整放入；与 Dock 同为 16:9
        int appW = Math.round(cardW * 0.9f);
        int appH = Math.round(appW * CARD_RATIO);
        int rows = (grid.size() + COLS - 1) / COLS;

        for (int r = 0; r < rows; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            // 关键：左对齐。不满 5 个的最后一行也从第 1 列开始，与上方列严格对齐。
            row.setGravity(Gravity.START);
            row.setClipChildren(false);
            gridColumn.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            for (int c = 0; c < COLS; c++) {
                int index = r * COLS + c;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(appW, appH);
                lp.setMargins(dp(8), dp(14), dp(8), dp(14));
                if (index < grid.size()) {
                    View tile = makeTile(grid.get(index), false, appW, appH);
                    row.addView(tile, lp);
                    gridTiles.add(tile);
                } else {
                    // 空槽位：与卡片同宽，保证列对齐且最后一行不居中
                    View placeholder = new View(this);
                    placeholder.setFocusable(false);
                    placeholder.setClickable(false);
                    row.addView(placeholder, lp);
                }
            }
        }
        setupGridFocus();
        gridBuilt = true;
    }

    private void setupGridFocus() {
        final int n = gridTiles.size();
        final int rowCount = (n + COLS - 1) / COLS;
        for (int i = 0; i < n; i++) {
            View v = gridTiles.get(i);
            int row = i / COLS, col = i % COLS;
            int left = col == 0 ? v.getId() : gridTiles.get(i - 1).getId();
            int right = (col == COLS - 1 || i + 1 >= n || (i + 1) / COLS != row)
                    ? v.getId() : gridTiles.get(i + 1).getId();
            int up = row == 0 ? v.getId() : gridTiles.get(i - COLS).getId();
            int down = (row == rowCount - 1 || i + COLS >= n) ? v.getId()
                    : gridTiles.get(i + COLS).getId();
            v.setNextFocusLeftId(left);
            v.setNextFocusRightId(right);
            v.setNextFocusUpId(up);
            v.setNextFocusDownId(down);
        }
    }

    /** ↓：进入应用页（Dock 隐藏，网格占满）。 */
    private void openApps() {
        if (appsOpen) return;
        appsOpen = true;
        if (!gridBuilt) buildGrid();
        homeBox.setVisibility(View.GONE);
        gridRoot.setVisibility(View.VISIBLE);
        gridRoot.setAlpha(0f);
        gridRoot.setTranslationY(dp(32));
        gridRoot.animate().alpha(1f).translationY(0f)
                .setDuration(180).setInterpolator(new DecelerateInterpolator()).start();
        if (!gridTiles.isEmpty()) gridTiles.get(0).requestFocus();
    }

    /** ↑ / 返回：回到首页。 */
    private void closeApps() {
        if (!appsOpen) return;
        appsOpen = false;
        gridRoot.animate().alpha(0f).translationY(dp(32))
                .setDuration(140).setInterpolator(new DecelerateInterpolator())
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        gridRoot.setVisibility(View.GONE);
                    }
                }).start();
        homeBox.setVisibility(View.VISIBLE);
        if (!dockTiles.isEmpty()) {
            View f = dockTiles.get(0);
            for (View t : dockTiles) {
                Object tag = t.getTag();
                if (tag instanceof AppEntry) {
                    heroName.setText(((AppEntry) tag).label);
                    break;
                }
            }
            f.requestFocus();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (moveMode) {
            int keyCode = event.getKeyCode();
            int action = event.getAction();
            if (action == KeyEvent.ACTION_DOWN) {
                if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                        || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                    commitMove();
                    return true;
                }
                if (keyCode == KeyEvent.KEYCODE_BACK) {
                    cancelMove();
                    return true;
                }
                if (handleMoveKey(keyCode)) return true;
            }
            // 关键：移动模式下把 UP 也吃掉，否则 UP 会落到卡片 click，导致“提交后又启动应用”
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                    || keyCode == KeyEvent.KEYCODE_BACK
                    || keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                    || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    || keyCode == KeyEvent.KEYCODE_DPAD_UP
                    || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            // 菜单键：优先对当前 focus 的卡片弹出操作菜单（含“调整位置”），
            // 没有 focus 卡片时打开桌面设置。
            View f = getCurrentFocus();
            Object tag = f == null ? null : f.getTag();
            if (tag instanceof AppEntry) {
                showTileMenu((AppEntry) tag);
            } else {
                showMainMenu();
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (appsOpen) { closeApps(); return true; }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && !appsOpen) {
            View f = getCurrentFocus();
            if (f != null && dockRow.indexOfChild(f) >= 0) {
                openApps();
                return true;
            }
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP && appsOpen) {
            View f = getCurrentFocus();
            int idx = gridTiles.indexOf(f);
            if (idx >= 0 && idx < COLS) {
                closeApps();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    // ---------------------------------------------------------------- 磁贴

    private View makeTile(final AppEntry e, final boolean isDock, int w, int h) {
        final FrameLayout tile = new FrameLayout(this);
        tile.setFocusable(true);
        tile.setFocusableInTouchMode(false);
        tile.setClickable(true);
        tile.setLongClickable(true);
        // 关键：电视遥控器的长按是"按住 OK 不放"，必须由卡片自己处理
        // OnLongClickListener 在部分固件上会被 click 抢走，这里再加一层按键计时兜底
        tile.setOnLongClickListener(this);
        tile.setOnKeyListener(new View.OnKeyListener() {
            private long downAt = 0L;
            private boolean longFired = false;
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                boolean ok = keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                        || keyCode == KeyEvent.KEYCODE_ENTER
                        || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER;
                if (!ok) return false;
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (event.getRepeatCount() == 0) {
                        downAt = System.currentTimeMillis();
                        longFired = false;
                    } else if (!longFired && System.currentTimeMillis() - downAt > 550) {
                        longFired = true;
                        Object tag = v.getTag();
                        if (tag instanceof AppEntry) {
                            showTileMenu((AppEntry) tag);
                        }
                        return true;
                    }
                }
                return false;
            }
        });
        tile.setBackground(new TileBackground(this));
        tile.setClipChildren(true);
        tile.setTag(e);
        tile.setOnClickListener(this);
        tile.setOnFocusChangeListener(this);

        final ImageView banner = new ImageView(this);
        banner.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        banner.setScaleType(ImageView.ScaleType.FIT_XY);
        // ImageView 无 setFilterBitmap；滤波由 Paint 默认开启（位图缩放时自动滤）
        banner.setVisibility(View.GONE);
        // 关键：不使用 clipToOutline。父卡片 scale(1.10) 时 outline 以未缩放尺寸计算，
        // 部分固件会裁出黑块；改为预渲染圆角 Bitmap，彻底绕开。
        banner.setClipToOutline(false);
        tile.addView(banner);
        final ImageView icon = new ImageView(this);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(iconPx, iconPx);
        ilp.gravity = Gravity.CENTER;
        icon.setLayoutParams(ilp);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setVisibility(View.GONE);
        tile.addView(icon);

        final TextView fallbackLabel = new TextView(this);
        fallbackLabel.setText(e.label);
        fallbackLabel.setTextColor(TEXT_DIM);
        fallbackLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        fallbackLabel.setSingleLine(true);
        fallbackLabel.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.gravity = Gravity.BOTTOM;
        flp.bottomMargin = dp(8);
        fallbackLabel.setVisibility(View.GONE);
        tile.addView(fallbackLabel, flp);

        tile.post(new Runnable() {
            @Override
            public void run() {
                // 用卡片的设计尺寸渲染，而不是 tile.getWidth()：
                // 应用页初始是 GONE，此时 getWidth()=0，会渲染成 1×1 纯色块并污染缓存。
                if (w <= 0 || h <= 0) return;
                Bitmap bmp = icons.loadBannerRounded(e.pkg, e.activity, w, h, dp(RADIUS_DP), e.label);
                if (bmp != null) {
                    banner.setImageBitmap(bmp);
                    banner.setVisibility(View.VISIBLE);
                } else {
                    icon.setImageBitmap(icons.load(e.pkg, e.label, iconPx));
                    icon.setVisibility(View.VISIBLE);
                    fallbackLabel.setVisibility(View.VISIBLE);
                }
            }
        });
        return tile;
    }

    // ---------------------------------------------------------------- 焦点 / 环境光

    @Override
    public void onFocusChange(View v, boolean hasFocus) {
        if (!(v instanceof FrameLayout)) return;
        FrameLayout tile = (FrameLayout) v;
        // 背景由 View 自己的 StateListDrawable（TileBackground）负责，
        // 这里不再重建 drawable —— 重建 + 放大是“卡片变黑”的根因。
        Object tag = tile.getTag();
        if (!(tag instanceof AppEntry)) return;
        final AppEntry e = (AppEntry) tag;

        if (hasFocus) {
            tile.setLayerType(View.LAYER_TYPE_NONE, null);
            tile.animate().scaleX(FOCUS_SCALE).scaleY(FOCUS_SCALE)
                    .setDuration(170).setInterpolator(new DecelerateInterpolator()).start();
            // 只有首页态才更新大标题；应用页态标题区域已隐藏
            if (!appsOpen) {
                heroName.setText(e.label);
                updateAmbient(e);
            }
        } else {
            tile.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
        }
    }

    private void updateAmbient(final AppEntry e) {
        Integer cached = ambientCache.get(e.pkg);
        final int color = cached != null ? cached
                : icons.dominantColor(e.pkg, e.activity, e.label);
        if (cached == null) ambientCache.put(e.pkg, color);
        ambient.post(new Runnable() {
            @Override
            public void run() {
                int w = ambient.getWidth(), h = ambient.getHeight();
                if (w <= 0 || h <= 0) return;
                // 全屏环境光：中心略偏下，半径覆盖到四角，让整块面板都有颜色，消除“黑框”
                int glow = (color & 0x00FFFFFF) | 0x8C000000;   // 约 55% 不透明
                RadialGradient shader = new RadialGradient(
                        w * 0.5f, h * 0.78f, Math.max(w, h) * 0.95f,
                        new int[]{glow, (color & 0x00FFFFFF) | 0x33000000, 0x00000000},
                        new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
                ShapeDrawable sd = new ShapeDrawable();
                sd.getPaint().setShader(shader);
                ambient.setBackground(sd);
                if (ambient.getAlpha() < 0.99f) ambient.animate().alpha(1f).setDuration(220).start();
            }
        });
    }

    // ---------------------------------------------------------------- 点击 / 长按

    @Override
    public void onClick(View v) {
        // 移动模式下任何点击都不应启动应用（双保险）
        if (moveMode) return;
        int id = v.getId();
        if (id == R.id.btn_settings) { showMainMenu(); return; }
        if (id == R.id.btn_all) {
            startActivity(new Intent(this, AllAppsActivity.class));
            overridePendingTransition(0, 0);
            return;
        }
        Object tag = v.getTag();
        if (!(tag instanceof AppEntry)) return;
        AppEntry e = (AppEntry) tag;
        Intent i = e.launchIntent(this);
        if (i == null) {
            Toast.makeText(this, "无法启动：" + e.label, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(i);
            overridePendingTransition(0, 0);
        } catch (Throwable t) {
            Toast.makeText(this, "启动失败：" + e.label, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public boolean onLongClick(View v) {
        Object tag = v.getTag();
        if (tag instanceof AppEntry) {
            showTileMenu((AppEntry) tag);
            return true;
        }
        return false;
    }

    private void showTileMenu(final AppEntry e) {
        // 电视遥控器场景：用大字号列表 + 明确的主操作，不用系统小对话框
        final String[] items = new String[]{
                "◀ 调整位置（方向键移动）",
                "☆ 加入 / 移出常用",
                "◌ 隐藏此应用",
                "ⓘ 应用信息"
        };
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
                .setTitle(e.label)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            startMoveMode(e);
                        } else if (which == 1) {
                            repo.toggleDock(e.pkg);
                            repo.reload();
                            buildDock();
                            gridBuilt = false;
                            if (appsOpen) { gridRoot.setVisibility(View.GONE); appsOpen = false; homeBox.setVisibility(View.VISIBLE); }
                        } else if (which == 2) {
                            repo.setHidden(e.pkg, true);
                            repo.reload();
                            buildDock();
                            gridBuilt = false;
                            if (appsOpen) { gridRoot.setVisibility(View.GONE); appsOpen = false; homeBox.setVisibility(View.VISIBLE); }
                        } else {
                            try {
                                Intent i = new Intent(
                                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                                i.setData(android.net.Uri.parse("package:" + e.pkg));
                                startActivity(i);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }).show();
    }

    // ---------------------------------------------------------------- 移动模式

    /** 进入移动模式：当前页内自由排序，OK 提交，返回取消。 */
    private void startMoveMode(AppEntry e) {
        List<View> pageTiles = appsOpen ? gridTiles : dockTiles;
        int idx = -1;
        for (int i = 0; i < pageTiles.size(); i++) {
            Object tag = pageTiles.get(i).getTag();
            if (tag instanceof AppEntry && ((AppEntry) tag).pkg.equals(e.pkg)) { idx = i; break; }
        }
        if (idx < 0) return;

        moveMode = true;
        moveInDock = !appsOpen;
        moveFrom = idx;
        moveTo = idx;
        moveOrder = new ArrayList<>();
        for (View v : pageTiles) {
            Object tag = v.getTag();
            if (tag instanceof AppEntry) moveOrder.add(((AppEntry) tag).pkg);
        }
        movePrevFocus = idx;
        showMoveHint();
        applyMoveVisual(true);
        // 焦点保持在被移动卡片上，避免方向键跑到顶部按钮
        pageTiles.get(idx).requestFocus();
    }

    /** 移动模式底部面板：方向键示意 + 当前位置 + 确认/取消。 */
    private void showMoveHint() {
        if (movePanel == null) {
            movePanel = new LinearLayout(this);
            movePanel.setOrientation(LinearLayout.VERTICAL);
            movePanel.setGravity(Gravity.CENTER);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xF0121214);
            bg.setCornerRadius(dp(18));
            bg.setStroke(dp(1), 0x33FFFFFF);
            movePanel.setBackground(bg);
            movePanel.setPadding(dp(28), dp(14), dp(28), dp(14));

            moveTitle = new TextView(this);
            moveTitle.setTextColor(0xFFFFFFFF);
            moveTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            moveTitle.setGravity(Gravity.CENTER);
            moveTitle.setSingleLine(true);
            movePanel.addView(moveTitle, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            moveKeys = new TextView(this);
            moveKeys.setTextColor(0xB3FFFFFF);
            moveKeys.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            moveKeys.setGravity(Gravity.CENTER);
            moveKeys.setPadding(0, dp(6), 0, dp(2));
            movePanel.addView(moveKeys, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            moveActions = new TextView(this);
            moveActions.setText("OK 确认    ·    返回 取消");
            moveActions.setTextColor(0x8CFFFFFF);
            moveActions.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            moveActions.setGravity(Gravity.CENTER);
            moveActions.setPadding(0, dp(6), 0, 0);
            movePanel.addView(moveActions, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            FrameLayout root = (FrameLayout) getWindow().getDecorView().findViewById(android.R.id.content);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            // 放在屏幕中上方，不遮挡 Dock；移动时视线不用来回跳
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.topMargin = dp(120);
            root.addView(movePanel, lp);
        }
        updateMovePanel();
        movePanel.setVisibility(View.VISIBLE);
        movePanel.setAlpha(0f);
        movePanel.animate().alpha(1f).setDuration(160).start();
    }

    private void updateMovePanel() {
        if (movePanel == null) return;
        String page = moveInDock ? "底部常用（Dock）" : "应用页";
        String name = "";
        List<View> pageTiles = moveInDock ? dockTiles : gridTiles;
        if (moveTo >= 0 && moveTo < pageTiles.size()) {
            Object tag = pageTiles.get(moveTo).getTag();
            if (tag instanceof AppEntry) name = ((AppEntry) tag).label;
        }
        moveTitle.setText("移动：" + name);
        int col = moveInDock ? moveTo : (moveTo % COLS);
        int row = moveInDock ? 0 : (moveTo / COLS);
        String pos = moveInDock
                ? ("第 " + (moveTo + 1) + " / " + pageTiles.size() + " 位")
                : ("第 " + (row + 1) + " 行 · 第 " + (col + 1) + " 列  （" + (moveTo + 1) + "/" + pageTiles.size() + "）");
        moveKeys.setText("← → 左右移动" + (moveInDock ? "" : "    ↑ ↓ 上下移动") + "    当前：" + pos);
    }

    /** 移动模式视觉：被移动卡片高亮，其他卡片变暗。 */
    private void applyMoveVisual(boolean on) {
        List<View> pageTiles = moveInDock ? dockTiles : gridTiles;
        for (int i = 0; i < pageTiles.size(); i++) {
            View v = pageTiles.get(i);
            if (!(v instanceof FrameLayout)) continue;
            boolean moving = i == moveTo;
            v.animate().alpha(on ? (moving ? 1f : 0.5f) : 1f).setDuration(120).start();
            if (on && moving) {
                v.setScaleX(1.08f);
                v.setScaleY(1.08f);
            } else if (!on) {
                v.setScaleX(1f);
                v.setScaleY(1f);
            }
        }
    }

    /** 移动模式下方向键：交换位置并即时预览。 */
    private boolean handleMoveKey(int keyCode) {
        List<View> pageTiles = moveInDock ? dockTiles : gridTiles;
        int size = pageTiles.size();
        if (size <= 1) return true;
        int target = moveTo;
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) target = Math.max(0, moveTo - 1);
        else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) target = Math.min(size - 1, moveTo + 1);
        else if (keyCode == KeyEvent.KEYCODE_DPAD_UP && !moveInDock) target = Math.max(0, moveTo - COLS);
        else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && !moveInDock) target = Math.min(size - 1, moveTo + COLS);
        else if (keyCode == KeyEvent.KEYCODE_DPAD_UP && moveInDock) return true;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && moveInDock) return true;
        else return false;

        if (target != moveTo && moveOrder != null) {
            String pkg = moveOrder.remove(moveTo);
            moveOrder.add(target, pkg);
            moveTo = target;
            rebuildMovePreview();
        }
        return true;
    }

    private void rebuildMovePreview() {
        if (moveInDock) buildDock(moveOrder);
        else { buildGrid(moveOrder); gridRoot.setVisibility(View.VISIBLE); }
        applyMoveVisual(true);
        if (movePanel != null) { updateMovePanel(); movePanel.setVisibility(View.VISIBLE); }
    }

    private void commitMove() {
        if (!moveMode) return;
        if (moveInDock) repo.moveInDock(moveFrom, moveTo);
        else repo.moveInGrid(moveFrom, moveTo);
        moveMode = false;
        if (movePanel != null) movePanel.setVisibility(View.GONE);
        repo.reload();
        if (moveInDock) {
            buildDock();
            gridBuilt = false;
            if (dockTiles.size() > moveTo) dockTiles.get(moveTo).requestFocus();
        } else {
            buildGrid();
            gridRoot.setVisibility(View.VISIBLE);
            if (gridTiles.size() > moveTo) gridTiles.get(moveTo).requestFocus();
        }
        applyMoveVisual(false);
    }

    private void cancelMove() {
        if (!moveMode) return;
        moveMode = false;
        if (movePanel != null) movePanel.setVisibility(View.GONE);
        if (moveInDock) buildDock(); else buildGrid();
        applyMoveVisual(false);
        List<View> pageTiles = moveInDock ? dockTiles : gridTiles;
        if (movePrevFocus >= 0 && movePrevFocus < pageTiles.size()) {
            pageTiles.get(movePrevFocus).requestFocus();
        }
    }

    private void showDockPicker() {
        final List<AppEntry> all = repo.visibleApps();
        final java.util.Set<String> dock = repo.dockSet();
        String[] names = new String[all.size()];
        boolean[] checked = new boolean[all.size()];
        for (int i = 0; i < all.size(); i++) {
            names[i] = (dock.contains(all.get(i).pkg) ? "✓ " : "   ") + all.get(i).label;
            checked[i] = dock.contains(all.get(i).pkg);
        }
        new AlertDialog.Builder(this)
                .setTitle("常用应用（底部 4 个）")
                .setMultiChoiceItems(names, checked, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which, boolean isChecked) {
                        String pkg = all.get(which).pkg;
                        if (isChecked) dock.add(pkg); else dock.remove(pkg);
                    }
                })
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        repo.setDock(new ArrayList<>(dock));
                        repo.reload();
                        buildDock();
                        gridBuilt = false;
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showMainMenu() {
        final String[] items = new String[]{
                "刷新应用列表",
                appsOpen ? "收起应用页" : "展开应用页",
                repo.cleanEnabled() ? "进入时清理：开" : "进入时清理：关",
                "清理一次聚好看",
                "恢复聚好看",
                "编辑常用（Dock）",
                "显示已隐藏应用",
                "关于"
        };
        new AlertDialog.Builder(this)
                .setTitle("TvHome 设置")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        switch (which) {
                            case 0:
                                repo.reload(); buildDock(); gridBuilt = false; break;
                            case 1:
                                if (appsOpen) closeApps(); else openApps();
                                break;
                            case 2:
                                repo.setCleanEnabled(!repo.cleanEnabled()); break;
                            case 3:
                                killVod(true); break;
                            case 4:
                                restoreVod(); break;
                            case 5:
                                showDockPicker(); break;
                            case 6:
                                showHiddenApps(); break;
                            default:
                                Toast.makeText(MainActivity.this,
                                        "TvHome 4.0 · Aerial 式两页 · 纯框架", Toast.LENGTH_LONG).show();
                                break;
                        }
                    }
                }).show();
    }

    private void showHiddenApps() {
        final List<AppEntry> hidden = repo.hiddenApps();
        if (hidden.isEmpty()) {
            Toast.makeText(this, "没有已隐藏的应用", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[hidden.size()];
        for (int i = 0; i < hidden.size(); i++) names[i] = hidden.get(i).label;
        new AlertDialog.Builder(this)
                .setTitle("已隐藏（点击恢复）")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        repo.setHidden(hidden.get(which).pkg, false);
                        repo.reload();
                        buildDock();
                        gridBuilt = false;
                    }
                }).show();
    }

    // ---------------------------------------------------------------- L0

    private void doCleanIfNeeded() {
        if (!repo.cleanEnabled()) return;
        if (System.currentTimeMillis() - repo.lastClean() < 60_000L) return;
        killVod(false);
    }

    private void killVod(boolean toast) {
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            am.killBackgroundProcesses(KILL_PKG);
            repo.setLastClean(System.currentTimeMillis());
            if (statusDot != null) statusDot.setTextColor(0xFF4CAF50);
            android.util.Log.i(TAG, "L0 killBackgroundProcesses(" + KILL_PKG + ")");
            if (toast) Toast.makeText(this, "已清理聚好看插件进程", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            android.util.Log.w(TAG, "L0 failed", t);
            if (toast) Toast.makeText(this, "清理失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void restoreVod() {
        try {
            Intent i = new Intent("com.ju.main.app.restart");
            i.setComponent(new android.content.ComponentName(KILL_PKG,
                    "com.ju.juui.receiver.StartReceiver"));
            sendBroadcast(i);
            Toast.makeText(this, "已发送恢复聚好看", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(this, "恢复失败", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- 生命周期

    @Override
    protected void onResume() {
        super.onResume();
        updateClock();
        // 从应用返回桌面时也做一次 L0（60 秒去抖，避免频繁清理）
        doCleanIfNeeded();
        if (statusDot != null) {
            boolean fresh = System.currentTimeMillis() - repo.lastClean() < 60_000L;
            statusDot.setTextColor(fresh ? 0xFF4CAF50 : 0x8CFFFFFF);
        }
        if (appsOpen) closeApps();
    }

    private String greetingText() {
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        if (h < 6) return "夜深了";
        if (h < 12) return "早上好";
        if (h < 14) return "中午好";
        if (h < 19) return "下午好";
        return "晚上好";
    }

    private void updateClock() {
        Date now = new Date();
        if (clock != null) clock.setText(timeFmt.format(now));
        if (date != null) date.setText(dateFmt.format(now));
        if (greeting != null) greeting.setText(greetingText());
    }

    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            updateClock();
            ui.postDelayed(this, 30_000L);
        }
    };

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(clockTick);
        if (icons != null) icons.clear();
        super.onDestroy();
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}








