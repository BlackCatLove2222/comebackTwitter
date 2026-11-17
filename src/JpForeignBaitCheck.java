package app.morphe.extension.newx.timeline;

import app.morphe.extension.newx.filteredreplies.FilteredRepliesStore;

/**
 * Z11' glue for the JP zombie filter: kept out of NewXTimelineFilter so
 * the JP build can ship it as one added class. Returns the reason to
 * hide the reply, or null to keep it. All inputs fail open.
 */
final class JpForeignBaitCheck {

    private JpForeignBaitCheck() {
    }

    static String check(
            FilteredRepliesStore store,
            String conversationRootId,
            Object post,
            TimelineModelAccess modelAccess,
            String normalizedText
    ) {
        if (!ZombieSettings.foreignBaitEnabled()) return null;
        String rootText = store.postTextFor(conversationRootId);
        String displayName = modelAccess.getPostAuthorDisplayName(post);
        return ZombieFilter.isForeignBait(rootText, normalizedText, displayName)
                ? ZombieFilter.REASON_FOREIGN_BAIT
                : null;
    }
}
