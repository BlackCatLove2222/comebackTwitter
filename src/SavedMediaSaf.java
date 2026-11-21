package app.morphe.extension.newx.misc;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

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

    // jp24.13.24.7: viewer tabs.
    static final int TAB_IMAGE = 0;
    static final int TAB_VIDEO = 1;
    static final int TAB_GIF = 2;

    private SavedMediaSaf() {
    }

    /**
     * jp24.13.24.7: a GIF is identified by its mime type or, when the
     * mime is missing/generic, by a .gif file name (case-insensitive).
     */
    static boolean isGif(String mime, String name) {
        if (mime != null && mime.equalsIgnoreCase("image/gif")) return true;
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".gif");
    }

    /**
     * jp24.13.24.7: which viewer tab an enumerated item belongs to.
     * GIFs go to the GIF tab only (never the image tab). Other images
     * go to the image tab and videos to the video tab; the mime type
     * decides first and the source-kind flag breaks ties for items
     * whose mime is unknown (every enumerated item carries a definite
     * kind from its source, so the classification is total).
     */
    static int tabFor(String mime, String name, boolean video) {
        if (isGif(mime, name)) return TAB_GIF;
        if (mime != null && mime.startsWith("video/")) return TAB_VIDEO;
        if (mime != null && mime.startsWith("image/")) return TAB_IMAGE;
        return video ? TAB_VIDEO : TAB_IMAGE;
    }

    /**
     * jp24.13.24.7: delete scope guard. An item may be deleted only
     * when it is part of the viewer's current enumerated set; a SAF
     * item additionally requires the tree it was enumerated from to
     * be one of the trees granted to (and enumerated by) this viewer
     * load. Nothing outside the enumerated folders is reachable:
     * MediaStore/index items in the set come only from the two fixed
     * folder queries and the extension's own download index.
     */
    static boolean canDelete(
            boolean saf,
            String itemUri,
            String treeUri,
            Collection<String> enumeratedUris,
            Collection<String> grantedTreeUris) {
        if (itemUri == null || enumeratedUris == null
                || !enumeratedUris.contains(itemUri)) {
            return false;
        }
        if (!saf) return true;
        return treeUri != null && grantedTreeUris != null
                && grantedTreeUris.contains(treeUri);
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
