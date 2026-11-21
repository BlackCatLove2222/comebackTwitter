package app.morphe.extension.newx.misc;

import android.app.Activity;
import android.app.Dialog;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import app.morphe.extension.crimera.downloader.model.ConflictPolicy;
import app.morphe.extension.crimera.settings.SettingsUi;
import app.morphe.extension.crimera.ui.ButtonView;
import app.morphe.extension.crimera.ui.DialogView;
import app.morphe.extension.newx.settings.NewXLogger;
import app.morphe.extension.newx.utils.NewXUtils;

/**
 * jp24.13.22 (C): asks before downloading when a file with the same name already exists in the
 * destination. Runs synchronously on the funnel's enqueue thread: the configured policy is
 * returned unchanged unless the ask-on-conflict setting is on, the file exists, and a usable
 * activity can host the dialog. Any failure also falls back to the configured policy, except a
 * dismissed or timed-out prompt, which means cancel (SKIP).
 *
 * <p>jp24.13.24.7.1: the usable-activity lookup is no longer a single probe.
 * The tracked activity (NewXUtils' lifecycle WeakReferences) is cleared when
 * the host activity pauses or stops and is only repopulated when it starts
 * or resumes again, so a probe that landed in that gap silently dropped
 * exactly one prompt (device report: the second image save in a row showed
 * no dialog and the file was silently skipped, because the configured
 * policy defaults to SKIP). The gate now probes for up to
 * {@link #ACTIVITY_PROBE_LIMIT} attempts spaced 50 ms apart, and a prompt
 * whose host activity finished or was destroyed underneath it (a dismissal
 * that was not the user's choice) is re-probed and re-shown once on the
 * replacement activity. The final fallback is unchanged (the configured
 * policy), but it is now logged with the tracked-activity state.
 *
 * <p>jp24.13.24.7.2: the existence gate for the built-in MediaStore
 * folders no longer trusts the MediaStore query alone. On the user's
 * device that query returns no rows for Pictures/Twitter — the folder
 * carries a deliberate .nomedia, and the jp24.13.23 staged queries
 * already proved the rows are not queryable there (an unfiltered
 * whole-collection query with client-side matching also found none),
 * while Movies/Twitter rows are queryable and video prompts worked.
 * With the query as the only signal, {@link #exists} was permanently
 * false for images: no prompt ever, and the destination silently
 * saved repeat images under provider-suffixed names. The gate now
 * unions the query with the extension's own download index
 * ({@link SavedMediaStore}), which records every item the MediaStore
 * destination published and does not depend on MediaStore row
 * visibility. No media scan is triggered and no MediaStore rows are
 * created by this check.
 */
public final class ConflictResolver {

    /** Activity probes per prompt round: the first is immediate, the rest are spaced. */
    static final int ACTIVITY_PROBE_LIMIT = 41;

    /** Delay between activity probes while the tracked activity is between lifecycle states. */
    private static final long ACTIVITY_PROBE_INTERVAL_MS = 50;

    /** Prompt rounds: the first prompt plus one re-prompt after the host died under the first. */
    private static final int PROMPT_ROUNDS = 2;

    private ConflictResolver() {
    }

    public static ConflictPolicy resolve(
            Context context,
            Uri tree,
            String fileName,
            boolean video,
            ConflictPolicy configured
    ) {
        try {
            if (DownloadSettings.skipConflictConfirmation()) {
                // jp24.13.24.7: never prompt. When the file exists, do
                // exactly what the prompt's "save with a new name"
                // choice does (RENAME: a numbered suffix is added by
                // the destination); when it does not, the configured
                // policy applies as usual.
                return bypassPolicy(true, exists(context, tree, fileName, video), configured);
            }
            if (!DownloadSettings.askOnConflict()) return configured;
            if (!exists(context, tree, fileName, video)) return configured;
            return promptForSave(fileName, configured);
        } catch (Throwable throwable) {
            NewXLogger.printException(
                    () -> "Failed to ask about the duplicate NewX download", throwable);
            return configured;
        }
    }

    /** Pure prompt gate, exposed for the JVM harness. */
    static boolean shouldPrompt(boolean askEnabled, boolean exists) {
        return askEnabled && exists;
    }

