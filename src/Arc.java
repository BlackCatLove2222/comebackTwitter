package piko.jp15;

import android.app.Activity;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.FrameLayout;

import java.lang.ref.WeakReference;

/**
 * jp15 EXPERIMENTAL: overlay pull-to-refresh spinner.
 *
 * Renders the refresh indicator as a plain View on the current
 * Activity's decorView, OUTSIDE the header's 28dp logo slot: a
 * circular badge carrying an accent arc, floating over the timeline.
 * The header bird itself (slot Lottie, stock-size bird since jp15.3)
 * is never touched.
 *
 * Feeds (all plain-code single call sites, jp8-proven categories):
 *   H1 MainActivity.onStart            -> attach(Activity)
 *   H2 refresh/g.invokeSuspend entry   -> pull(l, fraction)   [first entry only]
 *   H3 refresh/m.invokeSuspend (jp8 site, next to Snd.edge) -> edge(active)
 *
 * Motion between feeds is produced by a Choreographer frame loop that
 * reads refresh/l's Animatables (a = drag fraction, b = frame value)
 * directly, the same values the logo Lottie consumes, so spring-back
 * is tracked even though g stops emitting after finger-up.
 *
 * Every entry point swallows Throwable: worst case the spinner
 * silently never appears; the helper can never crash the app.
 *
 * jp15.4 motion model (REFERENCE-VIDEO ground truth, measured frame
 * by frame from the classic-Twitter recording the user supplied;
 * see jp15.4/research/REFERENCE_ANALYSIS.md): the classic indicator
 * is an AndroidX SwipeRefreshLayout spinner -- a white circular
 * badge (soft shadow) with a theme-accent Material arc on top. All
 * positions below are relative to the TAB BAR'S BOTTOM EDGE in a
 * static scene (the classic scene never moves; 12.41's own column
 * shift during a pull is a separate, pre-existing behaviour that
 * this helper does not track -- during HOLD the scene is at rest
 * and the position is exact):
 *  - appearance: once the pull is well underway (fraction >= 0.40),
 *    the badge is simply THERE at tabBottom + 81dp, full size, no
 *    fade/scale; only the arc's sweep grows from there;
 *  - pull: the center follows a SATURATING curve
 *    tabBottom + 81 + 70 x (1 - (1-f)^2), max tabBottom + 151dp.
 *    NOT gravity (the jp15.1 gravity model is removed: the real
 *    thing is an ease-out follow with a cap, which is why the
 *    gravity version never "felt" like falling). The arc does NOT
 *    rotate during pull; its sweep grows ~40deg -> 270deg with drag;
 *  - release: the badge RISES ~50dp over ~0.2s (ease-out) to a FIXED
 *    hold position, tabBottom + 101dp, independent of pull depth;
 *  - hold: Material cycle ~1.3s -- sweep oscillates
 *    270 -> 20 -> 270 (grow/shrink) while rotating ~360deg
 *    clockwise per cycle;
 *  - exit: instant disappearance when the refresh completes.
 *
 * Retired in jp15.4 (no longer part of the model): the gravity
 * integrator (GRAVITY_DP_S2, velocity state), and the bird-relative
 * anchors (HEADER_CENTER_DP / REST_BELOW_DP / BIRD_HALF_DP /
 * BIRD_MARGIN_DP) -- the badge's geometry is tab-relative now and
 * has no relation to the bird. HOLD_REV_MS is replaced by the
 * unified Material cycle HOLD_CYCLE_MS. The completion-edge exit
 * changed from jp15.3's grace handover to the reference's instant
 * detach (the same edge drives jp8's completion "pop", whose
 * timing the user has confirmed).
 *
 * jp16 POLISH (rendering quality ONLY -- the user confirmed the
 * jp15.4 motion on device but called the badge/arc "cheap"
 * looking): the flat translucent offset disc that faked the badge
 * shadow is replaced by a REAL soft shadow -- a black disc
 * pre-rendered ONCE into an offscreen bitmap through a
 * BlurMaskFilter (re-rendered only if the pixel geometry ever
 * changes, i.e. in practice once per attach/density) and blitted
 * under the badge each frame. A pre-rendered bitmap was chosen
 * over Paint.setShadowLayer because shadow layers are only
 * guaranteed for text/bitmap drawing under hardware acceleration
 * (a filled circle's shadow silently vanishes on many devices);
 * the bitmap renders identically on HW and SW canvases and costs
 * one drawBitmap per frame -- no per-frame blur, no per-frame
 * allocation. The ring paint already had ANTI_ALIAS_FLAG +
 * Cap.ROUND (the MaterialProgressDrawable look) since jp15, so
 * the stroke itself is unchanged; every motion/geometry knob
 * (TAB_BASE_DP, APPEAR_DP, TRAVEL_DP, SHOW_FRACTION, sweeps,
 * RELEASE_MS, HOLD position/cycle, RING_DP, STROKE_DP, BADGE_DP,
 * badge/ring colors) is byte-identical to jp15.4.
 *
 * jp18 TEXTURE (rendering only; the user picked the classic app's
 * "substantial" feel over jp15.4's harder look, after jp16's softer
 * shadow still read as cheap): replicate the classic spinner's own
 * parameters, taken from the Twitter 8.65 APK's
 * CircularProgressDrawable (obfuscated widget/b + Ring b$c) and
 * cross-checked against the reference frames pixel by pixel:
 *  - RING GEOMETRY CORRECTED: jp15.4's REFERENCE_ANALYSIS claimed
 *    ring outer 52px / stroke 5-6px (33dp / 3.4dp) -- that was a
 *    crop-scale error in the analysis. Direct measurement of
 *    frames f_053/f_058/f_061 gives ring outer diameter 28-29 video
 *    px and stroke core ~4px; at the analysis's own 0.625 dp/px
 *    that is ~18dp / ~2.5dp, matching the drawable's nominal
 *    DEFAULT set (centerRadius 7.5dp, strokeWidth 2.5dp -> outer
 *    diameter 17.5dp) that SwipeRefreshLayout selects via
 *    setStyle(1). RING_DP 33 -> 18, STROKE_DP 3.4 -> 2.5. The ring
 *    now floats in the badge with the classic's wide white margin
 *    instead of nearly filling it.
 *  - ARROWHEAD added, pull phase only, exactly per the drawable's
 *    Ring.drawTriangle: a filled triangle of width 10dp (radial)
 *    x height 5dp (tangential), scaled by the pull progress (the
 *    classic's setArrowScale(min(1, max(0, drag-0.4) x 5/3)) is
 *    driven here by the same normalized progress that grows the
 *    sweep), sitting at the arc's leading (end) point: base
 *    midpoint at the ring's OUTER radius, offset stroke/2 along
 *    the clockwise tangent, apex a further height along the
 *    tangent. Frame evidence: deep-pull f_058 shows the triangle
 *    mass at the arc's bottom-left end (radial span ~4.5-11dp,
 *    tangential ~5dp at sweep ~230deg); hold frames f_084/f_096/
 *    f_112/f_118 show a uniform stroke in every 30-degree sector
 *    -- NO arrow while spinning, so HOLD draws none.
 *  - SHADOW retuned toward the classic CircleImageView elevation
 *    (~4dp): the frame diff shows a darkening tail reaching
 *    ~6-7dp below the badge at ~5-10% -- longer than jp16's 2dp
 *    blur produced. Blur fraction 0.10 -> 0.175 (~3.5dp), alpha
 *    0x42 -> 0x4A, offset 1.5dp -> 2.0dp.
 * Every MOTION parameter (TAB_BASE_DP, APPEAR_DP, TRAVEL_DP,
 * SHOW_FRACTION, sweep formulas, RELEASE_MS, HOLD position/cycle,
 * BADGE_DP, colors) is untouched.
 */
