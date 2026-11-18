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
    public static final String ID_GENDER = "gender";
    public static final String ID_NIGHTWORK = "nightwork";
    public static final String ID_INTLFEUD = "intlfeud";
    public static final String ID_RELIGION = "religion";
    public static final String ID_REGIONAL = "regional";
    public static final String ID_BAIT = "bait";

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

        /** First phrase occurring in any of {@code texts} (jp24.13.16: link URLs). */
        String findInAny(List<String> texts) {
            if (texts == null || texts.isEmpty()) {
                return null;
            }
            for (String text : texts) {
                String phrase = findIn(text);
                if (phrase != null) {
                    return phrase;
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
            return findMatch(content, quoted, screenName, displayName, null, null);
        }

        /**
         * JP jp24.13.16: as {@link #findMatch(String, String, String, String)},
         * plus link-URL matching. The body text carries only t.co-wrapped
         * URLs, so the category vocabulary is also scanned against the
         * post's expanded link URLs under the content scope, and against
         * the quoted post's expanded URLs under the quoted scope (each
         * right after its scope's text). A URL hit records the matched
         * vocabulary word (typically a shortener domain) as the phrase.
         */
        public Match findMatch(
                String content, String quoted, String screenName, String displayName,
                List<String> contentUrls, List<String> quotedUrls) {
            for (CategoryMatcher matcher : matchers) {
                MuteCategory category = matcher.category;
                if (!category.enabled) {
                    continue;
                }
                String phrase = null;
                if (category.matchContent) {
                    phrase = matcher.findIn(content);
                    if (phrase == null) {
                        phrase = matcher.findInAny(contentUrls);
                    }
                }
                if (phrase == null && category.matchQuoted) {
                    phrase = matcher.findIn(quoted);
                    if (phrase == null) {
                        phrase = matcher.findInAny(quotedUrls);
                    }
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
        // jp24.13.12: gender-discourse preset. Quoted scope matters here —
        // these posts are typically quote-posts whose own text is innocuous.
        // jp24.13.13: seed extended with sexual-violence terms and English
        // equivalents (overseas posts arrive untranslated). Matching is
        // substring-based, so "rape" also fires inside "grape"/"drape" —
        // accepted trade-off, per the user's explicit direction.
        // jp24.13.15: seed extended with 男女分断 (gender-division bait
        // phrasing seen on device, 2026-11-18).
        presets.add(new MuteCategory(ID_GENDER, "ジェンダー・男女論", true, true,
                true, true, false, false, false, false,
                Arrays.asList(
                        "フェミニスト", "フェミ", "ツイフェミ", "ミソジニー", "ミサンドリー",
                        "男女論", "男女分断", "女尊男卑", "男尊女卑", "弱者男性", "弱男",
                        "不同意性交", "ジェンダー", "ジェンダーギャップ",
                        "男性差別", "女性差別", "痴漢冤罪", "性加害",
                        "レイプ", "強姦", "性暴力", "性的暴行", "輪姦",
                        "盗撮", "セクハラ", "痴漢", "性被害",
                        "feminist", "feminism", "misogyny", "misandry", "gender war",
                        "rape", "sexual assault", "sexual violence")));
        // jp24.13.12: nightlife-work preset. Bare ホスト is deliberately NOT
        // seeded — it false-positives on ホストコンピュータ and event hosts;
        // the seeded compounds (ホストクラブ / 担当ホスト) carry the signal.
        presets.add(new MuteCategory(ID_NIGHTWORK, "水商売", true, true,
                true, true, false, false, false, false,
                Arrays.asList(
                        "水商売", "夜職", "夜職女子", "夜のお仕事",
                        "キャバクラ", "キャバ嬢", "ホストクラブ", "担当ホスト", "初回指名",
                        "シャンパンタワー", "オリシャン", "同伴出勤", "体入", "体験入店",
                        "ガールズバー", "ラウンジ嬢",
                        "風俗", "デリヘル", "ソープランド", "メンエス", "売掛",
                        "host club", "cabaret")));
        // jp24.13.12: Japan–Korea / Japan–China feud preset. Bare 韓国 /
        // 中国 / 朝鮮 are deliberately NOT seeded — they would nuke benign
        // food and travel posts; the list targets fight-specific vocabulary.
        presets.add(new MuteCategory(ID_INTLFEUD, "日韓・日中対立", true, true,
                true, true, false, false, false, false,
                Arrays.asList(
                        "反日", "反韓", "反中", "嫌韓", "嫌日", "ヘイトスピーチ",
                        "竹島", "独島", "慰安婦", "徴用工", "旭日旗", "在日",
                        "チョン", "ジャップ", "支那",
                        "尖閣", "釣魚島", "南京事件", "歴史認識",
                        "韓国併合", "植民地支配", "日韓断交",
                        "ネトウヨ", "パヨク", "売国奴",
                        "comfort women", "dokdo", "takeshima",
                        "anti-japanese", "anti-korean",
                        "반일", "혐한", "혐일", "위안부", "독도",
                        "抗日", "仇日", "小日本", "日本鬼子", "慰安妇")));
        // jp24.13.13: religion / immigration-grievance preset. The seed
        // deliberately includes ハラール / halal, so benign halal-food posts
        // match too — accepted trade-off (same call as the gender seed's
        // substring "rape").
        presets.add(new MuteCategory(ID_RELIGION, "宗教・移民", true, true,
                true, true, false, false, false, false,
                Arrays.asList(
                        "イスラム", "ムスリム", "ヒジャブ", "ブルカ", "モスク",
                        "土葬", "ハラール", "クルド人",
                        "移民政策", "移民受け入れ", "不法移民", "不法滞在",
                        "外国人優遇", "外国人犯罪", "移民反対",
                        "islam", "muslim", "hijab", "burqa", "mosque", "halal",
                        "illegal immigrant", "immigrant", "immigration", "kurd")));
        // jp24.13.14: regional / nuclear-power feud preset. The seed
        // deliberately includes neutral technical terms (原発 / 処理水 /
        // 再稼働), so straight news and informational posts about nuclear
        // power match too — the user asked for the whole topic gone.
        presets.add(new MuteCategory(ID_REGIONAL, "地域・原発", true, true,
                true, true, false, false, false, false,
                Arrays.asList(
                        "放射能", "汚染水", "処理水", "風評被害", "被曝",
                        "メルトダウン", "放射脳", "原発", "原発推進", "反原発",
                        "脱原発", "再稼働")));
        // jp24.13.16: engagement-bait / short-link preset. The phrases are
        // the classic "full video here" lures; the domains are known URL
        // shorteners, matched against the EXPANDED link URLs (the body
        // text only ever carries the t.co form). Longer phrases precede
        // フル動画 so the recorded reason names the specific lure. Note:
        // bit.ly and friends are also used by legitimate accounts — the
        // vocabulary stays user-editable like every preset.
        presets.add(new MuteCategory(ID_BAIT, "誘導・釣り", true, true,
                true, true, false, false, false, false,
                Arrays.asList(
                        "フル動画はこちら", "フル動画はプロフ", "続きはプロフ", "プロフに続き",
                        "動画の続きはこちら", "フル動画",
                        "b-short.link", "bit.ly", "tinyurl.com", "cutt.ly",
                        "is.gd", "rb.gy", "rebrand.ly", "shorturl.at")));
        return presets;
    }

    /**
     * The jp24.13.12 seed vocabulary of the three presets whose seeds grew
     * in jp24.13.13. The upgrade migration compares a stored preset's
     * phrases against these lists: an exact match means the user never
     * edited the seed, so it is upgraded to the current seed; any edit
     * (addition, removal, reordering) leaves the stored list untouched.
     */
    private static final List<String> SEED_13_12_GENDER = Arrays.asList(
            "フェミニスト", "フェミ", "ツイフェミ", "ミソジニー", "ミサンドリー",
            "男女論", "女尊男卑", "男尊女卑", "弱者男性", "弱男",
            "不同意性交", "ジェンダー", "ジェンダーギャップ",
            "男性差別", "女性差別", "痴漢冤罪", "性加害");

    private static final List<String> SEED_13_12_NIGHTWORK = Arrays.asList(
            "水商売", "夜職", "夜職女子", "夜のお仕事",
            "キャバクラ", "キャバ嬢", "ホストクラブ", "担当ホスト", "初回指名",
            "シャンパンタワー", "オリシャン", "同伴出勤", "体入", "体験入店",
            "ガールズバー", "ラウンジ嬢",
            "風俗", "デリヘル", "ソープランド", "メンエス", "売掛");

    private static final List<String> SEED_13_12_INTLFEUD = Arrays.asList(
            "反日", "反韓", "反中", "嫌韓", "嫌日", "ヘイトスピーチ",
            "竹島", "独島", "慰安婦", "徴用工", "旭日旗", "在日",
            "チョン", "ジャップ", "支那",
            "尖閣", "釣魚島", "南京事件", "歴史認識",
            "韓国併合", "植民地支配", "日韓断交",
            "ネトウヨ", "パヨク", "売国奴");

    /**
     * The jp24.13.13 seed of the gender preset (unchanged in jp24.13.14),
     * whose seed grew in jp24.13.15. Same migration contract as the
     * jp24.13.12 lists: an exact match is upgraded to the current seed,
     * any user edit leaves the stored list untouched.
     */
    private static final List<String> SEED_13_14_GENDER = Arrays.asList(
            "フェミニスト", "フェミ", "ツイフェミ", "ミソジニー", "ミサンドリー",
            "男女論", "女尊男卑", "男尊女卑", "弱者男性", "弱男",
            "不同意性交", "ジェンダー", "ジェンダーギャップ",
            "男性差別", "女性差別", "痴漢冤罪", "性加害",
            "レイプ", "強姦", "性暴力", "性的暴行", "輪姦",
            "盗撮", "セクハラ", "痴漢", "性被害",
            "feminist", "feminism", "misogyny", "misandry", "gender war",
            "rape", "sexual assault", "sexual violence");

    /** The jp24.13.13/13.14 seed of a preset whose seed grew in jp24.13.15, else null. */
    private static List<String> seed13_14(String id) {
        if (id.equals(ID_GENDER)) {
            return SEED_13_14_GENDER;
        }
        return null;
    }

    /** True when a stored preset's phrases are exactly a superseded bundled seed. */
    private static boolean isSupersededSeed(MuteCategory category) {
        List<String> seed12 = seed13_12(category.id);
        if (seed12 != null && category.phrases.equals(seed12)) {
            return true;
        }
        List<String> seed14 = seed13_14(category.id);
        return seed14 != null && category.phrases.equals(seed14);
    }

    /** The jp24.13.12 seed of a preset whose seed grew in jp24.13.13, else null. */
    private static List<String> seed13_12(String id) {
        if (id.equals(ID_GENDER)) {
            return SEED_13_12_GENDER;
        }
        if (id.equals(ID_NIGHTWORK)) {
            return SEED_13_12_NIGHTWORK;
        }
        if (id.equals(ID_INTLFEUD)) {
            return SEED_13_12_INTLFEUD;
        }
        return null;
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
        // jp24.13.12 upgrade migration: a store initialized before the
        // gender / nightwork / intlfeud presets existed gains each missing
        // one (with its bundled defaults) on first load. Only the new
        // presets are appended — every stored category, including user
        // edits to the other presets, is carried over untouched, and
        // user-removed vocabulary is never restored. The empty-list cases
        // (unreadable or cleared store) are skipped: they are not
        // initialized stores.
        // jp24.13.14: the regional preset joins the append set.
        // jp24.13.13: the religion preset joins the append set, and the
        // three presets whose seeds grew get a seed upgrade — a stored
        // preset whose phrases are still exactly the jp24.13.12 seed is
        // replaced with the current seed (its other fields, e.g. the
        // enabled toggle, are preserved). A user-edited list is left
        // completely alone; the new words are published in the build
        // notes for manual adding instead.
        // jp24.13.15: the gender seed grows again (男女分断); a stored
        // gender preset still exactly at the jp24.13.13/13.14 seed is
        // upgraded the same way (see isSupersededSeed).
        // jp24.13.16: the bait preset joins the append set.
        if (!categories.isEmpty()) {
            boolean migrated = false;
            List<MuteCategory> presets = presetCategories();
            for (int index = 0; index < categories.size(); index++) {
                MuteCategory category = categories.get(index);
                if (!category.preset || !isSupersededSeed(category)) {
                    continue;
                }
                for (MuteCategory preset : presets) {
                    if (preset.id.equals(category.id)
                            && !preset.phrases.equals(category.phrases)) {
                        categories.set(index, category.withPhrases(preset.phrases));
                        migrated = true;
                    }
                }
            }
            for (MuteCategory preset : presets) {
                if ((preset.id.equals(ID_GENDER) || preset.id.equals(ID_NIGHTWORK)
                        || preset.id.equals(ID_INTLFEUD) || preset.id.equals(ID_RELIGION)
                        || preset.id.equals(ID_REGIONAL) || preset.id.equals(ID_BAIT))
                        && !containsId(categories, preset.id)) {
                    categories.add(preset);
                    migrated = true;
                }
            }
            if (migrated) {
                persist(categories);
            }
        }
        snapshot = new Snapshot(categories);
        if (!persistence.readSeeded()) {
            persistence.writeSeeded(true);
        }
    }

    private static boolean containsId(List<MuteCategory> categories, String id) {
        for (MuteCategory category : categories) {
            if (category.id.equals(id)) {
                return true;
            }
        }
        return false;
    }

    private void publish(List<MuteCategory> categories) {
        snapshot = new Snapshot(categories);
        persist(categories);
    }

    /** JP jp24.13.17: the persisted document, as embedded in a filter bundle. */
    public synchronized JSONObject exportDocument() throws JSONException {
        ensureLoaded();
        return toJson(snapshot.categories());
    }

    /**
     * JP jp24.13.17: replaces the whole category list with a validated
     * import, then re-completes any bundled preset the file did not
     * carry (with its bundled defaults), so a minimal hand-edited file
     * cannot accidentally delete a preset. Custom categories absent
     * from the file are gone — the file is the truth. Returns how many
     * categories the file itself carried.
     */
    public synchronized int replaceAllFromImport(List<MuteCategory> validated) {
        ensureLoaded();
        List<MuteCategory> categories = new ArrayList<>(validated);
        for (MuteCategory preset : presetCategories()) {
            if (!containsId(categories, preset.id)) {
                categories.add(preset);
            }
        }
        publish(categories);
        return validated.size();
    }

    /**
     * JP jp24.13.17: validates one imported muteCategories section and
     * returns the corrected category list; throws JSONException with an
     * item-indexed message on the first structural problem.
     *
     * <p>Force-corrections keep an import from widening privileges:
     * {@code preset} is true only for bundled preset IDs (an unknown ID
     * claiming preset would become undeletable), and the doxxing
     * detector is available only to the sarashi preset — the raw
     * fromJson path would let any category claim it. Name, vocabulary,
     * toggles and scopes are taken from the file as-is.
     */
    public static List<MuteCategory> validateImport(JSONObject section) throws JSONException {
        if (section == null) {
            throw new JSONException("muteCategories セクションがありません");
        }
        int version = section.optInt("version", -1);
        if (version != SCHEMA_VERSION) {
            throw new JSONException("muteCategories のバージョン " + version + " には対応していません");
        }
        JSONArray array;
        try {
            array = section.getJSONArray("categories");
        } catch (JSONException exception) {
            throw new JSONException("muteCategories: categories が配列ではありません");
        }
        if (array.length() > 64) {
            throw new JSONException("muteCategories: カテゴリが多すぎます（" + array.length() + "件）");
        }
        List<MuteCategory> presets = presetCategories();
        List<MuteCategory> validated = new ArrayList<>();
        int totalPhrases = 0;
        for (int index = 0; index < array.length(); index++) {
            String where = "muteCategories: カテゴリ" + (index + 1) + "件目";
            JSONObject object;
            try {
                object = array.getJSONObject(index);
            } catch (JSONException exception) {
                throw new JSONException(where + "がオブジェクトではありません");
            }
            String id = object.optString("id", "").trim();
            if (id.isEmpty()) {
                throw new JSONException(where + "の id が空です");
            }
            if (containsId(validated, id)) {
                continue; // First occurrence wins; later duplicates are ignored.
            }
            String name = sanitizeName(object.optString("name", ""));
            if (name.isEmpty()) {
                throw new JSONException(where + "の名前が空です");
            }
            if (name.length() > 50) {
                throw new JSONException(where + "の名前が長すぎます（50字まで）");
            }
            List<String> phrases = new ArrayList<>();
            JSONArray phraseArray = object.optJSONArray("phrases");
            if (phraseArray != null) {
                List<String> seenNormalized = new ArrayList<>();
                for (int phraseIndex = 0; phraseIndex < phraseArray.length(); phraseIndex++) {
                    String phrase;
                    try {
                        phrase = phraseArray.getString(phraseIndex).trim();
                    } catch (JSONException exception) {
                        throw new JSONException(
                                where + "の単語" + (phraseIndex + 1) + "件目が文字列ではありません");
                    }
                    if (phrase.isEmpty()) {
                        continue;
                    }
                    if (phrase.length() > 100) {
                        throw new JSONException(
                                where + "の単語" + (phraseIndex + 1) + "件目が長すぎます（100字まで）");
                    }
                    String normalized = PostFilterMatcher.normalize(phrase);
                    if (seenNormalized.contains(normalized)) {
                        continue;
                    }
                    seenNormalized.add(normalized);
                    phrases.add(phrase);
                    totalPhrases++;
                    if (totalPhrases > 2000) {
                        throw new JSONException("muteCategories: 単語が多すぎます（合計2000件まで）");
                    }
                }
            }
            boolean preset = false;
            for (MuteCategory candidate : presets) {
                if (candidate.id.equals(id)) {
                    preset = true;
                    break;
                }
            }
            boolean detectorAvailable = ID_SARASHI.equals(id);
            validated.add(new MuteCategory(
                    id,
                    name,
                    preset,
                    object.optBoolean("enabled", true),
                    object.optBoolean("content", true),
                    object.optBoolean("quoted", true),
                    object.optBoolean("screenName", false),
                    object.optBoolean("displayName", false),
                    detectorAvailable,
                    detectorAvailable && object.optBoolean("detector", false),
                    phrases));
        }
        return validated;
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
