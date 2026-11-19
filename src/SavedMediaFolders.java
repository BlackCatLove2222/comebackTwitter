package app.morphe.extension.newx.misc;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;

import app.morphe.extension.crimera.downloader.FolderPicker;
import app.morphe.extension.crimera.downloader.engine.DestinationCheck;
import app.morphe.extension.newx.misc.NewXDownloadFolders.DestinationState;
import app.morphe.extension.newx.misc.NewXDownloadFolders.MediaKind;

/**
 * jp24.13.24: the saved-media viewer's own folder grants, as a thin
 * adapter over {@link SavedMediaSettings} with the same four-state
 * model ({@link DestinationState}) the download folders use.
 *
 * <p>The viewer enumerates, in order: the configured download folder
 * (read-only reuse of the download grant, see
 * {@link NewXDownloadFolders}) and the viewer's own granted folder
 * stored here. The viewer grant exists for the standard folders
 * (Pictures/Twitter, Movies/Twitter) when no download folder is
 * configured, and as the remedy when a download grant was revoked.
 */
public final class SavedMediaFolders {

    private SavedMediaFolders() {
    }

    @Nullable
    public static Uri treeUri(MediaKind kind) {
        String value = kind == MediaKind.VIDEOS
                ? SavedMediaSettings.videosTreeUri()
                : SavedMediaSettings.imagesTreeUri();
        return value == null || value.isEmpty() ? null : Uri.parse(value);
    }

    @Nullable
    public static String displayPath(MediaKind kind) {
        String value = kind == MediaKind.VIDEOS
                ? SavedMediaSettings.videosDisplayPath()
                : SavedMediaSettings.imagesDisplayPath();
        return value == null || value.isEmpty() ? null : value;
    }

    static String treeSettingId(MediaKind kind) {
        return kind == MediaKind.VIDEOS
                ? SavedMediaSettings.VIDEOS_TREE_URI
                : SavedMediaSettings.IMAGES_TREE_URI;
    }

    static String displayPathSettingId(MediaKind kind) {
        return kind == MediaKind.VIDEOS
                ? SavedMediaSettings.VIDEOS_DISPLAY_PATH
                : SavedMediaSettings.IMAGES_DISPLAY_PATH;
    }

    /** Grant state of the viewer's own folder for {@code kind}. */
    public static DestinationState destinationState(Context context, MediaKind kind) {
        Uri tree = treeUri(kind);
        if (tree == null || context == null) return DestinationState.UNSET;

        ContentResolver resolver = context.getContentResolver();
        if (DestinationCheck.hasPersistedWritePermission(resolver, tree)) {
            return DestinationState.PERSISTED;
        }
        return DestinationCheck.hasLiveTreeAccess(resolver, tree)
                ? DestinationState.LIVE
                : DestinationState.UNUSABLE;
    }

    /** Persists a picker result under the viewer's own keys. */
    public static void store(MediaKind kind, @Nullable Uri treeUri, @Nullable String label) {
        SavedMediaSettings.setString(treeSettingId(kind), treeUri == null ? "" : treeUri.toString());
        SavedMediaSettings.setString(displayPathSettingId(kind), label == null ? "" : label);
        Log.i("SavedMedia", "grant stored kind=" + kind + " uri=" + treeUri);
    }

    /** Tree document id used for same-tree comparison; null when unparseable. */
    @Nullable
    public static String treeDocumentId(@Nullable Uri tree) {
        if (tree == null) return null;
        try {
            return DestinationCheck.treeDocumentIdOf(tree);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Launches the shared folder picker for the viewer's own grant.
     * The launch names this kind's settings keys, so the picker
     * activity persists the picked tree itself (jp24.13.24.1) even if
     * this process is recreated while the system picker is showing;
     * the callback below is only a reload hint and a second, redundant
     * store. {@code onStored} runs after a successful pick.
     * Cancellation and unwritable picks change nothing.
     */
    public static void choose(Context context, MediaKind kind, @Nullable Runnable onStored) {
        try {
            FolderPicker.launch(context, new FolderPicker.Callback() {
                @Override
                public void onPicked(Uri tree, String displayPath, boolean persisted) {
                    Log.i("SavedMedia", "grant onPicked kind=" + kind
                            + " persisted=" + persisted + " tree=" + tree);
                    try {
                        store(kind, tree, displayPath);
                    } catch (Throwable t) {
                        Log.e("SavedMedia", "grant store failed kind=" + kind, t);
                        return;
                    }
                    if (onStored != null) onStored.run();
                }

                @Override
                public void onCancelled() {
                }

                @Override
                public void onUnwritable() {
                }
            }, treeSettingId(kind), displayPathSettingId(kind));
        } catch (Throwable t) {
            // The picker is best-effort UI; a launch failure changes
            // nothing, but it must not fail silently (jp24.13.24.1).
            Log.e("SavedMedia", "grant launch failed kind=" + kind, t);
        }
    }
}
