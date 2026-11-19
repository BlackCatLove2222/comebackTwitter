package app.morphe.extension.crimera.downloader;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import app.morphe.extension.crimera.settings.SettingsRegistry;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.StringSetting;

/**
 * Reconstruction of the shipped crimera FolderPicker (classes20),
 * extended in jp24.13.24.1 with a persistence path that does not
 * depend on the in-process callback map: a launch may name two
 * settings keys (tree uri + display path), and FolderPickerActivity
 * then writes the picked tree into the registry itself, from inside
 * onActivityResult. The Intent that carries the keys is retained by
 * the framework across process recreation; the static CALLBACKS map
 * is not, which is why grants completed while the system picker was
 * up vanished whenever the app process had been killed underneath it
 * (jp24.13.24 device bug: viewer stuck at "not granted").
 */
public final class FolderPicker {
    public interface Callback {
        void onPicked(Uri tree, String displayPath, boolean persisted);

        void onCancelled();

        void onUnwritable();
    }

    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static final Map<Integer, Callback> CALLBACKS = new ConcurrentHashMap<>();
    static final String EXTRA_TOKEN = "app.morphe.extension.crimera.downloader.TOKEN";
    static final String EXTRA_TREE_KEY = "app.morphe.extension.crimera.downloader.TREE_KEY";
    static final String EXTRA_PATH_KEY = "app.morphe.extension.crimera.downloader.PATH_KEY";

    private FolderPicker() {
    }

    public static void launch(Context context, Callback callback) {
        launch(context, callback, null, null);
    }

    /**
     * @param treeKey settings key that receives the picked tree uri,
     *                or null for a callback-only launch (the download
     *                folder flow)
     * @param pathKey settings key that receives the display path
     */
    public static void launch(Context context, Callback callback,
                              @Nullable String treeKey, @Nullable String pathKey) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(callback, "callback");
        int token = COUNTER.incrementAndGet();
        CALLBACKS.put(token, callback);
        Intent intent = new Intent(context, FolderPickerActivity.class);
        intent.putExtra(EXTRA_TOKEN, token);
        if (treeKey != null) intent.putExtra(EXTRA_TREE_KEY, treeKey);
        if (pathKey != null) intent.putExtra(EXTRA_PATH_KEY, pathKey);
        if (!(context instanceof Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        try {
            context.startActivity(intent);
        } catch (RuntimeException e) {
            CALLBACKS.remove(token);
            throw e;
        }
    }

    public static boolean hasCallback(int token) {
        return CALLBACKS.containsKey(token);
    }

    @Nullable
    public static Callback takeCallback(int token) {
        return CALLBACKS.remove(token);
    }

    /**
     * jp24.13.24.1: writes a picked tree into the settings registry.
     * Called by FolderPickerActivity right after the persistable
     * permission has been taken, so the grant is stored even when the
     * process (and with it the callback map) was recreated while the
     * system picker was showing. Returns true when the tree uri was
     * stored. Failures are logged, never swallowed silently.
     */
    public static boolean persistTreeGrant(String treeKey, String pathKey,
                                           Uri tree, @Nullable String displayPath) {
        try {
            if (!SettingsRegistry.isLoaded()) SettingsRegistry.load();
            Setting<?> treeSetting = SettingsRegistry.settingOrNull(treeKey);
            if (!(treeSetting instanceof StringSetting)) {
                Log.e("SavedMedia", "grant persist: no string setting for " + treeKey);
                return false;
            }
            ((StringSetting) treeSetting).save(tree.toString());
            Setting<?> pathSetting = SettingsRegistry.settingOrNull(pathKey);
            if (pathSetting instanceof StringSetting) {
                ((StringSetting) pathSetting).save(displayPath == null ? "" : displayPath);
            } else {
                Log.e("SavedMedia", "grant persist: no string setting for " + pathKey);
            }
            Log.i("SavedMedia", "grant persisted " + treeKey + " -> " + tree);
            return true;
        } catch (Throwable t) {
            Log.e("SavedMedia", "grant persist failed for " + treeKey, t);
            return false;
        }
    }
}
