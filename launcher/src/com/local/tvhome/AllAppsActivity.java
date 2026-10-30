package com.local.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** 全部应用：5 列网格，与首页 Dock 同 16:9 比例、同圆角、同焦点描边。 */
public class AllAppsActivity extends Activity {
    private static final int COLS = 5;
    private static final float CARD_RATIO = 9f / 16f;

    private List<AppEntry> apps;
    private IconLoader icons;
    private int iconPx;
    private int cardW;
    private int cardH;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppRepository repo = new AppRepository(this);
        repo.reload();
        apps = repo.visibleApps();
        icons = new IconLoader(this);

        int screenW = getResources().getDisplayMetrics().widthPixels;
        int inner = screenW - dp(40) * 2;
        cardW = (inner - dp(12) * COLS) / COLS;
        cardH = Math.round(cardW * CARD_RATIO);
        iconPx = Math.round(Math.min(cardW, cardH) * 0.52f);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF000000);
        root.setPadding(dp(40), dp(26), dp(40), dp(18));

        TextView title = new TextView(this);
        title.setText("全部应用 · " + apps.size());
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        GridView grid = new GridView(this);
        grid.setNumColumns(COLS);
        grid.setVerticalSpacing(dp(16));
        grid.setHorizontalSpacing(dp(0));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setSelector(android.R.color.transparent);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        grid.setPadding(dp(10), dp(16), dp(10), dp(16));
        grid.setAdapter(new AppAdapter());
        grid.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view, int position, long id) {
                AppEntry e = apps.get(position);
                Intent i = e.launchIntent(AllAppsActivity.this);
                if (i == null) {
                    Toast.makeText(AllAppsActivity.this, "无法启动：" + e.label, Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    startActivity(i);
                    overridePendingTransition(0, 0);
                } catch (Throwable t) {
                    Toast.makeText(AllAppsActivity.this, "启动失败：" + e.label, Toast.LENGTH_SHORT).show();
                }
            }
        });
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        glp.topMargin = dp(14);
        root.addView(grid, glp);

        TextView hint = new TextView(this);
        hint.setText("返回键：回首页");
        hint.setTextColor(0xFF667079);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        hint.setGravity(Gravity.END);
        root.addView(hint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(30)));

        setContentView(root);
    }

    private class AppAdapter extends BaseAdapter {
        @Override
        public int getCount() { return apps.size(); }

        @Override
        public Object getItem(int position) { return apps.get(position); }

        @Override
        public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            AppEntry e = apps.get(position);
            FrameLayout cell;
            ImageView banner;
            ImageView icon;
            TextView label;
            if (convertView instanceof FrameLayout) {
                cell = (FrameLayout) convertView;
                banner = (ImageView) cell.getChildAt(0);
                icon = (ImageView) cell.getChildAt(1);
                label = (TextView) cell.getChildAt(2);
            } else {
                cell = new FrameLayout(AllAppsActivity.this);
                cell.setFocusable(true);
                cell.setFocusableInTouchMode(false);
                cell.setClickable(true);
                cell.setClipChildren(true);
                cell.setBackground(new TileBackground(AllAppsActivity.this));

                banner = new ImageView(AllAppsActivity.this);
                banner.setLayoutParams(new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                banner.setScaleType(ImageView.ScaleType.CENTER_CROP);
                banner.setVisibility(View.GONE);
                cell.addView(banner);

                icon = new ImageView(AllAppsActivity.this);
                FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(iconPx, iconPx);
                ilp.gravity = Gravity.CENTER;
                icon.setLayoutParams(ilp);
                icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                icon.setVisibility(View.GONE);
                cell.addView(icon);

                label = new TextView(AllAppsActivity.this);
                label.setTextColor(0xB3FFFFFF);
                label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                label.setGravity(Gravity.CENTER);
                label.setSingleLine(true);
                label.setShadowLayer(dp(4), 0, dp(1), 0xCC000000);
                FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                llp.gravity = Gravity.BOTTOM;
                llp.bottomMargin = dp(8);
                label.setVisibility(View.GONE);
                cell.addView(label, llp);

                cell.setLayoutParams(new GridView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, cardH + dp(24)));
            }
            // banner 预渲染圆角位图；无 banner 回退大图标 + 名称
            Bitmap bmp = icons.loadBannerRounded(e.pkg, e.activity, cardW, cardH, dp(16), e.label);
            if (bmp != null) {
                banner.setImageBitmap(bmp);
                banner.setVisibility(View.VISIBLE);
                icon.setVisibility(View.GONE);
                label.setVisibility(View.GONE);
            } else {
                banner.setVisibility(View.GONE);
                icon.setImageBitmap(icons.load(e.pkg, e.label, iconPx));
                icon.setVisibility(View.VISIBLE);
                label.setText(e.label);
                label.setVisibility(View.VISIBLE);
            }
            return cell;
        }
    }

    @Override
    protected void onDestroy() {
        if (icons != null) icons.clear();
        super.onDestroy();
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}


