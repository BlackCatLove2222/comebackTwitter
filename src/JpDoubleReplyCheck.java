package app.morphe.extension.newx.timeline;

import app.morphe.extension.newx.filteredreplies.FilteredRepliesStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Z12 glue for the JP zombie filter: a second direct reply by the same
 * author under the same post is spam (the "B replies twice to A's post"
 * pattern). Kept out of NewXTimelineFilter so the JP build can ship it
 * as one added class, like JpForeignBaitCheck.
 *
 * State is an ordered note log per conversation root, mirroring the Z8
 * repeat-scatter log: entries are {postId, authorId, parentId} in
 * display order, and re-noting a post truncates the log back to that
 * point, so timeline replays keep verdicts a pure function of display
 * order. Replies to the conversation root itself are Z8's territory
 * and are neither checked nor recorded here.
 */
final class JpDoubleReplyCheck {

    private static final int MAX_ROOTS = 32;
    private static final int MAX_LOG_PER_ROOT = 500;

    private static final Map<String, List<String[]>> LOGS =
            new LinkedHashMap<>(MAX_ROOTS, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<String[]>> eldest) {
                    return size() > MAX_ROOTS;
                }
            };

    private JpDoubleReplyCheck() {
    }

    static String check(
            FilteredRepliesStore store,
            String conversationRootId,
            String postId,
            String authorId,
            String parentId
    ) {
        if (!ZombieSettings.doubleReplyEnabled()) return null;
        if (store == null || conversationRootId == null || postId == null
                || authorId == null || parentId == null || parentId.isEmpty()) {
            return null;
        }
        // The root author never reaches this call: the evaluator's own
        // exemptions return first.
        if (parentId.equals(conversationRootId)) return null;
        String parentAuthorId = store.authorIdFor(parentId);
        // Unknown parent authorship fails open (the parent may not have
        // been noted, e.g. a focal post), and a reply under one's own
        // post is a chain continuation, not a double reply.
        if (parentAuthorId == null || authorId.equals(parentAuthorId)) return null;
        return noteAndCheck(conversationRootId, postId, authorId, parentId)
                ? ZombieFilter.REASON_DOUBLE_REPLY
                : null;
    }

    /**
     * Records one reply in display order and reports whether the same
     * author has already replied directly to the same parent post.
     */
    static synchronized boolean noteAndCheck(
            String rootPostId, String postId, String authorId, String parentId) {
        List<String[]> log = LOGS.computeIfAbsent(rootPostId, k -> new ArrayList<>());
        for (int index = 0; index < log.size(); index++) {
            if (log.get(index)[0].equals(postId)) {
                log.subList(index, log.size()).clear();
                break;
            }
        }
        boolean repeat = false;
        for (String[] entry : log) {
            if (entry[2].equals(parentId) && entry[1].equals(authorId)) {
                repeat = true;
                break;
            }
        }
        if (log.size() < MAX_LOG_PER_ROOT) {
            log.add(new String[]{postId, authorId, parentId});
        }
        return repeat;
    }

    /** Test hook: forget all recorded state. */
    static synchronized void resetForTest() {
        LOGS.clear();
    }
}
