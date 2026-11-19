package app.morphe.extension.newx.timeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.newx.postfilter.RecommendKeepStore;
import app.morphe.extension.newx.postfilter.VerifiedAccountWhitelistStore;
import app.morphe.extension.newx.utils.NewXUtils;

/**
 * JP jp24.13.9: evaluation engine for the recommendation keep filter
 * (RECOMMEND_FILTER_SPEC). Called from the type-aware pre-pass in
 * {@link NewXTimelineFilter#filterExploreItems}: the Following timelines
 * feed {@link #harvestFollowed}, and posts on the For You / Explore
 * timelines go through {@link #evaluatePost}, which returns a
 * FilteredRepliesStore reason code for a drop or null for a keep.
 *
 * <p>Fail-open throughout: a post is dropped only when its author is
 * certainly not followed and no keep category matches. Bridge failures,
 * unknown friendship state with an empty cache, and the diagnostic
 * warm-up phase all keep the post.
 *
 * <p>JP jp24.13.20: every entry point takes the viewer account key
 * (see {@code CurrentViewer}), resolved once per filter pass and
 * threaded through. The followed cache and the diagnostic are read
 * and written only inside that account's partition, so one
 * account's TRUSTED can no longer gate another
 * account, and a harvest is attributed to the account whose
 * Following response it came from. A null key (viewer unknown)
 * disables the gate entirely and harvests nothing — there is no
 * union fallback. The verdict memo records the account key
 * alongside each verdict: post IDs are global across accounts, so
 * an unkeyed memo would replay account A's drop on account B.
 */
public final class RecommendKeepGate {

    private static final int VERDICT_CACHE_MAX = 512;

    private static final class Verdict {
        final Object snapshot;
        final String reason;
        /** JP jp24.13.20: the account partition this verdict belongs to. */
        final String accountKey;

        Verdict(Object snapshot, String reason, String accountKey) {
            this.snapshot = snapshot;
            this.reason = reason;
            this.accountKey = accountKey;
        }
    }

