package piko.jp135;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * jp24.13.5: classic-style "post sent" banner (plain View overlay on the
 * activity decorView). Pale-blue rounded banner at the top of the screen,
 * blue circle + white check icon, dark text, no action button.
 * All entry points are failsafe: any failure reports done(0) so the
 * caller's coroutine always completes. The Done callback is NEVER fired
 * synchronously from show(); it is always delivered from a main-thread
 * runnable after show() has returned, so the caller's suspend frame is
 * never re-entered.
 */
public final class BannerUi {

    public interface Done {
        /** code: 1 = tapped, 0 = timeout / dismissed / failed. */
        void done(int code);
    }

    // Classic palette (sampled from the classic client reference).
    static final int BG_COLOR = 0xFFE8F5FE;   // pale Twitter blue tint
    static final int BLUE = 0xFF1DA1F2;       // classic Twitter blue
    static final int TEXT_COLOR = 0xFF14171A; // classic near-black
    static final long SHOW_MS = 3000L;        // matches native "Short" duration

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static View sBanner;
    private static ViewGroup sParent;
    private static Done sDone;
    private static boolean sFinished = true;
    private static float sDensity = 1f;

    private static final Runnable TIMEOUT = new Runnable() {
        @Override
        public void run() {
            finish(0, true);
        }
    };

    private BannerUi() {
    }

    public static void show(final Activity act, final String text, final Done done) {
        // Always asynchronous: the callback must not fire inside the
        // caller's (suspended) frame.
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                showOnMain(act, text, done);
            }
        });
    }

    private static void showOnMain(Activity act, String text, Done done) {
        sDone = done;
        sFinished = false;
        try {
            if (act == null || act.isFinishing() || act.isDestroyed()) {
                finish(0, false);
                return;
            }
            View decor = act.getWindow() != null ? act.getWindow().getDecorView() : null;
            if (!(decor instanceof ViewGroup)) {
                finish(0, false);
                return;
            }
            float d = act.getResources().getDisplayMetrics().density;
            sDensity = d;

            LinearLayout banner = new LinearLayout(act);
            banner.setOrientation(LinearLayout.HORIZONTAL);
            banner.setGravity(Gravity.CENTER_VERTICAL);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(BG_COLOR);
            bg.setCornerRadius(14f * d);
            banner.setBackground(bg);
            banner.setElevation(6f * d);
            banner.setPadding((int) (16f * d), (int) (12f * d), (int) (16f * d), (int) (12f * d));
            banner.setClickable(true);
            banner.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish(1, true);
                }
            });

            ImageView icon = new ImageView(act);
            icon.setImageDrawable(new CheckDrawable());
            int iconSize = (int) (24f * d);
            LinearLayout.LayoutParams iconLp =
                    new LinearLayout.LayoutParams(iconSize, iconSize);
            banner.addView(icon, iconLp);

            TextView label = new TextView(act);
            label.setText(text);
            label.setTextColor(TEXT_COLOR);
            label.setTextSize(15f);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            labelLp.leftMargin = (int) (10f * d);
            banner.addView(label, labelLp);

            int top = statusBarHeight(act, decor) + (int) (8f * d);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.TOP;
            int side = (int) (16f * d);
            lp.setMargins(side, top, side, 0);

            sBanner = banner;
            sParent = (ViewGroup) decor;
            sParent.addView(banner, lp);

            banner.setAlpha(0f);
            banner.setTranslationY(-14f * d);
            banner.animate().alpha(1f).translationY(0f).setDuration(220L).start();

            MAIN.postDelayed(TIMEOUT, SHOW_MS);
        } catch (Throwable t) {
            finish(0, false);
        }
    }

    private static int statusBarHeight(Activity act, View decor) {
        try {
            WindowInsets wi = decor.getRootWindowInsets();
            if (wi != null) {
                int top = wi.getSystemWindowInsetTop();
                if (top > 0) {
                    return top;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            int id = act.getResources().getIdentifier("status_bar_height", "dimen", "android");
            if (id > 0) {
                return act.getResources().getDimensionPixelSize(id);
            }
        } catch (Throwable ignored) {
        }
        return (int) (24f * sDensity);
    }

    private static void finish(final int code, boolean animateOut) {
        if (sFinished) {
            return;
        }
        sFinished = true;
        MAIN.removeCallbacks(TIMEOUT);
        final View banner = sBanner;
        final ViewGroup parent = sParent;
        final Done done = sDone;
        sBanner = null;
        sParent = null;
        sDone = null;
        if (banner != null && parent != null) {
            try {
                banner.animate().cancel();
                if (animateOut) {
                    banner.animate()
                            .alpha(0f)
                            .translationY(-10f * sDensity)
                            .setDuration(180L)
                            .withEndAction(new Runnable() {
                                @Override
                                public void run() {
                                    try {
                                        parent.removeView(banner);
                                    } catch (Throwable ignored) {
                                    }
                                }
                            })
                            .start();
                } else {
                    parent.removeView(banner);
                }
            } catch (Throwable ignored) {
            }
        }
        if (done != null) {
            try {
                done.done(code);
            } catch (Throwable ignored) {
            }
        }
    }

    /** Solid blue circle with a white check, drawn in code (no assets). */
    static final class CheckDrawable extends Drawable {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path check = new Path();

        CheckDrawable() {
            fill.setColor(BLUE);
            fill.setStyle(Paint.Style.FILL);
            stroke.setColor(0xFFFFFFFF);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float size = Math.min(b.width(), b.height());
            if (size <= 0f) {
                return;
            }
            float cx = b.exactCenterX();
            float cy = b.exactCenterY();
            canvas.drawCircle(cx, cy, size / 2f, fill);
            float u = size / 24f;
            stroke.setStrokeWidth(2.2f * u);
            check.reset();
            check.moveTo(cx + (7.6f - 12f) * u, cy + (12.4f - 12f) * u);
            check.lineTo(cx + (10.6f - 12f) * u, cy + (15.4f - 12f) * u);
            check.lineTo(cx + (16.6f - 12f) * u, cy + (8.6f - 12f) * u);
            canvas.drawPath(check, stroke);
        }

        @Override
        public void setAlpha(int alpha) {
            fill.setAlpha(alpha);
            stroke.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
