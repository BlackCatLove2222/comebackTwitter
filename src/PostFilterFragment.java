package app.morphe.extension.newx.postfilter;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.StringRef;
import app.morphe.extension.newx.settings.NewXSettingsActivity;
import app.morphe.extension.crimera.settings.SettingsUi;
import app.morphe.extension.crimera.ui.ButtonView;
import app.morphe.extension.crimera.ui.DialogView;
import app.morphe.extension.newx.ui.Theme;
import app.morphe.extension.crimera.settings.CustomScreenFragment;

@SuppressWarnings("deprecation")
public final class PostFilterFragment extends CustomScreenFragment implements PostFilterRuleAdapter.Listener {
    private final PostFilterRuleStore store = PostFilterRuleStore.shared();
    private PostFilterRuleAdapter adapter;
    // JP jp24.13.21: plain row container replacing the ListView
    // (single-scroll page, see SCROLL_DIAGNOSIS).
    private LinearLayout rulesList;
    private TextView emptyState;
    private FrameLayout rulesContainer;
    private View addButton;
    private SettingsUi.SwitchRow masterSwitch;
    // JP jp24.13.17: per-section containers so onResume can rebuild the
    // three settings sections after a filter-bundle import changed the
    // stores underneath them.
    private LinearLayout zombieContainer;
    private LinearLayout muteContainer;
    private LinearLayout keepContainer;

