package app.morphe.extension.newx.postfilter;

import app.morphe.extension.newx.settings.NewXLogger;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.StringSetting;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * JP jp24.13: store for the category-based mute (カテゴリ別ミュート).
 *
 * <p>Two layers, mirroring {@link BlockedAuthorsStore}: the bundled preset
 * definitions below are only the first-run seed; everything afterwards lives
 * in this store (JSON in one string setting), so preset vocabulary stays
 * editable and user categories can be added. The existing flat keyword rules
 * ({@link PostFilterRuleStore}) are a separate feature and are not touched.
 *
 * <p>Unlike the flat keyword filter, a category hit always carries its
 * category and the matched phrase, so hidden posts can be recorded with a
 * reason ({@code MUTE_CAT_<id>:<phrase>}) and reviewed later.
 *
 * <p>Matching is self-contained (normalized substring scan per category;
 * phrase counts are small) so the shipped phrase-matcher classes used by the
 * flat keyword filter stay untouched.
 */
public final class MuteCategoryStore {
    private static final int SCHEMA_VERSION = 1;

    public static final String ID_NEWS = "news";
    public static final String ID_POLITICS = "politics";
    public static final String ID_TROLL = "troll";
    public static final String ID_SARASHI = "sarashi";
    public static final String ID_ENJOU = "enjou";

    public static final String REASON_PREFIX = "MUTE_CAT_";
    public static final String REASON_CUSTOM = "MUTE_CAT_CUSTOM";

    /** Reason "phrase" recorded when the doxxing detector fires. */
    public static final String DETECTOR_LABEL_PHONE = "電話番号形式";
    public static final String DETECTOR_LABEL_POSTAL = "郵便番号形式";
    public static final String DETECTOR_LABEL_ADDRESS = "住所形式";

    private static final Pattern PHONE_HYPHEN = Pattern.compile("0[789]0-\\d{4}-\\d{4}");
    private static final Pattern PHONE_PLAIN = Pattern.compile("0[789]0\\d{8}");
    private static final Pattern POSTAL = Pattern.compile("〒\\s?\\d{3}-\\d{4}");
    private static final Pattern ADDRESS = Pattern.compile(
            "(北海道|青森県|岩手県|宮城県|秋田県|山形県|福島県|茨城県|栃木県|群馬県|埼玉県|千葉県|東京都"
                    + "|神奈川県|新潟県|富山県|石川県|福井県|山梨県|長野県|岐阜県|静岡県|愛知県|三重県"
                    + "|滋賀県|京都府|大阪府|兵庫県|奈良県|和歌山県|鳥取県|島根県|岡山県|広島県|山口県"
                    + "|徳島県|香川県|愛媛県|高知県|福岡県|佐賀県|長崎県|熊本県|大分県|宮崎県|鹿児島県|沖縄県)"
                    + "[^、。\\s]{0,14}?(市|区|町|村)[^、。\\s]{0,14}?\\d{1,4}(丁目|番地|-\\d{1,4}|ー\\d{1,4})");

    public interface Persistence {
        String readCategories();

        void writeCategories(String value);

        boolean readSeeded();

        void writeSeeded(boolean seeded);
    }

    private static final class SharedPreferencesPersistence implements Persistence {
        private final StringSetting categoriesJson =
                new StringSetting("newx.content.mute_categories", "");
        private final BooleanSetting seeded =
                new BooleanSetting("newx.content.mute_categories.seeded", false);

        @Override
        public String readCategories() {
            return categoriesJson.get();
        }

        @Override
        public void writeCategories(String value) {
            categoriesJson.save(value);
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
            BLANK_NAME,
            DUPLICATE_NAME,
            BLANK_PHRASE,
            DUPLICATE_PHRASE,
            UNKNOWN_CATEGORY,
            PRESET_LOCKED
        }

        public final Error error;

        public ValidationException(Error error, String message) {
            super(message);
            this.error = error;
        }
    }

    /** One category hit: which category fired and which phrase (or detector label) matched. */
    public static final class Match {
        public final String categoryId;
        public final String categoryName;
        public final boolean preset;
        public final String phrase;

        Match(String categoryId, String categoryName, boolean preset, String phrase) {
            this.categoryId = categoryId;
            this.categoryName = categoryName;
            this.preset = preset;
            this.phrase = phrase;
        }

