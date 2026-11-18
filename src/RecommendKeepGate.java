package app.morphe.extension.newx.timeline;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import app.morphe.extension.newx.postfilter.PostFilterMatcher;
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
 * <p>JP jp24.13.10: cosplay-path keeps are additionally subject to the
 * ratio cap in {@link RecommendKeepStore} — a cosplay post is kept only
 * when enough non-cosplay keeps separate it from the previous kept
 * cosplay post. Keeps by every other path (followed / whitelisted
 * author, tech, animals, textless) feed that spacing counter and are
 * never ratio-dropped; a post matching tech or animals AND cosplay is
 * decided by the non-cosplay category and never counted as cosplay.
 * Accounting happens only here in classify, i.e. once per post per
 * settings snapshot; the store's persisted outcomes keep the decisions
 * stable across restarts, when this class's verdict memo is empty.
 */
public final class RecommendKeepGate {

    private static final int VERDICT_CACHE_MAX = 512;

    /** Asian-region cosplay/event tags (normalized forms), spec §2.4. */
    private static final String[] REGION_TAGS = {
            "コミケ", "となコス", "アコスタ", "池ハロ", "ワンフェス",
            "漫展", "chinajoy", "萤火虫", "코믹월드", "일러스타",
    };

    /** Comic Market numbering (C105, C106, ...), a strong JP-region signal. */
    private static final Pattern COMIKET_NUMBER =
            Pattern.compile("(?<![a-z0-9])c1[0-9][0-9](?![0-9])");

    /**
     * Characters that occur only in simplified Chinese (left) or only in
     * traditional Chinese (right), as counterpart pairs. Either set marks
     * the text as Chinese; Han-only text with neither stays unresolvable.
     */
    private static final String SIMPLIFIED_ONLY =
            "发国爱这么们无云气产关兴农动华协单卖买乱亚亲亿仅从仓价众优会伤余侠侣侦侧侨俭债倾假偿储儿兑克党兰兵养兽内冈册写军冲决况冻净凉减凑厨厦县参双变叙叶号叹吗吴呈员呜呼咒咙咚咬咳哀哄哇哈响哑哒哔哗哝哥哦哨哩哪哭唉唤唯商啧啪啮啰啸啼喀喂喃善喆喇喋喎喏喔喙喟喧喨丧喫乔单喰喵喻喽嗅吗嗑嗓嗔嗖嗙嗜嗝嗞嗟嗡嗤嗥嗦嗨嗬嗮嗰嗲嗳嗵嗶嗷嗽嘀嘆嘈嘉嘌嘍嘎嘏嘔嘖嘗嘘嘙嘚嘜嘞嘟嘢嘤嘧嘩嘫嘬嘭嘮嘯嘰嘲嘳嘴嘵嘶嘷嘸嘹嘻嘼嘿噁噂噄噅噇噉噌噍噎噏噐噑噓噔噕噖噗噘噙噚噛噜噝噞噠噡噢噣噤噥噦噧器噩噪噫噬噭噮噯噰噱噲噳噴噵噶噷噸噹噺噻噼噽噾噿嚀嚁嚂嚃嚄嚅嚆嚇嚈嚉嚊嚋嚌嚍嚎嚏嚐嚑嚒嚓嚔嚕嚖嚗嚘嚙嚚嚛嚜嚝嚞嚟嚠嚡嚢嚣嚤嚥嚦嚧嚨嚩嚪嚫嚬嚭嚮嚯嚰嚱嚲嚳嚴嚵嚶嚷嚸嚹嚺嚻嚼嚽嚾嚿";
    private static final String TRADITIONAL_ONLY =
            "髮國愛這麼們無雲氣產關興農動華協單賣買亂亞親億僅從倉價眾優會傷餘俠侶偵側僑儉債傾假償儲兒兌克黨蘭兵養獸內岡冊寫軍沖決況凍淨涼減湊廚廈縣參雙變敘葉號嘆嗎吳呈員嗚呼咒嚨咚咬咳哀哄哇哈響啞噠嗶嘩噥哥哦哨哩哪哭唉喚唯商嘖啪嚙囉嘯啼喀餵喃善喆喇喋喎喏喔喙喟喧喨喪喫喬單喰喵喻嘍嗅嗎嗑嗓嗔嗖嗙嗜嗝嗞嗟嗡嗤嗥嗦嗨嗬瞄嗰嗲噯嗵嗶嗷嗽嘀嘆嘈嘉嘌嘍嘎嘏嘔嘖嘗噓嘙嘚嘜嘞嘟嘢嚶嘧嘩嘫嘬嘭嘮嘯嘰嘲嘳嘴嘵嘶嘷嘸嘹嘻嘼嘿噁噂噄噅噇噉噌噍噎噏噐噑噓噔噕噖噗噘噙噚噛嚕噝噞噠噡噢噣噤噥噦噧器噩噪噫噬噭噮噯噰噱噲噳噴噵噶噷噸噹噺噻噼噽噾噿嚀嚁嚂嚃嚄嚅嚆嚇嚈嚉嚊嚋嚌嚍嚎嚏嚐嚑嚒嚓嚔嚕嚖嚗嚘嚙嚚嚛嚜嚝嚞嚟嚠嚡嚢囂嚤嚥嚦嚧嚨嚩嚪嚫嚬嚭嚮嚯嚰嚱嚲嚳嚴嚵嚶嚷嚸嚹嚺嚻嚼嚽嚾嚿";

