package com.live2d.demo.minimum;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** Short volume feedback drawn above the GL surface, without the system volume panel. */
final class VolumeOverlayView extends View {
    private static final int BAR_COUNT = 12;
    private static final long VISIBLE_MS = 2400;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path speaker = new Path();
    private final float density;
    private final Runnable dismiss = new Runnable() {
        @Override public void run() {
            animate().alpha(0f).setDuration(260).withEndAction(new Runnable() {
                @Override public void run() { setVisibility(INVISIBLE); }
            }).start();
        }
    };

    private ValueAnimator levelAnimator;
    private float level;
    private int volume;
    private int maximum = 1;

    VolumeOverlayView(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        setVisibility(INVISIBLE);
        setClickable(false);
    }

    void show(int current, int max) {
        removeCallbacks(dismiss);
        animate().cancel();
        if (levelAnimator != null) levelAnimator.cancel();

        volume = current;
        maximum = Math.max(1, max);
        float target = Math.max(0f, Math.min(1f, current / (float) maximum));
        levelAnimator = ValueAnimator.ofFloat(level, target);
        levelAnimator.setDuration(190);
        levelAnimator.setInterpolator(new DecelerateInterpolator());
        levelAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator animation) {
                level = (Float) animation.getAnimatedValue();
                invalidate();
            }
        });
        levelAnimator.start();

        if (getVisibility() != VISIBLE) {
            setAlpha(0f);
            setVisibility(VISIBLE);
        }
        animate().alpha(1f).setDuration(140).start();
        postDelayed(dismiss, VISIBLE_MS);
    }

    void hide() {
        removeCallbacks(dismiss);
        animate().cancel();
        if (levelAnimator != null) levelAnimator.cancel();
        setVisibility(INVISIBLE);
    }

    private float dp(float value) { return value * density; }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = Math.min(dp(392), getWidth() - dp(32));
        float height = dp(78);
        float left = (getWidth() - width) / 2f;
        float top = getHeight() - dp(30) - height;
        float centerY = top + height / 2f;

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xDC121820);
        rect.set(left, top, left + width, top + height);
        canvas.drawRoundRect(rect, dp(24), dp(24), paint);

        float iconX = left + dp(32);
        paint.setColor(Color.WHITE);
        speaker.reset();
        speaker.moveTo(iconX, centerY - dp(7));
        speaker.lineTo(iconX + dp(8), centerY - dp(7));
        speaker.lineTo(iconX + dp(17), centerY - dp(15));
        speaker.lineTo(iconX + dp(17), centerY + dp(15));
        speaker.lineTo(iconX + dp(8), centerY + dp(7));
        speaker.lineTo(iconX, centerY + dp(7));
        speaker.close();
        canvas.drawPath(speaker, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2.5f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        if (volume == 0) {
            canvas.drawLine(iconX + dp(24), centerY - dp(6),
                iconX + dp(34), centerY + dp(6), paint);
            canvas.drawLine(iconX + dp(34), centerY - dp(6),
                iconX + dp(24), centerY + dp(6), paint);
        } else {
            rect.set(iconX + dp(17), centerY - dp(12),
                iconX + dp(31), centerY + dp(12));
            canvas.drawArc(rect, -65, 130, false, paint);
        }
        paint.setStyle(Paint.Style.FILL);

        float barsLeft = left + dp(88);
        float barsRight = left + width - dp(61);
        float stride = (barsRight - barsLeft) / BAR_COUNT;
        for (int i = 0; i < BAR_COUNT; i++) {
            float fraction = Math.max(0f, Math.min(1f, level * BAR_COUNT - i));
            float x = barsLeft + i * stride;
            rect.set(x, centerY - dp(11), x + stride - dp(5), centerY + dp(11));
            paint.setColor(0xFF46515E);
            canvas.drawRoundRect(rect, dp(4), dp(4), paint);
            if (fraction > 0f) {
                rect.right = rect.left + (rect.width() * fraction);
                paint.setColor(0xFFB8F5F0);
                canvas.drawRoundRect(rect, dp(4), dp(4), paint);
            }
        }

        paint.setColor(Color.WHITE);
        paint.setTextSize(dp(18));
        paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
        paint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(Math.round(volume * 100f / maximum) + "%",
            left + width - dp(23), centerY - (paint.ascent() + paint.descent()) / 2f, paint);
    }
}