        /** Filtered-replies reason string, e.g. {@code MUTE_CAT_NEWS:速報}. */
        public String reason() {
            if (preset) {
                return REASON_PREFIX + categoryId.toUpperCase(Locale.ROOT) + ":" + phrase;
            }
            return REASON_CUSTOM + ":" + categoryName + ":" + phrase;
        }
    }

    private static final class CategoryMatcher {
        final MuteCategory category;
        /** Normalized phrase -> phrase as entered, in entry order. */
        final LinkedHashMap<String, String> normalizedPhrases = new LinkedHashMap<>();

        CategoryMatcher(MuteCategory category) {
            this.category = category;
            for (String phrase : category.phrases) {
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
                if (normalized.contains(entry.getKey())) {
                    return entry.getValue();
                }
            }
            return null;
        }
    }

    /** Immutable view of the store; identity changes whenever the store changes. */
    public static final class Snapshot {
        private final List<MuteCategory> categories;
        private final List<CategoryMatcher> matchers;
        private final boolean anyContent;
        private final boolean anyQuoted;
        private final boolean anyScreenName;
        private final boolean anyDisplayName;
        private final boolean anyActive;

        Snapshot(List<MuteCategory> categories) {
            this.categories = Collections.unmodifiableList(new ArrayList<>(categories));
            List<CategoryMatcher> built = new ArrayList<>();
            boolean content = false;
            boolean quoted = false;
            boolean screenName = false;
            boolean displayName = false;
            boolean active = false;
            for (MuteCategory category : categories) {
                CategoryMatcher matcher = new CategoryMatcher(category);
                built.add(matcher);
                if (!category.enabled) {
                    continue;
                }
                content |= category.matchContent;
                quoted |= category.matchQuoted;
                screenName |= category.matchScreenName;
                displayName |= category.matchDisplayName;
                if (!matcher.normalizedPhrases.isEmpty() || category.detectorEnabled) {
                    active = true;
                }
            }
            this.matchers = Collections.unmodifiableList(built);
            this.anyContent = content;
            this.anyQuoted = quoted;
            this.anyScreenName = screenName;
            this.anyDisplayName = displayName;
            this.anyActive = active;
        }

        public List<MuteCategory> categories() {
            return categories;
        }

        public MuteCategory category(String id) {
            for (MuteCategory category : categories) {
                if (category.id.equals(id)) {
                    return category;
                }
            }
            return null;
        }

        /** True when at least one enabled category can match anything. */
        public boolean hasActiveCategories() {
            return anyActive;
        }

        /** Scope unions over enabled categories, so callers can skip field reads. */
        public boolean matchesContent() {
            return anyContent;
        }

        public boolean matchesQuoted() {
            return anyQuoted;
        }

        public boolean matchesScreenName() {
            return anyScreenName;
        }

        public boolean matchesDisplayName() {
            return anyDisplayName;
        }

        /**
         * First category hit over the given fields, in store order. Within a
         * category, phrase scopes are checked body, quoted, display name,
         * screen name; the doxxing detector (when enabled) runs last, over
         * body then quoted text.
         */
        public Match findMatch(
                String content, String quoted, String screenName, String displayName) {
            for (CategoryMatcher matcher : matchers) {
                MuteCategory category = matcher.category;
                if (!category.enabled) {
                    continue;
                }
                String phrase = null;
                if (category.matchContent) {
                    phrase = matcher.findIn(content);
                }
                if (phrase == null && category.matchQuoted) {
                    phrase = matcher.findIn(quoted);
                }
                if (phrase == null && category.matchDisplayName) {
                    phrase = matcher.findIn(displayName);
                }
                if (phrase == null && category.matchScreenName) {
                    phrase = matcher.findIn(screenName);
                }
                if (phrase != null) {
                    return new Match(category.id, category.name, category.preset, phrase);
                }
                if (category.detectorEnabled) {
                    String label = findDetectorLabel(content);
                    if (label == null) {
                        label = findDetectorLabel(quoted);
                    }
                    if (label != null) {
                        return new Match(category.id, category.name, category.preset, label);
                    }
                }
            }
            return null;
        }
    }

