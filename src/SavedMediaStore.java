package app.morphe.extension.newx.misc;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * jp24.13.22 (B): persistent index of media saved through the MediaStore
 * destination (Pictures/Twitter, Movies/Twitter).
 *
 * <p>{@link JpMediaStoreDestination#complete} records every published item
 * here so the authenticated saved-media viewer can enumerate downloads even
 * when the containing folder carries a .nomedia file. jp24.13.24.7.2:
 * device evidence (the jp24.13.23 staged queries and the 24.7
 * duplicate-download dialog defect) shows MediaStore queries return no
 * rows for the .nomedia image folder on the user's device even for items
 * this destination itself published, while Movies/Twitter rows stay
 * queryable — so this index is also the existence record the download
 * conflict path ({@code ConflictResolver}, {@code JpMediaStoreDestination})
 * consults for the built-in folders. The viewer prunes dead entries
 * through {@link #replaceAll}.
 *
 * <p>Storage: SharedPreferences {@code "newx_saved_media_index"}, key
 * {@code "entries_v1"} holding a JSONArray string of objects
 * {@code {uri, name, mime, added}} (added = epoch millis), newest first,
 * capped at {@value #MAX_ENTRIES} entries. Every public entry point is
 * fail-soft: a broken index must never break a download or the viewer.
 */
public final class SavedMediaStore {

    static final String PREFS_NAME = "newx_saved_media_index";
    static final String KEY_ENTRIES = "entries_v1";
    static final int MAX_ENTRIES = 2000;

    /** One indexed download. {@code added} is epoch millis. */
    public static final class Entry {
        public final String uri;
        public final String name;
        public final String mime;
        public final long added;

        public Entry(String uri, String name, String mime, long added) {
            this.uri = uri;
            this.name = name;
            this.mime = mime;
            this.added = added;
        }
    }

    private SavedMediaStore() {
    }

    /** Records one published download. Fail-soft; newest wins on duplicate uri. */
    public static synchronized void record(Context context, Uri uri, String name, String mime) {
        if (context == null || uri == null) return;
        try {
            Entry entry = new Entry(
                    uri.toString(),
                    name == null ? "" : name,
                    mime == null ? "" : mime,
                    System.currentTimeMillis());
            List<Entry> updated = addEntry(entries(context), entry);
            save(context, updated);
        } catch (Throwable ignored) {
            // Indexing is best-effort only; publishing already succeeded.
        }
    }

    /** All indexed entries, newest first. Empty on any failure. */
    public static synchronized List<Entry> entries(Context context) {
        if (context == null) return new ArrayList<>();
        try {
            SharedPreferences prefs =
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            return parse(prefs.getString(KEY_ENTRIES, null));
        } catch (Throwable ignored) {
            return new ArrayList<>();
        }
    }

    // ---- Existence lookup (jp24.13.24.7.2) ----

    /**
     * First entry (the list is newest first) whose recorded name equals
     * {@code name} and whose MIME kind matches {@code video}: video
     * entries carry a {@code video/} MIME type, image entries an
     * {@code image/} one. Entries without a usable MIME type never match
     * (fail closed, mirroring the download router's media-kind routing).
     * Android-free; exercised by the JVM harness.
     */
    static Entry findNamed(List<Entry> entries, String name, boolean video) {
        if (entries == null || name == null) return null;
        for (Entry entry : entries) {
            if (entry == null || entry.name == null || entry.mime == null) continue;
            if (video ? !entry.mime.startsWith("video/") : !entry.mime.startsWith("image/")) {
                continue;
            }
            if (name.equals(entry.name)) return entry;
        }
        return null;
    }

    /** True when {@link #findNamed} finds an entry. Android-free. */
    static boolean hasNamed(List<Entry> entries, String name, boolean video) {
        return findNamed(entries, name, video) != null;
    }

    /** Replaces the whole index (the viewer uses this to prune dead entries). */
    public static synchronized void replaceAll(Context context, List<Entry> entries) {
        if (context == null) return;
        try {
            save(context, entries == null ? new ArrayList<>() : entries);
        } catch (Throwable ignored) {
            // Best-effort only.
        }
    }

    private static void save(Context context, List<Entry> entries) {
        SharedPreferences prefs =
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_ENTRIES, serialize(cap(entries))).apply();
    }

    // ---- Core list logic (Android-free; exercised by the JVM harness) ----

    /**
     * Returns a new list with {@code entry} at the head: any existing entry
     * with the same uri string is dropped (newest wins), order is otherwise
     * preserved, and the result is capped at {@link #MAX_ENTRIES}.
     */
    static List<Entry> addEntry(List<Entry> current, Entry entry) {
        List<Entry> out = new ArrayList<>();
        if (entry != null && entry.uri != null && !entry.uri.isEmpty()) {
            out.add(entry);
        }
        if (current != null) {
            for (Entry existing : current) {
                if (existing == null || existing.uri == null) continue;
                if (entry != null && existing.uri.equals(entry.uri)) continue;
                out.add(existing);
            }
        }
        return cap(out);
    }

    /** Truncates to {@link #MAX_ENTRIES}, keeping the head (newest). */
    static List<Entry> cap(List<Entry> entries) {
        if (entries == null) return new ArrayList<>();
        if (entries.size() <= MAX_ENTRIES) return entries;
        return new ArrayList<>(entries.subList(0, MAX_ENTRIES));
    }

    static String serialize(List<Entry> entries) {
        JSONArray array = new JSONArray();
        if (entries != null) {
            for (Entry entry : entries) {
                if (entry == null || entry.uri == null) continue;
                JSONObject object = new JSONObject();
                try {
                    object.put("uri", entry.uri);
                    object.put("name", entry.name == null ? "" : entry.name);
                    object.put("mime", entry.mime == null ? "" : entry.mime);
                    object.put("added", entry.added);
                } catch (Throwable ignored) {
                    continue;
                }
                array.put(object);
            }
        }
        return array.toString();
    }

    /** Parses the stored JSONArray string. Malformed input yields what could be salvaged. */
    static List<Entry> parse(String json) {
        List<Entry> out = new ArrayList<>();
        if (json == null || json.isEmpty()) return out;
        JSONArray array;
        try {
            array = new JSONArray(json);
        } catch (Throwable ignored) {
            return out;
        }
        for (int i = 0; i < array.length(); i++) {
            try {
                JSONObject object = array.optJSONObject(i);
                if (object == null) continue;
                String uri = object.optString("uri", null);
                if (uri == null || uri.isEmpty()) continue;
                out.add(new Entry(
                        uri,
                        object.optString("name", ""),
                        object.optString("mime", ""),
                        object.optLong("added", 0L)));
            } catch (Throwable ignored) {
                // Skip the malformed element, keep the rest.
            }
        }
        return out;
    }
}
