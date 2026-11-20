package app.morphe.extension.newx.postfilter;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import app.morphe.extension.newx.settings.NewXLogger;
import app.morphe.extension.newx.ui.Theme;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.crimera.settings.SettingsUi;
import app.morphe.extension.crimera.ui.ButtonView;
import app.morphe.extension.crimera.ui.DialogView;

/**
 * JP jp24.13.17: JSON import/export for the whole ツイートフィルター
 * screen (design jp24_13/FREETEXT_DESIGN.md section E).
 *
 * <p>Bundle shape:
 * {@code {"format":"comebacktwitter-filter-bundle","version":1,
 * "sections":{...}}}. Export carries muteCategories, keywordRules and
 * the recommendKeep PROJECTION (categories/options — never
 * the followed-ID cache or the follower-gate diagnostics).
 * blockedAuthors is NEVER exported (user decision, final): the section
 * exists on the import side only, for hand-authored files.
 *
 * <p>Import is two-phase: every present section is validated first
 * (invalid sections are reported, never applied), then the valid ones
 * are applied. muteCategories replaces wholesale with missing presets
 * re-completed; blockedAuthors replaces wholesale and always raises
 * the store's SEEDED flag so the bundled defaults cannot merge back in
 * on a fresh install.
 *
 * <p>JP jp24.13.24.5: an additive （追加/マージ) import mode joins the
 * original replace mode. After the bundle validates, both routes
 * (file and paste) offer the choice through one shared dialog; the
 * default is replace. Merge unions phrases/rules/IDs into the current
 * state (existing categories' flags win, keep options are never
 * overwritten) — see the stores' mergeFromImport methods.
 */
public final class FilterBundleImportExport {
    public static final String FORMAT = "comebacktwitter-filter-bundle";
    public static final int BUNDLE_VERSION = 1;
    private static final String EXPORTED_BY = "jp24.13.24.5";
    private static final int IMPORT_REQUEST_CODE = 0x5071;
    private static final int EXPORT_REQUEST_CODE = 0x5072;
    private static final int MAX_IMPORT_BYTES = 1024 * 1024;

    private FilterBundleImportExport() {
    }

    // ------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------

