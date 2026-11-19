package app.morphe.extension.newx.misc;

import app.morphe.extension.crimera.settings.SettingsRegistry;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.StringSetting;

/**
 * jp24.13.24: registry access for the saved-media viewer's own folder
 * grants. Mirrors {@link DownloadSettings}: the values are NewX settings
 * nodes (registered in SettingsRegistry.load) so the settings backup
 * carries the viewer's chosen folders between installs. The keys are
 * separate from the download destination keys on purpose — changing a
 * download folder must not silently retarget the viewer; the viewer
 * only reads the download grants as a fallback source.
 */
final class SavedMediaSettings {
    static final String IMAGES_TREE_URI = "newx.content.saved_media.images_tree_uri";
    static final String VIDEOS_TREE_URI = "newx.content.saved_media.videos_tree_uri";
    static final String IMAGES_DISPLAY_PATH = "newx.content.saved_media.images_display_path";
    static final String VIDEOS_DISPLAY_PATH = "newx.content.saved_media.videos_display_path";

    private SavedMediaSettings() {
    }

    private static void ensureLoaded() {
        if (SettingsRegistry.isLoaded()) return;
        SettingsRegistry.load();
    }

    static String imagesTreeUri() {
        ensureLoaded();
        return SettingsRegistry.getStringOrDefault(IMAGES_TREE_URI, "");
    }

    static String videosTreeUri() {
        ensureLoaded();
        return SettingsRegistry.getStringOrDefault(VIDEOS_TREE_URI, "");
    }

    static String imagesDisplayPath() {
        ensureLoaded();
        return SettingsRegistry.getStringOrDefault(IMAGES_DISPLAY_PATH, "");
    }

    static String videosDisplayPath() {
        ensureLoaded();
        return SettingsRegistry.getStringOrDefault(VIDEOS_DISPLAY_PATH, "");
    }

    static void setString(String settingId, String value) {
        ensureLoaded();
        Setting<?> setting = SettingsRegistry.settingOrNull(settingId);
        if (!(setting instanceof StringSetting text)) {
            throw new IllegalStateException("Missing NewX saved media setting " + settingId);
        }
        text.save(value);
    }
}
