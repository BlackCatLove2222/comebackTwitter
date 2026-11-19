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
 */
public final class ConflictResolver {

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
            if (!DownloadSettings.askOnConflict()) return configured;
            if (!exists(context, tree, fileName, video)) return configured;
            Activity activity = NewXUtils.findUsableActivity(null);
            if (activity == null) return configured;
            return prompt(activity, fileName);
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

    /** MediaStore relative path of the built-in folder, mirroring JpMediaStoreDestination. */
    static String mediaRelativePath(boolean video) {
        return video ? "Movies/Twitter/" : "Pictures/Twitter/";
    }

    static boolean exists(Context context, Uri tree, String fileName, boolean video) {
        try {
            if (context == null || tree == null || fileName == null) return false;
            if (JpMediaStoreDestination.isMediaStoreCollection(tree)) {
                return mediaStoreExists(context, tree, fileName, video);
            }
            if (DocumentsContract.isTreeUri(tree)) {
                return safExists(context, tree, fileName);
            }
            return false;
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

    private static ConflictPolicy prompt(Activity activity, String fileName) {
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
        return result != null ? result : ConflictPolicy.SKIP;
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