public final class Arc {

    // ================= tuning knobs (jp15.4, reference-measured) ====
    /** Tab bar's bottom edge below the status bar's bottom edge, dp.
     *  CONVENTION (unchanged since jp15.2): every Y in this helper
     *  is INSET-RELATIVE -- measured from the BOTTOM edge of the
     *  status bar; absolute screen Y = insetPx + yDp x density.
     *  VALUE MEASURED on the user's device (not assumed): in the
     *  rest-state jp12 screenshot the slot bird's glyph is exactly
     *  10.76dp tall (Lottie path 184.54 units x group scale 50% /
     *  240 canvas x 28dp slot) and measures 22px, i.e. 2.045 px/dp;
     *  its center (== slot center; group anchor maps bbox center to
     *  canvas center) sits 158.5px above the tab underline's bottom
     *  edge = 77.5dp; 28 (slot center) + 77.5 = 105.5 -> 105.
     *  Cross-checked against the jp15.2 mid-pull screenshot
     *  (consistent at pull-scale ~1.5). This refutes the analysis's
     *  TAB_DP=48 assumption (which implied 76). THE calibration
     *  knob: if the hold depth is off on device, this one number
     *  shifts the whole choreography. */
    private static final float TAB_BASE_DP = 105f;
    /** Appearance depth: center below the tab bottom when the badge
     *  appears, dp (reference: +81). */
    private static final float APPEAR_DP = 81f;
    /** Pull travel along the saturating curve, dp (reference:
     *  appearance -> saturation = +70, max tabBottom + 151). */
    private static final float TRAVEL_DP = 70f;
    /** Hold depth: center below the tab bottom while refreshing,
     *  dp (reference: +101, fixed regardless of pull depth). */
    private static final float HOLD_BELOW_TAB_DP = 101f;
    /** Pull fraction at/above which the badge session starts
     *  (user-specified in jp15.3: hidden at the start of the pull). */
    private static final float SHOW_FRACTION = 0.40f;
    /** Pull-phase arc sweep at the appearance fraction, degrees. */
    private static final float SWEEP_PULL_MIN = 40f;
    /** Pull-phase arc sweep at full pull, degrees. */
    private static final float SWEEP_PULL_MAX = 270f;
    /** Release -> hold tween duration, ms (reference: ~0.15-0.25s
     *  rise, ease-out). */
    private static final long RELEASE_MS = 200L;
    /** Hold Material cycle period, ms (sweep grow/shrink + one full
     *  clockwise rotation per cycle; reference: ~1.3s). */
    private static final long HOLD_CYCLE_MS = 1300L;
    /** Badge (disc) diameter, dp (reference: ~40 = SwipeRefreshLayout
     *  standard). */
    private static final float BADGE_DP = 40f;
    /** Overlay view box, dp: badge + shadow margin (the badge itself
     *  is BADGE_DP; the box only frames it, center-aligned). */
    private static final float SIZE_DP = 46f;
    /** Ring OUTER diameter, dp. jp18 CORRECTION: jp15.4's analysis
     *  said ~33 (52 video px) -- a crop-scale error. Direct frame
     *  measurement: outer 28-29px ~ 18dp at 0.625 dp/px; the
     *  classic drawable's nominal outer diameter is 17.5dp
     *  (centerRadius 7.5 + stroke 2.5/2). One-knob reversible. */
    private static final float RING_DP = 18f;
    /** Ring stroke, dp (frame core ~4px ~ 2.5dp; classic nominal
     *  strokeWidth 2.5dp). jp15.4-jp17 used 3.4. */
    private static final float STROKE_DP = 2.5f;
    /** Arrowhead width (radial), dp -- the classic drawable's
     *  arrowWidth for the style SwipeRefreshLayout selects
     *  (setSizeParameters via setStyle(1): 10dp). jp18. */
    private static final float ARROW_W_DP = 10f;
    /** Arrowhead height (tangential), dp -- classic arrowHeight
     *  5dp. jp18. */
    private static final float ARROW_H_DP = 5f;
    /** Badge fill, light theme (reference badge is white). */
    private static final int BADGE_COLOR_LIGHT = 0xFFFFFFFF;
    /** Badge fill, night/unknown theme: a raised-disc tone against
     *  the Dim theme's #15202B background. */
    private static final int BADGE_COLOR_DARK = 0xFF22303C;
    /** Badge shadow strength: alpha of the pre-rendered blurred
     *  shadow bitmap. jp16 used 0x42 (~2dp-elevation feel); jp18
     *  raises it to 0x4A as part of matching the classic badge's
     *  ~4dp-elevation shadow (frame-measured darkening tail
     *  ~6-7dp below the badge at ~5-10%). */
    private static final int SHADOW_ALPHA = 0x4A;
    /** Shadow blur radius as a fraction of the badge radius in px
     *  (jp16: 0.10 = ~2dp of blur; jp18: 0.175 = ~3.5dp, for the
     *  classic elevation shadow's longer penumbra). */
    private static final float SHADOW_BLUR_FRACTION = 0.175f;
    /** Hard cap for the HOLD phase, ms (failsafe if edges are lost). */
    private static final long HOLD_FAILSAFE_MS = 20000L;
    /** PULL freeze backstop, ms: if l.a/l.b are bitwise frozen longer
     *  than this while pulling (collectors died, e.g. composition
     *  disposed mid-gesture), hide. A perfectly steady >5s finger hold
     *  can false-trigger this; the arc then reappears on the next feed
     *  or refresh edge. */
    private static final long FREEZE_MS = 5000L;
    /** Status-bar height fallback when insets are unavailable, dp. */
    private static final float FALLBACK_STATUS_DP = 24f;
    private static final float EPS = 0.001f;
    private static final int BLUE = 0xFF1D9BF0;

