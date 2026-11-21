package app.morphe.extension.newx.misc;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.io.IOException;
import java.util.List;

import app.morphe.extension.crimera.downloader.engine.DestinationWriter;
import app.morphe.extension.crimera.downloader.engine.Reservation;
import app.morphe.extension.crimera.downloader.engine.SafDestination;
import app.morphe.extension.crimera.downloader.model.ConflictPolicy;
import app.morphe.extension.crimera.downloader.model.DownloadRequest;
import app.morphe.extension.newx.settings.NewXLogger;

/**
 * jp24.3: engine destination that saves into the shared media collections through
 * MediaStore — Pictures/Twitter for images, Movies/Twitter for videos — so downloads
 * work before the user picks a folder, with no SAF grant involved. Reservations are
 * inserted pending and published by {@link #complete} once the transfer has saved.
 */
public final class JpMediaStoreDestination implements DestinationWriter {

    private static final int MAX_NAME_ATTEMPTS = 32;

    /** True when the uri is one of the two media collection uris used as a "tree". */
    public static boolean isMediaStoreCollection(Uri uri) {
        if (uri == null || !"media".equals(uri.getAuthority())) return false;
        String path = uri.getPath();
        return path != null && (path.endsWith("/images/media") || path.endsWith("/video/media"));
    }

    /** True when the uri points into the media store (a reserved item). */
    public static boolean isMediaStoreItem(Uri uri) {
        return uri != null && "media".equals(uri.getAuthority());
    }

    /** Relative path (trailing slash included) for a collection plus optional subpath. */
    static String relativePathFor(boolean video, List<String> subpath) {
        StringBuilder path = new StringBuilder(video ? "Movies/Twitter/" : "Pictures/Twitter/");
        if (subpath != null) {
            for (String segment : subpath) {
                String clean = sanitizeSegment(segment);
                if (clean != null) path.append(clean).append('/');
            }
        }
        return path.toString();
    }

    private static String sanitizeSegment(String segment) {
        if (segment == null) return null;
        String clean = segment.trim().replace('/', '_').replace('\\', '_');
        if (clean.isEmpty() || ".".equals(clean) || "..".equals(clean)) return null;
        return clean;
    }

    @Override
    public Reservation reserve(Context context, DownloadRequest request) throws IOException {
        Uri collection = request.destinationTree();
        if (!isMediaStoreCollection(collection)) return null;
        boolean video = collection.getPath() != null && collection.getPath().contains("/video/");
        String relativePath = relativePathFor(video, request.subpath());
        ContentResolver resolver = context.getContentResolver();
        String name = request.fileName();
        ConflictPolicy policy = request.conflict();
        for (int attempt = 0; attempt < MAX_NAME_ATTEMPTS; attempt++) {
            String candidate = attempt == 0 ? name : SafDestination.appendSuffix(name, attempt);
            Uri existing = findItem(resolver, collection, relativePath, candidate);
            if (existing == null) {
                // jp24.13.24.7.2: the MediaStore query is blind for the
                // .nomedia image folder; consult the download index.
                existing = findIndexedItem(context, collection, relativePath, candidate);
            }
            if (existing != null) {
                if (policy == ConflictPolicy.SKIP) return null;
                if (policy == ConflictPolicy.RENAME) continue;
                // OVERWRITE: remove the old item, then fall through to insert.
                try {
                    resolver.delete(existing, null, null);
                } catch (RuntimeException ignored) {
                    // A failed delete surfaces as an insert failure below.
                }
            }
            Uri item = insertPending(resolver, collection, relativePath, candidate, request.mimeType());
            if (item != null) return new Reservation(item, candidate, request.mimeType());
            if (policy != ConflictPolicy.RENAME) return null;
        }
        return null;
    }

    @Override
    public void discard(Context context, Reservation reservation) {
        if (context == null || reservation == null) return;
        try {
            context.getContentResolver().delete(reservation.documentUri(), null, null);
        } catch (RuntimeException ignored) {
            // Cleanup only; the engine already treats the download as finished.
        }
    }

    /** Publishes a saved reservation: clears IS_PENDING so other apps can see the file. */
    public static void complete(Context context, Reservation reservation) {
        if (context == null || reservation == null) return;
        if (Build.VERSION.SDK_INT < 29) return;
        Uri uri = reservation.documentUri();
        if (!isMediaStoreItem(uri)) return;
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
        try {
            context.getContentResolver().update(uri, values, null, null);
        } catch (RuntimeException exception) {
            NewXLogger.printException(() -> "Failed to publish the downloaded media item", exception);
        }
        // jp24.13.22 (B): index the published item for the authenticated
        // saved-media viewer. After the publish try/catch and itself
        // fail-soft, so indexing can never affect publishing.
        SavedMediaStore.record(context, uri, reservation.fileName(), reservation.mimeType());
    }

    private static Uri findItem(ContentResolver resolver, Uri collection, String relativePath, String name) {
        if (Build.VERSION.SDK_INT < 29) return null;
        try (Cursor cursor = resolver.query(
                collection,
                new String[]{MediaStore.MediaColumns._ID},
                MediaStore.MediaColumns.RELATIVE_PATH + "=? AND "
                        + MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                new String[]{relativePath, name},
                null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return ContentUris.withAppendedId(collection, cursor.getLong(0));
            }
        } catch (RuntimeException exception) {
            NewXLogger.printException(() -> "MediaStore lookup failed for " + name, exception);
        }
        return null;
    }

    /**
     * jp24.13.24.7.2: when the MediaStore query cannot see the item —
     * on the user's device it returns no rows for the .nomedia image
     * folder, even for items this destination itself published — fall
     * back to the extension's own download index ({@link SavedMediaStore}),
     * which records every published item. Without this, reserve() could
     * not apply SKIP / OVERWRITE / RENAME for repeat image saves at all:
     * every repeat silently inserted a provider-suffixed copy. Base
     * folder only (the index records base-folder publishes; subpath
     * requests keep the query-only behavior). Fail-soft.
     *
     * <p>Note: in the shipped build this fallback is applied as a smali
     * splice (a new method on the shipped class plus a call in the
     * shipped reserve loop; the class is not recompiled, per the
     * DownloadSettings precedent); this source mirrors it.
     */
    private static Uri findIndexedItem(
            Context context, Uri collection, String relativePath, String name) {
        try {
            if (context == null) return null;
            boolean video = collection.getPath() != null && collection.getPath().contains("/video/");
            if (!relativePathFor(video, null).equals(relativePath)) return null;
            SavedMediaStore.Entry entry =
                    SavedMediaStore.findNamed(SavedMediaStore.entries(context), name, video);
            if (entry == null || entry.uri == null || entry.uri.isEmpty()) return null;
            return Uri.parse(entry.uri);
        } catch (Throwable throwable) {
            return null;
        }
    }

    private static Uri insertPending(ContentResolver resolver, Uri collection, String relativePath,
                                     String name, String mimeType) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        if (mimeType != null) values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
        if (Build.VERSION.SDK_INT >= 29) {
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        }
        try {
            return resolver.insert(collection, values);
        } catch (RuntimeException exception) {
            NewXLogger.printException(() -> "MediaStore insert failed for " + name, exception);
            return null;
        }
    }
}