    /**
     * Code-fixed doxxing-pattern detector (晒し系). Runs on NFKC-normalized
     * text; returns a Japanese label for the first pattern found, else null.
     * Postal codes require the 〒 mark so dates and order numbers do not fire.
     */
    public static String findDetectorLabel(String text) {
        if (text == null) {
            return null;
        }
        String normalized = PostFilterMatcher.normalize(text);
        if (normalized.isEmpty()) {
            return null;
        }
        if (PHONE_HYPHEN.matcher(normalized).find() || PHONE_PLAIN.matcher(normalized).find()) {
            return DETECTOR_LABEL_PHONE;
        }
        if (POSTAL.matcher(normalized).find()) {
            return DETECTOR_LABEL_POSTAL;
        }
        if (ADDRESS.matcher(normalized).find()) {
            return DETECTOR_LABEL_ADDRESS;
        }
        return null;
    }

    /** Bundled preset definitions: the first-run seed and the "add preset words" source. */
    public static List<MuteCategory> presetCategories() {
        List<MuteCategory> presets = new ArrayList<>();
        presets.add(new MuteCategory(ID_NEWS, "ニュース", true, true,
                true, true, false, true, false, false,
                Arrays.asList(
                        "共同通信", "時事通信", "ロイター", "ブルームバーグ",
                        "朝日新聞", "読売新聞", "毎日新聞", "産経新聞", "日本経済新聞", "日経新聞", "東京新聞",
                        "NHKニュース", "日テレNEWS", "TBS NEWS", "テレ朝news", "FNN",
                        "速報", "号外", "第一報", "Yahoo!ニュース", "ハフポスト")));
        presets.add(new MuteCategory(ID_POLITICS, "政治系", true, true,
                true, true, false, false, false, false,
                Arrays.asList(
                        "自民党", "自由民主党", "立憲民主党", "公明党", "日本維新の会", "国民民主党",
                        "日本共産党", "共産党", "れいわ新選組", "参政党", "社民党",
                        "国会", "衆議院", "参議院", "総理大臣", "予算委員会", "選挙管理委員会",
                        "内閣", "法案", "与党", "野党", "首相", "大臣", "憲法改正", "支持率", "増税", "減税")));
        presets.add(new MuteCategory(ID_TROLL, "荒らし", true, true,
                true, true, false, false, false, false,
                Arrays.asList(
                        "荒らしに来た", "荒らしにきた", "荒らしてやる",
                        "集団通報", "通報祭り", "ネットリンチ", "粘着")));
        presets.add(new MuteCategory(ID_SARASHI, "晒し系", true, true,
                true, true, false, false, true, true,
                Arrays.asList(
                        "特定班", "特定完了", "住所特定", "晒し上げ", "晒すぞ",
                        "特定した", "実名", "本名", "個人情報", "DM晒し")));
        presets.add(new MuteCategory(ID_ENJOU, "炎上系", true, true,
                true, true, false, false, false, false,
                Arrays.asList(
                        "大炎上", "謝罪動画", "謝罪文",
                        "炎上", "騒動", "不祥事")));
        return presets;
    }

    /** Display name for a category id: live store first, bundled presets as fallback. */
    public static String nameForId(String id) {
        if (id == null) {
            return null;
        }
        try {
            MuteCategory category = shared().snapshot().category(id);
            if (category != null) {
                return category.name;
            }
        } catch (RuntimeException ignored) {
            // Fall through to the bundled names.
        }
        for (MuteCategory preset : presetCategories()) {
            if (preset.id.equals(id)) {
                return preset.name;
            }
        }
        return null;
    }

    private static volatile MuteCategoryStore shared;

    public static MuteCategoryStore shared() {
        MuteCategoryStore local = shared;
        if (local == null) {
            synchronized (MuteCategoryStore.class) {
                local = shared;
                if (local == null) {
                    local = new MuteCategoryStore(new SharedPreferencesPersistence());
                    shared = local;
                }
            }
        }
        return local;
    }

    private final Persistence persistence;
    private Snapshot snapshot;

    public MuteCategoryStore(Persistence persistence) {
        this.persistence = persistence;
    }

    public synchronized Snapshot snapshot() {
        ensureLoaded();
        return snapshot;
    }

