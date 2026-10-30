package com.local.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.StateListDrawable;

/**
 * 卡片背景：用抗锯齿 Paint 绘制圆角矩形，替代 GradientDrawable。
 *
 * GradientDrawable 的圆角在部分低端 GPU / 硬件层缩放时会走位图路径，
 * 边缘出现锯齿（“图标/卡片边缘不光滑”）。这里改为自绘：
 *  - Paint 开 ANTI_ALIAS_FLAG + DITHER_FLAG；
 *  - 焦点描边用 STROKE 抗锯齿绘制，并内缩半个线宽，避免被卡片边界裁成毛边；
 *  - 仍是 StateListDrawable，状态切换不重建对象。
 */
public class TileBackground extends StateListDrawable {

    private final TileDrawable normal;
    private final TileDrawable focused;

    public TileBackground(Context ctx) {
        this(ctx, 0xFF1C1C1E, 0xFF2A2A2E, 0xFFFFFFFF, 16, 3);
    }

    public TileBackground(Context ctx, int normalColor, int focusedColor,
                          int focusStroke, int radiusDp, int strokeDp) {
        float density = ctx.getResources().getDisplayMetrics().density;
        float radius = radiusDp * density;
        float stroke = Math.max(1f, strokeDp * density);

        normal = new TileDrawable(normalColor, 0, 0f, radius);
        focused = new TileDrawable(focusedColor, focusStroke, stroke, radius);

        addState(new int[]{android.R.attr.state_focused}, focused);
        addState(new int[]{android.R.attr.state_pressed}, focused);
        addState(new int[]{}, normal);
    }

    /** 单个状态的抗锯齿圆角矩形。 */
    private static final class TileDrawable extends Drawable {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        private final RectF rect = new RectF();
        private final float radius;
        private final float strokeWidth;
        private final boolean hasStroke;

        TileDrawable(int color, int strokeColor, float strokeWidth, float radius) {
            this.fill.setColor(color);
            this.fill.setStyle(Paint.Style.FILL);
            this.radius = radius;
            this.strokeWidth = strokeWidth;
            this.hasStroke = strokeWidth > 0f;
            if (hasStroke) {
                strokePaint.setColor(strokeColor);
                strokePaint.setStyle(Paint.Style.STROKE);
                strokePaint.setStrokeWidth(strokeWidth);
                strokePaint.setStrokeJoin(Paint.Join.ROUND);
                strokePaint.setStrokeCap(Paint.Cap.ROUND);
            }
        }

        @Override
        public void draw(Canvas canvas) {
            RectF r = rect;
            r.set(getBounds());
            canvas.drawRoundRect(r, radius, radius, fill);
            if (hasStroke) {
                float inset = strokeWidth / 2f;
                r.inset(inset, inset);
                float innerRadius = Math.max(0f, radius - inset);
                canvas.drawRoundRect(r, innerRadius, innerRadius, strokePaint);
            }
        }

        @Override
        public void setAlpha(int alpha) {
            fill.setAlpha(alpha);
            strokePaint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
            fill.setColorFilter(colorFilter);
            strokePaint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }
}