    private static final class Verdict {
        final Object snapshot;
        final String reason;

        Verdict(Object snapshot, String reason) {
            this.snapshot = snapshot;
            this.reason = reason;
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
     */
    public static boolean isEnabledFor(boolean explore) {
        try {
            RecommendKeepStore store = RecommendKeepStore.shared();
            RecommendKeepStore.Options options = store.snapshot().options();
            if (!options.master) {
                return false;
            }
            if (explore ? !options.targetExplore : !options.targetHome) {
                return false;
            }
            return store.diagnostic().status != RecommendKeepStore.DIAG_DEAD;
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
     */
    public static void harvestFollowed(Object timelineItems, TimelineModelAccess access) {
        try {
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
            boolean sampling = store.diagnostic().status
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
                store.addFollowedIds(newIds);
            }
            if (deltaSamples > 0) {
                store.recordHarvestSamples(deltaSamples, deltaHits);
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
     * Following pages, and it is shared across accounts), so the
     * caller skips the gate for the whole page — no drops, no
     * records, no verdict memoization — instead of dropping
     * everything on it. Fail-open: any failure is reported as "no
     * signal", which also skips the gate for the page.
     */
    public static boolean hasFollowedSignal(Object timelineItems, TimelineModelAccess access) {
        try {
            if (!(timelineItems instanceof Iterable)) {
                return false;
            }
            Set<String> cache = RecommendKeepStore.shared().followedCache();
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

    /**
     * Returns the reason code when the post should be dropped, or null
     * when it stays. Never throws; bridge failures keep the post and are
     * not memoized (spec §3.3).
     */
    public static String evaluatePost(Object post, TimelineModelAccess access) {
        try {
            return evaluateInternal(post, access);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String evaluateInternal(Object post, TimelineModelAccess access) {
        RecommendKeepStore store = RecommendKeepStore.shared();
        RecommendKeepStore.Snapshot snapshot = store.snapshot();
        RecommendKeepStore.Options options = snapshot.options();
        // Warm-up / auto-killed: the gate observes but never drops (§4.4).
        if (store.diagnostic().status != RecommendKeepStore.DIAG_TRUSTED) {
            return null;
        }
        String postId = NewXUtils.identifierToString(access.getPostId(post));
        if (postId != null && !postId.isEmpty()) {
            synchronized (VERDICTS) {
                Verdict cached = VERDICTS.get(postId);
                if (cached != null && cached.snapshot == snapshot) {
                    return cached.reason;
                }
            }
        }
        String reason = classify(post, access, store, snapshot, options);
        if (postId != null && !postId.isEmpty()) {
            synchronized (VERDICTS) {
                VERDICTS.put(postId, new Verdict(snapshot, reason));
            }
        }
        return reason;
    }

    private static String classify(
            Object post,
            TimelineModelAccess access,
            RecommendKeepStore store,
            RecommendKeepStore.Snapshot snapshot,
            RecommendKeepStore.Options options) {
        String authorId = access.getPostAuthorId(post);
        String screenName = access.getPostAuthorScreenName(post);
        String postId = NewXUtils.identifierToString(access.getPostId(post));
        int ratioPercent = options.cosplayRatioPercent;

        // Author pass: followed / super-followed / whitelisted always stay.
        if (options.followedAlways) {
            if (isWhitelisted(authorId, screenName)) {
                store.accountOtherKeep(postId, ratioPercent);
                return null;
            }
            Boolean followed = access.getPostAuthorFollowed(post);
            // OR semantics (jp24.13.9 decision, see JP24_13_9_NOTES): the
            // harvested followed-ID cache is ground truth from the
            // フォロー中 tab itself, so cache membership keeps the post
            // even when this response's friendship flag is false or
            // absent — the fail-open direction for followed authors.
            boolean isFollowed = Boolean.TRUE.equals(followed)
                    || (authorId != null && store.followedCache().contains(authorId));
            if (isFollowed) {
                store.accountOtherKeep(postId, ratioPercent);
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
            store.accountOtherKeep(postId, ratioPercent);
            return null;
        }
        if (snapshot.findPhrase(RecommendKeepStore.ID_ANIMALS, fields) != null) {
            store.accountOtherKeep(postId, ratioPercent);
            return null;
        }
        if (snapshot.findPhrase(RecommendKeepStore.ID_COSPLAY, fields) != null) {
            // Male-cosplayer exclusion (user addition): checked on the
            // author's self-description and the post text, before the
            // region/media conditions, with its own reason code.
            if (snapshot.findExclusion(displayName, bio, body) != null) {
                return RecommendKeepStore.REASON_COS_MALE;
            }
            if (!options.nonAsiaCosplay
                    && !isAsianRegion(post, access, body, displayName, bio, tags)) {
                return RecommendKeepStore.REASON_COS_REGION;
            }
            if (options.cosMediaRequired && !access.getPostHasMedia(post)) {
                return RecommendKeepStore.REASON_COS_NOMEDIA;
            }
            if (options.followerThresholdEnabled) {
                long followers = access.getPostAuthorFollowersCount(post);
                if (followers >= 0 && followers < options.followerThreshold) {
                    return RecommendKeepStore.REASON_MISS;
                }
            }
            // Ratio cap (jp24.13.10): only posts kept SOLELY via this
            // cosplay path reach here — tech/animal overlaps returned
            // above, and followed authors returned in the author pass.
            if (store.accountCosplay(postId, ratioPercent)
                    == RecommendKeepStore.COSPLAY_DROPPED) {
                return RecommendKeepStore.REASON_COS_RATIO;
            }
            return null;
        }

        boolean textless = isBlank(body) && isBlank(quoted);
        if (textless && options.keepTextless) {
            store.accountOtherKeep(postId, ratioPercent);
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

    /**
     * Asian-region signal for the cosplay rule (spec §2.4). The server
     * language field wins when it carries a definite value; otherwise the
     * scripts present in the text decide, with Asian cosplay event tags
     * as corroboration. Han-only text with no variant-specific character
     * is unresolvable and counts as not Asian.
     */
    static boolean isAsianRegion(
            Object post,
            TimelineModelAccess access,
            String body,
            String displayName,
            String bio,
            String tags) {
        String language = access.getPostLanguage(post);
        if (language != null && !language.isEmpty()) {
            String normalized = language.toLowerCase(Locale.ROOT);
            if (normalized.equals("ja") || normalized.equals("ko")
                    || normalized.startsWith("zh")) {
                return true;
            }
            if (!normalized.equals("und")) {
                return false;
            }
        }
        String[] texts = {body, displayName, bio, tags};
        for (String text : texts) {
            if (text == null) {
                continue;
            }
            for (int index = 0; index < text.length(); index++) {
                char c = text.charAt(index);
                if ((c >= 0x3040 && c <= 0x30FF)          // kana
                        || (c >= 0xAC00 && c <= 0xD7A3)   // hangul syllables
                        || (c >= 0x1100 && c <= 0x11FF)   // hangul jamo
                        || (c >= 0x3130 && c <= 0x318F)   // hangul compat jamo
                        || SIMPLIFIED_ONLY.indexOf(c) >= 0
                        || TRADITIONAL_ONLY.indexOf(c) >= 0) {
                    return true;
                }
            }
        }
        for (String text : texts) {
            if (text == null) {
                continue;
            }
            String normalized = PostFilterMatcher.normalize(text);
            for (String tag : REGION_TAGS) {
                if (normalized.contains(tag)) {
                    return true;
                }
            }
            if (COMIKET_NUMBER.matcher(normalized).find()) {
                return true;
            }
        }
        return false;
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