    /**
     * Pure jp24.13.24.7.1 prompt-gate model, exposed for the JVM harness:
     * with the bypass off, a save reaches the dialog iff the ask setting is
     * on, the file exists, and at least one of the successive activity
     * probes (bounded by {@link #ACTIVITY_PROBE_LIMIT}) finds a usable
     * activity. {@link #awaitUsableActivity()} implements exactly this
     * policy with real probes; the pre-24.7.1 gate probed exactly once,
     * which is the case {@code probeOutcomes = {false}} for the unlucky
     * save in a sequence.
     */
    static boolean reachesDialog(boolean askEnabled, boolean exists, boolean[] probeOutcomes) {
        if (!shouldPrompt(askEnabled, exists)) return false;
        int probes = Math.min(probeOutcomes.length, ACTIVITY_PROBE_LIMIT);
        for (int index = 0; index < probes; index++) {
            if (probeOutcomes[index]) return true;
        }
        return false;
    }

    /**
     * Pure jp24.13.24.7 bypass decision, exposed for the JVM harness:
     * with the bypass on and an existing file, the download proceeds
     * without UI as RENAME (the prompt's "save with a new name"
     * outcome); in every other case the configured policy stands.
     */
    static ConflictPolicy bypassPolicy(
            boolean bypassEnabled, boolean exists, ConflictPolicy configured) {
        return bypassEnabled && exists ? ConflictPolicy.RENAME : configured;
    }

    /** MediaStore relative path of the built-in folder, mirroring JpMediaStoreDestination. */
    static String mediaRelativePath(boolean video) {
        return video ? "Movies/Twitter/" : "Pictures/Twitter/";
    }

    static boolean exists(Context context, Uri tree, String fileName, boolean video) {
        try {
            if (context == null || tree == null || fileName == null) return false;
            if (JpMediaStoreDestination.isMediaStoreCollection(tree)) {
                return existsFromSignals(
                        mediaStoreExists(context, tree, fileName, video),
                        indexExists(context, fileName, video));
            }
            if (DocumentsContract.isTreeUri(tree)) {
                return safExists(context, tree, fileName);
            }
            return false;
        } catch (Throwable throwable) {
            return false;
        }
    }

    /** Pure union of the two existence signals, exposed for the JVM harness. */
    static boolean existsFromSignals(boolean queryHit, boolean indexHit) {
        return queryHit || indexHit;
    }

    /**
     * jp24.13.24.7.2: existence signal from the extension's own download
     * index — the names this app published into the built-in folder,
     * recorded at publish time. Independent of MediaStore row visibility
     * (see the class javadoc). Fail-soft: any index problem reads as
     * "not indexed", never as an error.
     */
    private static boolean indexExists(Context context, String fileName, boolean video) {
        try {
            return SavedMediaStore.hasNamed(SavedMediaStore.entries(context), fileName, video);
        } catch (Throwable throwable) {
            return false;
        }
    }

    private static boolean mediaStoreExists(
            Context context,
            Uri tree,
            String fileName,
            boolean video
    ) {
        ContentResolver resolver = context.getContentResolver();
        if (resolver == null) return false;
        try (Cursor cursor = resolver.query(
                tree,
                new String[]{MediaStore.MediaColumns._ID},
                MediaStore.MediaColumns.RELATIVE_PATH + "=? AND "
                        + MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                new String[]{mediaRelativePath(video), fileName},
                null
        )) {
            return cursor != null && cursor.moveToFirst();
        } catch (Throwable throwable) {
            return false;
        }
    }

    private static boolean safExists(Context context, Uri tree, String fileName) {
        ContentResolver resolver = context.getContentResolver();
        if (resolver == null) return false;
        final Uri children;
        try {
            children = DocumentsContract.buildChildDocumentsUriUsingTree(
                    tree, DocumentsContract.getTreeDocumentId(tree));
        } catch (Throwable throwable) {
            return false;
        }
        try (Cursor cursor = resolver.query(
                children,
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                null,
                null,
                null
        )) {
            if (cursor == null) return false;
            int nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            if (nameColumn < 0) return false;
            while (cursor.moveToNext()) {
                if (fileName.equals(cursor.getString(nameColumn))) return true;
            }
            return false;
        } catch (Throwable throwable) {
            return false;
        }
    }