    public synchronized void setCategoryEnabled(String id, boolean enabled) {
        List<MuteCategory> categories = mutableCategories();
        int index = indexOf(categories, id);
        categories.set(index, categories.get(index).withEnabled(enabled));
        publish(categories);
    }

    public synchronized void setCategoryScopes(
            String id, boolean content, boolean quoted, boolean screenName, boolean displayName) {
        List<MuteCategory> categories = mutableCategories();
        int index = indexOf(categories, id);
        categories.set(index, categories.get(index)
                .withScopes(content, quoted, screenName, displayName));
        publish(categories);
    }

    public synchronized void setCategoryDetectorEnabled(String id, boolean enabled) {
        List<MuteCategory> categories = mutableCategories();
        int index = indexOf(categories, id);
        categories.set(index, categories.get(index).withDetectorEnabled(enabled));
        publish(categories);
    }

    public synchronized void addPhrase(String id, String phrase) {
        String trimmed = phrase == null ? "" : phrase.trim();
        String normalized = PostFilterMatcher.normalize(trimmed);
        if (normalized.isEmpty()) {
            throw new ValidationException(
                    ValidationException.Error.BLANK_PHRASE, "単語が空です");
        }
        List<MuteCategory> categories = mutableCategories();
        int index = indexOf(categories, id);
        for (MuteCategory category : categories) {
            for (String existing : category.phrases) {
                if (PostFilterMatcher.normalize(existing).equals(normalized)) {
                    throw new ValidationException(
                            ValidationException.Error.DUPLICATE_PHRASE,
                            "その単語はすでにカテゴリに登録されています");
                }
            }
        }
        List<String> phrases = new ArrayList<>(categories.get(index).phrases);
        phrases.add(trimmed);
        categories.set(index, categories.get(index).withPhrases(phrases));
        publish(categories);
    }

    public synchronized void removePhrase(String id, String phrase) {
        List<MuteCategory> categories = mutableCategories();
        int index = indexOf(categories, id);
        List<String> phrases = new ArrayList<>(categories.get(index).phrases);
        phrases.remove(phrase);
        categories.set(index, categories.get(index).withPhrases(phrases));
        publish(categories);
    }

