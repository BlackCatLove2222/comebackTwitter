package app.morphe.extension.newx.misc;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import app.morphe.extension.newx.settings.NewXLogger;
import app.morphe.extension.shared.Utils;

/**
 * Drawer row filter.
 *
 * Fix (jp24.13.24.6, drawer jank): shouldHide() used to resolve every hidden
 * option with an uncached Resources.getIdentifier() on each call. It runs for
 * every drawer row on every recomposition of the drawer content (e.g. when
 * the drawer crosses its open/close midpoint during a drag), costing ~50ms
 * per burst. Now the hidden option set is resolved to a Set of title strings
 * once, and reused until the option set or the locale changes. Matching
 * semantics are unchanged, including per-option failure isolation: a stale
 * or malformed option never hides unrelated rows and never poisons the
 * cached resolution.
 */
public final class DrawerItemFilter {
    private static final String RESOURCE_NAME_ITEM_ID_PREFIX = "RESOURCE_NAME_";
    private static final String RESOURCE_STRING_ITEM_ID_PREFIX = "RESOURCE_STRING_";

    // Cache: (options snapshot, locale) -> resolved hidden titles.
    private static volatile Set<String> cachedOptions = null;
    private static volatile String cachedLocaleKey = null;
    private static volatile Set<String> cachedTitles = Collections.emptySet();

    private DrawerItemFilter() {
    }

    // Legacy drawer rows expose only their localized title. Stable option IDs carry the
    // string entry name, while RESOURCE_STRING_ remains readable for older saved settings.
    public static boolean shouldHide(String title, Set<String> hiddenItemIds) {
        if (title == null || hiddenItemIds == null || hiddenItemIds.isEmpty()) return false;

        try {
            return resolvedTitles(hiddenItemIds).contains(title);
        } catch (Exception exception) {
            NewXLogger.printException(() -> "Failed to customize NewX drawer", exception);
            return false;
        }
    }

    // BETA PATH: the settings footer exposes a stable logical item ID instead of a title.
    public static boolean shouldHideId(String itemId, Set<String> hiddenItemIds) {
        return itemId != null && hiddenItemIds != null && hiddenItemIds.contains(itemId);
    }

    private static Set<String> resolvedTitles(Set<String> hiddenItemIds) {
        Context context = Utils.getContext();
        if (context == null) {
            return Collections.emptySet();
        }
        Resources resources = context.getResources();
        String localeKey = localeKey(resources);

        Set<String> options = cachedOptions;
        if (options != null && options.equals(hiddenItemIds)
                && localeKey.equals(cachedLocaleKey)) {
            return cachedTitles;
        }

        synchronized (DrawerItemFilter.class) {
            if (cachedOptions != null && cachedOptions.equals(hiddenItemIds)
                    && localeKey.equals(cachedLocaleKey)) {
                return cachedTitles;
            }
            Set<String> titles = new HashSet<>();
            String packageName = context.getPackageName();
            for (String hiddenItemId : hiddenItemIds) {
                if (hiddenItemId != null) {
                    collectTitles(hiddenItemId, packageName, resources, titles);
                }
            }
            // Copy the option set: the caller's Set may be mutable / reused.
            cachedOptions = new HashSet<>(hiddenItemIds);
            cachedLocaleKey = localeKey;
            cachedTitles = Collections.unmodifiableSet(titles);
            return cachedTitles;
        }
    }

    /** Same resolution rules as the old matchesResourceOption(), per option. */
    private static void collectTitles(
            String hiddenItemId,
            String packageName,
            Resources resources,
            Set<String> out
    ) {
        if (hiddenItemId.startsWith(RESOURCE_NAME_ITEM_ID_PREFIX)) {
            String entryName = hiddenItemId.substring(RESOURCE_NAME_ITEM_ID_PREFIX.length());
            if (entryName.isEmpty()) return;

            int resourceId = resources.getIdentifier(entryName, "string", packageName);
            if (resourceId != 0) {
                try {
                    out.add(resources.getString(resourceId));
                } catch (Resources.NotFoundException ignored) {
                    // A stale or malformed option must not hide unrelated rows.
                }
            }
            return;
        }

        if (!hiddenItemId.startsWith(RESOURCE_STRING_ITEM_ID_PREFIX)) return;

        String encodedResourceIds = hiddenItemId.substring(RESOURCE_STRING_ITEM_ID_PREFIX.length());
        if (encodedResourceIds.isEmpty()) return;

        for (String encodedResourceId : encodedResourceIds.split("-")) {
            try {
                int resourceId = Integer.parseInt(encodedResourceId, 16);
                if (resourceId != 0) {
                    out.add(resources.getString(resourceId));
                }
            } catch (NumberFormatException | Resources.NotFoundException ignored) {
                // A stale or malformed option must not hide unrelated rows.
            }
        }
    }

    private static String localeKey(Resources resources) {
        // Configuration.getLocales() is API 24+; the extension minSdk is 29.
        Configuration configuration = resources.getConfiguration();
        return configuration.getLocales().isEmpty()
                ? ""
                : configuration.getLocales().get(0).toLanguageTag();
    }
}