    @Override
    public View onCreateView(
            LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState
    ) {
        Context context = requireContext();
        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(SettingsUi.backgroundColor(context));

        // JP jp24.13.3: the whole page scrolls. The sections above the rules
        // list are taller than one screen, so without a ScrollView the lower
        // sections (mute categories) were pushed off-screen, unreachable.
        ScrollView scrollView = new ScrollView(context);
        root.addView(scrollView, matchParent());

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));

        masterSwitch = SettingsUi.switchRow(
                context,
                StringRef.str("piko_newx_post_filtering_enabled_title"),
                null,
                store.isEnabled()
        );
        masterSwitch.setOnCheckedChangeListener(this::setFilteringEnabled);
        content.addView(masterSwitch, new LinearLayout.LayoutParams(-1, -2));
        content.addView(SettingsUi.divider(context));

        // JP jp24.13.17: each section gets its own container so onResume
        // can rebuild them after a filter-bundle import (see onResume).
        zombieContainer = new LinearLayout(context);
        zombieContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(zombieContainer, new LinearLayout.LayoutParams(-1, -2));
        ZombieSettingsSection.addSection(context, zombieContainer, this::refreshRules);
        muteContainer = new LinearLayout(context);
        muteContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(muteContainer, new LinearLayout.LayoutParams(-1, -2));
        // JP jp24.13: category-based mute section.
        MuteCategorySettingsSection.addSection(context, muteContainer);
        keepContainer = new LinearLayout(context);
        keepContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(keepContainer, new LinearLayout.LayoutParams(-1, -2));
        // JP jp24.13.9: recommendation keep-filter section.
        RecommendKeepSettingsSection.addSection(context, keepContainer);
        // JP jp24.13.17: filter-bundle import/export, at the end of the
        // settings sections (above the keyword rules list).
        FilterBundleImportExport.addButtons(context, content);

        rulesContainer = new FrameLayout(context);
        // JP jp24.13.21: single-scroll page (SCROLL_DIAGNOSIS). The rules
        // are plain rows in a vertical container at the tail of the one
        // ScrollView; the container height wraps its content and the
        // adapter is retained only as the row factory/binder.
        content.addView(rulesContainer, new LinearLayout.LayoutParams(-1, -2));

        rulesList = new LinearLayout(context);
        rulesList.setOrientation(LinearLayout.VERTICAL);
        rulesList.setPadding(
                0,
                Theme.dpToPx(context, 4f),
                0,
                Theme.dpToPx(context, 96f)
        );
        adapter = new PostFilterRuleAdapter(context, this);
        rulesContainer.addView(rulesList, matchParent());

        emptyState = new TextView(context);
        emptyState.setText(StringRef.str("piko_newx_post_filtering_empty"));
        emptyState.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        emptyState.setTextColor(Theme.secondaryText(context));
        emptyState.setGravity(Gravity.CENTER);
        int emptyPadding = Theme.dpToPx(context, 32f);
        emptyState.setPadding(emptyPadding, emptyPadding, emptyPadding, emptyPadding);
        rulesContainer.addView(emptyState, matchParent());

        addButton = SettingsUi.floatingActionButton(
                context,
                StringRef.str("piko_newx_post_filtering_add"),
                ignored -> showRuleDialog(null)
        );
        FrameLayout.LayoutParams addParams = new FrameLayout.LayoutParams(
                Theme.dpToPx(context, 56f),
                Theme.dpToPx(context, 56f)
        );
        addParams.gravity = Gravity.BOTTOM | Gravity.END;
        addParams.setMargins(
                0,
                0,
                Theme.dpToPx(context, 20f),
                Theme.dpToPx(context, 20f)
        );
        root.addView(addButton, addParams);

        refreshRules();
        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        Activity activity = getActivity();
        if (activity instanceof NewXSettingsActivity settingsActivity) {
            settingsActivity.setPageTitle(StringRef.str("piko_newx_post_filtering_title"));
        }
        // JP jp24.13.17: a filter-bundle import returns here through the
        // document picker; the sections were built from the pre-import
        // stores, so rebuild them from the current snapshots.
        rebuildSections();
        refreshRules();
    }

    // JP jp24.13.17: rebuilds the three store-driven sections in place.
    private void rebuildSections() {
        if (zombieContainer == null) {
            return;
        }
        Context context = requireContext();
        zombieContainer.removeAllViews();
        ZombieSettingsSection.addSection(context, zombieContainer, this::refreshRules);
        muteContainer.removeAllViews();
        MuteCategorySettingsSection.addSection(context, muteContainer);
        keepContainer.removeAllViews();
        RecommendKeepSettingsSection.addSection(context, keepContainer);
    }

    @Override
    public void edit(PostFilterRule rule) {
        showRuleDialog(rule);
    }

    @Override
    public void setEnabled(PostFilterRule rule, boolean enabled) {
        store.setRuleEnabled(rule.getId(), enabled);
        refreshRules();
    }

    private void showRuleDialog(@Nullable PostFilterRule editingRule) {
        Context context = requireContext();
        LinearLayout form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(
                Theme.dpToPx(context, 24f),
                Theme.dpToPx(context, 8f),
                Theme.dpToPx(context, 24f),
                0
        );

        EditText phrase = SettingsUi.textInput(
                context,
                StringRef.str("piko_newx_post_filtering_phrase_hint"),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        );
        if (editingRule != null) phrase.setText(editingRule.getPhrase());
        form.addView(phrase, new LinearLayout.LayoutParams(-1, -2));

        SettingsUi.SwitchRow matchContent = scopeSwitch(
                context,
                "piko_newx_post_filtering_match_content",
                editingRule == null || editingRule.matchesContent()
        );
        form.addView(matchContent, new LinearLayout.LayoutParams(-1, -2));

        SettingsUi.SwitchRow matchUsernames = scopeSwitch(
                context,
                "piko_newx_post_filtering_match_usernames",
                editingRule != null && editingRule.matchesUsernames()
        );
        form.addView(matchUsernames, new LinearLayout.LayoutParams(-1, -2));

        TextView validation = SettingsUi.summaryText(context);
        validation.setTextColor(Color.rgb(244, 33, 46));
        validation.setPadding(0, Theme.dpToPx(context, 8f), 0, 0);
        validation.setVisibility(View.GONE);
        form.addView(validation, new LinearLayout.LayoutParams(-1, -2));

        DialogView dialog = new DialogView(context)
                .setTitle(StringRef.str(editingRule == null
                        ? "piko_newx_post_filtering_add_title"
                        : "piko_newx_post_filtering_edit_title"))
                .setScrollableBodyView(form);
        dialog.getDialog().setCanceledOnTouchOutside(true);

        ButtonView cancel = new ButtonView(
                context,
                ButtonView.ButtonStyle.TEXT,
                StringRef.str("piko_newx_post_filtering_cancel")
        );
        cancel.setOnClickListener(ignored -> dialog.dismiss());

        ButtonView save = new ButtonView(
                context,
                ButtonView.ButtonStyle.TEXT,
                StringRef.str("piko_newx_post_filtering_save")
        );
        save.setOnClickListener(ignored -> {
            try {
                if (editingRule == null) {
                    store.add(
                            phrase.getText().toString(),
                            matchContent.isChecked(),
                            matchUsernames.isChecked()
                    );
                } else {
                    store.update(
                            editingRule.getId(),
                            phrase.getText().toString(),
                            matchContent.isChecked(),
                            matchUsernames.isChecked()
                    );
                }
                refreshRules();
                dialog.dismiss();
            } catch (PostFilterRuleStore.ValidationException exception) {
                validation.setText(validationMessage(exception.getError()));
                validation.setVisibility(View.VISIBLE);
            }
        });

        dialog.addButton(cancel);
        if (editingRule != null) {
            ButtonView remove = new ButtonView(
                    context,
                    ButtonView.ButtonStyle.TEXT,
                    StringRef.str("piko_newx_post_filtering_remove")
            );
            remove.setTextColor(Color.rgb(244, 33, 46));
            remove.setOnClickListener(ignored -> {
                store.remove(editingRule.getId());
                refreshRules();
                dialog.dismiss();
            });
            dialog.addButton(remove);
        }
        dialog.addButton(save).show();
    }

    private CharSequence validationMessage(PostFilterRuleStore.ValidationError error) {
        return switch (error) {
            case BLANK_PHRASE -> StringRef.str("piko_newx_post_filtering_error_blank");
            case NO_SCOPE -> StringRef.str("piko_newx_post_filtering_error_scope");
            case DUPLICATE_PHRASE -> StringRef.str("piko_newx_post_filtering_error_duplicate");
        };
    }

    private SettingsUi.SwitchRow scopeSwitch(
            Context context,
            String textResource,
            boolean checked
    ) {
        return SettingsUi.switchRow(context, StringRef.str(textResource), null, checked);
    }

    private void refreshRules() {
        if (adapter == null || emptyState == null) return;
        java.util.List<PostFilterRule> rules = store.snapshot().getRules();
        renderRules(rules);
        emptyState.setVisibility(rules.isEmpty() ? View.VISIBLE : View.GONE);
        if (masterSwitch != null && masterSwitch.isChecked() != store.isEnabled()) {
            masterSwitch.setOnCheckedChangeListener(null);
            masterSwitch.setChecked(store.isEnabled(), false);
            masterSwitch.setOnCheckedChangeListener(this::setFilteringEnabled);
        }
        applyFilteringEnabled(store.isEnabled());
    }

    private void setFilteringEnabled(boolean enabled) {
        store.setEnabled(enabled);
        applyFilteringEnabled(enabled);
    }

    private void applyFilteringEnabled(boolean enabled) {
        if (rulesContainer == null || addButton == null || adapter == null) return;
        adapter.setInteractionsEnabled(enabled);
        rulesContainer.setEnabled(enabled);
        rulesContainer.setAlpha(enabled ? 1f : 0.45f);
        addButton.setEnabled(enabled);
        addButton.setAlpha(enabled ? 1f : 0.45f);
        renderRules(store.snapshot().getRules());
    }

    // JP jp24.13.21: render the keyword rules as plain rows (no ListView,
    // so no nested scroller); row click/switch wiring comes from the
    // adapter's getView, exactly as the ListView used it.
    // JP jp24.13.22.2: the adapter is the single source of truth for both
    // count and items. The 13.21 version looped over the passed list but
    // bound rows through the adapter, whose own list was never submitted
    // (it stayed the initial empty list), so getItem(0) threw
    // IndexOutOfBoundsException whenever at least one rule existed and
    // the screen crashed on open. Submitting first and looping over
    // adapter.getCount() makes the two consistent by construction and
    // tolerates the empty case.
    private void renderRules(java.util.List<PostFilterRule> rules) {
        if (rulesList == null || adapter == null) return;
        adapter.submit(rules);
        rulesList.removeAllViews();
        for (int i = 0; i < adapter.getCount(); i++) {
            rulesList.addView(adapter.getView(i, null, rulesList));
        }
    }

    private Context requireContext() {
        Context context = getActivity();
        if (context == null) throw new IllegalStateException("Post-filter activity is missing");
        return context;
    }

    private static FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(-1, -1);
    }

}
