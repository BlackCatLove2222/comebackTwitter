package app.morphe.extension.newx.timeline;

import app.morphe.extension.shared.settings.BooleanSetting;

/**
 * Settings for the JP zombie reply filter. Stored through the shared
 * settings framework, the same persistence the post-filter rules use,
 * so no patch-side registration is required.
 */
public final class ZombieSettings {
    static final String KEY_THREAD = "newx.content.zombie_filtering.thread";
    static final String KEY_CHARSET = "newx.content.zombie_filtering.charset";
    static final String KEY_EMOJI_ONLY = "newx.content.zombie_filtering.emoji_only";
    static final String KEY_DUPLICATE = "newx.content.zombie_filtering.duplicate";
    static final String KEY_NEAR_DUPLICATE = "newx.content.zombie_filtering.near_duplicate";
    static final String KEY_COPY_LINK_OR_QUOTE = "newx.content.zombie_filtering.copy_link_quote";
    static final String KEY_REPEAT_SCATTER = "newx.content.zombie_filtering.repeat_scatter";
    static final String KEY_QUOTED_TEMPLATE = "newx.content.zombie_filtering.quoted_template";
    static final String KEY_QUOTE_BAIT = "newx.content.zombie_filtering.quote_bait";

    private static final BooleanSetting THREAD = new BooleanSetting(KEY_THREAD, true);
    private static final BooleanSetting CHARSET = new BooleanSetting(KEY_CHARSET, true);
    private static final BooleanSetting EMOJI_ONLY = new BooleanSetting(KEY_EMOJI_ONLY, true);
    private static final BooleanSetting DUPLICATE = new BooleanSetting(KEY_DUPLICATE, true);
    private static final BooleanSetting NEAR_DUPLICATE =
            new BooleanSetting(KEY_NEAR_DUPLICATE, true);
    private static final BooleanSetting COPY_LINK_OR_QUOTE =
            new BooleanSetting(KEY_COPY_LINK_OR_QUOTE, true);
    private static final BooleanSetting REPEAT_SCATTER =
            new BooleanSetting(KEY_REPEAT_SCATTER, true);
    private static final BooleanSetting QUOTED_TEMPLATE =
            new BooleanSetting(KEY_QUOTED_TEMPLATE, true);
    private static final BooleanSetting QUOTE_BAIT =
            new BooleanSetting(KEY_QUOTE_BAIT, true);

    private ZombieSettings() {
    }

    public static boolean threadEnabled() {
        return Boolean.TRUE.equals(THREAD.get());
    }

    public static boolean charsetEnabled() {
        return Boolean.TRUE.equals(CHARSET.get());
    }

    public static boolean emojiOnlyEnabled() {
        return Boolean.TRUE.equals(EMOJI_ONLY.get());
    }

    public static boolean duplicateEnabled() {
        return Boolean.TRUE.equals(DUPLICATE.get());
    }

    public static boolean nearDuplicateEnabled() {
        return Boolean.TRUE.equals(NEAR_DUPLICATE.get());
    }

    public static boolean copyLinkOrQuoteEnabled() {
        return Boolean.TRUE.equals(COPY_LINK_OR_QUOTE.get());
    }

    public static boolean repeatScatterEnabled() {
        return Boolean.TRUE.equals(REPEAT_SCATTER.get());
    }

    public static boolean quotedTemplateEnabled() {
        return Boolean.TRUE.equals(QUOTED_TEMPLATE.get());
    }

    public static boolean quoteBaitEnabled() {
        return Boolean.TRUE.equals(QUOTE_BAIT.get());
    }

    public static void setThread(boolean enabled) {
        THREAD.save(enabled);
    }

    public static void setCharset(boolean enabled) {
        CHARSET.save(enabled);
    }

    public static void setEmojiOnly(boolean enabled) {
        EMOJI_ONLY.save(enabled);
    }

    public static void setDuplicate(boolean enabled) {
        DUPLICATE.save(enabled);
    }

    public static void setNearDuplicate(boolean enabled) {
        NEAR_DUPLICATE.save(enabled);
    }

    public static void setCopyLinkOrQuote(boolean enabled) {
        COPY_LINK_OR_QUOTE.save(enabled);
    }

    public static void setRepeatScatter(boolean enabled) {
        REPEAT_SCATTER.save(enabled);
    }

    public static void setQuotedTemplate(boolean enabled) {
        QUOTED_TEMPLATE.save(enabled);
    }

    public static void setQuoteBait(boolean enabled) {
        QUOTE_BAIT.save(enabled);
    }
}
