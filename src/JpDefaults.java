package app.morphe.extension.newx.timeline;

import android.content.Context;
import android.content.SharedPreferences;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BooleanSetting;

/**
 * One-time JP default values, applied on first timeline load.
 *
 * <p>The data-usage settings live in the stock media repository
 * ({@code com.x.core.media.repo.n}, classes6.dex), backed by the app's
 * default SharedPreferences file ({@code <package>_preferences}) with
 * string values {@code "never"} / {@code "wifi_only"} /
 * {@code "wifi_and_mobile"}. Writing the keys once, before the user
 * changes them, makes the JP choices the effective defaults while
 * leaving the settings UI fully functional afterwards — the same
 * first-launch flip pattern jp22 used for the reply sort order.
 */
public final class JpDefaults {

    private static final String KEY_APPLIED = "newx.jp.data_usage_defaults_v1";
    private static final BooleanSetting APPLIED = new BooleanSetting(KEY_APPLIED, false);

    private JpDefaults() {
    }

    /** Applies the data-usage defaults once; fail-open so a failure retries next time. */
    public static void applyDataUsageDefaultsOnce() {
        try {
            if (Boolean.TRUE.equals(APPLIED.get())) return;
            Context context = Utils.getContext();
            if (context == null) return;
            SharedPreferences prefs = context.getSharedPreferences(
                    context.getPackageName() + "_preferences", Context.MODE_PRIVATE);
            prefs.edit()
                    .putString("video_autoplay", "never")
                    .putString("image_quality", "wifi_and_mobile")
                    .putString("video_quality", "wifi_and_mobile")
                    .apply();
            APPLIED.save(true);
        } catch (RuntimeException ignored) {
            // Defaults are best-effort; never disturb the timeline pipeline.
        }
    }
}