    // ================= state ==========================================
    private static final int PHASE_HIDDEN = 0;
    private static final int PHASE_PULL = 1;
    private static final int PHASE_HOLD = 2;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static WeakReference<Activity> sAct;
    private static View sView;
    private static ArcDrawable sDr;

    private static int sPhase = PHASE_HIDDEN;
    private static com.x.home.tabbed.refresh.l sL;
    private static float sFeedFrac = 0f;
    private static float sLastA = -1f;
    private static float sLastB = -1f;
    private static long sLastChangeMs = 0L;
    private static long sHoldStartMs = 0L;
    /** Current displayed center Y, dp below the status inset; set
     *  directly from the saturating curve in PULL, tweened in HOLD.
     *  -1 = uninitialised. */
    private static float sDispYDp = -1f;
    /** Release tween: start Y (dp) and start time (uptime ms). The
     *  tween is active while uptime < sTweenStartMs + RELEASE_MS. */
    private static float sTweenFromDp = -1f;
    private static long sTweenStartMs = 0L;
    private static boolean sLoopPosted = false;
    /** Badge fill chosen from the attached Activity's uiMode at
     *  attach time (light -> white; night/unknown -> dark tone). */
    private static int sBadgeColor = BADGE_COLOR_DARK;
    /** Cached status inset, px (jp15.2); <= 0 = no real measurement
     *  yet. Written ONLY when a real source (window insets or the
     *  system dimen) contributed a positive value, so a fallback-only
     *  result is re-measured on the next frame -- an inset that was 0
     *  or unavailable at first read (jp15.1's failure) self-corrects
     *  as soon as a real value shows up. Reset whenever attach()
     *  observes a different Activity (cache is per attach). */
    private static float sInsetPx = -1f;