    private static final Map<String, Verdict> VERDICTS =
            new LinkedHashMap<String, Verdict>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Verdict> eldest) {
                    return size() > VERDICT_CACHE_MAX;
                }
            };

    private RecommendKeepGate() {
    }

    /**
     * True when the gate should process posts of the given surface at all:
     * master on, the surface's target toggle on, and the friendship
     * diagnostic has not auto-killed the gate (spec §4.4).
     *
     * <p>JP jp24.13.20: the diagnostic consulted is the viewer
     * account's partition, and an unknown viewer (null key) disables
     * the gate outright.
     */
    public static boolean isEnabledFor(boolean explore, String accountKey) {
        try {
            if (accountKey == null) {
                return false;
            }
            RecommendKeepStore store = RecommendKeepStore.shared();
            RecommendKeepStore.Options options = store.snapshot().options();
            if (!options.master) {
                return false;
            }
            if (explore ? !options.targetExplore : !options.targetHome) {
                return false;
            }
            return store.diagnostic(accountKey).status != RecommendKeepStore.DIAG_DEAD;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /**
     * Harvests followed-author IDs (and diagnostic samples) from a
     * Following / Ranked-Following response. Repost items are skipped:
     * their canonical author is the original author, not the followed
     * reposter, so they would poison both the cache and the diagnostic
     * (spec §6 item 7).
     *
     * <p>JP jp24.13.18: the diagnostic hit is "the post's author ID was
     * harvestable" — the bridge-alive signal the gate itself depends
     * on (the harvested followed-ID cache). The model friendship flag
     * no longer decides death: it is not populated in Following
     * responses, so the pre-13.18 diagnostic declared DIAG_DEAD while
     * the filter worked correctly via the cache. The flag remains in
     * use as a classify-time signal only. The pass counts deltas
     * locally and merges them through the store's atomic, monotonic
     * {@link RecommendKeepStore#recordHarvestSamples} — no gate-side
     * read-modify-write of the diagnostic state.
     *
     * <p>JP jp24.13.20: the harvest is attributed to the viewer
     * account's partition. An unknown viewer (null key) harvests
     * nothing: unattributable data is dropped rather than filed
     * under a wrong account.
     */
    public static void harvestFollowed(
            Object timelineItems, TimelineModelAccess access, String accountKey) {
        try {
            if (accountKey == null) {
                return;
            }
            RecommendKeepStore store = RecommendKeepStore.shared();
            if (!store.snapshot().options().master) {
                return;
            }
            if (!(timelineItems instanceof Iterable)) {
                return;
            }
            List<Object> posts = new ArrayList<>();
            collectPosts(timelineItems, access, posts);
            Set<String> newIds = new LinkedHashSet<>();
            boolean sampling = store.diagnostic(accountKey).status
                    == RecommendKeepStore.DIAG_UNKNOWN;
            int deltaSamples = 0;
            int deltaHits = 0;
            for (Object post : posts) {
                if (access.getPostRepostedAuthorId(post) != null) {
                    continue;
                }
                String authorId = access.getPostAuthorId(post);
                boolean harvestable = authorId != null && !authorId.isEmpty();
                if (harvestable) {
                    newIds.add(authorId);
                }
                if (sampling) {
                    deltaSamples++;
                    if (harvestable) {
                        deltaHits++;
                    }
                }
            }
            if (!newIds.isEmpty()) {
                store.addFollowedIds(accountKey, newIds);
            }
            if (deltaSamples > 0) {
                store.recordHarvestSamples(accountKey, deltaSamples, deltaHits);
            }
        } catch (RuntimeException exception) {
            // Harvesting is best-effort; failures just slow the warm-up.
        }
    }

    /**
     * JP jp24.13.19: page-level pre-scan for the For You / Explore
     * fail-open (FORYOU_EMPTY_DIAGNOSIS §6). Returns true when at
     * least one post on the page is recognized as followed — the
     * friendship flag TRUE, or the author ID present in the harvested
     * followed-ID cache: the same two signals classify's author pass
     * uses. When no post on the page carries either signal, the
     * viewer-side followed data plainly is not reaching this page
     * (the cache is only the subset harvested from actually-loaded
     * Following pages), so the caller skips the gate for the whole
     * page — no drops, no records, no verdict memoization — instead
     * of dropping everything on it. Fail-open: any failure is
     * reported as "no signal", which also skips the gate for the
     * page.
     *
     * <p>JP jp24.13.20: the cache consulted is the viewer account's
     * partition only, so a page full of authors followed by ANOTHER
     * account no longer counts as signalled.
     */
    public static boolean hasFollowedSignal(
            Object timelineItems, TimelineModelAccess access, String accountKey) {
        try {
            if (!(timelineItems instanceof Iterable)) {
                return false;
            }
            Set<String> cache = RecommendKeepStore.shared().followedCache(accountKey);
            List<Object> posts = new ArrayList<>();
            collectPosts(timelineItems, access, posts);
            for (Object post : posts) {
                if (Boolean.TRUE.equals(access.getPostAuthorFollowed(post))) {
                    return true;
                }
                String authorId = access.getPostAuthorId(post);
                if (authorId != null && cache.contains(authorId)) {
                    return true;
                }
            }
            return false;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static void collectPosts(
            Object timelineItems, TimelineModelAccess access, List<Object> out) {
        for (Object item : (Iterable<?>) timelineItems) {
            if (item == null) {
                continue;
            }
            if (access.isPost(item)) {
                out.add(item);
                continue;
            }
            if (access.isModuleItem(item)) {
                Object inner = access.getModuleItem(item);
                if (inner != null && access.isPost(inner)) {
                    out.add(inner);
                }
                continue;
            }
            if (access.isModule(item)) {
                List<?> children = access.getModuleChildren(item);
                if (children == null) {
                    continue;
                }
                for (Object child : children) {
                    if (child == null) {
                        continue;
                    }
                    Object inner = child;
                    if (access.isModuleItem(child)) {
                        inner = access.getModuleItem(child);
                    }
                    if (inner != null && access.isPost(inner)) {
                        out.add(inner);
                    }
                }
            }
        }
    }

    /** Pages with fewer posts than this are never yield-guarded. */
    private static final int YIELD_MIN_PAGE_POSTS = 8;

    /** A page keeping at most this many posts counts as collapsed. */
    private static final int YIELD_MAX_KEPT = 3;

    /** JP jp24.13.21 yield guard (absolute form). */
    static boolean yieldGuardTrips(int totalPosts, int keptPosts) {
        return totalPosts >= YIELD_MIN_PAGE_POSTS && keptPosts <= YIELD_MAX_KEPT;
    }

    /**
     * JP jp24.13.23 proportional guard (user-chosen Option A,
     * 2026-11-19): a page that would keep strictly less than half of
     * its posts counts as collapsed, whatever its size. At exactly
     * half kept the gate applies. This subsumes the absolute yield
     * guard (kept &le; 3 of &ge; 8 always keeps &lt; 1/2); both are
     * kept and a page is rescued when either trips.
     */
    static boolean proportionalGuardTrips(int totalPosts, int keptPosts) {
        return keptPosts * 2 < totalPosts;
    }

    /**
     * JP jp24.13.21: page yield guard. The jp24.13.19 pre-scan only
     * rescues pages with NO followed signal at all; a page whose one
     * recognized followed author switches the gate on can still
     * collapse to a couple of posts when the account's followed cache
     * is thin (しろ, 2026-11-18 22:58: spinner + ~3 posts shown).
     * When a page of {@value #YIELD_MIN_PAGE_POSTS}+ posts would keep
     * at most {@value #YIELD_MAX_KEPT}, the gate stands down for the
     * whole page: the caller passes it unfiltered.
     *
     * <p>JP jp24.13.23: the proportional guard runs alongside the
     * absolute one — after the same dry-run, a page keeping strictly
     * less than half of its posts is also passed unfiltered (with the
     * same rollback), including pages smaller than
     * {@value #YIELD_MIN_PAGE_POSTS} that the absolute guard never
     * covered.
     *
     * <p>The check evaluates the page's posts through the ordinary
     * {@link #evaluatePost} path (so its verdicts are exactly the ones
     * the real pass would produce) after snapshotting the verdict
     * memo, the one side channel a speculative evaluation can touch.
     * When the guard trips it is restored, so the skipped page leaves
     * no trace: no memoized verdicts, and (the caller's side) no drop
     * records. When the guard does not trip, the speculative verdicts
     * stand and the real pass replays them from the memo — identical
     * decisions either way. Fail-open: any failure means
     * "do not trip" and the normal gate applies.
     */
    public static boolean pageYieldGuardTrips(
            Object timelineItems, TimelineModelAccess access, String accountKey) {
        try {
            if (accountKey == null || !(timelineItems instanceof Iterable)) {
                return false;
            }
            List<Object> posts = new ArrayList<>();
            collectPosts(timelineItems, access, posts);
            if (posts.isEmpty()) {
                return false;
            }
            Map<String, Verdict> verdictsBefore;
            synchronized (VERDICTS) {
                verdictsBefore = new LinkedHashMap<>(VERDICTS);
            }
            int kept = 0;
            for (Object post : posts) {
                if (evaluatePost(post, access, accountKey) == null) {
                    kept++;
                }
            }
            if (!yieldGuardTrips(posts.size(), kept)
                    && !proportionalGuardTrips(posts.size(), kept)) {
                return false;
            }
            synchronized (VERDICTS) {
                VERDICTS.clear();
                VERDICTS.putAll(verdictsBefore);
            }
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /**
     * Returns the reason code when the post should be dropped, or null
     * when it stays. Never throws; bridge failures keep the post and are
     * not memoized (spec §3.3).
     */
    public static String evaluatePost(
            Object post, TimelineModelAccess access, String accountKey) {
        try {
            return evaluateInternal(post, access, accountKey);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String evaluateInternal(
            Object post, TimelineModelAccess access, String accountKey) {
        if (accountKey == null) {
            // Unknown viewer: the gate is fully off (PERACCOUNT_DESIGN §4).
            return null;
        }
        RecommendKeepStore store = RecommendKeepStore.shared();
        RecommendKeepStore.Snapshot snapshot = store.snapshot();
        RecommendKeepStore.Options options = snapshot.options();
        // Warm-up / auto-killed: the gate observes but never drops (§4.4).
        if (store.diagnostic(accountKey).status != RecommendKeepStore.DIAG_TRUSTED) {
            return null;
        }
        String postId = NewXUtils.identifierToString(access.getPostId(post));
        if (postId != null && !postId.isEmpty()) {
            synchronized (VERDICTS) {
                Verdict cached = VERDICTS.get(postId);
                if (cached != null && cached.snapshot == snapshot
                        && accountKey.equals(cached.accountKey)) {
                    return cached.reason;
                }
            }
        }
        String reason = classify(post, access, store, snapshot, options, accountKey);
        if (postId != null && !postId.isEmpty()) {
            synchronized (VERDICTS) {
                VERDICTS.put(postId, new Verdict(snapshot, reason, accountKey));
            }
        }
        return reason;
    }

    private static String classify(
            Object post,
            TimelineModelAccess access,
            RecommendKeepStore store,
            RecommendKeepStore.Snapshot snapshot,
            RecommendKeepStore.Options options,
            String accountKey) {
        String authorId = access.getPostAuthorId(post);
        String screenName = access.getPostAuthorScreenName(post);

        // Author pass: followed / super-followed / whitelisted always stay.
        if (options.followedAlways) {
            if (isWhitelisted(authorId, screenName)) {
                return null;
            }
            Boolean followed = access.getPostAuthorFollowed(post);
            // OR semantics (jp24.13.9 decision, see JP24_13_9_NOTES): the
            // harvested followed-ID cache is ground truth from the
            // フォロー中 tab itself, so cache membership keeps the post
            // even when this response's friendship flag is false or
            // absent — the fail-open direction for followed authors.
            boolean isFollowed = Boolean.TRUE.equals(followed)
                    || (authorId != null && store.followedCache(accountKey).contains(authorId));
            if (isFollowed) {
                return null;
            }
        }

        String body = access.getPostTextForFilter(post);
        String quoted = access.getPostQuotedText(post);
        String displayName = access.getPostAuthorNameForFilter(post);
        String bio = access.getPostAuthorBio(post);
        String tags = joinHashtags(access.getPostHashtags(post));
        String[] fields = {body, quoted, displayName, screenName, bio, tags};

        if (snapshot.findPhrase(RecommendKeepStore.ID_TECH, fields) != null) {
            return null;
        }
        if (snapshot.findPhrase(RecommendKeepStore.ID_ANIMALS, fields) != null) {
            return null;
        }
        boolean textless = isBlank(body) && isBlank(quoted);
        if (textless && options.keepTextless) {
            return null;
        }
        return RecommendKeepStore.REASON_MISS;
    }

    private static boolean isWhitelisted(String authorId, String screenName) {
        try {
            Set<String> whitelist = VerifiedAccountWhitelistStore.shared().snapshot();
            return whitelist != null && !whitelist.isEmpty()
                    && VerifiedAccountWhitelistStore.matches(
                            whitelist, authorId, screenName);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static String joinHashtags(List<String> hashtags) {
        if (hashtags == null || hashtags.isEmpty()) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        for (String tag : hashtags) {
            if (tag == null || tag.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(tag);
        }
        return builder.length() == 0 ? null : builder.toString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
