package app.morphe.extension.newx.misc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * jp24.13.24: Android-free helpers for the saved-media viewer's SAF
 * enumeration route. Kept free of Android types so the merge/dedupe and
 * filtering rules can be exercised by the JVM harness against the exact
 * compiled class.
 */
final class SavedMediaSaf {

    static final int KIND_OTHER = 0;
    static final int KIND_IMAGE = 1;
    static final int KIND_VIDEO = 2;
    static final int KIND_DIRECTORY = 3;

    static final String MIME_DIRECTORY = "vnd.android.document/directory";

    private SavedMediaSaf() {
    }

    /** Dotfiles (including .nomedia itself) never become media items. */
    static boolean isHiddenName(String name) {
        return name == null || name.startsWith(".");
    }

    static int mimeKind(String mime) {
        if (mime == null) return KIND_OTHER;
        if (MIME_DIRECTORY.equals(mime)) return KIND_DIRECTORY;
        if (mime.startsWith("image/")) return KIND_IMAGE;
        if (mime.startsWith("video/")) return KIND_VIDEO;
        return KIND_OTHER;
    }

    /** True when {@code mime} belongs to the requested collection kind. */
    static boolean accepts(String mime, boolean video) {
        int kind = mimeKind(mime);
        return video ? kind == KIND_VIDEO : kind == KIND_IMAGE;
    }

    /**
     * Cross-scheme identity of one physical file: the display name plus
     * the byte size. MediaStore rows and SAF documents of the same file
     * share no id space, so (name, size) is the only key that spans both.
     * An unknown size is -1 and only collides with another unknown size.
     */
    static String dedupeKey(String name, long size) {
        return (name == null ? "" : name) + "\n" + size;
    }

    /** Newest first; ties broken by name so the order is deterministic. */
    static int compareItems(long addedA, String nameA, long addedB, String nameB) {
        int byAdded = Long.compare(addedB, addedA);
        if (byAdded != 0) return byAdded;
        String a = nameA == null ? "" : nameA;
        String b = nameB == null ? "" : nameB;
        return a.compareTo(b);
    }

    /**
     * First-wins merge over sources already concatenated in priority
     * order (SAF, then MediaStore, then the index): returns the indices
     * to keep. An item is dropped when its Uri was already kept or its
     * dedupe key was already kept.
     */
    static List<Integer> firstWinsIndices(List<String> uris, List<String> keys) {
        List<Integer> kept = new ArrayList<>();
        HashSet<String> seenUris = new HashSet<>();
        HashSet<String> seenKeys = new HashSet<>();
        int count = Math.min(uris.size(), keys.size());
        for (int i = 0; i < count; i++) {
            String uri = uris.get(i);
            if (uri != null && !seenUris.add(uri)) continue;
            String key = keys.get(i);
            if (key != null && !seenKeys.add(key)) continue;
            kept.add(i);
        }
        return kept;
    }
}