    private Arc() {}

    // ================= entry points (hooks) ===========================

    /** H1: supply the current Activity. Zero register writes at the
     *  call site; p0 is only read. Also (jp15.4) samples the theme
     *  for the badge fill while we have the Activity. */
    public static void attach(Activity a) {
        try {
            if (a == null) return;
            Activity old = sAct != null ? sAct.get() : null;
            if (old != a) {
                sAct = new WeakReference<Activity>(a);
                sInsetPx = -1f;
                dropView();
            }
            try {
                int night = a.getResources().getConfiguration().uiMode
                        & Configuration.UI_MODE_NIGHT_MASK;
                sBadgeColor = night == Configuration.UI_MODE_NIGHT_NO
                        ? BADGE_COLOR_LIGHT : BADGE_COLOR_DARK;
                if (sDr != null) sDr.setBadgeColor(sBadgeColor);
            } catch (Throwable ignored) {
            }
            if (sPhase != PHASE_HIDDEN) ensureLoop();
        } catch (Throwable ignored) {
        }
    }

    /** H2: progress feed from refresh/g (first entry per emission).
     *  Supplies session identity (l) + the emitted fraction; smooth
     *  motion comes from the frame loop's direct Animatable reads. */
    public static void pull(com.x.home.tabbed.refresh.l l, float fraction) {
        try {
            if (l == null) return;
            if (sPhase == PHASE_HOLD) {
                if (l == sL) sFeedFrac = fraction;
                return;
            }
            if (l != sL) {
                sL = l;
                sLastA = -1f;
                sLastB = -1f;
                sLastChangeMs = SystemClock.uptimeMillis();
                sDispYDp = -1f;
            }
            sFeedFrac = fraction;
            if (sPhase == PHASE_HIDDEN && fraction > SHOW_FRACTION) {
                // Appearance (jp15.4): the badge pops in at full
                // size, already deep (tabBottom + ~126dp at the
                // threshold fraction on the saturating curve); the
                // first step() sets the displayed Y straight from
                // the curve, so clear any stale position.
                sDispYDp = -1f;
                sPhase = PHASE_PULL;
                sLastChangeMs = SystemClock.uptimeMillis();
            }
            if (sPhase != PHASE_HIDDEN) ensureLoop();
        } catch (Throwable ignored) {
        }
    }

    /** H3: refresh edges from refresh/m, adjacent to the jp8 sound
     *  hook (same boolean, same polarity: true = start). */
    public static void edge(boolean active) {
        try {
            long now = SystemClock.uptimeMillis();
            if (active) {
                if (sPhase != PHASE_HOLD) {
                    sPhase = PHASE_HOLD;
                    sHoldStartMs = now;
                    // Release tween (jp15.4): from wherever the
                    // badge currently sits to the fixed hold depth,
                    // over RELEASE_MS, ease-out. If no pull position
                    // was ever displayed, snap straight to hold.
                    sTweenFromDp = sDispYDp;
                    sTweenStartMs = now;
                }
                ensureLoop();
            } else if (sPhase == PHASE_HOLD) {
                // Completion (jp15.4, reference S4.5): the badge
                // disappears INSTANTLY as the content swaps -- the
                // same edge fires jp8's completion pop, whose
                // timing is device-confirmed. (jp15.3 handed over
                // to a grace settle instead; the reference shows
                // there is no exit animation to wait for.)
                hide();
            }
            // edge(false) while HIDDEN/PULL is the collector's baseline
            // observation: no-op by design (mirrors jp8 Snd semantics).
        } catch (Throwable ignored) {
        }
    }

