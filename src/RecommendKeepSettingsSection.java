package app.morphe.extension.newx.postfilter;

import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

import app.morphe.extension.crimera.settings.SettingsUi;
import app.morphe.extension.crimera.ui.ButtonView;
import app.morphe.extension.crimera.ui.DialogView;
import app.morphe.extension.newx.timeline.CurrentViewer;
import app.morphe.extension.newx.ui.Theme;
import app.morphe.extension.shared.Utils;

/**
 * JP jp24.13.9: settings section for the recommendation keep filter,
 * added to the post-filter settings screen below the category-mute
 * section. Same visual language as {@link MuteCategorySettingsSection}:
 * toggles inline, phrase lists edited in dialogs.
 */
public final class RecommendKeepSettingsSection {

    private RecommendKeepSettingsSection() {
    }

    public static void addSection(Context context, LinearLayout content) {
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        content.addView(container, new LinearLayout.LayoutParams(-1, -2));
        buildInto(context, container);
    }

    private static void buildInto(final Context context, final LinearLayout container) {
        container.removeAllViews();
        final RecommendKeepStore store = RecommendKeepStore.shared();
        final RecommendKeepStore.Snapshot snapshot = store.snapshot();
        final RecommendKeepStore.Options options = snapshot.options();

        TextView header = SettingsUi.titleText(context);
        header.setText("おすすめキープフィルター");
        container.addView(header);

        TextView summary = SettingsUi.summaryText(context);
        summary.setText("おすすめ（ホーム）と探索のおすすめ投稿のうち、"
                + "技術系・動物に一致するものだけを残します。"
                + "フォロー中アカウントの投稿は常に表示されます。"
                + "非表示にした投稿は、投稿メニューの「非表示にした返信」から理由つきで確認できます。");
        container.addView(summary);

        container.addView(SettingsUi.divider(context));
        SettingsUi.SwitchRow masterRow = SettingsUi.switchRow(
                context, "おすすめキープフィルター", null, options.master);
        masterRow.setOnCheckedChangeListener(new SettingsUi.CheckedChangeListener() {
            @Override
            public void onCheckedChanged(boolean checked) {
                RecommendKeepStore.Options current =
                        RecommendKeepStore.shared().snapshot().options();
                RecommendKeepStore.shared().setOptions(new RecommendKeepStore.Options(
                        checked, current.targetHome, current.targetExplore,
                        current.followedAlways, current.keepTextless));
                buildInto(context, container);
            }
        });
        container.addView(masterRow);

        // JP jp24.13.20: the diagnostic shown (and reset) is the
        // CURRENT account's partition; other accounts' partitions
        // are neither displayed nor touched here.
        final String viewerKey = CurrentViewer.accountKey();
        TextView diagView = SettingsUi.summaryText(context);
        diagView.setText(diagnosticText(store, viewerKey));
        container.addView(diagView);
        if (viewerKey != null
                && store.diagnostic(viewerKey).status == RecommendKeepStore.DIAG_DEAD) {
            TextView reset = textButton(context, "フォロー判定をやり直す（診断リセット）");
            reset.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    RecommendKeepStore.shared().resetDiagnostic(viewerKey);
                    Utils.showToastShort("診断をリセットしました");
                    buildInto(context, container);
                }
            });
            container.addView(reset);
        }

        container.addView(SettingsUi.divider(context));
        container.addView(switchRow(context, "対象: ホーム（おすすめ）", null,
                options.targetHome, new OnToggle() {
                    @Override
                    public void apply(RecommendKeepStore.Options current, boolean checked) {
                        RecommendKeepStore.shared().setOptions(new RecommendKeepStore.Options(
                                current.master, checked, current.targetExplore,
                                current.followedAlways, current.keepTextless));
                    }
                }));
        container.addView(switchRow(context, "対象: 探索ランディング", null,
                options.targetExplore, new OnToggle() {
                    @Override
                    public void apply(RecommendKeepStore.Options current, boolean checked) {
                        RecommendKeepStore.shared().setOptions(new RecommendKeepStore.Options(
                                current.master, current.targetHome, checked,
                                current.followedAlways, current.keepTextless));
                    }
                }));
        container.addView(switchRow(context, "フォロー中アカウントは常時表示",
                "オフにするとフォロー中の投稿もカテゴリ判定の対象になります",
                options.followedAlways, new OnToggle() {
                    @Override
                    public void apply(RecommendKeepStore.Options current, boolean checked) {
                        RecommendKeepStore.shared().setOptions(new RecommendKeepStore.Options(
                                current.master, current.targetHome, current.targetExplore,
                                checked, current.keepTextless));
                    }
                }));

        for (final RecommendKeepStore.Category category : snapshot.categories()) {
            container.addView(SettingsUi.divider(context));
            SettingsUi.SwitchRow row = SettingsUi.switchRow(
                    context, category.name, null, category.enabled);
            row.setOnCheckedChangeListener(new SettingsUi.CheckedChangeListener() {
                @Override
                public void onCheckedChanged(boolean checked) {
                    RecommendKeepStore.shared().setCategoryEnabled(category.id, checked);
                }
            });
            container.addView(row);
            TextView edit = textButton(context,
                    "単語を編集（" + category.phrases.size() + "件の単語）");
            edit.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    showPhraseEditor(context, container, category.id,
                            category.name);
                }
            });
            container.addView(edit);
        }

        container.addView(SettingsUi.divider(context));
        container.addView(switchRow(context, "テキスト無し投稿も残す",
                "本文のない（画像だけの）おすすめ投稿を落としません",
                options.keepTextless, new OnToggle() {
                    @Override
                    public void apply(RecommendKeepStore.Options current, boolean checked) {
                        RecommendKeepStore.shared().setOptions(new RecommendKeepStore.Options(
                                current.master, current.targetHome, current.targetExplore,
                                current.followedAlways, checked));
                    }
                }));
    }

    private interface OnToggle {
        void apply(RecommendKeepStore.Options current, boolean checked);
    }

    private static SettingsUi.SwitchRow switchRow(
            Context context, String title, String summary, boolean checked,
            final OnToggle toggle) {
        SettingsUi.SwitchRow row = SettingsUi.switchRow(context, title, summary, checked);
        row.setOnCheckedChangeListener(new SettingsUi.CheckedChangeListener() {
            @Override
            public void onCheckedChanged(boolean value) {
                RecommendKeepStore.Options current =
                        RecommendKeepStore.shared().snapshot().options();
                toggle.apply(current, value);
            }
        });
        return row;
    }

    private static String diagnosticText(RecommendKeepStore store, String viewerKey) {
        if (viewerKey == null) {
            // JP jp24.13.20: no timeline component has reported the
            // current account yet (or the user is logged out).
            return "フォロー判定: アカウント未判定"
                    + "（タイムラインを開くと判定します）";
        }
        RecommendKeepStore.Diagnostic diagnostic = store.diagnostic(viewerKey);
        switch (diagnostic.status) {
            case RecommendKeepStore.DIAG_TRUSTED:
                return "フォロー判定: 有効（フォロー中タブの投稿 "
                        + diagnostic.samples + " 件で確認済み／アカウント "
                        + viewerKey + "）";
            case RecommendKeepStore.DIAG_DEAD:
                return "フォロー判定: 自動停止中（投稿の著者IDが取得できなかったため、"
                        + "安全のためフィルターは動作していません）"
                        + "（アカウント " + viewerKey + "）";
            default:
                return "フォロー判定: 診断中（フォロー中タブの投稿 "
                        + diagnostic.samples + " / 40 件を確認済み。"
                        + "診断が終わるまで投稿は落とされません／アカウント "
                        + viewerKey + "）";
        }
    }

    private static TextView textButton(Context context, String text) {
        TextView button = new TextView(context);
        button.setText(text);
        button.setTextColor(Theme.primaryAccent(context));
        button.setTextSize(14);
        int vertical = Theme.dpToPx(context, 10f);
        button.setPadding(Theme.dpToPx(context, 24f), vertical,
                Theme.dpToPx(context, 24f), vertical);
        button.setClickable(true);
        return button;
    }

    private static void showPhraseEditor(
            final Context context,
            final LinearLayout container,
            final String categoryId,
            String title) {
        LinearLayout form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(
                Theme.dpToPx(context, 24f),
                Theme.dpToPx(context, 8f),
                Theme.dpToPx(context, 24f),
                0);

        final DialogView dialog = new DialogView(context)
                .setTitle(title)
                .setScrollableBodyView(form);
        renderPhraseEditor(context, dialog, form, categoryId);

        ButtonView close = new ButtonView(context, ButtonView.ButtonStyle.TEXT, "閉じる");
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                dialog.dismiss();
            }
        });
        dialog.addButton(close);
        dialog.getDialog().setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialogInterface) {
                buildInto(context, container);
            }
        });
        dialog.show();
    }

    private static java.util.List<String> phrasesOf(
            RecommendKeepStore store, String categoryId) {
        RecommendKeepStore.Category category = store.snapshot().category(categoryId);
        return category == null ? java.util.Collections.emptyList() : category.phrases;
    }

    private static void renderPhraseEditor(
            final Context context,
            final DialogView dialog,
            final LinearLayout form,
            final String categoryId) {
        form.removeAllViews();
        final RecommendKeepStore store = RecommendKeepStore.shared();

        final TextView phraseHeader = SettingsUi.summaryText(context);
        form.addView(phraseHeader);

        final LinearLayout phraseList = new LinearLayout(context);
        phraseList.setOrientation(LinearLayout.VERTICAL);
        form.addView(phraseList);
        renderPhrases(context, phraseList, phraseHeader, categoryId);

        LinearLayout addRow = new LinearLayout(context);
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        final EditText phraseInput = SettingsUi.textInput(
                context, "追加する単語",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        addRow.addView(phraseInput, new LinearLayout.LayoutParams(0, -2, 1f));
        ButtonView addPhrase = new ButtonView(context, ButtonView.ButtonStyle.TEXT, "追加");
        addPhrase.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                // JP jp24.13.21: bulk add — see BulkPhraseInput. A
                // single word behaves exactly as before.
                String raw = phraseInput.getText().toString();
                List<String> phrases = BulkPhraseInput.split(raw);
                if (phrases.size() <= 1) {
                    try {
                        store.addPhrase(categoryId,
                                phrases.isEmpty() ? raw : phrases.get(0));
                        phraseInput.setText("");
                        renderPhrases(context, phraseList, phraseHeader, categoryId);
                    } catch (RecommendKeepStore.ValidationException exception) {
                        Utils.showToastShort(exception.getMessage());
                    }
                    return;
                }
                int added = 0;
                String firstError = null;
                for (String phrase : phrases) {
                    try {
                        store.addPhrase(categoryId, phrase);
                        added++;
                    } catch (RecommendKeepStore.ValidationException exception) {
                        if (firstError == null) {
                            firstError = exception.getMessage();
                        }
                    }
                }
                if (added > 0) {
                    phraseInput.setText("");
                    renderPhrases(context, phraseList, phraseHeader, categoryId);
                    Utils.showToastShort(added + "件の単語を追加しました");
                } else if (firstError != null) {
                    Utils.showToastShort(firstError);
                }
            }
        });
        addRow.addView(addPhrase);
        form.addView(addRow);

        TextView restore = textButton(context, "プリセットの単語を追加");
        restore.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                int added = store.addPresetPhrases(categoryId);
                Utils.showToastShort(added > 0
                        ? added + "件の単語を追加しました"
                        : "追加できる単語はありません");
                renderPhrases(context, phraseList, phraseHeader, categoryId);
            }
        });
        form.addView(restore);
    }

    private static void renderPhrases(
            final Context context,
            final LinearLayout phraseList,
            final TextView phraseHeader,
            final String categoryId) {
        phraseList.removeAllViews();
        final RecommendKeepStore store = RecommendKeepStore.shared();
        java.util.List<String> phrases = phrasesOf(store, categoryId);
        phraseHeader.setText("単語（" + phrases.size() + "件）");
        for (final String phrase : phrases) {
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView phraseView = new TextView(context);
            phraseView.setText(phrase);
            phraseView.setTextColor(Theme.primaryText(context));
            phraseView.setTextSize(14);
            row.addView(phraseView, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView delete = new TextView(context);
            delete.setText("削除");
            delete.setTextColor(Color.rgb(244, 33, 46));
            delete.setTextSize(14);
            delete.setClickable(true);
            delete.setPadding(
                    Theme.dpToPx(context, 12f),
                    Theme.dpToPx(context, 4f),
                    0,
                    Theme.dpToPx(context, 4f));
            delete.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    store.removePhrase(categoryId, phrase);
                    renderPhrases(context, phraseList, phraseHeader, categoryId);
                }
            });
            row.addView(delete);
            phraseList.addView(row);
        }
    }
}
