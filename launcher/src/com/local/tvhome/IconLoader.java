package com.local.tvhome;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.util.LruCache;

/**
 * Aerial 式图标渲染：
 *  - 取系统应用图标（自适应图标会自带遮罩）；
 *  - 统一缩放到方形容器；
 *  - 统一按 22.5% 圆角裁剪；
 *  - 结果缓存为 Bitmap（LruCache，上限 1/8 heap）。
 *
 * 说明：Aerial 的 APK 内并没有预置图标库（res 里只有 2 张 PNG），
 * 它展示的彩色图标就是各 App 宿主的系统图标 + 统一圆角容器。
 */
public class IconLoader {
    private static final float CORNER_RATIO = 0.225f;

    /**
     * 超采样倍数：卡片聚焦会放大 1.10，若位图按 1:1 渲染，放大后必然被重采样出锯齿。
     * 按 1.4 倍渲染，聚焦时仍有 1.27 像素/显示像素，边缘圆滑。
     */
    private static final float SUPERSAMPLE = 1.4f;

    private final Context ctx;
    private final PackageManager pm;
    private final LruCache<String, Bitmap> cache;
    private final java.util.HashMap<String, Drawable> drawableCache = new java.util.HashMap<>();

    public IconLoader(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.pm = this.ctx.getPackageManager();
        int max = (int) (Runtime.getRuntime().maxMemory() / 8);
        cache = new LruCache<String, Bitmap>(max) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };
    }

    public Bitmap load(String pkg, String label, int sizePx) {
        String key = pkg + "@" + sizePx;
        Bitmap cached = cache.get(key);
        if (cached != null) return cached;

        Bitmap bmp = null;
        try {
            Drawable d = pm.getApplicationIcon(pkg);
            if (d != null) bmp = renderRounded(d, sizePx);
        } catch (Throwable ignored) {
        }
        if (bmp == null) bmp = placeholder(label, sizePx);
        cache.put(key, bmp);
        return bmp;
    }

    /**
     * 取应用 banner（或图标）的主色，用于背景环境光。
     * 只采样 16×16，结果由调用方缓存，避免反复解码。
     */
    public int dominantColor(String pkg, String activityName, String label) {
        Drawable d = newBanner(pkg, activityName);
        if (d == null) d = loadDrawable(pkg);
        if (d == null) {
            try {
                return placeholderColor(label);
            } catch (Throwable t) {
                return 0xFF2A2A2E;
            }
        }
        try {
            Bitmap bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            d.setBounds(0, 0, 16, 16);
            d.draw(c);
            long r = 0, g = 0, b = 0, n = 0;
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    int px = bmp.getPixel(x, y);
                    int a = (px >>> 24);
                    if (a < 40) continue;                 // 透明
                    int rr = (px >> 16) & 0xFF, gg = (px >> 8) & 0xFF, bb = px & 0xFF;
                    int lum = (rr * 299 + gg * 587 + bb * 114) / 1000;
                    if (lum < 18 || lum > 245) continue;   // 近黑/近白不参与
                    r += rr; g += gg; b += bb; n++;
                }
            }
            bmp.recycle();
            if (n == 0) return 0xFF2A2A2E;
            int rr = (int) (r / n), gg = (int) (g / n), bb = (int) (b / n);
            // 稍微提亮，避免环境光太脏
            rr = Math.min(255, (int) (rr * 1.15));
            gg = Math.min(255, (int) (gg * 1.15));
            bb = Math.min(255, (int) (bb * 1.15));
            return 0xFF000000 | (rr << 16) | (gg << 8) | bb;
        } catch (Throwable t) {
            return 0xFF2A2A2E;
        }
    }

    private int placeholderColor(String label) {
        int h = label == null ? 0 : label.hashCode();
        int r = 40 + Math.abs(h % 60);
        int g = 40 + Math.abs((h / 7) % 60);
        int b = 40 + Math.abs((h / 13) % 60);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    public synchronized Drawable loadDrawable(String pkg) {
        Drawable d = drawableCache.get(pkg);
        if (d != null) return d;
        try {
            d = pm.getApplicationIcon(pkg);
        } catch (Throwable ignored) {
            d = null;
        }
        if (d != null) drawableCache.put(pkg, d);
        return d;
    }

    /**
     * App 的 TV Banner（320×180 横幅）。tvhome-zh 的“大彩色块”就是它。
     * 双路径与 tvhome-zh 一致：activityInfo.loadBanner() → applicationInfo.loadBanner()。
     *
     * 注意：这里**不缓存 Drawable 实例**。Banner drawable 有隐式 bounds 状态，
     * 多处并发 setBounds + draw 会互相踩，最终表现为“卡片变成纯色块”。
     * 每次渲染都重新取一个独立实例（系统会缓存资源，开销可控）。
     */
    public synchronized Drawable newBanner(String pkg, String activityName) {
        Drawable d = null;
        try {
            if (activityName != null) {
                android.content.pm.ActivityInfo ai = pm.getActivityInfo(
                        new android.content.ComponentName(pkg, activityName), 0);
                d = ai.loadBanner(pm);
            }
        } catch (Throwable ignored) {
        }
        if (d == null) {
            try {
                android.content.pm.ApplicationInfo app = pm.getApplicationInfo(pkg, 0);
                d = app.loadBanner(pm);
            } catch (Throwable ignored) {
            }
        }
        return d;
    }

    /**
     * 预渲染「圆角 banner Bitmap」：
     *  - 解决父容器 scale 时 clipToOutline 裁出黑块的问题；
     *  - 每次使用全新的 Drawable 实例渲染，避免 bounds 互相覆盖；
     *  - 用 BitmapShader + 抗锯齿 Paint 绘制圆角（clipPath 无抗锯齿，边缘会锯齿）；
     *  - CENTER_CROP 语义：按比例填满再居中裁剪。
     */
    public synchronized Bitmap loadBannerRounded(String pkg, String activityName,
                                                 int w, int h, int radiusPx, String label) {
        // 防御：尺寸过小（未布局）时不要渲染，否则会得到 1×1 纯色并污染缓存
        if (w < 24 || h < 16) return null;
        // 超采样渲染：避免聚焦放大 1.10 后被重采样出锯齿
        int rw = Math.round(w * SUPERSAMPLE);
        int rh = Math.round(h * SUPERSAMPLE);
        int rr = Math.round(radiusPx * SUPERSAMPLE);
        String key = "banner@" + pkg + "/" + activityName + "@" + w + "x" + h + "r" + radiusPx;
        Bitmap cached = cache.get(key);
        if (cached != null) return cached;
        Drawable b = newBanner(pkg, activityName);
        if (b == null) return null;

        // 1) 先把 banner 按 CENTER_CROP 画进超采样位图
        Bitmap raw = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888);
        Canvas rc = new Canvas(raw);
        int iw = b.getIntrinsicWidth();
        int ih = b.getIntrinsicHeight();
        if (iw > 0 && ih > 0) {
            float scale = Math.max((float) rw / iw, (float) rh / ih);
            int dw = Math.round(iw * scale);
            int dh = Math.round(ih * scale);
            int left = (rw - dw) / 2, top = (rh - dh) / 2;
            b.setBounds(left, top, left + dw, top + dh);
        } else {
            b.setBounds(0, 0, rw, rh);
        }
        b.draw(rc);

        // 2) BitmapShader + 抗锯齿 Paint 画圆角（clipPath 无抗锯齿，边缘会锯齿）
        Bitmap out = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG | Paint.FILTER_BITMAP_FLAG);
        paint.setShader(new android.graphics.BitmapShader(
                raw, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP));
        float r = Math.min(rr, Math.min(rw, rh) / 2f);
        c.drawRoundRect(new RectF(0, 0, rw, rh), r, r, paint);
        raw.recycle();

        cache.put(key, out);
        return out;
    }


    private Bitmap renderRounded(Drawable d, int size) {
        int s = Math.round(size * SUPERSAMPLE);
        Bitmap raw = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas rc = new Canvas(raw);
        drawInside(d, rc, s);

        Bitmap out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG | Paint.FILTER_BITMAP_FLAG);
        paint.setShader(new android.graphics.BitmapShader(
                raw, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP));
        float r = s * CORNER_RATIO;
        c.drawRoundRect(new RectF(0, 0, s, s), r, r, paint);
        raw.recycle();
        return out;
    }

    /**
     * 把图标等比居中画进方形容器：
     *  - 自适应图标按原尺寸铺满（自带遮罩）；
     *  - 其它图标取 intrinsic 尺寸，长边不超过容器的 92%，避免被裁边。
     */
    private void drawInside(Drawable d, Canvas c, int size) {
        int iw = d.getIntrinsicWidth();
        int ih = d.getIntrinsicHeight();
        if (iw <= 0 || ih <= 0) {
            d.setBounds(0, 0, size, size);
            d.draw(c);
            return;
        }
        float max = size * 0.92f;
        float scale = Math.min(max / iw, max / ih);
        // 小图标不要放大太多（避免糊）
        scale = Math.min(scale, Math.max(1f, (float) size / Math.max(iw, ih)));
        int w = Math.round(iw * scale);
        int h = Math.round(ih * scale);
        int left = (size - w) / 2;
        int top = (size - h) / 2;
        d.setBounds(left, top, left + w, top + h);
        d.draw(c);
    }

    /** “全部应用”入口：2×2 小白块，保持纯图标语言。 */
    public Bitmap loadAllApps(int size) {
        String key = "@allapps@" + size;
        Bitmap cached = cache.get(key);
        if (cached != null) return cached;

        int s = Math.round(size * SUPERSAMPLE);
        Bitmap out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        bg.setColor(0xFF23272D);
        float r = s * CORNER_RATIO;
        c.drawRoundRect(new RectF(0, 0, s, s), r, r, bg);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setColor(0xFFE6E9EE);
        float cell = s * 0.17f;
        float gap = s * 0.09f;
        float start = s / 2f - cell - gap / 2f;
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 2; col++) {
                float x = start + col * (cell + gap);
                float y = start + row * (cell + gap);
                c.drawRoundRect(new RectF(x, y, x + cell, y + cell),
                        cell * 0.28f, cell * 0.28f, p);
            }
        }
        cache.put(key, out);
        return out;
    }

    private Bitmap placeholder(String label, int size) {
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Path path = new Path();
        float r = size * CORNER_RATIO;
        path.addRoundRect(new RectF(0, 0, size, size), r, r, Path.Direction.CW);
        c.save();
        c.clipPath(path);
        c.drawColor(0xFF23272D);
        String ch = (label == null || label.length() == 0) ? "?" :
                label.substring(0, 1);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFE6E9EE);
        p.setTextSize(size * 0.42f);
        p.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        p.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = p.getFontMetrics();
        float baseline = size / 2f - (fm.ascent + fm.descent) / 2f;
        c.drawText(ch, size / 2f, baseline, p);
        c.restore();
        return out;
    }

    public void clear() {
        cache.evictAll();
    }
}