    /**
     * Prompts for one conflicting save. Each round waits for a usable
     * activity (see {@link #awaitUsableActivity()}) and shows the prompt
     * once; when the host activity died under the prompt, the next round
     * re-probes and re-prompts on the replacement activity. Falls back to
     * the configured policy (logged) when no round produces a decision.
     */
    private static ConflictPolicy promptForSave(String fileName, ConflictPolicy configured) {
        for (int round = 0; round < PROMPT_ROUNDS; round++) {
            Activity activity = awaitUsableActivity();
            if (activity == null) break;
            ConflictPolicy decided = promptOnce(activity, fileName);
            if (decided != null) return decided;
            // The host finished or was destroyed under the dialog: that
            // dismissal was not the user's choice, so ask again on the
            // activity that replaced it instead of silently skipping.
        }
        NewXLogger.printInfo(() -> "NewX duplicate-download prompt skipped (no usable activity): "
                + NewXUtils.describeTrackedActivities());
        return configured;
    }

    /**
     * Probes for a usable activity, retrying while the tracked activity is
     * between lifecycle states (the tracker is cleared on pause/stop and
     * repopulated on start/resume). The first probe is immediate, so the
     * common case pays nothing; the bounded wait (41 probes x 50 ms)
     * rides out the transition gap that used to eat exactly one prompt.
     */
    private static Activity awaitUsableActivity() {
        for (int probe = 0; probe < ACTIVITY_PROBE_LIMIT; probe++) {
            Activity activity = NewXUtils.findUsableActivity(null);
            if (activity != null) return activity;
            if (probe + 1 < ACTIVITY_PROBE_LIMIT) {
                try {
                    Thread.sleep(ACTIVITY_PROBE_INTERVAL_MS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * Shows the prompt on {@code activity} and blocks until it resolves.
     * Returns the chosen policy — a dismissal or timeout on a live host is
     * a cancel (SKIP), as before — or null when the host activity finished
     * or was destroyed while the dialog was up, which is not a user choice
     * and tells the caller to re-prompt on a fresh activity.
     */
    private static ConflictPolicy promptOnce(Activity activity, String fileName) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ConflictPolicy> chosen = new AtomicReference<>();
        NewXUtils.runOnUiThread(() -> {
            try {
                showPrompt(activity, fileName, chosen, latch);
            } catch (Throwable throwable) {
                latch.countDown();
            }
        });
        try {
            latch.await(300, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        ConflictPolicy result = chosen.get();
        if (result != null) return result;
        return activity.isFinishing() || activity.isDestroyed() ? null : ConflictPolicy.SKIP;
    }

    private static void showPrompt(
            Activity activity,
            String fileName,
            AtomicReference<ConflictPolicy> chosen,
            CountDownLatch latch
    ) {
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(24f * activity.getResources().getDisplayMetrics().density);
        body.setPadding(padding, 0, padding, 0);

        TextView nameView = SettingsUi.summaryText(activity);
        nameView.setText(fileName);
        body.addView(nameView, new LinearLayout.LayoutParams(-1, -2));

        TextView noteView = SettingsUi.summaryText(activity);
        noteView.setText("同じ名前のファイルが既に保存されています。どうしますか？");
        body.addView(noteView, new LinearLayout.LayoutParams(-1, -2));

        DialogView dialog = new DialogView(activity)
                .setTitle("同じファイル名が既にあります")
                .setBodyView(body);
        Dialog androidDialog = dialog.getDialog();
        androidDialog.setOnDismissListener(ignored -> latch.countDown());
        androidDialog.setCanceledOnTouchOutside(true);

        dialog.addButton(choiceButton(
                activity, dialog, "上書き", ConflictPolicy.OVERWRITE, chosen, latch));
        dialog.addButton(choiceButton(
                activity, dialog, "別名で保存", ConflictPolicy.RENAME, chosen, latch));
        dialog.addButton(choiceButton(
                activity, dialog, "キャンセル", ConflictPolicy.SKIP, chosen, latch));
        try {
            dialog.show();
        } catch (Throwable throwable) {
            latch.countDown();
        }
    }

    private static ButtonView choiceButton(
            Activity activity,
            DialogView dialog,
            String label,
            ConflictPolicy policy,
            AtomicReference<ConflictPolicy> chosen,
            CountDownLatch latch
    ) {
        ButtonView button = SettingsUi.dialogButton(activity, label);
        button.setOnClickListener(view -> {
            chosen.set(policy);
            dialog.dismiss();
            latch.countDown();
        });
        return button;
    }
}