    public static JSONObject buildExportBundle() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("format", FORMAT);
        root.put("version", BUNDLE_VERSION);
        root.put("exportedBy", EXPORTED_BY);
        JSONObject sections = new JSONObject();
        sections.put("muteCategories", MuteCategoryStore.shared().exportDocument());
        sections.put("keywordRules", PostFilterRuleStore.shared().exportDocument());
        sections.put("recommendKeep", RecommendKeepStore.shared().exportProjection());
        // blockedAuthors is never exported (user decision, jp24.13.17).
        root.put("sections", sections);
        return root;
    }

    public static String buildExportBundleString() throws JSONException {
        return buildExportBundle().toString(2);
    }

    // ------------------------------------------------------------------
    // Import: two-phase validation, then apply
    // ------------------------------------------------------------------

    public static final class SectionResult {
        public final String label;
        public final boolean applied;
        public final String detail;

        private SectionResult(String label, boolean applied, String detail) {
            this.label = label;
            this.applied = applied;
            this.detail = detail;
        }
    }

    public static final class ImportOutcome {
        /** True when the bundle itself was rejected; nothing was applied. */
        public final boolean bundleError;
        public final String error;
        public final List<SectionResult> sections;
        /** JP jp24.13.24.5: true when the additive （追加） mode applied the bundle. */
        public final boolean merge;

        private ImportOutcome(String error) {
            this.bundleError = true;
            this.error = error;
            this.sections = new ArrayList<>();
            this.merge = false;
        }

        private ImportOutcome(List<SectionResult> sections, boolean merge) {
            this.bundleError = false;
            this.error = null;
            this.sections = sections;
            this.merge = merge;
        }

        public int appliedCount() {
            int count = 0;
            for (SectionResult section : sections) {
                if (section.applied) {
                    count++;
                }
            }
            return count;
        }

        public String summaryText() {
            if (bundleError) {
                return error;
            }
            StringBuilder builder = new StringBuilder();
            builder.append(merge ? "方法: 追加（マージ）" : "方法: 上書き（置き換え）");
            for (SectionResult section : sections) {
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append(section.label).append(": ").append(section.detail);
            }
            return builder.toString();
        }
    }

    private static final class KeepImport {
        List<RecommendKeepStore.Category> categories;
        RecommendKeepStore.Options options;
    }

    private static final class BlockedImport {
        final List<String> ids = new ArrayList<>();
        int skipped;
    }

    /**
     * JP jp24.13.24.5: a bundle that passed phase-1 validation. The UI
     * holds one of these between validation and the mode choice, so the
     * two-phase discipline (validate everything, then apply) survives
     * the dialog round trip: nothing is applied from here until
     * {@link #applyValidated} runs, in either mode.
     */
    public static final class ValidatedBundle {
        public final boolean bundleError;
        public final String error;
        final boolean hasMute;
        final List<MuteCategory> muteCategories;
        final String muteError;
        final boolean hasRules;
        final List<PostFilterRule> rules;
        final String rulesError;
        final boolean hasKeep;
        final KeepImport keep;
        final String keepError;
        final boolean hasBlocked;
        final BlockedImport blocked;
        final String blockedError;

        private ValidatedBundle(String error) {
            this.bundleError = true;
            this.error = error;
            this.hasMute = false;
            this.muteCategories = null;
            this.muteError = null;
            this.hasRules = false;
            this.rules = null;
            this.rulesError = null;
            this.hasKeep = false;
            this.keep = null;
            this.keepError = null;
            this.hasBlocked = false;
            this.blocked = null;
            this.blockedError = null;
        }

        private ValidatedBundle(
                boolean hasMute, List<MuteCategory> muteCategories, String muteError,
                boolean hasRules, List<PostFilterRule> rules, String rulesError,
                boolean hasKeep, KeepImport keep, String keepError,
                boolean hasBlocked, BlockedImport blocked, String blockedError) {
            this.bundleError = false;
            this.error = null;
            this.hasMute = hasMute;
            this.muteCategories = muteCategories;
            this.muteError = muteError;
            this.hasRules = hasRules;
            this.rules = rules;
            this.rulesError = rulesError;
            this.hasKeep = hasKeep;
            this.keep = keep;
            this.keepError = keepError;
            this.hasBlocked = hasBlocked;
            this.blocked = blocked;
            this.blockedError = blockedError;
        }
    }

    /** Replace-mode import (the original jp24.13.17 behavior). */
    public static ImportOutcome importBundle(String text) {
        return importBundle(text, false);
    }

    /**
     * JP jp24.13.24.5: import with an explicit mode — {@code merge}
     * false replaces (original behavior), true merges additively.
     */
    public static ImportOutcome importBundle(String text, boolean merge) {
        return applyValidated(validateBundle(text), merge);
    }

    /** Phase 1 only: parse and validate every present section; no side effects. */
    public static ValidatedBundle validateBundle(String text) {
        if (text == null || text.trim().isEmpty()) {
            return new ValidatedBundle("テキストが空です");
        }
        if (text.length() > MAX_IMPORT_BYTES) {
            return new ValidatedBundle("データが大きすぎます（1MBまで）");
        }
        JSONObject root;
        try {
            root = new JSONObject(text);
        } catch (JSONException exception) {
            return new ValidatedBundle("JSONとして読み込めませんでした");
        }
        if (!FORMAT.equals(root.optString("format", ""))) {
            return new ValidatedBundle("フィルターバンドルの形式ではありません");
        }
        if (root.optInt("version", -1) != BUNDLE_VERSION) {
            return new ValidatedBundle("対応していないバンドルのバージョンです");
        }
        JSONObject sections = root.optJSONObject("sections");
        if (sections == null) {
            return new ValidatedBundle("sections がありません");
        }

        List<MuteCategory> muteCategories = null;
        String muteError = null;
        boolean hasMute = sections.has("muteCategories");
        if (hasMute) {
            try {
                muteCategories = MuteCategoryStore.validateImport(
                        sections.optJSONObject("muteCategories"));
            } catch (JSONException exception) {
                muteError = exception.getMessage();
            }
        }
        List<PostFilterRule> rules = null;
        String rulesError = null;
        boolean hasRules = sections.has("keywordRules");
        if (hasRules) {
            try {
                rules = PostFilterRuleStore.validateImport(sections.optJSONObject("keywordRules"));
            } catch (JSONException exception) {
                rulesError = exception.getMessage();
            }
        }
        KeepImport keep = null;
        String keepError = null;
        boolean hasKeep = sections.has("recommendKeep");
        if (hasKeep) {
            try {
                keep = validateRecommendKeep(sections.optJSONObject("recommendKeep"));
            } catch (JSONException exception) {
                keepError = exception.getMessage();
            }
        }
        BlockedImport blocked = null;
        String blockedError = null;
        boolean hasBlocked = sections.has("blockedAuthors");
        if (hasBlocked) {
            try {
                blocked = validateBlockedAuthors(sections.optJSONObject("blockedAuthors"));
            } catch (JSONException exception) {
                blockedError = exception.getMessage();
            }
        }
        if (!hasMute && !hasRules && !hasKeep && !hasBlocked) {
            return new ValidatedBundle("取り込めるセクションがありませんでした");
        }
        return new ValidatedBundle(
                hasMute, muteCategories, muteError,
                hasRules, rules, rulesError,
                hasKeep, keep, keepError,
                hasBlocked, blocked, blockedError);
    }

    /** Phase 2: apply the sections that validated, in the chosen mode. */
    public static ImportOutcome applyValidated(ValidatedBundle validated, boolean merge) {
        if (validated.bundleError) {
            return new ImportOutcome(validated.error);
        }
        List<SectionResult> results = new ArrayList<>();
        if (validated.hasMute) {
            if (validated.muteError != null) {
                results.add(new SectionResult("カテゴリ別ミュート", false,
                        "取り込めませんでした（" + validated.muteError + "）"));
            } else {
                try {
                    if (merge) {
                        int[] counts = MuteCategoryStore.shared()
                                .mergeFromImport(validated.muteCategories);
                        results.add(new SectionResult("カテゴリ別ミュート", true,
                                "カテゴリ+" + counts[0] + " / 単語+" + counts[1]));
                    } else {
                        int count = MuteCategoryStore.shared()
                                .replaceAllFromImport(validated.muteCategories);
                        results.add(new SectionResult("カテゴリ別ミュート", true,
                                count + "件を取り込みました"));
                    }
                } catch (Exception exception) {
                    NewXLogger.printException(() -> "FilterBundleImportExport mute apply failed", exception);
                    results.add(new SectionResult("カテゴリ別ミュート", false,
                            "適用に失敗しました"));
                }
            }
        }
        if (validated.hasRules) {
            if (validated.rulesError != null) {
                results.add(new SectionResult("キーワードフィルター", false,
                        "取り込めませんでした（" + validated.rulesError + "）"));
            } else {
                try {
                    if (merge) {
                        int[] counts = PostFilterRuleStore.shared()
                                .mergeFromImport(validated.rules);
                        String detail = "ルール+" + counts[0];
                        if (counts[1] > 0) {
                            detail += "（重複" + counts[1] + "件をスキップ）";
                        }
                        results.add(new SectionResult("キーワードフィルター", true, detail));
                    } else {
                        PostFilterRuleStore.shared().replaceAllFromImport(validated.rules);
                        results.add(new SectionResult("キーワードフィルター", true,
                                validated.rules.size() + "件を取り込みました"));
                    }
                } catch (Exception exception) {
                    NewXLogger.printException(() -> "FilterBundleImportExport rules apply failed", exception);
                    results.add(new SectionResult("キーワードフィルター", false,
                            "適用に失敗しました"));
                }
            }
        }
        if (validated.hasKeep) {
            if (validated.keepError != null) {
                results.add(new SectionResult("おすすめキープ", false,
                        "取り込めませんでした（" + validated.keepError + "）"));
            } else {
                try {
                    if (merge) {
                        int[] counts = RecommendKeepStore.shared().mergeProjectionFromImport(
                                validated.keep.categories);
                        results.add(new SectionResult("おすすめキープ", true,
                                "カテゴリ+" + counts[0] + " / 単語+" + counts[1]));
                    } else {
                        RecommendKeepStore.shared().replaceProjectionFromImport(
                                validated.keep.categories, validated.keep.options);
                        results.add(new SectionResult("おすすめキープ", true,
                                "カテゴリ" + validated.keep.categories.size()
                                        + "件を取り込みました"));
                    }
                } catch (Exception exception) {
                    NewXLogger.printException(() -> "FilterBundleImportExport keep apply failed", exception);
                    results.add(new SectionResult("おすすめキープ", false,
                            "適用に失敗しました"));
                }
            }
        }
        if (validated.hasBlocked) {
            if (validated.blockedError != null) {
                results.add(new SectionResult("指定アカウント", false,
                        "取り込めませんでした（" + validated.blockedError + "）"));
            } else {
                try {
                    if (merge) {
                        int added = BlockedAuthorsStore.shared().addAll(validated.blocked.ids);
                        String detail = "ID+" + added + "件を追加";
                        if (validated.blocked.skipped > 0) {
                            detail += "（不正な" + validated.blocked.skipped + "件をスキップ）";
                        }
                        results.add(new SectionResult("指定アカウント", true, detail));
                    } else {
                        BlockedAuthorsStore.shared().replaceAll(validated.blocked.ids);
                        String detail = validated.blocked.ids.size() + "件を取り込みました";
                        if (validated.blocked.skipped > 0) {
                            detail += "（不正な" + validated.blocked.skipped + "件をスキップ）";
                        }
                        results.add(new SectionResult("指定アカウント", true, detail));
                    }
                } catch (Exception exception) {
                    NewXLogger.printException(() -> "FilterBundleImportExport blocked apply failed", exception);
                    results.add(new SectionResult("指定アカウント", false,
                            "適用に失敗しました"));
                }
            }
        }
        return new ImportOutcome(results, merge);
    }

    private static KeepImport validateRecommendKeep(JSONObject section) throws JSONException {
        if (section == null) {
            throw new JSONException("recommendKeep セクションがありません");
        }
        JSONArray array;
        try {
            array = section.getJSONArray("categories");
        } catch (JSONException exception) {
            throw new JSONException("おすすめキープ: categories が配列ではありません");
        }
        Set<String> knownIds = new LinkedHashSet<>(
                Arrays.asList("tech", "animals"));
        KeepImport result = new KeepImport();
        result.categories = new ArrayList<>();
        int[] totalPhrases = {0};
        for (int index = 0; index < array.length(); index++) {
            String where = "おすすめキープ: カテゴリ" + (index + 1) + "件目";
            JSONObject object;
            try {
                object = array.getJSONObject(index);
            } catch (JSONException exception) {
                throw new JSONException(where + "がオブジェクトではありません");
            }
            String id = object.optString("id", "").trim();
            if (!knownIds.contains(id)) {
                throw new JSONException(where + "のID「" + id + "」には対応していません");
            }
            boolean duplicate = false;
            for (RecommendKeepStore.Category existing : result.categories) {
                if (existing.id.equals(id)) {
                    duplicate = true;
                    break;
                }
            }
            if (duplicate) {
                continue;
            }
            String name = object.optString("name", "").replace(':', '：').trim();
            if (name.isEmpty()) {
                throw new JSONException(where + "の名前が空です");
            }
            if (name.length() > 50) {
                throw new JSONException(where + "の名前が長すぎます（50字まで）");
            }
            List<String> phrases = validatedPhrases(
                    object.optJSONArray("phrases"), where, totalPhrases);
            result.categories.add(new RecommendKeepStore.Category(
                    id, name, object.optBoolean("enabled", true), phrases));
        }
        RecommendKeepStore.Options defaults = RecommendKeepStore.Options.defaults();
        result.options = defaults;
        JSONObject optionsObject = section.optJSONObject("options");
        if (optionsObject != null) {
            result.options = new RecommendKeepStore.Options(
                    optionsObject.optBoolean("master", defaults.master),
                    optionsObject.optBoolean("targetHome", defaults.targetHome),
                    optionsObject.optBoolean("targetExplore", defaults.targetExplore),
                    optionsObject.optBoolean("followedAlways", defaults.followedAlways),
                    optionsObject.optBoolean("keepTextless", defaults.keepTextless));
        }
        return result;
    }

    private static List<String> validatedPhrases(
            JSONArray array, String where, int[] totalPhrases) throws JSONException {
        List<String> phrases = new ArrayList<>();
        if (array == null) {
            return phrases;
        }
        List<String> seenNormalized = new ArrayList<>();
        for (int index = 0; index < array.length(); index++) {
            String phrase;
            try {
                phrase = array.getString(index).trim();
            } catch (JSONException exception) {
                throw new JSONException(where + "の単語" + (index + 1) + "件目が文字列ではありません");
            }
            if (phrase.isEmpty()) {
                continue;
            }
            if (phrase.length() > 100) {
                throw new JSONException(where + "の単語" + (index + 1) + "件目が長すぎます（100字まで）");
            }
            String normalized = PostFilterMatcher.normalize(phrase);
            if (seenNormalized.contains(normalized)) {
                continue;
            }
            seenNormalized.add(normalized);
            phrases.add(phrase);
            totalPhrases[0]++;
            if (totalPhrases[0] > 2000) {
                throw new JSONException("おすすめキープ: 単語が多すぎます（合計2000件まで）");
            }
        }
        return phrases;
    }

    private static BlockedImport validateBlockedAuthors(JSONObject section) throws JSONException {
        if (section == null) {
            throw new JSONException("blockedAuthors セクションがありません");
        }
        JSONArray array;
        try {
            array = section.getJSONArray("ids");
        } catch (JSONException exception) {
            throw new JSONException("指定アカウント: ids が配列ではありません");
        }
        if (array.length() > 1000) {
            throw new JSONException("指定アカウント: 件数が多すぎます（" + array.length() + "件）");
        }
        BlockedImport result = new BlockedImport();
        for (int index = 0; index < array.length(); index++) {
            Object entry = array.opt(index);
            String normalized = entry instanceof String
                    ? BlockedAuthorsStore.normalizeIdInput((String) entry)
                    : null;
            if (normalized == null) {
                result.skipped++;
                continue;
            }
            if (!result.ids.contains(normalized)) {
                result.ids.add(normalized);
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Settings-screen buttons and choosers
    // ------------------------------------------------------------------

    public static void addButtons(final Context context, LinearLayout content) {
        content.addView(SettingsUi.divider(context));
        TextView exportButton = textButton(context, "フィルター設定をエクスポート");
        exportButton.setOnClickListener(view -> showExportChooser(context));
        content.addView(exportButton);
        TextView importButton = textButton(context, "フィルター設定をインポート");
        importButton.setOnClickListener(view -> showImportChooser(context));
        content.addView(importButton);
    }

    private static TextView textButton(Context context, String text) {
        TextView button = new TextView(context);
        button.setText(text);
        button.setTextColor(Theme.primaryAccent(context));
        button.setTextSize(14.0f);
        button.setPadding(
                Theme.dpToPx(context, 24.0f),
                Theme.dpToPx(context, 10.0f),
                Theme.dpToPx(context, 24.0f),
                Theme.dpToPx(context, 10.0f));
        return button;
    }

    private static void showExportChooser(final Context context) {
        final DialogView dialog = new DialogView(context)
                .setTitle("フィルター設定をエクスポート")
                .setSubtitle("保存方法を選んでください");
        ButtonView toFile = new ButtonView(context, ButtonView.ButtonStyle.TEXT, "ファイルに保存");
        toFile.setOnClickListener(view -> {
            dialog.dismiss();
            if (context instanceof Activity) {
                launchExport((Activity) context);
            } else {
                Utils.showToastShort("この画面からはファイル保存できません");
            }
        });
        dialog.addButton(toFile);
        ButtonView toClipboard = new ButtonView(context, ButtonView.ButtonStyle.TEXT,
                "クリップボードにコピー");
        toClipboard.setOnClickListener(view -> {
            dialog.dismiss();
            copyExportToClipboard(context);
        });
        dialog.addButton(toClipboard);
        ButtonView cancel = new ButtonView(context, ButtonView.ButtonStyle.TEXT, "キャンセル");
        cancel.setOnClickListener(view -> dialog.dismiss());
        dialog.addButton(cancel);
        dialog.show();
    }

    private static void showImportChooser(final Context context) {
        final DialogView dialog = new DialogView(context)
                .setTitle("フィルター設定をインポート")
                .setSubtitle("読み込み方法を選んでください");
        ButtonView fromFile = new ButtonView(context, ButtonView.ButtonStyle.TEXT,
                "ファイルから読み込む");
        fromFile.setOnClickListener(view -> {
            dialog.dismiss();
            if (context instanceof Activity) {
                launchImport((Activity) context);
            } else {
                Utils.showToastShort("この画面からはファイル読み込みできません");
            }
        });
        dialog.addButton(fromFile);
        ButtonView fromText = new ButtonView(context, ButtonView.ButtonStyle.TEXT,
                "テキストを貼り付ける");
        fromText.setOnClickListener(view -> {
            dialog.dismiss();
            showPasteDialog(context);
        });
        dialog.addButton(fromText);
        ButtonView cancel = new ButtonView(context, ButtonView.ButtonStyle.TEXT, "キャンセル");
        cancel.setOnClickListener(view -> dialog.dismiss());
        dialog.addButton(cancel);
        dialog.show();
    }

    private static void copyExportToClipboard(Context context) {
        try {
            ClipboardManager clipboard =
                    (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) {
                Utils.showToastShort("クリップボードを利用できません");
                return;
            }
            clipboard.setPrimaryClip(ClipData.newPlainText(
                    "comebacktwitter filter bundle", buildExportBundleString()));
            Utils.showToastShort("フィルター設定をコピーしました");
        } catch (Exception exception) {
            NewXLogger.printException(() -> "Filter bundle clipboard export failed", exception);
            Utils.showToastShort("エクスポートに失敗しました");
        }
    }

    private static void showPasteDialog(final Context context) {
        LinearLayout form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(
                Theme.dpToPx(context, 24.0f),
                Theme.dpToPx(context, 8.0f),
                Theme.dpToPx(context, 24.0f),
                0);
        final EditText input = SettingsUi.textInput(
                context, "JSONを貼り付け",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setMinLines(6);
        form.addView(input);
        final TextView error = SettingsUi.summaryText(context);
        error.setTextColor(android.graphics.Color.rgb(244, 33, 46));
        error.setVisibility(View.GONE);
        form.addView(error);
        final DialogView dialog = new DialogView(context)
                .setTitle("フィルター設定をインポート")
                .setScrollableBodyView(form);
        ButtonView cancel = new ButtonView(context, ButtonView.ButtonStyle.TEXT, "キャンセル");
        cancel.setOnClickListener(view -> dialog.dismiss());
        dialog.addButton(cancel);
        ButtonView apply = new ButtonView(context, ButtonView.ButtonStyle.TEXT, "取り込む");
        apply.setOnClickListener(view -> {
            ValidatedBundle validated = validateBundle(input.getText().toString());
            if (validated.bundleError) {
                error.setText(validated.error);
                error.setVisibility(View.VISIBLE);
                return;
            }
            dialog.dismiss();
            showModeChooser(context, validated);
        });
        dialog.addButton(apply);
        dialog.show();
    }

    /**
     * JP jp24.13.24.5: the shared import-mode choice, offered by both
     * the file and the paste route after validation has passed.
     * 「上書き（置き換え）」 is listed first and stays the default
     * behavior; 「追加（マージ）」 unions into the current settings.
     */
    private static void showModeChooser(final Context context, final ValidatedBundle validated) {
        final DialogView dialog = new DialogView(context)
                .setTitle("フィルター設定をインポート")
                .setSubtitle("取り込み方法を選んでください");
        ButtonView replace = new ButtonView(context, ButtonView.ButtonStyle.TEXT,
                "上書き（置き換え）");
        replace.setOnClickListener(view -> {
            dialog.dismiss();
            showSummary(context, applyValidated(validated, false));
        });
        dialog.addButton(replace);
        ButtonView merge = new ButtonView(context, ButtonView.ButtonStyle.TEXT,
                "追加（マージ）");
        merge.setOnClickListener(view -> {
            dialog.dismiss();
            showSummary(context, applyValidated(validated, true));
        });
        dialog.addButton(merge);
        ButtonView cancel = new ButtonView(context, ButtonView.ButtonStyle.TEXT, "キャンセル");
        cancel.setOnClickListener(view -> dialog.dismiss());
        dialog.addButton(cancel);
        dialog.show();
    }

    private static void showSummary(Context context, ImportOutcome outcome) {
        TextView body = SettingsUi.summaryText(context);
        body.setText(outcome.summaryText());
        body.setPadding(
                Theme.dpToPx(context, 24.0f),
                Theme.dpToPx(context, 8.0f),
                Theme.dpToPx(context, 24.0f),
                0);
        DialogView dialog = new DialogView(context)
                .setTitle("取り込み結果")
                .setBodyView(body);
        ButtonView close = new ButtonView(context, ButtonView.ButtonStyle.TEXT, "閉じる");
        close.setOnClickListener(view -> dialog.dismiss());
        dialog.addButton(close);
        dialog.show();
    }

    // ------------------------------------------------------------------
    // File routes (imitate FeatureSwitchImportExport)
    // ------------------------------------------------------------------

    public static void launchImport(Activity activity) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        activity.startActivityForResult(intent, IMPORT_REQUEST_CODE);
    }

    public static void launchExport(Activity activity) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, exportFileName());
        activity.startActivityForResult(intent, EXPORT_REQUEST_CODE);
    }

    public static boolean handleActivityResult(
            Activity activity, int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode == IMPORT_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                importFromUri(activity, data.getData());
            }
            return true;
        }
        if (requestCode == EXPORT_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                exportToUri(activity, data.getData());
            }
            return true;
        }
        return false;
    }

    private static void importFromUri(Activity activity, Uri uri) {
        try (InputStream input = activity.getContentResolver().openInputStream(uri)) {
            if (input == null) {
                throw new IOException("Could not open the selected file");
            }
            String text = new String(readAll(input), StandardCharsets.UTF_8);
            ValidatedBundle validated = validateBundle(text);
            if (validated.bundleError) {
                Utils.showToastShort(validated.error);
            } else {
                showModeChooser(activity, validated);
            }
        } catch (Exception exception) {
            NewXLogger.printException(() -> "Filter bundle import failed", exception);
            Utils.showToastShort("読み込みに失敗しました");
        }
    }

    private static void exportToUri(Activity activity, Uri uri) {
        try (OutputStream output = activity.getContentResolver().openOutputStream(uri)) {
            if (output == null) {
                throw new IOException("Could not open the selected file");
            }
            output.write(buildExportBundleString().getBytes(StandardCharsets.UTF_8));
            Utils.showToastShort("フィルター設定をエクスポートしました");
        } catch (Exception exception) {
            NewXLogger.printException(() -> "Filter bundle export failed", exception);
            Utils.showToastShort("エクスポートに失敗しました");
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > MAX_IMPORT_BYTES) {
                throw new IOException("Filter bundle is too large");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String exportFileName() {
        String timestamp = new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.US).format(new Date());
        return "comebacktwitter_filter_" + timestamp + ".json";
    }
}