    /** Re-adds any bundled preset phrases the user removed. Returns how many were added. */
    public synchronized int addPresetPhrases(String id) {
        List<MuteCategory> categories = mutableCategories();
        int index = indexOf(categories, id);
        MuteCategory preset = null;
        for (MuteCategory candidate : presetCategories()) {
            if (candidate.id.equals(id)) {
                preset = candidate;
                break;
            }
        }
        if (preset == null) {
            return 0;
        }
        List<String> phrases = new ArrayList<>(categories.get(index).phrases);
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
            categories.set(index, categories.get(index).withPhrases(phrases));
            publish(categories);
        }
        return added;
    }

    public synchronized MuteCategory addCustomCategory(String name) {
        String trimmed = sanitizeName(name);
        if (trimmed.isEmpty()) {
            throw new ValidationException(
                    ValidationException.Error.BLANK_NAME, "カテゴリ名が空です");
        }
        List<MuteCategory> categories = mutableCategories();
        for (MuteCategory category : categories) {
            if (category.name.equals(trimmed)) {
                throw new ValidationException(
                        ValidationException.Error.DUPLICATE_NAME,
                        "同じ名前のカテゴリがあります");
            }
        }
        MuteCategory created = new MuteCategory(
                "custom-" + UUID.randomUUID(), trimmed, false, true,
                true, true, false, false, false, false, new ArrayList<>());
        categories.add(created);
        publish(categories);
        return created;
    }

    public synchronized void renameCategory(String id, String name) {
        List<MuteCategory> categories = mutableCategories();
        int index = indexOf(categories, id);
        if (categories.get(index).preset) {
            throw new ValidationException(
                    ValidationException.Error.PRESET_LOCKED,
                    "プリセットカテゴリの名前は変更できません");
        }
        String trimmed = sanitizeName(name);
        if (trimmed.isEmpty()) {
            throw new ValidationException(
                    ValidationException.Error.BLANK_NAME, "カテゴリ名が空です");
        }
        for (MuteCategory category : categories) {
            if (!category.id.equals(id) && category.name.equals(trimmed)) {
                throw new ValidationException(
                        ValidationException.Error.DUPLICATE_NAME,
                        "同じ名前のカテゴリがあります");
            }
        }
        categories.set(index, categories.get(index).withName(trimmed));
        publish(categories);
    }

    public synchronized void removeCategory(String id) {
        List<MuteCategory> categories = mutableCategories();
        int index = indexOf(categories, id);
        if (categories.get(index).preset) {
            throw new ValidationException(
                    ValidationException.Error.PRESET_LOCKED,
                    "プリセットカテゴリは削除できません");
        }
        categories.remove(index);
        publish(categories);
    }

    private static String sanitizeName(String name) {
        if (name == null) {
            return "";
        }
        // ':' is the reason-string separator; keep it out of category names.
        return name.replace(':', '：').trim();
    }

    private List<MuteCategory> mutableCategories() {
        ensureLoaded();
        return new ArrayList<>(snapshot.categories());
    }

    private static int indexOf(List<MuteCategory> categories, String id) {
        for (int index = 0; index < categories.size(); index++) {
            if (categories.get(index).id.equals(id)) {
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
        List<MuteCategory> categories;
        String stored = persistence.readCategories();
        if ((stored == null || stored.isEmpty()) && !persistence.readSeeded()) {
            categories = presetCategories();
            snapshot = new Snapshot(categories);
            persist(categories);
            persistence.writeSeeded(true);
            return;
        }
        if (stored != null && !stored.isEmpty()) {
            try {
                categories = fromJson(new JSONObject(stored));
            } catch (JSONException | RuntimeException exception) {
                NewXLogger.printException(
                        () -> "MuteCategoryStore: stored categories could not be read",
                        exception);
                categories = new ArrayList<>();
            }
        } else {
            categories = new ArrayList<>();
        }
        snapshot = new Snapshot(categories);
        if (!persistence.readSeeded()) {
            persistence.writeSeeded(true);
        }
    }

    private void publish(List<MuteCategory> categories) {
        snapshot = new Snapshot(categories);
        persist(categories);
    }

    private void persist(List<MuteCategory> categories) {
        try {
            persistence.writeCategories(toJson(categories).toString());
        } catch (JSONException exception) {
            NewXLogger.printException(
                    () -> "MuteCategoryStore: categories could not be saved", exception);
        }
    }

    private static JSONObject toJson(List<MuteCategory> categories) throws JSONException {
        JSONArray array = new JSONArray();
        for (MuteCategory category : categories) {
            JSONObject object = new JSONObject();
            object.put("id", category.id);
            object.put("name", category.name);
            object.put("preset", category.preset);
            object.put("enabled", category.enabled);
            object.put("content", category.matchContent);
            object.put("quoted", category.matchQuoted);
            object.put("screenName", category.matchScreenName);
            object.put("displayName", category.matchDisplayName);
            object.put("detectorAvailable", category.detectorAvailable);
            object.put("detector", category.detectorEnabled);
            JSONArray phrases = new JSONArray();
            for (String phrase : category.phrases) {
                phrases.put(phrase);
            }
            object.put("phrases", phrases);
            array.put(object);
        }
        JSONObject root = new JSONObject();
        root.put("version", SCHEMA_VERSION);
        root.put("categories", array);
        return root;
    }

    private static List<MuteCategory> fromJson(JSONObject root) throws JSONException {
        List<MuteCategory> categories = new ArrayList<>();
        JSONArray array = root.getJSONArray("categories");
        for (int index = 0; index < array.length(); index++) {
            JSONObject object = array.getJSONObject(index);
            JSONArray phraseArray = object.optJSONArray("phrases");
            List<String> phrases = new ArrayList<>();
            if (phraseArray != null) {
                for (int phraseIndex = 0; phraseIndex < phraseArray.length(); phraseIndex++) {
                    phrases.add(phraseArray.getString(phraseIndex));
                }
            }
            categories.add(new MuteCategory(
                    object.getString("id"),
                    object.getString("name"),
                    object.optBoolean("preset", false),
                    object.optBoolean("enabled", true),
                    object.optBoolean("content", true),
                    object.optBoolean("quoted", true),
                    object.optBoolean("screenName", false),
                    object.optBoolean("displayName", false),
                    object.optBoolean("detectorAvailable", false),
                    object.optBoolean("detector", false),
                    phrases));
        }
        return categories;
    }
}
