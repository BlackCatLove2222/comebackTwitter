package app.morphe.extension.newx.postfilter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * JP jp24.13: one category for the category-based mute (カテゴリ別ミュート).
 *
 * <p>A category bundles a set of phrases with the post fields they are matched
 * against. Preset categories ship with the extension and cannot be deleted
 * (their vocabulary stays editable); custom categories are fully user managed.
 * Instances are immutable; edits go through {@link MuteCategoryStore}, which
 * publishes a fresh snapshot per change.
 */
public final class MuteCategory {
    public final String id;
    public final String name;
    public final boolean preset;
    public final boolean enabled;
    public final boolean matchContent;
    public final boolean matchQuoted;
    public final boolean matchScreenName;
    public final boolean matchDisplayName;
    /** Whether this category may run the code-fixed doxxing-pattern detector. */
    public final boolean detectorAvailable;
    public final boolean detectorEnabled;
    /** Phrases exactly as the user entered them (matching normalizes copies). */
    public final List<String> phrases;

    public MuteCategory(
            String id,
            String name,
            boolean preset,
            boolean enabled,
            boolean matchContent,
            boolean matchQuoted,
            boolean matchScreenName,
            boolean matchDisplayName,
            boolean detectorAvailable,
            boolean detectorEnabled,
            List<String> phrases) {
        this.id = id;
        this.name = name;
        this.preset = preset;
        this.enabled = enabled;
        this.matchContent = matchContent;
        this.matchQuoted = matchQuoted;
        this.matchScreenName = matchScreenName;
        this.matchDisplayName = matchDisplayName;
        this.detectorAvailable = detectorAvailable;
        this.detectorEnabled = detectorEnabled;
        this.phrases = Collections.unmodifiableList(new ArrayList<>(phrases));
    }

    public MuteCategory withEnabled(boolean value) {
        return new MuteCategory(id, name, preset, value, matchContent, matchQuoted,
                matchScreenName, matchDisplayName, detectorAvailable, detectorEnabled, phrases);
    }

    public MuteCategory withName(String value) {
        return new MuteCategory(id, value, preset, enabled, matchContent, matchQuoted,
                matchScreenName, matchDisplayName, detectorAvailable, detectorEnabled, phrases);
    }

    public MuteCategory withScopes(
            boolean content, boolean quoted, boolean screenName, boolean displayName) {
        return new MuteCategory(id, name, preset, enabled, content, quoted,
                screenName, displayName, detectorAvailable, detectorEnabled, phrases);
    }

    public MuteCategory withDetectorEnabled(boolean value) {
        return new MuteCategory(id, name, preset, enabled, matchContent, matchQuoted,
                matchScreenName, matchDisplayName, detectorAvailable, value, phrases);
    }

    public MuteCategory withPhrases(List<String> value) {
        return new MuteCategory(id, name, preset, enabled, matchContent, matchQuoted,
                matchScreenName, matchDisplayName, detectorAvailable, detectorEnabled, value);
    }
}