    // ================= frame loop =====================================

    private static final Runnable LOOP_STARTER = new Runnable() {
        @Override public void run() { ensureLoopOnMain(); }
    };

    private static final Choreographer.FrameCallback FRAME =
            new Choreographer.FrameCallback() {
                @Override public void doFrame(long frameTimeNanos) {
                    sLoopPosted = false;
                    try {
                        step(frameTimeNanos);
                    } catch (Throwable t) {
                        try { hide(); } catch (Throwable ignored) {}
                        return;
                    }
                    if (sPhase != PHASE_HIDDEN) ensureLoopOnMain();
                }
            };

    private static void ensureLoop() {
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                ensureLoopOnMain();
            } else {
                MAIN.post(LOOP_STARTER);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void ensureLoopOnMain() {
        try {
            if (!sLoopPosted && sPhase != PHASE_HIDDEN) {
                sLoopPosted = true;
                Choreographer.getInstance().postFrameCallback(FRAME);
            }
        } catch (Throwable ignored) {
            sLoopPosted = false;
        }
    }

    private static float val(androidx.compose.animation.core.e anim) {
        try {
            if (anim == null) return 0f;
            Object o = anim.e();
            return o instanceof Number ? ((Number) o).floatValue() : 0f;
        } catch (Throwable t) {
            return 0f;
        }
    }

    /** Pull-phase center Y (dp below the inset) for fraction f:
     *  the reference's saturating curve, tabBottom + 81 + 70 x
     *  (1 - (1-f)^2). */
    private static float pullCenterDp(float f) {
        float cf = Math.min(Math.max(f, 0f), 1f);
        float sat = 1f - (1f - cf) * (1f - cf);
        return TAB_BASE_DP + APPEAR_DP + TRAVEL_DP * sat;
    }

    /** Hold center Y (dp below the inset): fixed, tabBottom + 101. */
    private static float holdCenterDp() {
        return TAB_BASE_DP + HOLD_BELOW_TAB_DP;
    }

    private static void step(long frameTimeNanos) {
        Activity a = sAct != null ? sAct.get() : null;
        if (a == null || a.isFinishing() || a.isDestroyed()) { hide(); return; }
        float density = a.getResources().getDisplayMetrics().density;

        boolean haveL = sL != null;
        float fa = haveL ? val(sL.a) : 0f;
        float fb = haveL ? val(sL.b) : 0f;
        long now = SystemClock.uptimeMillis();
        if (haveL && (Math.abs(fa - sLastA) > EPS || Math.abs(fb - sLastB) > EPS)) {
            sLastA = fa;
            sLastB = fb;
            sLastChangeMs = now;
        }

        boolean pip = false;
        try { pip = a.isInPictureInPictureMode(); } catch (Throwable ignored) {}

        // ---- phase exit conditions ----------------------------------
        if (sPhase == PHASE_HOLD) {
            if (now - sHoldStartMs > HOLD_FAILSAFE_MS) { hide(); return; }
        } else if (sPhase == PHASE_PULL) {
            if (!haveL) { hide(); return; }
            boolean settled = fa <= EPS && fb <= EPS && sFeedFrac <= SHOW_FRACTION;
            if (settled) { hide(); return; }
            if (!pip && now - sLastChangeMs > FREEZE_MS) { hide(); return; }
        } else {
            return;
        }

        // ---- view management ----------------------------------------
        if (!ensureView(a)) return;
        if (pip) {
            sView.setVisibility(View.INVISIBLE);
            return;
        }
        ViewGroup parent = (ViewGroup) sView.getParent();
        if (parent != null) parent.bringChildToFront(sView);
        sView.setVisibility(View.VISIBLE);

        // ---- geometry -------------------------------------------------
        int dw = parent != null ? parent.getWidth() : 0;
        if (dw == 0) dw = a.getResources().getDisplayMetrics().widthPixels;
        float cx = dw / 2f;
        float inset = statusInsetPx(a, density);

        float yDp;
        float sweepDeg;
        float rotDeg;
        float arrowScale = 0f;
        if (sPhase == PHASE_HOLD) {
            long elapsed = now - sHoldStartMs;
            // Release tween: ease-out (decelerate) from the captured
            // pull position to the fixed hold depth over RELEASE_MS,
            // in whichever direction applies (deep pull: ~50dp rise;
            // shallow pull: a small settle down).
            float target = holdCenterDp();
            if (sTweenFromDp >= 0f && elapsed < RELEASE_MS) {
                float t = elapsed / (float) RELEASE_MS;
                float ease = 1f - (1f - t) * (1f - t);
                yDp = sTweenFromDp + (target - sTweenFromDp) * ease;
            } else {
                yDp = target;
            }
            // Material cycle: sweep 20 -> 270 -> 20 via sin(pi p)
            // (20 at both cycle ends, so the wrap has no pop), and
            // one continuous clockwise rotation per cycle.
            float p = (elapsed % HOLD_CYCLE_MS) / (float) HOLD_CYCLE_MS;
            sweepDeg = 20f + 250f * (float) Math.sin(Math.PI * p);
            rotDeg = 360f * (elapsed / (float) HOLD_CYCLE_MS);
        } else {
            // PULL: center straight from the saturating curve (no
            // inertia, no gravity); arc does not rotate; sweep grows
            // with drag past the appearance threshold.
            float f = Math.min(Math.max(fa, 0f), 1f);
            yDp = pullCenterDp(f);
            float sf = (f - SHOW_FRACTION) / (1f - SHOW_FRACTION);
            sf = Math.min(Math.max(sf, 0f), 1f);
            sweepDeg = SWEEP_PULL_MIN
                    + (SWEEP_PULL_MAX - SWEEP_PULL_MIN) * sf;
            rotDeg = 0f;
            // Arrowhead (jp18): the classic scales its arrow with
            // the drag (setArrowScale reaches 1 at full drag); our
            // analog is sf, the same normalized progress that grows
            // the sweep. HOLD keeps arrowScale 0 -- the classic
            // shows no arrow while spinning (frame-verified).
            arrowScale = sf;
        }
        sDispYDp = yDp;

        float cy = inset + yDp * density;
        int sizePx = Math.max(1, Math.round(SIZE_DP * density));
        ViewGroup.LayoutParams lp = sView.getLayoutParams();
        if (lp != null && (lp.width != sizePx || lp.height != sizePx)) {
            lp.width = sizePx;
            lp.height = sizePx;
            sView.setLayoutParams(lp);
        }
        sView.setX(cx - sizePx / 2f);
        sView.setY(cy - sizePx / 2f);

        // ---- drawable state -------------------------------------------
        sDr.setGeom(BADGE_DP * density / 2f,
                (RING_DP - STROKE_DP) * density / 2f,
                STROKE_DP * density,
                2.0f * density,
                ARROW_W_DP * density,
                ARROW_H_DP * density);
        sDr.setArc(sweepDeg, rotDeg, arrowScale);
        sView.invalidate();
    }

    /**
     * Status-bar height (the "inset" every Y in this helper hangs
     * from), px -- measured defensively (jp15.2). jp15.1 trusted the
     * decorView's rootWindowInsets alone; on the real device that
     * read came out ~0 (view not yet attached / insets not yet
     * delivered), which shifted the whole anchor up by one status-bar
     * height and put the ring on top of the bird.
     *
     * Precedence, taking the MAX of the available positive values --
     * under-measurement is the failure mode being killed, and a
     * couple of dp over is harmless because every position below is
     * tab-relative with tens of dp of clearance:
     *   (a) the decorView's rootWindowInsets status-bar inset;
     *   (b) the framework's status_bar_height dimen, via
     *       Resources.getSystem() + getIdentifier (the only
     *       identifier lookup in this helper; no other reflection);
     *   (c) FALLBACK_STATUS_DP.
     * The result is cached (sInsetPx) only when a real source, (a) or
     * (b), contributed a positive value; a fallback-only result is
     * re-measured on the next frame so a late insets delivery still
     * corrects the anchor. Every read is Throwable-guarded like the
     * rest of the helper.
     */
    private static float statusInsetPx(Activity a, float density) {
        if (sInsetPx > 0f) return sInsetPx;
        float best = FALLBACK_STATUS_DP * density;
        boolean real = false;
        // (a) the window's own status-bar inset.
        try {
            Window w = a.getWindow();
            View decor = w != null ? w.getDecorView() : null;
            WindowInsets in = decor != null ? decor.getRootWindowInsets() : null;
            if (in != null) {
                int top;
                if (Build.VERSION.SDK_INT >= 30) {
                    top = in.getInsets(WindowInsets.Type.statusBars()).top;
                } else {
                    top = in.getSystemWindowInsetTop();
                }
                if (top > 0) {
                    real = true;
                    if (top > best) best = top;
                }
            }
        } catch (Throwable ignored) {
        }
        // (b) the framework's status_bar_height dimen.
        try {
            Resources sys = Resources.getSystem();
            if (sys != null) {
                int id = sys.getIdentifier(
                        "status_bar_height", "dimen", "android");
                if (id > 0) {
                    int h = sys.getDimensionPixelSize(id);
                    if (h > 0) {
                        real = true;
                        if (h > best) best = h;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // (c) is the FALLBACK_STATUS_DP seed in `best` above.
        if (real) sInsetPx = best;
        return best;
    }

    private static boolean ensureView(Activity a) {
        try {
            Window w = a.getWindow();
            if (w == null) return false;
            View decor = w.getDecorView();
            if (!(decor instanceof ViewGroup)) return false;
            ViewGroup dg = (ViewGroup) decor;
            if (sDr == null) {
                sDr = new ArcDrawable();
                sDr.setBadgeColor(sBadgeColor);
            }
            if (sView == null) {
                sView = new View(a);
                sView.setBackground(sDr);
                sView.setFocusable(false);
                sView.setClickable(false);
                sView.setFocusableInTouchMode(false);
                sView.setImportantForAccessibility(
                        View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                sView.setVisibility(View.INVISIBLE);
            }
            if (sView.getParent() != dg) {
                dropView();
                int sizePx = Math.max(1, Math.round(SIZE_DP
                        * a.getResources().getDisplayMetrics().density));
                dg.addView(sView, new FrameLayout.LayoutParams(sizePx, sizePx));
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void dropView() {
        try {
            if (sView != null) {
                ViewParent p = sView.getParent();
                if (p instanceof ViewGroup) ((ViewGroup) p).removeView(sView);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void hide() {
        sPhase = PHASE_HIDDEN;
        sDispYDp = -1f;
        sTweenFromDp = -1f;
        sHoldStartMs = 0L;
        dropView();
    }

    // ================= drawable =======================================

    static final class ArcDrawable extends Drawable {
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        /** Blit paint for the pre-rendered shadow bitmap (jp16). */
        private final Paint shadowBmpPaint =
                new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        /** Bake paint for the one-off shadow render (jp16). */
        private final Paint shadowBakePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();
        /** Pre-rendered soft shadow (jp16): a black disc blurred
         *  once via BlurMaskFilter. Re-rendered ONLY when the pixel
         *  geometry (badgeRPx) changes -- in practice once, on the
         *  first frame after attach. draw() itself never allocates
         *  and never blurs. */
        private Bitmap shadowBmp = null;
        private float shadowForR = -1f;
        private float sweepDeg = 0f;
        private float rotDeg = 0f;
        /** Arrowhead (jp18): fill paint + a single reused path
         *  (reset and rebuilt per draw; never reallocated), the
         *  pull-progress scale (0 = no arrow: HOLD, or the very
         *  start of a pull), and the unscaled arrow size in px. */
        private final Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrowPath = new Path();
        private float arrowScale = 0f;
        private float arrowWPx = 0f;
        private float arrowHPx = 0f;
        private float badgeRPx = 20f;
        private float ringRPx = 14f;
        private float strokePx = 3f;
        private float shadowDyPx = 2f;
        private int badgeColor = BADGE_COLOR_DARK;
        private int alpha = 255;

        ArcDrawable() {
            ringPaint.setColor(BLUE);
            ringPaint.setStyle(Paint.Style.STROKE);
            ringPaint.setStrokeCap(Paint.Cap.ROUND);
            arrowPaint.setColor(BLUE);
            arrowPaint.setStyle(Paint.Style.FILL);
            badgePaint.setStyle(Paint.Style.FILL);
            badgePaint.setColor(badgeColor);
            shadowBakePaint.setStyle(Paint.Style.FILL);
            shadowBakePaint.setColor(0xFF000000);
        }

        /** (Re)render the blurred shadow bitmap if the geometry it
         *  was baked for no longer matches. Called from draw(); the
         *  bake branch runs at most once per distinct badgeRPx. */
        private void ensureShadow() {
            if (shadowBmp != null && shadowForR == badgeRPx) return;
            try {
                float blurPx = Math.max(1f, badgeRPx * SHADOW_BLUR_FRACTION);
                int pad = (int) Math.ceil(blurPx);
                int size = Math.max(2, Math.round(2f * (badgeRPx + pad)));
                Bitmap bmp = Bitmap.createBitmap(
                        size, size, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(bmp);
                shadowBakePaint.setMaskFilter(new BlurMaskFilter(
                        blurPx, BlurMaskFilter.Blur.NORMAL));
                c.drawCircle(size / 2f, size / 2f, badgeRPx, shadowBakePaint);
                shadowBakePaint.setMaskFilter(null);
                if (shadowBmp != null) shadowBmp.recycle();
                shadowBmp = bmp;
                shadowForR = badgeRPx;
            } catch (Throwable t) {
                // Shadow is cosmetic: on any failure, fall back to
                // no shadow rather than disturbing the frame loop.
                shadowBmp = null;
                shadowForR = badgeRPx;
            }
        }

        void setArc(float sweep, float rot, float arrow) {
            sweepDeg = sweep;
            rotDeg = rot;
            arrowScale = arrow;
        }

        void setGeom(float badgeR, float ringR, float stroke, float shadowDy,
                float arrowW, float arrowH) {
            badgeRPx = badgeR;
            ringRPx = ringR;
            strokePx = stroke;
            shadowDyPx = shadowDy;
            arrowWPx = arrowW;
            arrowHPx = arrowH;
        }

        void setBadgeColor(int color) {
            badgeColor = color;
            badgePaint.setColor(color);
        }

        @Override public void draw(Canvas canvas) {
            if (sweepDeg < 0.5f) return;
            Rect b = getBounds();
            if (b.width() <= 0 || b.height() <= 0) return;
            float cx = b.exactCenterX();
            float cy = b.exactCenterY();
            // Soft shadow (jp16): blit the pre-rendered blurred
            // disc under the badge, nudged down by shadowDyPx. One
            // drawBitmap per frame; the blur itself was baked once
            // in ensureShadow().
            ensureShadow();
            if (shadowBmp != null) {
                shadowBmpPaint.setAlpha(alpha * SHADOW_ALPHA / 255);
                canvas.drawBitmap(shadowBmp,
                        cx - shadowBmp.getWidth() / 2f,
                        cy + shadowDyPx - shadowBmp.getHeight() / 2f,
                        shadowBmpPaint);
            }
            // Badge disc.
            badgePaint.setAlpha(alpha);
            canvas.drawCircle(cx, cy, badgeRPx, badgePaint);
            // Accent arc on top. Android canvas angles grow
            // clockwise (y axis points down): -90 = top; positive
            // rotation = clockwise, as required.
            oval.set(cx - ringRPx, cy - ringRPx, cx + ringRPx, cy + ringRPx);
            ringPaint.setStrokeWidth(strokePx);
            ringPaint.setAlpha(alpha);
            canvas.drawArc(oval, -90f + rotDeg, sweepDeg, false, ringPaint);
            // Arrowhead (jp18, pull phase only -- step() passes
            // arrowScale 0 in HOLD). A direct transcription of the
            // classic drawable's Ring.drawTriangle: the classic
            // builds the triangle (0,0)-(W,0)-(W/2,H) in a local
            // frame, offsets it so the base midpoint lands at
            // (center + outerRadius, center + stroke/2), then
            // rotates the canvas by the arc's END angle around the
            // center. Here the same points are computed already
            // rotated: with R = radial unit at the end angle and
            // T = clockwise tangent unit, the base midpoint is
            // B = C + outerR*R + (stroke/2)*T, the base endpoints
            // are B -/+ (W/2)*R, and the apex is B + H*T. The
            // reused path is reset and rebuilt in place -- no
            // per-frame allocation.
            if (arrowScale > 0.01f) {
                double th = Math.toRadians(-90f + rotDeg + sweepDeg);
                float cosT = (float) Math.cos(th);
                float sinT = (float) Math.sin(th);
                float outerR = ringRPx + strokePx / 2f;
                float w = arrowWPx * arrowScale;
                float h = arrowHPx * arrowScale;
                float bx = cx + outerR * cosT - (strokePx / 2f) * sinT;
                float by = cy + outerR * sinT + (strokePx / 2f) * cosT;
                float hw = w / 2f;
                arrowPath.reset();
                arrowPath.moveTo(bx - hw * cosT, by - hw * sinT);
                arrowPath.lineTo(bx + hw * cosT, by + hw * sinT);
                arrowPath.lineTo(bx - h * sinT, by + h * cosT);
                arrowPath.close();
                arrowPaint.setAlpha(alpha);
                canvas.drawPath(arrowPath, arrowPaint);
            }
        }

        @Override public void setAlpha(int a) { alpha = a; }

        @Override public void setColorFilter(ColorFilter cf) {
            ringPaint.setColorFilter(cf);
            badgePaint.setColorFilter(cf);
            shadowBmpPaint.setColorFilter(cf);
            arrowPaint.setColorFilter(cf);
        }

        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
