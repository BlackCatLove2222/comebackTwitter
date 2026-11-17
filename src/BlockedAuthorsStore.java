package app.morphe.extension.newx.postfilter;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import app.morphe.extension.crimera.settings.StringSetSetting;
import app.morphe.extension.shared.settings.BooleanSetting;

/**
 * JP designated-author list (jp24.11): accounts whose posts are hidden
 * by default, client-side only — nothing is sent to the server and no
 * block/mute API is called. Entries are numeric user IDs as decimal
 * strings — screen names are never matched because they can change.
 *
 * Two layers, per spec: the bundled defaults below, seeded into the
 * persisted set exactly once, and the persisted set itself, which is
 * the only layer user edits (add/remove) ever touch. Modeled on
 * {@link VerifiedAccountWhitelistStore}.
 *
 * NOTE (comebackTwitter distribution): the author's own seed list is
 * intentionally NOT included in this repository. The seed below is
 * empty — add the numeric IDs of the accounts you want hidden to
 * {@link #defaultIds()} in your own build.
 */
public final class BlockedAuthorsStore {
    private static final String KEY = "newx.content.blocked_authors";
    private static final String SEEDED_KEY = "newx.content.blocked_authors.seeded";

    /** Bundled defaults: seed your own numeric author IDs here. */
    static final Set<String> DEFAULT_IDS = defaultIds();

    private static Set<String> defaultIds() {
        Set<String> ids = new LinkedHashSet<>();
        // Example (placeholder format only — replace with your own):
        // ids.add("0000000000000000000"); // numeric user ID, decimal string
        return Collections.unmodifiableSet(ids);
    }

    private volatile Set<String> cachedRaw;
    private volatile Set<String> cachedSnapshot;

    private BlockedAuthorsStore() {
    }

    public static BlockedAuthorsStore shared() {
        return Holder.INSTANCE;
    }

    /** Current designated IDs (unmodifiable). Seeds the defaults once. */
    public synchronized Set<String> snapshot() {
        seedDefaultsOnce();
        Set<String> raw = setting().get();
        if (raw == null) raw = Collections.emptySet();
        if (cachedSnapshot != null && raw.equals(cachedRaw)) return cachedSnapshot;
        Set<String> normalized = new LinkedHashSet<>();
        for (String id : raw) {
            String value = normalize(id);
            if (!value.isEmpty()) normalized.add(value);
        }
        cachedRaw = raw;
        cachedSnapshot = Collections.unmodifiableSet(normalized);
        return cachedSnapshot;
    }

    public void add(String authorId) {
        String normalized = normalize(authorId);
        if (normalized.isEmpty()) return;
        Set<String> updated = new LinkedHashSet<>(snapshot());
        if (updated.add(normalized)) {
            setting().save(Collections.unmodifiableSet(updated));
        }
    }

    public void remove(String authorId) {
        String normalized = normalize(authorId);
        if (normalized.isEmpty()) return;
        Set<String> updated = new LinkedHashSet<>(snapshot());
        if (updated.remove(normalized)) {
            setting().save(Collections.unmodifiableSet(updated));
        }
    }

    public static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private void seedDefaultsOnce() {
        if (Boolean.TRUE.equals(Holder.SEEDED.get())) return;
        Set<String> current = setting().get();
        Set<String> merged = new LinkedHashSet<>();
        if (current != null) merged.addAll(current);
        merged.addAll(DEFAULT_IDS);
        setting().save(Collections.unmodifiableSet(merged));
        Holder.SEEDED.save(true);
    }

    private static StringSetSetting setting() {
        return Holder.SETTING;
    }

    private static final class Holder {
        private static final StringSetSetting SETTING =
                new StringSetSetting(KEY, Collections.emptySet(), false);
        private static final BooleanSetting SEEDED =
                new BooleanSetting(SEEDED_KEY, false);
        private static final BlockedAuthorsStore INSTANCE =
                new BlockedAuthorsStore();
    }
}
