package app.morphe.extension.newx.postfilter;

import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.crimera.settings.SettingsUi;
import app.morphe.extension.crimera.ui.ButtonView;
import app.morphe.extension.crimera.ui.DialogView;
import app.morphe.extension.newx.ui.Theme;
import app.morphe.extension.shared.Utils;

/**
 * JP jp24.13.9: settings section for the recommendation keep filter,
 * added to the post-filter settings screen below the category-mute
 * section. Same visual language as {@link MuteCategorySettingsSection}:
 * toggles inline, phrase lists edited in dialogs.
 */
public final class RecommendKeepSettingsSection {

    private static final int KIND_CATEGORY = 0;
    private static final int KIND_EXCLUSION = 1;

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
                + "技術系・動物・コスプレ（アジア圏）に一致するものだけを残します。"
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
                        current.followedAlways, current.nonAsiaCosplay,
                        current.cosMediaRequired, current.keepTextless,
                        current.followerThresholdEnabled, current.followerThreshold,
                                current.cosplayRatioPercent));
                buildInto(context, container);
            }
        });
        container.addView(masterRow);

        TextView diagView = SettingsUi.summaryText(context);
        diagView.setText(diagnosticText(store));
        container.addView(diagView);
        if (store.diagnostic().status == RecommendKeepStore.DIAG_DEAD) {
            TextView reset = textButton(context, "フォロー判定をやり直す（診断リセット）");
            reset.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    RecommendKeepStore.shared().resetDiagnostic();
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
                                current.followedAlways, current.nonAsiaCosplay,
                                current.cosMediaRequired, current.keepTextless,
                                current.followerThresholdEnabled, current.followerThreshold,
                                current.cosplayRatioPercent));
                    }
                }));
        container.addView(switchRow(context, "対象: 探索ランディング", null,
                options.targetExplore, new OnToggle() {
                    @Override
                    public void apply(RecommendKeepStore.Options current, boolean checked) {
                        RecommendKeepStore.shared().setOptions(new RecommendKeepStore.Options(
                                current.master, current.targetHome, checked,
                                current.followedAlways, current.nonAsiaCosplay,
                                current.cosMediaRequired, current.keepTextless,
                                current.followerThresholdEnabled, current.followerThreshold,
                                current.cosplayRatioPercent));
                    }
                }));
        container.addView(switchRow(context, "フォロー中アカウントは常時表示",
                "オフにするとフォロー中の投稿もカテゴリ判定の対象になります",
                options.followedAlways, new OnToggle() {
                    @Override
                    public void apply(RecommendKeepStore.Options current, boolean checked) {
                        RecommendKeepStore.shared().setOptions(new RecommendKeepStore.Options(
                                current.master, current.targetHome, current.targetExplore,
                                checked, current.nonAsiaCosplay,
                                current.cosMediaRequired, current.keepTextless,
                                current.followerThresholdEnabled, current.followerThreshold,
                                current.cosplayRatioPercent));
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
                    showPhraseEditor(context, container, KIND_CATEGORY, category.id,
                            category.name);
                }
            });
            container.addView(edit);
        }

        container.addView(SettingsUi.divider(context));
        TextView cosplayHeader = SettingsUi.summaryText(context);
        cosplayHeader.setText("コスプレの条件");
        container.addView(cosplayHeader);
        container.addView(switchRow(context, "非アジア圏コスプレも残す", null,
                options.nonAsiaCosplay, new OnToggle() {
                    @Override
                    public void apply(RecommendKeepStore.Options current, boolean checked) {
                        RecommendKeepStore.shared().setOptions(new RecommendKeepStore.Options(
                                current.master, current.targetHome, current.targetExplore,
                                current.followedAlways, checked,
                                current.cosMediaRequired, current.keepTextless,
                                current.followerThresholdEnabled, current.followerThreshold,
                                current.cosplayRatioPercent));
                    }
                }));
        container.addView(switchRow(context, "コスプレはメディア必須",
                "画像・動画のないコスプレ投稿を落とします",
                options.cosMediaRequired, new OnToggle() {
                    @Override
                    public void apply(RecommendKeepStore.Options current, boolean checked) {
                        RecommendKeepStore.shared().setOptions(new RecommendKeepStore.Options(
                                current.master, current.targetHome, current.targetExplore,
                                current.followedAlways, current.nonAsiaCosplay,
                                checked, current.keepTextless,
                                current.followerThresholdEnabled, current.followerThreshold,
                                current.cosplayRatioPercent));
                    }
                }));
        container.addView(switchRow(context, "フォロワー数しきい値（コスプレ用）",
                "指定数未満のアカウントのコスプレ投稿を落とします",
                options.followerThresholdEnabled, new OnToggle() {
                    @Override
                    public void apply(RecommendKeepStore.Options current, boolean checked) {
                        RecommendKeepStore.shared().setOptions(new RecommendKeepStore.Options(
                                current.master, current.targetHome, current.targetExplore,
                                current.followedAlways, current.nonAsiaCosplay,
                                current.cosMediaRequired, current.keepTextless,
                                checked, current.followerThreshold,
                                current.cosplayRatioPercent));
                        buildInto(context, container);
                    }
                }));
        if (options.followerThresholdEnabled) {
            TextView threshold = textButton(context,
                    "しきい値: " + options.followerThreshold + "人（タップで変更）");
            threshold.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    showThresholdEditor(context, container);
                }
            });
            container.addView(threshold);
        }

        TextView ratio = textButton(context,
                "コスプレの割合上限: " + options.cosplayRatioPercent + "%（タップで変更）");
        ratio.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showRatioEditor(context, container);
            }
        });
        container.addView(ratio);
        TextView ratioNote = SettingsUi.summaryText(context);
        ratioNote.setText("残す投稿のうちコスプレが占める割合の上限です。"
                + "コスプレ以外の投稿（フォロー中・技術系・動物など）が間隔を空けます。"
                + "0%ではコスプレはすべて落とします。変更すると割合の数え直しが始まります。");
        container.addView(ratioNote);

        container.addView(SettingsUi.divider(context));
        SettingsUi.SwitchRow exclusionRow = SettingsUi.switchRow(
                context, "男性レイヤー・男の娘を除外",
                "コスプレ判定の前に、表示名・プロフィール・本文の除外単語で落とします",
                snapshot.exclusionEnabled());
        exclusionRow.setOnCheckedChangeListener(new SettingsUi.CheckedChangeListener() {
            @Override
            public void onCheckedChanged(boolean checked) {
                RecommendKeepStore.shared().setExclusionEnabled(checked);
            }
        });
        container.addView(exclusionRow);
        TextView exclusionEdit = textButton(context,
                "除外単語を編集（" + snapshot.exclusionPhrases().size() + "件の単語）");
        exclusionEdit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showPhraseEditor(context, container, KIND_EXCLUSION, null,
                        "男性レイヤー・男の娘の除外単語");
            }
        });
        container.addView(exclusionEdit);

        container.addView(SettingsUi.divider(context));
        container.addView(switchRow(context, "テキスト無し投稿も残す",
                "本文のない（画像だけの）おすすめ投稿を落としません",
                options.keepTextless, new OnToggle() {
                    @Override
                    public void apply(RecommendKeepStore.Options current, boolean checked) {
                        RecommendKeepStore.shared().setOptions(new RecommendKeepStore.Options(
                                current.master, current.targetHome, current.targetExplore,
                                current.followedAlways, current.nonAsiaCosplay,
                                current.cosMediaRequired, checked,
                                current.followerThresholdEnabled, current.followerThreshold,
                                current.cosplayRatioPercent));
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

    private static String diagnosticText(RecommendKeepStore store) {
        RecommendKeepStore.Diagnostic diagnostic = store.diagnostic();
        switch (diagnostic.status) {
            case RecommendKeepStore.DIAG_TRUSTED:
                return "フォロー判定: 有効（フォロー中タブの投稿 "
                        + diagnostic.samples + " 件で確認済み）";
            case RecommendKeepStore.DIAG_DEAD:
                return "フォロー判定: 自動停止中（投稿の著者IDが取得できなかったため、"
                        + "安全のためフィルターは動作していません）";
            default:
                return "フォロー判定: 診断中（フォロー中タブの投稿 "
                        + diagnostic.samples + " / 40 件を確認済み。"
                        + "診断が終わるまで投稿は落とされません）";
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

    private static void showThresholdEditor(
            final Context context, final LinearLayout container) {
        LinearLayout form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(
                Theme.dpToPx(context, 24f),
                Theme.dpToPx(context, 8f),
                Theme.dpToPx(context, 24f),
                0);
        final EditText input = SettingsUi.textInput(
                context, "フォロワー数", InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(
                RecommendKeepStore.shared().snapshot().options().followerThreshold));
        form.addView(input, new LinearLayout.LayoutParams(-1, -2));

        final DialogView dialog = new DialogView(context)
                .setTitle("フォロワー数しきい値")
                .setScrollableBodyView(form);
        ButtonView cancel = new ButtonView(context, ButtonView.ButtonStyle.TEXT, "キャンセル");
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                dialog.dismiss();
            }
        });
        dialog.addButton(cancel);
        ButtonView save = new ButtonView(context, ButtonView.ButtonStyle.TEXT, "保存");
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    long value = Long.parseLong(input.getText().toString().trim());
                    if (value < 0) {
                        throw new NumberFormatException("negative");
                    }
                    RecommendKeepStore.Options current =
                            RecommendKeepStore.shared().snapshot().options();
                    RecommendKeepStore.shared().setOptions(new RecommendKeepStore.Options(
                            current.master, current.targetHome, current.targetExplore,
                            current.followedAlways, current.nonAsiaCosplay,
                            current.cosMediaRequired, current.keepTextless,
                            current.followerThresholdEnabled, value,
                            current.cosplayRatioPercent));
                    dialog.dismiss();
                    buildInto(context, container);
                } catch (NumberFormatException exception) {
                    Utils.showToastShort("数値を入力してください");
                }
            }
        });
        dialog.addButton(save);
        dialog.show();
    }

    private static void showRatioEditor(
            final Context context, final LinearLayout container) {
        LinearLayout form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(
                Theme.dpToPx(context, 24f),
                Theme.dpToPx(context, 8f),
                Theme.dpToPx(context, 24f),
                0);
        final EditText input = SettingsUi.textInput(
                context, "割合（0〜50）", InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(
                RecommendKeepStore.shared().snapshot().options().cosplayRatioPercent));
        form.addView(input, new LinearLayout.LayoutParams(-1, -2));

        final DialogView dialog = new DialogView(context)
                .setTitle("コスプレの割合上限")
                .setScrollableBodyView(form);
        ButtonView cancel = new ButtonView(context, ButtonView.ButtonStyle.TEXT, "キャンセル");
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                dialog.dismiss();
            }
        });
        dialog.addButton(cancel);
        ButtonView save = new ButtonView(context, ButtonView.ButtonStyle.TEXT, "保存");
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                try {
                    int value = Integer.parseInt(input.getText().toString().trim());
                    if (value < 0 || value > 50) {
                        throw new NumberFormatException("out of range");
                    }
                    RecommendKeepStore.Options current =
                            RecommendKeepStore.shared().snapshot().options();
                    RecommendKeepStore.shared().setOptions(new RecommendKeepStore.Options(
                            current.master, current.targetHome, current.targetExplore,
                            current.followedAlways, current.nonAsiaCosplay,
                            current.cosMediaRequired, current.keepTextless,
                            current.followerThresholdEnabled, current.followerThreshold,
                            value));
                    dialog.dismiss();
                    buildInto(context, container);
                } catch (NumberFormatException exception) {
                    Utils.showToastShort("0〜50の数値を入力してください");
                }
            }
        });
        dialog.addButton(save);
        dialog.show();
    }

    private static void showPhraseEditor(
            final Context context,
            final LinearLayout container,
            final int kind,
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
        renderPhraseEditor(context, dialog, form, kind, categoryId);

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
            RecommendKeepStore store, int kind, String categoryId) {
        if (kind == KIND_EXCLUSION) {
            return store.snapshot().exclusionPhrases();
        }
        RecommendKeepStore.Category category = store.snapshot().category(categoryId);
        return category == null ? java.util.Collections.emptyList() : category.phrases;
    }

    private static void renderPhraseEditor(
            final Context context,
            final DialogView dialog,
            final LinearLayout form,
            final int kind,
            final String categoryId) {
        form.removeAllViews();
        final RecommendKeepStore store = RecommendKeepStore.shared();

        final TextView phraseHeader = SettingsUi.summaryText(context);
        form.addView(phraseHeader);

        final LinearLayout phraseList = new LinearLayout(context);
        phraseList.setOrientation(LinearLayout.VERTICAL);
        form.addView(phraseList);
        renderPhrases(context, phraseList, phraseHeader, kind, categoryId);

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
                try {
                    if (kind == KIND_EXCLUSION) {
                        store.addExclusionPhrase(phraseInput.getText().toString());
                    } else {
                        store.addPhrase(categoryId, phraseInput.getText().toString());
                    }
                    phraseInput.setText("");
                    renderPhrases(context, phraseList, phraseHeader, kind, categoryId);
                } catch (RecommendKeepStore.ValidationException exception) {
                    Utils.showToastShort(exception.getMessage());
                }
            }
        });
        addRow.addView(addPhrase);
        form.addView(addRow);

        TextView restore = textButton(context, "プリセットの単語を追加");
        restore.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                int added = kind == KIND_EXCLUSION
                        ? store.addPresetExclusionPhrases()
                        : store.addPresetPhrases(categoryId);
                Utils.showToastShort(added > 0
                        ? added + "件の単語を追加しました"
                        : "追加できる単語はありません");
                renderPhrases(context, phraseList, phraseHeader, kind, categoryId);
            }
        });
        form.addView(restore);
    }

    private static void renderPhrases(
            final Context context,
            final LinearLayout phraseList,
            final TextView phraseHeader,
            final int kind,
            final String categoryId) {
        phraseList.removeAllViews();
        final RecommendKeepStore store = RecommendKeepStore.shared();
        java.util.List<String> phrases = phrasesOf(store, kind, categoryId);
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
                    if (kind == KIND_EXCLUSION) {
                        store.removeExclusionPhrase(phrase);
                    } else {
                        store.removePhrase(categoryId, phrase);
                    }
                    renderPhrases(context, phraseList, phraseHeader, kind, categoryId);
                }
            });
            row.addView(delete);
            phraseList.addView(row);
        }
    }
}
