package app.morphe.extension.newx.postfilter;

import app.morphe.extension.newx.settings.NewXLogger;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.StringSetting;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * JP jp24.13.9: store for the recommendation keep filter (おすすめキープ
 * フィルター). Mirrors {@link MuteCategoryStore}: the bundled preset
 * vocabulary below is only the first-run seed; everything afterwards lives
 * in this store (one JSON document in a string setting), so the vocabulary
 * stays editable and the preset words can be re-added on demand.
 *
 * <p>The document also carries the gate options, the followed-author
 * ID cache harvested from the Following timelines, and the friendship
 * diagnostic state. Only the categories / options are part of the
 * {@link Snapshot}; cache and diagnostic updates persist without
 * invalidating snapshots (and therefore without invalidating verdicts
 * memoized against them).
 *
 * <p>JP jp24.13.11: phrase matching semantics changed (matcher v2).
 * Phrases consisting solely of ASCII characters now match only at word
 * boundaries (neither neighbour may be an ASCII letter or digit), so
 * short Latin tokens no longer fire inside ordinary words ("ai" inside
 * "again" / "said", "rust" inside "trust", "api" inside "capital").
 * Phrases containing any non-ASCII character (CJK, emoji, …) keep the
 * legacy substring semantics — CJK text has no word spaces to bound by.
 * This matcher is private to this store: the keyword filter
 * (PostFilterRuleStore / AhoMatcher) and the category mute
 * (MuteCategoryStore's own matcher) keep legacy substring behaviour.
 * The semantics version is stamped into the persisted document: a
 * document written by matcher v1 is migrated on load (schema 2:
 * the bare seed token "AI" — meaningless under boundary matching as a
 * tech signal and the worst false-keep source — is stripped from the
 * tech list).
 *
 * <p>JP jp24.13.18 (schema 3): the friendship diagnostic was redefined.
 * Up to 13.17 the diagnostic counted only the model friendship flag
 * during Following-timeline harvests, but that flag is not populated
 * in Following responses, so the diagnostic declared DIAG_DEAD while
 * the gate worked correctly through the harvested followed-ID cache —
 * and a non-atomic read-modify-write in the harvest path could roll a
 * reached DIAG_TRUSTED back to UNKNOWN and lose counted hits. The hit
 * is now "the post's author ID was harvestable" (the bridge-alive
 * signal the gate itself depends on), all diagnostic updates go
 * through the atomic, monotonic {@link #recordHarvestSamples}, and a
 * DIAG_DEAD persisted by the old semantics is reset to UNKNOWN once
 * on load of a pre-schema-3 document (the schema-3 migration) so
 * it re-diagnoses under the new rules; a DEAD produced by the new
 * semantics is stored at schema 3 and persists normally.
 *
 * <p>JP jp24.13.20 (schema 4): the followed-ID cache and the
 * diagnostic are partitioned per account. Up to 13.19 both were a
 * single app-global union: the cache was dominated by whichever
 * account's Following pages had been loaded, yet the moment the
 * diagnostic reached TRUSTED the gate applied that graph to EVERY
 * account (the 13.18/13.19 For You incidents). Both now live under
 * {@code accounts: {userId: …}} keyed by the viewer's user ID (see
 * {@code CurrentViewer}). Categories and options stay shared — they
 * are user settings, not account state.
 * Migration DISCARDS the union cache and diagnostic: neither carries
 * any record of which account it was harvested under, so attribution
 * is impossible, and seeding it into partitions would persist
 * exactly the wrong-graph contamination this schema exists to
 * remove. Each account re-warms from UNKNOWN (the gate drops
 * nothing and the 13.19 pre-scan passes pages while a partition's
 * diagnostic is not TRUSTED, so the warm-up period is the
 * pre-13.9 unfiltered state, not a broken one).
 * Partitions are LRU-capped at {@link #PARTITION_CAP}; eviction
 * loses only warm-up state and fails safe.
 */
public final class RecommendKeepStore {
    private static final int SCHEMA_VERSION = 4;

    /** Phrase-matching semantics version (see class doc). */
    private static final int MATCHER_SEMANTICS_VERSION = 2;

    public static final String ID_TECH = "tech";
    public static final String ID_ANIMALS = "animals";

    /** Filtered-replies reason code recorded for gate drops. */
    public static final String REASON_MISS = "REC_KEEP_MISS";

    /** Friendship diagnostic states (spec §1.3 / §4.4). */
    public static final int DIAG_UNKNOWN = 0;
    public static final int DIAG_TRUSTED = 1;
    public static final int DIAG_DEAD = 2;

    /** Harvested Following-timeline samples before the diagnostic decides. */
    public static final int DIAG_TARGET_SAMPLES = 40;

    private static final int FOLLOWED_CACHE_CAP = 5000;

    /** JP jp24.13.20: LRU cap on per-account partitions. */
    private static final int PARTITION_CAP = 8;

    public interface Persistence {
        String readJson();

        void writeJson(String value);

        boolean readSeeded();

        void writeSeeded(boolean seeded);
    }

    private static final class SharedPreferencesPersistence implements Persistence {
        private final StringSetting json =
                new StringSetting("newx.content.recommend_keep", "");
        private final BooleanSetting seeded =
                new BooleanSetting("newx.content.recommend_keep.seeded", false);

        @Override
        public String readJson() {
            return json.get();
        }

        @Override
        public void writeJson(String value) {
            json.save(value);
        }

        @Override
        public boolean readSeeded() {
            Boolean value = seeded.get();
            return value != null && value;
        }

        @Override
        public void writeSeeded(boolean value) {
            seeded.save(value);
        }
    }

    public static final class ValidationException extends IllegalArgumentException {
        public enum Error {
            BLANK_PHRASE,
            DUPLICATE_PHRASE,
            UNKNOWN_CATEGORY
        }

        public final Error error;

        public ValidationException(Error error, String message) {
            super(message);
            this.error = error;
        }
    }

    /** One keep category: an editable phrase list with an enable toggle. */
    public static final class Category {
        public final String id;
        public final String name;
        public final boolean enabled;
        public final List<String> phrases;

        public Category(String id, String name, boolean enabled, List<String> phrases) {
            this.id = id;
            this.name = name;
            this.enabled = enabled;
            this.phrases = Collections.unmodifiableList(new ArrayList<>(phrases));
        }

        public Category withEnabled(boolean value) {
            return new Category(id, name, value, phrases);
        }

        public Category withPhrases(List<String> value) {
            return new Category(id, name, enabled, value);
        }
    }

    /** Gate options (spec §5), with the approved defaults. */
    public static final class Options {
        public final boolean master;
        public final boolean targetHome;
        public final boolean targetExplore;
        public final boolean followedAlways;
        public final boolean keepTextless;

        public Options(
                boolean master,
                boolean targetHome,
                boolean targetExplore,
                boolean followedAlways,
                boolean keepTextless) {
            this.master = master;
            this.targetHome = targetHome;
            this.targetExplore = targetExplore;
            this.followedAlways = followedAlways;
            this.keepTextless = keepTextless;
        }

        public static Options defaults() {
            return new Options(true, true, true, true, false);
        }
    }

    /**
     * Bridge-alive diagnostic counters (spec §6 item 1). A "hit" is a
     * sampled Following-timeline post whose author ID was harvestable
     * (jp24.13.18; before that, a populated friendship flag).
     */
    public static final class Diagnostic {
        public final int samples;
        public final int hits;
        public final int status;

        public Diagnostic(int samples, int hits, int status) {
            this.samples = samples;
            this.hits = hits;
            this.status = status;
        }
    }

    /**
     * JP jp24.13.20: one account's partition — its harvested
     * followed-author ID cache and its diagnostic state. Partitions
     * are keyed by the viewer's user ID (string form) and held in an
     * access-ordered map (LRU, see {@link #PARTITION_CAP}).
     */
    private static final class AccountData {
        final LinkedHashSet<String> followedCache = new LinkedHashSet<>();
        Diagnostic diagnostic = new Diagnostic(0, 0, DIAG_UNKNOWN);
    }

    private static final class PhraseMatcher {
        /** Normalized phrase -> phrase as entered, in entry order. */
        final LinkedHashMap<String, String> normalizedPhrases = new LinkedHashMap<>();

        PhraseMatcher(List<String> phrases) {
            for (String phrase : phrases) {
                String normalized = PostFilterMatcher.normalize(phrase);
                if (!normalized.isEmpty()) {
                    normalizedPhrases.putIfAbsent(normalized, phrase);
                }
            }
        }

        /** Returns the phrase as entered when it occurs in {@code text}, else null. */
        String findIn(String text) {
            if (text == null || normalizedPhrases.isEmpty()) {
                return null;
            }
            String normalized = PostFilterMatcher.normalize(text);
            if (normalized.isEmpty()) {
                return null;
            }
            for (Map.Entry<String, String> entry : normalizedPhrases.entrySet()) {
                String phrase = entry.getKey();
                // JP jp24.13.11 (matcher v2): pure-ASCII phrases match
                // only at word boundaries; any phrase containing a
                // non-ASCII character keeps substring semantics.
                boolean hit = isPureAscii(phrase)
                        ? containsAtWordBoundary(normalized, phrase)
                        : normalized.contains(phrase);
                if (hit) {
                    return entry.getValue();
                }
            }
            return null;
        }

        private static boolean isPureAscii(String value) {
            for (int index = 0; index < value.length(); index++) {
                if (value.charAt(index) >= 0x80) {
                    return false;
                }
            }
            return !value.isEmpty();
        }

        /**
         * True when {@code phrase} occurs in {@code text} with neither
         * neighbouring character (where one exists) being an ASCII letter
         * or digit. Every occurrence is tried: an early in-word hit does
         * not mask a later bounded one.
         */
        private static boolean containsAtWordBoundary(String text, String phrase) {
            int from = 0;
            while (true) {
                int index = text.indexOf(phrase, from);
                if (index < 0) {
                    return false;
                }
                int end = index + phrase.length();
                boolean beforeOk = index == 0
                        || !isAsciiLetterOrDigit(text.charAt(index - 1));
                boolean afterOk = end == text.length()
                        || !isAsciiLetterOrDigit(text.charAt(end));
                if (beforeOk && afterOk) {
                    return true;
                }
                from = index + 1;
            }
        }

        private static boolean isAsciiLetterOrDigit(char c) {
            return (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9');
        }

        String findInAny(String... fields) {
            if (fields == null) {
                return null;
            }
            for (String field : fields) {
                String hit = findIn(field);
                if (hit != null) {
                    return hit;
                }
            }
            return null;
        }
    }

    /** Immutable view of categories + options. */
    public static final class Snapshot {
        private final List<Category> categories;
        private final Map<String, PhraseMatcher> matchers;
        private final Options options;

        Snapshot(
                List<Category> categories,
                Options options) {
            this.categories = Collections.unmodifiableList(new ArrayList<>(categories));
            Map<String, PhraseMatcher> built = new LinkedHashMap<>();
            for (Category category : categories) {
                built.put(category.id, new PhraseMatcher(category.phrases));
            }
            this.matchers = Collections.unmodifiableMap(built);
            this.options = options;
        }

        public List<Category> categories() {
            return categories;
        }

        public Category category(String id) {
            for (Category category : categories) {
                if (category.id.equals(id)) {
                    return category;
                }
            }
            return null;
        }

        public Options options() {
            return options;
        }

        /**
         * First phrase of the given category occurring in any of the given
         * fields, or null. Disabled or unknown categories never match.
         */
        public String findPhrase(String categoryId, String... fields) {
            Category category = category(categoryId);
            if (category == null || !category.enabled) {
                return null;
            }
            PhraseMatcher matcher = matchers.get(categoryId);
            return matcher == null ? null : matcher.findInAny(fields);
        }
    }

    /** Bundled preset categories: the first-run seed and the restore source. */
    public static List<Category> presetCategories() {
        List<Category> presets = new ArrayList<>();
        presets.add(new Category(ID_TECH, "技術系", true, Arrays.asList(
                "プログラミング", "エンジニア", "ソフトウェア", "ハードウェア", "開発環境",
                "実装した", "デバッグ", "コンパイル", "ビルドした", "リリースした",
                "アップデート", "バグ修正", "サーバー", "サーバ", "Linux", "Ubuntu",
                "Debian", "Android", "カスタムROM", "ルート化", "ブートローダー",
                "ファームウェア", "自作PC", "GPU", "CPU", "ガジェット", "スマホ",
                "電子工作", "Arduino", "ラズパイ", "Raspberry", "Python", "JavaScript",
                "TypeScript", "Kotlin", "Rust", "GitHub", "オープンソース", "API",
                "SQL", "データベース", "Docker", "ネットワーク", "ルーター", "NAS",
                "Wi-Fi", "機械学習", "生成AI", "LLM", "人工知能", "半導体", "基板",
                "回路", "組み込み", "3Dプリンタ", "プログラム", "ソースコード",
                "リポジトリ", "テック", "テクノロジー", "PC自作", "計測器",
                "プログラマ", "コーディング", "スクリプト", "ターミナル", "シェル",
                "커널", "프로그래밍", "程序员", "编程", "程序", "开发", "代码",
                "服务器", "인공지능")));
        presets.add(new Category(ID_ANIMALS, "動物", true, Arrays.asList(
                "猫", "ねこ", "ネコ", "にゃん", "ニャン", "子猫", "仔猫", "愛猫",
                "保護猫", "野良猫", "三毛猫", "茶トラ", "ハチワレ", "肉球", "猫好き",
                "犬", "いぬ", "イヌ", "わんこ", "子犬", "愛犬", "柴犬",
                "うさぎ", "ウサギ", "ハムスター", "インコ", "文鳥", "セキセイ",
                "亀", "金魚", "熱帯魚", "アクアリウム", "ペット", "もふもふ",
                "モフモフ", "フェレット", "爬虫類", "両生類", "乗馬", "動物園",
                "水族館", "cat", "cats", "kitten", "kittens", "dog", "dogs",
                "puppy", "puppies", "猫咪", "小猫",
                "狗狗", "小狗", "兔子", "고양이", "강아지", "냥", "멍멍",
                "🐱", "🐈", "🐶", "🐕", "🐰", "🐹", "🐾")));
        return presets;
    }

    private static volatile RecommendKeepStore shared;

    public static RecommendKeepStore shared() {
        RecommendKeepStore local = shared;
        if (local == null) {
            synchronized (RecommendKeepStore.class) {
                local = shared;
                if (local == null) {
                    local = new RecommendKeepStore(new SharedPreferencesPersistence());
                    shared = local;
                }
            }
        }
        return local;
    }

    private final Persistence persistence;
    private Snapshot snapshot;
    private List<Category> categories;
    private Options options;
    /** JP jp24.13.20: per-account partitions, access-ordered (LRU). */
    private LinkedHashMap<String, AccountData> accounts;
    /** Set by {@link #fromJson} when a pre-current-schema document was migrated. */
    private boolean schemaMigrated;

    public RecommendKeepStore(Persistence persistence) {
        this.persistence = persistence;
    }

    public synchronized Snapshot snapshot() {
        ensureLoaded();
        return snapshot;
    }

    // ---- category / option edits (each republishes the snapshot)

    public synchronized void setCategoryEnabled(String id, boolean enabled) {
        ensureLoaded();
        List<Category> updated = new ArrayList<>(categories);
        int index = indexOf(updated, id);
        updated.set(index, updated.get(index).withEnabled(enabled));
        categories = updated;
        publish();
    }

    public synchronized void addPhrase(String id, String phrase) {
        String trimmed = requirePhrase(phrase);
        ensureLoaded();
        List<Category> updated = new ArrayList<>(categories);
        int index = indexOf(updated, id);
        List<String> phrases = new ArrayList<>(updated.get(index).phrases);
        phrases.add(trimmed);
        updated.set(index, updated.get(index).withPhrases(phrases));
        categories = updated;
        publish();
    }

    public synchronized void removePhrase(String id, String phrase) {
        ensureLoaded();
        List<Category> updated = new ArrayList<>(categories);
        int index = indexOf(updated, id);
        List<String> phrases = new ArrayList<>(updated.get(index).phrases);
        phrases.remove(phrase);
        updated.set(index, updated.get(index).withPhrases(phrases));
        categories = updated;
        publish();
    }

    /** Re-adds any bundled preset phrases the user removed. Returns how many were added. */
    public synchronized int addPresetPhrases(String id) {
        ensureLoaded();
        Category preset = null;
        for (Category candidate : presetCategories()) {
            if (candidate.id.equals(id)) {
                preset = candidate;
                break;
            }
        }
        if (preset == null) {
            return 0;
        }
        List<Category> updated = new ArrayList<>(categories);
        int index = indexOf(updated, id);
        List<String> phrases = new ArrayList<>(updated.get(index).phrases);
        List<String> existingNormalized = new ArrayList<>();
        for (String existing : phrases) {
            existingNormalized.add(PostFilterMatcher.normalize(existing));
        }
        int added = 0;
        for (String phrase : preset.phrases) {
            if (!existingNormalized.contains(PostFilterMatcher.normalize(phrase))) {
                phrases.add(phrase);
                added++;
            }
        }
        if (added > 0) {
            updated.set(index, updated.get(index).withPhrases(phrases));
            categories = updated;
            publish();
        }
        return added;
    }

    public synchronized void setOptions(Options updated) {
        ensureLoaded();
        options = updated;
        publish();
    }

    // ---- per-account followed cache + diagnostic (persisted, no snapshot change)

    /**
     * JP jp24.13.20: the partition for {@code key}, materializing it
     * (and LRU-evicting the eldest partition past the cap) when
     * {@code create} is set. Read-only lookups never materialize, so
     * merely displaying an unknown account leaves no trace.
     */
    private AccountData partition(String key, boolean create) {
        AccountData data = accounts.get(key);
        if (data == null && create) {
            data = new AccountData();
            accounts.put(key, data);
            evictPartitions();
        }
        return data;
    }

    /** Drops eldest partitions (cache + diagnostic) past the cap. */
    private void evictPartitions() {
        while (accounts.size() > PARTITION_CAP) {
            Iterator<String> iterator = accounts.keySet().iterator();
            iterator.next();
            iterator.remove();
        }
    }

    /**
     * The followed-author ID cache of one account's partition. An
     * unknown (or null) key reads as an empty set and materializes
     * nothing.
     */
    public synchronized Set<String> followedCache(String key) {
        ensureLoaded();
        if (key == null) {
            return Collections.emptySet();
        }
        AccountData data = partition(key, false);
        if (data == null) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(data.followedCache));
    }

    /** Adds newly harvested followed-author IDs to a partition. Returns how many were new. */
    public synchronized int addFollowedIds(String key, java.util.Collection<String> ids) {
        ensureLoaded();
        if (key == null) {
            return 0;
        }
        AccountData data = partition(key, true);
        int added = 0;
        for (String id : ids) {
            if (id == null || id.isEmpty()) {
                continue;
            }
            if (data.followedCache.size() >= FOLLOWED_CACHE_CAP) {
                break;
            }
            if (data.followedCache.add(id)) {
                added++;
            }
        }
        if (added > 0) {
            persist();
        }
        return added;
    }

    /** One account's diagnostic; unknown (or null) keys read as UNKNOWN (0, 0). */
    public synchronized Diagnostic diagnostic(String key) {
        ensureLoaded();
        if (key == null) {
            return new Diagnostic(0, 0, DIAG_UNKNOWN);
        }
        AccountData data = partition(key, false);
        return data == null ? new Diagnostic(0, 0, DIAG_UNKNOWN) : data.diagnostic;
    }

    /**
     * JP jp24.13.18: atomically merges one harvest pass's diagnostic
     * contribution (delta samples / delta hits) into the stored state
     * and decides the trip. This is the ONLY diagnostic write path
     * besides the user-initiated {@link #resetDiagnostic}: transitions
     * are monotonic — while UNKNOWN, samples and hits only grow; once
     * TRUSTED or DEAD is reached the state never moves again, so a
     * slow concurrent pass can neither roll a decided state back to
     * UNKNOWN nor drop hits another pass already recorded (the
     * pre-13.18 gate-side read-modify-write could do both). The trip
     * fires when the accumulated samples reach
     * {@link #DIAG_TARGET_SAMPLES}: hits &gt; 0 trusts the bridge,
     * zero hits declares it dead.
     *
     * <p>JP jp24.13.20: the merge is scoped to one account's
     * partition (materialized on first contribution); interleaved
     * passes for different accounts touch disjoint state, so a late
     * merge for account A can never roll back account B.
     *
     * @return the partition's diagnostic state after the merge
     */
    public synchronized Diagnostic recordHarvestSamples(
            String key, int deltaSamples, int deltaHits) {
        ensureLoaded();
        if (key == null) {
            return new Diagnostic(0, 0, DIAG_UNKNOWN);
        }
        AccountData data = partition(key, true);
        Diagnostic diagnostic = data.diagnostic;
        if (diagnostic.status != DIAG_UNKNOWN) {
            return diagnostic;
        }
        int samples = diagnostic.samples + Math.max(0, deltaSamples);
        int hits = diagnostic.hits + Math.max(0, deltaHits);
        int status = DIAG_UNKNOWN;
        if (samples >= DIAG_TARGET_SAMPLES) {
            status = hits > 0 ? DIAG_TRUSTED : DIAG_DEAD;
        }
        data.diagnostic = new Diagnostic(samples, hits, status);
        persist();
        return data.diagnostic;
    }

    /** Resets one account's diagnostic; unknown (or null) keys are a no-op. */
    public synchronized void resetDiagnostic(String key) {
        ensureLoaded();
        if (key == null) {
            return;
        }
        AccountData data = partition(key, false);
        if (data == null) {
            return;
        }
        data.diagnostic = new Diagnostic(0, 0, DIAG_UNKNOWN);
        persist();
    }

    // ---- internals

    private String requirePhrase(String phrase) {
        String trimmed = phrase == null ? "" : phrase.trim();
        String normalized = PostFilterMatcher.normalize(trimmed);
        if (normalized.isEmpty()) {
            throw new ValidationException(
                    ValidationException.Error.BLANK_PHRASE, "単語が空です");
        }
        ensureLoaded();
        for (Category category : categories) {
            for (String existing : category.phrases) {
                if (PostFilterMatcher.normalize(existing).equals(normalized)) {
                    throw new ValidationException(
                            ValidationException.Error.DUPLICATE_PHRASE,
                            "その単語はすでに登録されています");
                }
            }
        }
        return trimmed;
    }

    private static int indexOf(List<Category> list, String id) {
        for (int index = 0; index < list.size(); index++) {
            if (list.get(index).id.equals(id)) {
                return index;
            }
        }
        throw new ValidationException(
                ValidationException.Error.UNKNOWN_CATEGORY, "カテゴリが見つかりません");
    }

    private void ensureLoaded() {
        if (snapshot != null) {
            return;
        }
        String stored = persistence.readJson();
        if ((stored == null || stored.isEmpty()) && !persistence.readSeeded()) {
            categories = presetCategories();
            options = Options.defaults();
            accounts = newAccountsMap();
            snapshot = new Snapshot(categories, options);
            persist();
            persistence.writeSeeded(true);
            return;
        }
        categories = new ArrayList<>();
        options = Options.defaults();
        accounts = newAccountsMap();
        if (stored != null && !stored.isEmpty()) {
            try {
                fromJson(new JSONObject(stored));
            } catch (JSONException | RuntimeException exception) {
                NewXLogger.printException(
                        () -> "RecommendKeepStore: stored data could not be read",
                        exception);
                categories = new ArrayList<>();
                accounts = newAccountsMap();
            }
        }
        if (schemaMigrated) {
            schemaMigrated = false;
            persist();
        }
        snapshot = new Snapshot(categories, options);
        if (!persistence.readSeeded()) {
            persistence.writeSeeded(true);
        }
    }

    private void publish() {
        snapshot = new Snapshot(categories, options);
        persist();
    }

    private void persist() {
        try {
            persistence.writeJson(toJson().toString());
        } catch (JSONException exception) {
            NewXLogger.printException(
                    () -> "RecommendKeepStore: data could not be saved", exception);
        }
    }

    private JSONObject toJson() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("version", SCHEMA_VERSION);
        JSONArray categoryArray = new JSONArray();
        for (Category category : categories) {
            JSONObject object = new JSONObject();
            object.put("id", category.id);
            object.put("name", category.name);
            object.put("enabled", category.enabled);
            JSONArray phrases = new JSONArray();
            for (String phrase : category.phrases) {
                phrases.put(phrase);
            }
            object.put("phrases", phrases);
            categoryArray.put(object);
        }
        root.put("categories", categoryArray);
        JSONObject opts = new JSONObject();
        opts.put("master", options.master);
        opts.put("targetHome", options.targetHome);
        opts.put("targetExplore", options.targetExplore);
        opts.put("followedAlways", options.followedAlways);
        opts.put("keepTextless", options.keepTextless);
        root.put("options", opts);
        JSONObject accountsJson = new JSONObject();
        JSONArray lru = new JSONArray();
        for (Map.Entry<String, AccountData> partition : accounts.entrySet()) {
            AccountData data = partition.getValue();
            JSONObject entry = new JSONObject();
            JSONArray cache = new JSONArray();
            for (String id : data.followedCache) {
                cache.put(id);
            }
            entry.put("followedCache", cache);
            JSONObject diag = new JSONObject();
            diag.put("samples", data.diagnostic.samples);
            diag.put("hits", data.diagnostic.hits);
            diag.put("status", data.diagnostic.status);
            entry.put("diag", diag);
            accountsJson.put(partition.getKey(), entry);
            lru.put(partition.getKey());
        }
        root.put("accounts", accountsJson);
        root.put("accountsLru", lru);
        return root;
    }

    /** Access-ordered (LRU) partition map: eldest first when iterated. */
    private static LinkedHashMap<String, AccountData> newAccountsMap() {
        return new LinkedHashMap<>(16, 0.75f, true);
    }

    /**
     * JP jp24.13.17: the filter-bundle projection of this store —
     * categories and options only. The followed-ID cache is
     * the user's social graph and the follower-gate diagnostics are
     * device state; neither is ever exported.
     */
    public synchronized JSONObject exportProjection() throws JSONException {
        ensureLoaded();
        JSONObject root = new JSONObject();
        root.put("version", SCHEMA_VERSION);
        JSONArray categoriesJson = new JSONArray();
        for (Category category : categories) {
            JSONObject object = new JSONObject();
            object.put("id", category.id);
            object.put("name", category.name);
            object.put("enabled", category.enabled);
            object.put("phrases", new JSONArray(category.phrases));
            categoriesJson.put(object);
        }
        root.put("categories", categoriesJson);
        JSONObject optionsJson = new JSONObject();
        optionsJson.put("master", options.master);
        optionsJson.put("targetHome", options.targetHome);
        optionsJson.put("targetExplore", options.targetExplore);
        optionsJson.put("followedAlways", options.followedAlways);
        optionsJson.put("keepTextless", options.keepTextless);
        root.put("options", optionsJson);
        return root;
    }

    /**
     * JP jp24.13.17: replaces categories/options with a
     * validated import projection. The followed-ID cache and the
     * follower-gate diagnostics live outside the projection and are
     * kept as-is (publish() re-persists the full document).
     */
    public synchronized void replaceProjectionFromImport(
            List<Category> importedCategories,
            Options importedOptions) {
        ensureLoaded();
        categories = new ArrayList<>(importedCategories);
        options = importedOptions;
        publish();
    }

    /**
     * JP jp24.13.24.5: additive (merge) counterpart of
     * {@link #replaceProjectionFromImport}. Categories match by ID:
     * phrases are unioned (existing first, imported additions
     * appended, duplicates by normalized form dropped) and the
     * existing category's name and enabled flag win; unmatched
     * imported categories are appended as validated. ALL options
     * are left untouched (existing options win entirely). The
     * followed-ID cache and the follower-gate diagnostics live
     * outside the projection and are kept as-is.
     * Returns {categoriesAdded, phrasesAdded}.
     */
    public synchronized int[] mergeProjectionFromImport(
            List<Category> importedCategories) {
        ensureLoaded();
        List<Category> merged = new ArrayList<>(categories);
        int categoriesAdded = 0;
        int phrasesAdded = 0;
        for (Category imported : importedCategories) {
            int index = indexOf(merged, imported.id);
            if (index < 0) {
                merged.add(imported);
                categoriesAdded++;
                phrasesAdded += imported.phrases.size();
                continue;
            }
            Category existing = merged.get(index);
            List<String> union = new ArrayList<>(existing.phrases);
            List<String> seenNormalized = new ArrayList<>();
            for (String phrase : existing.phrases) {
                seenNormalized.add(PostFilterMatcher.normalize(phrase));
            }
            int added = 0;
            for (String phrase : imported.phrases) {
                String normalized = PostFilterMatcher.normalize(phrase);
                if (seenNormalized.contains(normalized)) {
                    continue;
                }
                seenNormalized.add(normalized);
                union.add(phrase);
                added++;
            }
            if (added > 0) {
                merged.set(index, existing.withPhrases(union));
                phrasesAdded += added;
            }
        }
        categories = merged;
        publish();
        return new int[]{categoriesAdded, phrasesAdded};
    }

    private void fromJson(JSONObject root) throws JSONException {
        JSONArray categoryArray = root.getJSONArray("categories");
        List<Category> loaded = new ArrayList<>();
        for (int index = 0; index < categoryArray.length(); index++) {
            JSONObject object = categoryArray.getJSONObject(index);
            loaded.add(new Category(
                    object.getString("id"),
                    object.getString("name"),
                    object.optBoolean("enabled", true),
                    phrasesOf(object)));
        }
        categories = loaded;
        JSONObject opts = root.optJSONObject("options");
        if (opts != null) {
            Options defaults = Options.defaults();
            options = new Options(
                    opts.optBoolean("master", defaults.master),
                    opts.optBoolean("targetHome", defaults.targetHome),
                    opts.optBoolean("targetExplore", defaults.targetExplore),
                    opts.optBoolean("followedAlways", defaults.followedAlways),
                    opts.optBoolean("keepTextless", defaults.keepTextless));
        }
        if (root.optInt("version", 1) >= 4) {
            parseAccounts(root);
        }
        migrateSchema2(root);
        migrateSchema4(root);
    }

    /** Reads the schema-4 {@code accounts} object in LRU order. */
    private void parseAccounts(JSONObject root) {
        JSONObject accountsJson = root.optJSONObject("accounts");
        if (accountsJson == null) {
            return;
        }
        List<String> order = new ArrayList<>();
        JSONArray lru = root.optJSONArray("accountsLru");
        if (lru != null) {
            for (int index = 0; index < lru.length(); index++) {
                String key = lru.optString(index, "");
                if (!key.isEmpty() && accountsJson.has(key) && !order.contains(key)) {
                    order.add(key);
                }
            }
        }
        Iterator<String> keys = accountsJson.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!order.contains(key)) {
                order.add(key);
            }
        }
        for (String key : order) {
            JSONObject entry = accountsJson.optJSONObject(key);
            if (entry == null) {
                continue;
            }
            AccountData data = new AccountData();
            JSONArray cache = entry.optJSONArray("followedCache");
            if (cache != null) {
                for (int index = 0; index < cache.length(); index++) {
                    String id = cache.optString(index, "");
                    if (!id.isEmpty() && data.followedCache.size() < FOLLOWED_CACHE_CAP) {
                        data.followedCache.add(id);
                    }
                }
            }
            JSONObject diag = entry.optJSONObject("diag");
            if (diag != null) {
                data.diagnostic = new Diagnostic(
                        diag.optInt("samples", 0),
                        diag.optInt("hits", 0),
                        diag.optInt("status", DIAG_UNKNOWN));
            }
            accounts.put(key, data);
        }
        evictPartitions();
    }

    /**
     * JP jp24.13.11 (schema 2): under matcher v1 the bare seed token
     * "AI" was the keep filter's biggest false-keep source — as a raw
     * substring it fired inside "again", "said", "available", "detail"
     * and half of English. Documents seeded by 13.9/13.10 carry it in
     * the tech list, so on load of a pre-schema-2 document any tech
     * phrase normalizing to exactly "ai" is stripped, once. Compounds
     * such as 生成AI are CJK phrases and untouched; a user who
     * deliberately re-adds "AI" afterwards is on schema 2 and is never
     * stripped again.
     */
    private void migrateSchema2(JSONObject root) {
        // Guard on the schema this migration targets, not the current
        // one: a schema-2 document was already migrated, and a tech
        // phrase "AI" the user deliberately re-added on schema 2 must
        // never be stripped again.
        if (root.optInt("version", 1) >= 2) {
            return;
        }
        schemaMigrated = true;
        for (int index = 0; index < categories.size(); index++) {
            Category category = categories.get(index);
            if (!ID_TECH.equals(category.id)) {
                continue;
            }
            List<String> kept = new ArrayList<>();
            for (String phrase : category.phrases) {
                if (!PostFilterMatcher.normalize(phrase).equals("ai")) {
                    kept.add(phrase);
                }
            }
            if (kept.size() != category.phrases.size()) {
                categories.set(index, category.withPhrases(kept));
            }
        }
    }

    /**
     * JP jp24.13.20 (schema 4): the pre-13.20 document held ONE
     * app-global followed-ID cache and diagnostic (top-level
     * {@code followedCache} / {@code diag}). Neither records which
     * account it was harvested under, so attribution to a partition
     * is impossible in principle; both are simply never read here,
     * and the document is re-persisted at schema 4 with empty
     * partitions (exactly-once via {@code schemaMigrated}, as with
     * the earlier migrations). Each account re-diagnoses from
     * UNKNOWN under the 13.18 harvestable-ID rule and rebuilds its
     * cache from its own Following tab; while a partition is not
     * TRUSTED the gate drops nothing for it. This subsumes the
     * schema-3 migration (jp24.13.18): its one-time reset of an
     * old-semantics DIAG_DEAD is moot when the diagnostic itself is
     * discarded.
     */
    private void migrateSchema4(JSONObject root) {
        if (root.optInt("version", 1) >= SCHEMA_VERSION) {
            return;
        }
        schemaMigrated = true;
    }

    private static List<String> phrasesOf(JSONObject object) throws JSONException {
        List<String> phrases = new ArrayList<>();
        JSONArray array = object.optJSONArray("phrases");
        if (array != null) {
            for (int index = 0; index < array.length(); index++) {
                phrases.add(array.getString(index));
            }
        }
        return phrases;
    }

    /** Reason string -> short Japanese label for the filtered-replies dialog. */
    public static String reasonLabel(String reason) {
        if (reason == null) {
            return "";
        }
        if (REASON_MISS.equals(reason)) {
            return "おすすめキープ対象外";
        }
        return reason;
    }

    @Override
    public String toString() {
        return "RecommendKeepStore";
    }
}
