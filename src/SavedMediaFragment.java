package app.morphe.extension.newx.misc;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.annotation.Nullable;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.morphe.extension.crimera.settings.CustomScreenFragment;
import app.morphe.extension.crimera.settings.SettingsUi;
import app.morphe.extension.crimera.ui.ButtonView;
import app.morphe.extension.crimera.ui.DialogView;
import app.morphe.extension.newx.misc.NewXDownloadFolders.DestinationState;
import app.morphe.extension.newx.misc.NewXDownloadFolders.MediaKind;
import app.morphe.extension.newx.settings.NewXSettingsActivity;

/**
 * jp24.13.22 (B): authenticated viewer for downloaded media
 * (Pictures/Twitter images, Movies/Twitter videos).
 *
 * <p>The screen is gated by the framework {@link BiometricPrompt}: nothing
 * is enumerated and no thumbnail is decoded before authentication succeeds.
 * When the device has neither a biometric nor a screen lock the screen
 * fails closed with an explanation instead of showing content.
 *
 * <p>jp24.13.24: enumeration is SAF-first. Files saved in the SAF era
 * (jp–jp24.2, or into a folder carrying .nomedia) have no MediaStore rows
 * at all — the media scanner never indexed them — so no MediaStore query
 * can ever list them. The viewer therefore enumerates the granted folder
 * trees directly through {@link DocumentsContract} child cursors (which
 * .nomedia does not affect, and which create no MediaStore rows, so the
 * files stay invisible to gallery apps), then merges the jp24.13.23
 * staged MediaStore enumeration and the {@link SavedMediaStore} index
 * as supplements. Merge order is SAF, MediaStore, index; duplicates are
 * dropped first-wins on Uri and on the (display name, size) key — the
 * only identity a file keeps across the SAF and MediaStore schemes.
 * Grant sources per media kind: the configured download folder (read
 * reuse of its grant) and the viewer's own grant
 * ({@link SavedMediaFolders}), obtained from this screen after
 * authentication through the shared folder picker.
 *
 * <p>jp24.13.23: each MediaStore collection is queried in stages — a
 * RELATIVE_PATH selection first, then an unfiltered query with
 * client-side path matching, then the Files collection (see
 * {@link #queryCollection}).
 *
 * <p>jp24.13.22.2: the prompt is built ONLY through the framework
 * {@link BiometricPrompt.Builder} API. The jp24.13.22 build called the
 * {@code BiometricPrompt(Context, Executor, Callback)} constructor and
 * {@code authenticate(PromptInfo)} with a nested {@code PromptInfo} class;
 * that API shape exists only in androidx.biometric, not in the Android
 * framework (verified against AOSP and the framework jar: the framework
 * class has no such constructor and no nested PromptInfo). It compiled
 * only because a hand-written stub shadowed the framework class, and on
 * device the constructor threw NoSuchMethodError, which the catch-all in
 * startAuth() swallowed into the "auth required" state — the prompt could
 * never appear. Errors are now logged and surfaced in the status text.
 */
@SuppressWarnings("deprecation")
public final class SavedMediaFragment extends CustomScreenFragment {

    private static final String TAG = "SavedMediaFragment";

    private TextView statusText;
    private LinearLayout grantPanel;
    private LinearLayout tabBar;
    private GridLayout grid;
    private ButtonView retryButton;
    // jp24.13.24.7: tab state + the last load, kept so tab switches
    // and post-delete re-renders never rescan.
    private int currentTab = SavedMediaSaf.TAB_IMAGE;
    private LoadResult lastResult;
    private volatile boolean destroyed;
    private boolean authInProgress;
    private boolean authenticated;
    private ExecutorService thumbExecutor;
    private final Map<String, Bitmap> thumbCache = new java.util.HashMap<>();

    private static final class MediaItem {
        final Uri uri;
        final String name;
        final String mime;
        final long added;
        final long size;
        final boolean video;
        final boolean saf;
        // jp24.13.24.7: the granted tree a SAF item was enumerated
        // from (null for MediaStore/index items). The delete scope
        // guard requires it, so a SAF delete can only ever target a
        // document inside an enumerated tree.
        final Uri tree;

        MediaItem(Uri uri, String name, String mime, long added, long size,
                  boolean video, boolean saf, Uri tree) {
            this.uri = uri;
            this.name = name;
            this.mime = mime;
            this.added = added;
            this.size = size;
            this.video = video;
            this.saf = saf;
            this.tree = tree;
        }
    }

    /** Per-kind SAF folder outcome, computed during enumeration. */
    private static final class FolderStatus {
        boolean anyTree;   // at least one tree enumerated (fully or partially)
        boolean revoked;   // a stored tree is unusable, or enumeration was denied
        int safCount;      // items this kind contributed through SAF
    }

    private static final class LoadResult {
        final List<MediaItem> items;
        final FolderStatus images;
        final FolderStatus videos;
        // jp24.13.24.7: string forms of the trees this load resolved
        // as usable and enumerated; the delete scope guard checks a
        // SAF item's tree against this set.
        final java.util.Set<String> safTrees;

        LoadResult(List<MediaItem> items, FolderStatus images, FolderStatus videos,
                   java.util.Set<String> safTrees) {
            this.items = items;
            this.images = images;
            this.videos = videos;
            this.safTrees = safTrees;
        }
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState
    ) {
        Context context = requireContext();
        destroyed = false;

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(SettingsUi.backgroundColor(context));

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dpToPx(context, 16f);
        content.setPadding(padding, padding, padding, dpToPx(context, 32f));
        scroll.addView(content, new ViewGroup.LayoutParams(-1, -2));

        statusText = SettingsUi.summaryText(context);
        content.addView(statusText, new LinearLayout.LayoutParams(-1, -2));

        grantPanel = new LinearLayout(context);
        grantPanel.setOrientation(LinearLayout.VERTICAL);
        content.addView(grantPanel, new LinearLayout.LayoutParams(-1, -2));

        tabBar = new LinearLayout(context);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams tabBarParams = new LinearLayout.LayoutParams(-1, -2);
        tabBarParams.topMargin = dpToPx(context, 8f);
        content.addView(tabBar, tabBarParams);

        grid = new GridLayout(context);
        grid.setColumnCount(3);
        LinearLayout.LayoutParams gridParams = new LinearLayout.LayoutParams(-1, -2);
        gridParams.topMargin = dpToPx(context, 8f);
        content.addView(grid, gridParams);

        retryButton = new ButtonView(
                context, ButtonView.ButtonStyle.FILLED, "再試行");
        retryButton.setVisibility(View.GONE);
        retryButton.setOnClickListener(view -> startAuth());
        LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(-1, -2);
        retryParams.topMargin = dpToPx(context, 16f);
        content.addView(retryButton, retryParams);

        startAuth();
        return scroll;
    }

    @Override
    public void onResume() {
        super.onResume();
        Activity activity = getActivity();
        if (activity instanceof NewXSettingsActivity settingsActivity) {
            settingsActivity.setPageTitle("保存したメディア");
        }
        // jp24.13.24.1: pick up grants completed while the folder
        // picker (or any other screen) was in front. Before this,
        // onResume only set the title, so a grant persisted by the
        // picker activity never refreshed an already-open viewer.
        if (authenticated && !authInProgress && !destroyed) loadMedia();
    }

    @Override
    public void onDestroyView() {
        destroyed = true;
        if (thumbExecutor != null) {
            thumbExecutor.shutdownNow();
            thumbExecutor = null;
        }
        super.onDestroyView();
    }

    // ---- Authentication gate ----

    private void startAuth() {
        Activity activity = getActivity();
        if (activity == null || destroyed || authInProgress) return;

        boolean credential = false;
        try {
            KeyguardManager keyguard =
                    (KeyguardManager) activity.getSystemService(Context.KEYGUARD_SERVICE);
            credential = keyguard != null && keyguard.isDeviceSecure();
        } catch (Throwable ignored) {
        }

        BiometricManager biometrics = null;
        try {
            biometrics = activity.getSystemService(BiometricManager.class);
        } catch (Throwable ignored) {
        }

        boolean available;
        if (Build.VERSION.SDK_INT >= 30) {
            boolean combined = false;
            if (biometrics != null) {
                try {
                    combined = biometrics.canAuthenticate(
                            BiometricManager.Authenticators.BIOMETRIC_STRONG
                                    | BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                            == BiometricManager.BIOMETRIC_SUCCESS;
                } catch (Throwable ignored) {
                }
            }
            available = combined || credential;
        } else {
            boolean biometric = false;
            if (biometrics != null) {
                try {
                    biometric = biometrics.canAuthenticate()
                            == BiometricManager.BIOMETRIC_SUCCESS;
                } catch (Throwable ignored) {
                }
            }
            available = biometric || credential;
        }

        if (!available) {
            // Fail closed: no prompt, no content, nothing to retry.
            setStatus("保存したメディアの表示には、生体認証または画面ロックが必要です。"
                    + "端末の画面ロックを設定してください。");
            if (retryButton != null) retryButton.setVisibility(View.GONE);
            return;
        }

        if (retryButton != null) retryButton.setVisibility(View.GONE);
        authInProgress = true;
        BiometricPrompt.AuthenticationCallback callback =
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(
                            BiometricPrompt.AuthenticationResult result) {
                        authInProgress = false;
                        authenticated = true;
                        loadMedia();
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, CharSequence errString) {
                        authInProgress = false;
                        Log.e(TAG, "Authentication error " + errorCode + ": " + errString);
                        onAuthError("認証エラー（コード " + errorCode + "）"
                                + (errString != null ? ": " + errString : ""));
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        onAuthFailed();
                    }
                };
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                showPromptStrong(activity, callback);
            } else {
                showPromptLegacy(activity, callback);
            }
        } catch (Throwable t) {
            authInProgress = false;
            Log.e(TAG, "Failed to start authentication", t);
            onAuthError("認証を開始できませんでした: " + t.getClass().getSimpleName()
                    + (t.getMessage() != null ? ": " + t.getMessage() : ""));
        }
    }

    /** API 30+: framework Builder prompt with an explicit authenticator set. */
    private static void showPromptStrong(
            Activity activity, BiometricPrompt.AuthenticationCallback callback) {
        BiometricPrompt prompt = new BiometricPrompt.Builder(activity)
                .setTitle("保存したメディア")
                .setAllowedAuthenticators(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG
                                | BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build();
        prompt.authenticate(new CancellationSignal(), activity.getMainExecutor(), callback);
    }

    /** API 29: builder-based prompt with the device credential allowed. */
    private static void showPromptLegacy(
            Activity activity, BiometricPrompt.AuthenticationCallback callback) {
        BiometricPrompt prompt = new BiometricPrompt.Builder(activity)
                .setTitle("保存したメディア")
                .setDeviceCredentialAllowed(true)
                .build();
        prompt.authenticate(new CancellationSignal(), activity.getMainExecutor(), callback);
    }

    private void onAuthFailed() {
        setStatus("認証が必要です");
        if (retryButton != null) retryButton.setVisibility(View.VISIBLE);
    }

    /** Terminal auth failure (error callback or a launch exception): log + distinct text. */
    private void onAuthError(String message) {
        setStatus(message);
        if (retryButton != null) retryButton.setVisibility(View.VISIBLE);
    }

    // ---- Enumeration (only ever runs after a successful authentication) ----

    private void loadMedia() {
        Activity activity = getActivity();
        if (activity == null || destroyed) return;
        Context appContext = activity.getApplicationContext();
        final Context context = appContext != null ? appContext : activity;
        setStatus("");
        if (grantPanel != null) grantPanel.removeAllViews();
        new Thread(() -> {
            LoadResult result;
            try {
                result = collectMedia(context);
            } catch (Throwable ignored) {
                Activity current = getActivity();
                if (current == null || destroyed) return;
                current.runOnUiThread(() -> {
                    if (!destroyed) setStatus("読み込みに失敗しました");
                });
                return;
            }
            Activity current = getActivity();
            if (current == null || destroyed) return;
            LoadResult loaded = result;
            current.runOnUiThread(() -> {
                if (!destroyed) renderMedia(loaded);
            });
        }).start();
    }

    private static LoadResult collectMedia(Context context) {
        ContentResolver resolver = context.getContentResolver();

        // 1. SAF enumeration (primary): granted folder trees, read directly.
        List<MediaItem> safItems = new ArrayList<>();
        java.util.Set<String> grantedTrees = new java.util.HashSet<>();
        FolderStatus imageStatus =
                collectSaf(context, resolver, MediaKind.IMAGES, false, safItems, grantedTrees);
        FolderStatus videoStatus =
                collectSaf(context, resolver, MediaKind.VIDEOS, true, safItems, grantedTrees);

        // 2. MediaStore staged enumeration (supplement).
        List<MediaItem> storeItems = new ArrayList<>();
        HashSet<String> storeSeen = new HashSet<>();
        queryCollection(resolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                "Pictures/Twitter", false, storeItems, storeSeen);
        queryCollection(resolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                "Movies/Twitter", true, storeItems, storeSeen);

        // 3. Extension index (supplement): entries are pruned when the
        // underlying file is gone. Rows the staged queries already
        // listed are represented by those items; other surviving rows
        // are probed for their real name/size so the merge can still
        // dedupe them against a SAF copy of the same file.
        // jp24.13.24.7.2: a failed row probe no longer prunes on its
        // own — on the user's device the MediaStore rows for the
        // .nomedia image folder are not queryable even though the
        // files are on disk (the SAF stage above lists them). The
        // download conflict path consults this index as its existence
        // record for the built-in folders, so dropping an entry whose
        // file the SAF stage just saw would blind it again. An entry
        // is pruned only when neither the row nor the file is found.
        java.util.Set<String> safFileKeys = new java.util.HashSet<>();
        for (MediaItem safItem : safItems) {
            safFileKeys.add(fileKey(safItem.name, safItem.video));
        }
        List<MediaItem> indexItems = new ArrayList<>();
        List<SavedMediaStore.Entry> verified = new ArrayList<>();
        for (SavedMediaStore.Entry entry : SavedMediaStore.entries(context)) {
            if (entry == null || entry.uri == null || entry.uri.isEmpty()) continue;
            Uri uri;
            try {
                uri = Uri.parse(entry.uri);
            } catch (Throwable ignored) {
                continue;
            }
            if (storeSeen.contains(entry.uri)) {
                verified.add(entry);
                continue;
            }
            String[] probed = probeRow(resolver, uri);
            if (probed == null) {
                // Row gone or unqueryable: keep the entry when the SAF
                // stage saw the same file on disk (see the stage-3 note
                // above); the SAF item already represents it in the
                // merge, so no index item is added here.
                boolean entryVideo = entry.mime != null && entry.mime.startsWith("video/");
                if (safFileKeys.contains(fileKey(entry.name, entryVideo))) {
                    verified.add(entry);
                }
                continue;
            }
            verified.add(entry);
            long size = -1;
            try {
                size = Long.parseLong(probed[1]);
            } catch (Throwable ignored) {
            }
            boolean video = entry.mime != null && entry.mime.startsWith("video/");
            indexItems.add(new MediaItem(
                    uri,
                    probed[0] != null ? probed[0] : (entry.name == null ? "" : entry.name),
                    entry.mime == null ? "" : entry.mime,
                    entry.added,
                    size,
                    video,
                    false,
                    null));
        }
        SavedMediaStore.replaceAll(context, verified);

        // Merge: SAF, then MediaStore, then index; first wins on Uri
        // and on the (name, size) dedupe key.
        List<MediaItem> all = new ArrayList<>();
        all.addAll(safItems);
        all.addAll(storeItems);
        all.addAll(indexItems);
        List<String> uris = new ArrayList<>(all.size());
        List<String> keys = new ArrayList<>(all.size());
        for (MediaItem item : all) {
            uris.add(item.uri.toString());
            keys.add(SavedMediaSaf.dedupeKey(item.name, item.size));
        }
        List<MediaItem> merged = new ArrayList<>();
        for (int index : SavedMediaSaf.firstWinsIndices(uris, keys)) {
            merged.add(all.get(index));
        }
        merged.sort((first, second) -> SavedMediaSaf.compareItems(
                first.added, first.name, second.added, second.name));
        return new LoadResult(merged, imageStatus, videoStatus, grantedTrees);
    }

    // ---- SAF enumeration ----

    /**
     * Resolves the usable trees for one media kind — the configured
     * download folder first (its grant is reused read-only), then the
     * viewer's own grant when it points at a different tree — and
     * enumerates each. A stored but unusable tree marks the status
     * revoked; per-tree failures never fail the whole load.
     */
    private static FolderStatus collectSaf(
            Context context,
            ContentResolver resolver,
            MediaKind kind,
            boolean video,
            List<MediaItem> out,
            java.util.Set<String> grantedTrees) {
        FolderStatus status = new FolderStatus();
        List<Uri> trees = new ArrayList<>();

        Uri downloadTree = null;
        try {
            downloadTree = NewXDownloadFolders.treeUri(kind);
        } catch (Throwable ignored) {
        }
        if (downloadTree != null) {
            DestinationState state = null;
            try {
                state = NewXDownloadFolders.destinationState(context, kind);
            } catch (Throwable ignored) {
            }
            if (NewXDownloadFolders.isUsable(state)) {
                trees.add(downloadTree);
            } else {
                status.revoked = true;
            }
        }

        Uri viewerTree = null;
        try {
            viewerTree = SavedMediaFolders.treeUri(kind);
        } catch (Throwable ignored) {
        }
        if (viewerTree != null) {
            DestinationState state = null;
            try {
                state = SavedMediaFolders.destinationState(context, kind);
            } catch (Throwable ignored) {
            }
            if (NewXDownloadFolders.isUsable(state)) {
                String viewerDoc = SavedMediaFolders.treeDocumentId(viewerTree);
                boolean duplicate = false;
                if (viewerDoc != null) {
                    for (Uri tree : trees) {
                        if (viewerDoc.equals(SavedMediaFolders.treeDocumentId(tree))) {
                            duplicate = true;
                            break;
                        }
                    }
                }
                if (!duplicate) trees.add(viewerTree);
            } else {
                status.revoked = true;
            }
        }

        int before = out.size();
        for (Uri tree : trees) {
            grantedTrees.add(tree.toString());
            if (enumerateTree(resolver, tree, video, out, status)) {
                status.anyTree = true;
            }
        }
        status.safCount = out.size() - before;
        Log.i("SavedMedia", "saf stage " + (video ? "Movies/Twitter" : "Pictures/Twitter")
                + ": " + status.safCount);
        return status;
    }

    private static boolean enumerateTree(
            ContentResolver resolver,
            Uri tree,
            boolean video,
            List<MediaItem> out,
            FolderStatus status) {
        String rootId;
        try {
            rootId = DocumentsContract.getTreeDocumentId(tree);
        } catch (Throwable ignored) {
            status.revoked = true;
            return false;
        }
        if (rootId == null) return false;
        int before = out.size();
        HashSet<String> visited = new HashSet<>();
        try {
            enumerateDir(resolver, tree, rootId, video, 0, visited, out);
            return true;
        } catch (SecurityException denied) {
            // Grant lost between the state check and the query: this
            // tree is unusable, but items already read stay listed.
            status.revoked = true;
            return out.size() > before;
        } catch (Throwable ignored) {
            return out.size() > before;
        }
    }

    /**
     * Lists one directory through the tree's child-documents cursor and
     * recurses into subdirectories (depth at most 4, visited set guards
     * against cycles). Dotfiles — .nomedia included — are skipped, as
     * are zero-size and unknown-mime files. Reading through
     * DocumentsContract never triggers a media scan and never creates
     * MediaStore rows, so .nomedia keeps its gallery-hiding effect.
     */
    private static void enumerateDir(
            ContentResolver resolver,
            Uri tree,
            String dirId,
            boolean video,
            int depth,
            HashSet<String> visited,
            List<MediaItem> out) {
        if (depth > 4 || !visited.add(dirId)) return;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId);
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_FLAGS};
        try (Cursor cursor = resolver.query(children, projection, null, null, null)) {
            if (cursor == null) return;
            while (cursor.moveToNext()) {
                String docId = cursor.getString(0);
                String name = cursor.getString(1);
                String mime = cursor.getString(2);
                long size = cursor.isNull(3) ? -1 : cursor.getLong(3);
                long modified = cursor.isNull(4) ? 0 : cursor.getLong(4);
                if (docId == null || SavedMediaSaf.isHiddenName(name)) continue;
                if (SavedMediaSaf.mimeKind(mime) == SavedMediaSaf.KIND_DIRECTORY) {
                    enumerateDir(resolver, tree, docId, video, depth + 1, visited, out);
                } else if (SavedMediaSaf.accepts(mime, video) && size != 0) {
                    Uri docUri = DocumentsContract.buildDocumentUriUsingTree(tree, docId);
                    out.add(new MediaItem(
                            docUri,
                            name,
                            mime == null ? "" : mime,
                            modified,
                            size,
                            video,
                            true,
                            tree));
                }
            }
        }
    }

    // ---- MediaStore enumeration (jp24.13.23 staged queries) ----

    /**
     * jp24.13.23: staged enumeration per collection. On device
     * (jp24.13.22.2) the single {@code RELATIVE_PATH LIKE '…/%'}
     * query listed the one Movies/Twitter video but NO Pictures/Twitter
     * images at all, although the query construction for both
     * collections is identical and the render path drops nothing.
     * The stages below remove every query-side single point of
     * failure, in order:
     * <ol>
     *   <li>selection query matching both stored RELATIVE_PATH forms
     *       (with and without the trailing slash) plus subfolders;</li>
     *   <li>if that adds nothing: an unfiltered query of the same
     *       collection, filtered client-side by
     *       {@link #matchesFolder} — no provider-side selection
     *       handling can silently empty it;</li>
     *   <li>if still nothing: the same client-side filter over the
     *       Files collection (rows whose media type kept them out of
     *       the Images/Video view), additionally requiring the row's
     *       mime type to match the collection kind; item Uris are
     *       still built on the canonical collection Uri and rows are
     *       deduplicated by row id across stages.</li>
     * </ol>
     * Counts are logged per stage ("SavedMedia" tag). jp24.13.24 adds
     * the SIZE column (last projection slot in both shapes) so the
     * merge can dedupe against SAF items on (name, size).
     */
    private static void queryCollection(
            ContentResolver resolver,
            Uri collection,
            String folder,
            boolean video,
            List<MediaItem> out,
            HashSet<String> seen) {
        HashSet<Long> seenIds = new HashSet<>();
        int added = runCollectionQuery(
                resolver, collection, collection, folder, video, out, seen, seenIds, 1);
        Log.i("SavedMedia", "mediastore stage1 " + folder + ": " + added);
        if (added == 0) {
            added = runCollectionQuery(
                    resolver, collection, collection, folder, video, out, seen, seenIds, 2);
            Log.i("SavedMedia", "mediastore stage2 " + folder + ": " + added);
        }
        if (added == 0) {
            added = runCollectionQuery(
                    resolver, MediaStore.Files.getContentUri("external"), collection,
                    folder, video, out, seen, seenIds, 3);
            Log.i("SavedMedia", "mediastore stage3 " + folder + ": " + added);
        }
    }

    private static int runCollectionQuery(
            ContentResolver resolver,
            Uri queryUri,
            Uri itemBaseUri,
            String folder,
            boolean video,
            List<MediaItem> out,
            HashSet<String> seen,
            HashSet<Long> seenIds,
            int stage) {
        boolean filtered = stage > 1;
        String[] projection = filtered
                ? new String[]{
                        MediaStore.MediaColumns._ID,
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.MIME_TYPE,
                        MediaStore.MediaColumns.DATE_ADDED,
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        MediaStore.MediaColumns.SIZE}
                : new String[]{
                        MediaStore.MediaColumns._ID,
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.MIME_TYPE,
                        MediaStore.MediaColumns.DATE_ADDED,
                        MediaStore.MediaColumns.SIZE};
        int sizeIndex = filtered ? 5 : 4;
        String selection = filtered
                ? null
                : MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? OR "
                        + MediaStore.MediaColumns.RELATIVE_PATH + " = ?";
        String[] selectionArgs = filtered
                ? null
                : new String[]{folder + "/%", folder};
        int added = 0;
        try (Cursor cursor = resolver.query(
                queryUri,
                projection,
                selection,
                selectionArgs,
                MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
            if (cursor == null) return 0;
            while (cursor.moveToNext()) {
                long id = cursor.getLong(0);
                if (seenIds.contains(id)) continue;
                String mime = cursor.getString(2);
                if (filtered && !matchesFolder(cursor.getString(4), folder)) continue;
                if (stage == 3 && (mime == null
                        || !mime.startsWith(video ? "video/" : "image/"))) continue;
                String name = cursor.getString(1);
                long addedSeconds = cursor.getLong(3);
                long size = cursor.isNull(sizeIndex) ? -1 : cursor.getLong(sizeIndex);
                Uri uri = ContentUris.withAppendedId(itemBaseUri, id);
                if (!seen.add(uri.toString())) continue;
                seenIds.add(id);
                out.add(new MediaItem(
                        uri,
                        name == null ? "" : name,
                        mime == null ? "" : mime,
                        addedSeconds * 1000L,
                        size,
                        video,
                        false,
                        null));
                added++;
            }
        } catch (Throwable ignored) {
            // One broken collection must not hide the other one.
        }
        return added;
    }

    /**
     * True when a stored RELATIVE_PATH value denotes {@code folder}
     * itself or a subfolder of it. Tolerates the stored forms
     * "Pictures/Twitter" and "Pictures/Twitter/" and case differences;
     * sibling folders such as "Pictures/Twitter2/" do not match.
     * Android-free; exercised by the JVM harness.
     */
    /** (name, kind) key used to cross-check index entries against the SAF stage. */
    static String fileKey(String name, boolean video) {
        return (video ? "v:" : "i:") + (name == null ? "" : name);
    }

    static boolean matchesFolder(String relativePath, String folder) {
        if (relativePath == null || folder == null) return false;
        String path = relativePath.endsWith("/") ? relativePath : relativePath + "/";
        String prefix = folder.endsWith("/") ? folder : folder + "/";
        return path.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    /**
     * Reads (display name, size) of the row behind an index Uri, or
     * null when the row is gone. SecurityException included: treated
     * as missing so the entry is pruned.
     */
    private static String[] probeRow(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(
                uri,
                new String[]{
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.SIZE},
                null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            String name = cursor.getString(0);
            String size = cursor.isNull(1) ? null : String.valueOf(cursor.getLong(1));
            return new String[]{name, size};
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ---- Grid rendering ----

    private static final int STATE_OK = 0;
    private static final int STATE_NOT_GRANTED = 1;
    private static final int STATE_REVOKED = 2;

    private static int folderState(FolderStatus status) {
        if (status.anyTree) return STATE_OK;
        return status.revoked ? STATE_REVOKED : STATE_NOT_GRANTED;
    }

    private void renderMedia(LoadResult result) {
        if (grid == null) return;
        lastResult = result;
        grid.removeAllViews();
        if (grantPanel != null) grantPanel.removeAllViews();
        if (tabBar != null) tabBar.setVisibility(View.GONE);
        List<MediaItem> items = result.items;
        int imageState = folderState(result.images);
        int videoState = folderState(result.videos);
        Log.i("SavedMedia", "folder states images=" + imageState + " videos=" + videoState
                + " items=" + (items == null ? 0 : items.size()));
        boolean imageProblem = imageState != STATE_OK;
        boolean videoProblem = videoState != STATE_OK;

        if (items == null || items.isEmpty()) {
            if (imageProblem || videoProblem) {
                // "Empty" and "not granted" are different states and
                // must not be conflated: without a grant the folders
                // were never listed, so nothing can be called absent.
                if (imageState == STATE_REVOKED || videoState == STATE_REVOKED) {
                    setStatus("フォルダへのアクセスが失効しています。再度許可してください。");
                } else {
                    setStatus("フォルダへのアクセス許可が必要です。");
                }
                Context context = requireContext();
                if (imageProblem) {
                    grantPanel.addView(grantBlock(context, MediaKind.IMAGES, imageState));
                }
                if (videoProblem) {
                    grantPanel.addView(grantBlock(context, MediaKind.VIDEOS, videoState));
                }
            } else {
                setStatus("保存したメディアはまだありません");
            }
            return;
        }

        if (imageProblem || videoProblem) {
            // Partial grant: what could be listed is listed, and the
            // missing folder is called out instead of staying silent.
            Context context = requireContext();
            if (imageProblem) {
                grantPanel.addView(bannerRow(context, MediaKind.IMAGES, imageState));
            }
            if (videoProblem) {
                grantPanel.addView(bannerRow(context, MediaKind.VIDEOS, videoState));
            }
        }
        Context context = requireContext();
        // jp24.13.24.7: tabs filter the already-enumerated set; no
        // rescan happens on a tab switch.
        renderTabs(context, items);
        List<MediaItem> visible = new ArrayList<>();
        for (MediaItem item : items) {
            if (SavedMediaSaf.tabFor(item.mime, item.name, item.video) == currentTab) {
                visible.add(item);
            }
        }
        setStatus(visible.isEmpty() ? "このタブに表示するメディアはありません" : "");
        int cell = Math.max(
                dpToPx(context, 96f),
                context.getResources().getDisplayMetrics().widthPixels / 3
                        - dpToPx(context, 8f));
        for (MediaItem item : visible) {
            grid.addView(cellView(context, item), cellParams(context, cell));
        }
    }

    /** jp24.13.24.7: rebuilds the 画像 / 動画 / GIF tab row with per-tab counts. */
    private void renderTabs(Context context, List<MediaItem> items) {
        if (tabBar == null) return;
        tabBar.removeAllViews();
        int[] tabs = {SavedMediaSaf.TAB_IMAGE, SavedMediaSaf.TAB_VIDEO, SavedMediaSaf.TAB_GIF};
        String[] labels = {"画像", "動画", "GIF"};
        int[] counts = new int[3];
        for (MediaItem item : items) {
            counts[SavedMediaSaf.tabFor(item.mime, item.name, item.video)]++;
        }
        for (int i = 0; i < tabs.length; i++) {
            final int tab = tabs[i];
            ButtonView button = new ButtonView(
                    context,
                    tab == currentTab
                            ? ButtonView.ButtonStyle.FILLED
                            : ButtonView.ButtonStyle.TONAL,
                    labels[i] + "（" + counts[tab] + "）");
            button.setOnClickListener(view -> {
                if (currentTab != tab) {
                    currentTab = tab;
                    if (lastResult != null) renderMedia(lastResult);
                }
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1f);
            int margin = dpToPx(context, 2f);
            params.setMargins(margin, 0, margin, 0);
            tabBar.addView(button, params);
        }
        tabBar.setVisibility(View.VISIBLE);
    }

    /** Full grant prompt block for one folder, used when nothing is listed. */
    private View grantBlock(Context context, MediaKind kind, int state) {
        LinearLayout block = new LinearLayout(context);
        block.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams blockParams = new LinearLayout.LayoutParams(-1, -2);
        blockParams.topMargin = dpToPx(context, 12f);
        block.setLayoutParams(blockParams);

        TextView hint = SettingsUi.summaryText(context);
        hint.setText(grantHint(kind, state));
        block.addView(hint, new LinearLayout.LayoutParams(-1, -2));

        ButtonView button = new ButtonView(
                context, ButtonView.ButtonStyle.FILLED,
                kind == MediaKind.IMAGES ? "画像フォルダを許可" : "動画フォルダを許可");
        button.setOnClickListener(view -> launchGrant(kind));
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(-1, -2);
        buttonParams.topMargin = dpToPx(context, 8f);
        block.addView(button, buttonParams);
        return block;
    }

    /** Slim banner row for one ungranted folder, shown above a non-empty grid. */
    private View bannerRow(Context context, MediaKind kind, int state) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
        rowParams.topMargin = dpToPx(context, 8f);
        row.setLayoutParams(rowParams);

        TextView label = SettingsUi.summaryText(context);
        String folder = kind == MediaKind.IMAGES ? "画像" : "動画";
        label.setText(state == STATE_REVOKED
                ? folder + "フォルダへのアクセスが失効しています"
                : folder + "フォルダが未許可です");
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));

        ButtonView button = new ButtonView(
                context, ButtonView.ButtonStyle.TONAL, "許可する");
        button.setOnClickListener(view -> launchGrant(kind));
        row.addView(button, new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    private static String grantHint(MediaKind kind, int state) {
        String pick = kind == MediaKind.IMAGES
                ? "『Pictures』内の『Twitter』フォルダを選んでください"
                : "『Movies』内の『Twitter』フォルダを選んでください";
        String lead = kind == MediaKind.IMAGES ? "画像" : "動画";
        if (state == STATE_REVOKED) {
            return lead + "フォルダへのアクセスが失効しています。" + pick + "。";
        }
        return lead + "を表示するにはフォルダへのアクセス許可が必要です。" + pick
                + "（ダウンロード先に使っていたフォルダでも構いません）。";
    }

    /** Post-auth only: the grant UI never appears before authentication. */
    private void launchGrant(MediaKind kind) {
        Activity activity = getActivity();
        if (activity == null || destroyed) return;
        SavedMediaFolders.choose(activity, kind, () -> {
            if (!destroyed) loadMedia();
        });
    }

    private View cellView(Context context, MediaItem item) {
        FrameLayout cell = new FrameLayout(context);
        ImageView image = new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setTag(item);
        cell.addView(image, new FrameLayout.LayoutParams(-1, -1));
        if (item.video) {
            TextView badge = new TextView(context);
            badge.setText("▶");
            badge.setTextColor(Color.WHITE);
            badge.setBackgroundColor(Color.argb(160, 0, 0, 0));
            int badgePadding = dpToPx(context, 4f);
            badge.setPadding(badgePadding, badgePadding, badgePadding, badgePadding);
            FrameLayout.LayoutParams badgeParams =
                    new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
            int badgeMargin = dpToPx(context, 4f);
            badgeParams.setMargins(badgeMargin, badgeMargin, badgeMargin, badgeMargin);
            cell.addView(badge, badgeParams);
        }
        cell.setOnClickListener(view -> {
            if (item.video) {
                openVideo(item);
            } else {
                openImage(item);
            }
        });
        // jp24.13.24.7: long-press deletes the file (with a
        // confirmation), scoped to the enumerated folders.
        cell.setOnLongClickListener(view -> {
            confirmDelete(item);
            return true;
        });
        loadThumbnail(item, image);
        return cell;
    }

    /** jp24.13.24.7: delete confirmation, in the ConflictResolver dialog shape. */
    private void confirmDelete(MediaItem item) {
        Activity activity = getActivity();
        if (activity == null || destroyed) return;
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        int padding = dpToPx(activity, 24f);
        body.setPadding(padding, 0, padding, 0);

        TextView nameView = SettingsUi.summaryText(activity);
        nameView.setText(item.name);
        body.addView(nameView, new LinearLayout.LayoutParams(-1, -2));

        TextView noteView = SettingsUi.summaryText(activity);
        noteView.setText("このファイルを削除しますか？（フォルダ内から完全に削除されます）");
        body.addView(noteView, new LinearLayout.LayoutParams(-1, -2));

        DialogView dialog = new DialogView(activity)
                .setTitle("ファイルを削除")
                .setBodyView(body);
        ButtonView deleteButton = SettingsUi.dialogButton(activity, "削除する");
        deleteButton.setOnClickListener(view -> {
            dialog.dismiss();
            deleteItem(item);
        });
        dialog.addButton(deleteButton);
        ButtonView cancelButton = SettingsUi.dialogButton(activity, "キャンセル");
        cancelButton.setOnClickListener(view -> dialog.dismiss());
        dialog.addButton(cancelButton);
        dialog.show();
    }

    /**
     * jp24.13.24.7: deletes one enumerated item. The scope guard
     * ({@link SavedMediaSaf#canDelete}) runs first: the item must be
     * in the current enumerated set, and a SAF item's tree must be
     * one of the trees this load enumerated. SAF items are deleted
     * through DocumentsContract.deleteDocument on their document Uri;
     * MediaStore/index items through the content resolver on their
     * row Uri. Directories are never items, so they are never
     * deleted. On success the item leaves every tab at once; on any
     * failure a toast explains and the item stays.
     */
    private void deleteItem(MediaItem item) {
        Activity activity = getActivity();
        if (activity == null || destroyed || lastResult == null) return;
        Context appContext = activity.getApplicationContext();
        final Context context = appContext != null ? appContext : activity;
        final LoadResult result = lastResult;
        new Thread(() -> {
            boolean deleted = false;
            try {
                ContentResolver resolver = context.getContentResolver();
                java.util.Set<String> enumerated = new java.util.HashSet<>();
                for (MediaItem entry : result.items) {
                    enumerated.add(entry.uri.toString());
                }
                boolean allowed = SavedMediaSaf.canDelete(
                        item.saf,
                        item.uri.toString(),
                        item.tree == null ? null : item.tree.toString(),
                        enumerated,
                        result.safTrees);
                if (!allowed) {
                    Log.w("SavedMedia", "delete refused (out of scope): " + item.uri);
                } else if (item.saf) {
                    deleted = DocumentsContract.deleteDocument(resolver, item.uri);
                } else {
                    deleted = resolver.delete(item.uri, null, null) > 0;
                }
            } catch (Throwable t) {
                Log.e("SavedMedia", "delete failed: " + item.uri, t);
                deleted = false;
            }
            boolean success = deleted;
            Activity current = getActivity();
            if (current == null || destroyed) return;
            current.runOnUiThread(() -> {
                if (destroyed) return;
                if (success) {
                    result.items.remove(item);
                    synchronized (thumbCache) {
                        thumbCache.remove(item.uri.toString());
                    }
                    Toast.makeText(current, "削除しました", Toast.LENGTH_SHORT).show();
                    renderMedia(result);
                } else {
                    Toast.makeText(current, "削除できませんでした", Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    private static GridLayout.LayoutParams cellParams(Context context, int cell) {
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = cell;
        params.height = cell;
        int margin = dpToPx(context, 2f);
        params.setMargins(margin, margin, margin, margin);
        return params;
    }

    private void loadThumbnail(MediaItem item, ImageView image) {
        String key = item.uri.toString();
        Bitmap cached;
        synchronized (thumbCache) {
            cached = thumbCache.get(key);
        }
        if (cached != null) {
            image.setImageBitmap(cached);
            return;
        }
        Activity activity = getActivity();
        if (activity == null) return;
        ContentResolver resolver = activity.getContentResolver();
        Context appContext = activity.getApplicationContext();
        final Context frameContext = appContext != null ? appContext : activity;
        if (thumbExecutor == null || thumbExecutor.isShutdown()) {
            thumbExecutor = Executors.newFixedThreadPool(3);
        }
        thumbExecutor.execute(() -> {
            Bitmap bitmap = null;
            try {
                if (item.saf && item.video) {
                    // loadThumbnail is a MediaStore facility; SAF video
                    // thumbnails come from a retriever frame instead.
                    bitmap = videoFrame(frameContext, item.uri);
                } else if (item.saf) {
                    bitmap = decodeBounded(resolver, item.uri, 360);
                } else {
                    bitmap = resolver.loadThumbnail(item.uri, new Size(360, 360), null);
                }
            } catch (Throwable ignored) {
            }
            if (bitmap == null) return;
            synchronized (thumbCache) {
                thumbCache.put(key, bitmap);
            }
            Activity current = getActivity();
            if (current == null || destroyed) return;
            Bitmap loaded = bitmap;
            current.runOnUiThread(() -> {
                if (!destroyed && image.getTag() == item) {
                    image.setImageBitmap(loaded);
                }
            });
        });
    }

    private static Bitmap videoFrame(Context context, Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            Bitmap frame = retriever.getFrameAtTime(
                    0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) return null;
            int largest = Math.max(frame.getWidth(), frame.getHeight());
            if (largest <= 360) return frame;
            float scale = 360f / largest;
            Bitmap scaled = Bitmap.createScaledBitmap(
                    frame,
                    Math.max(1, Math.round(frame.getWidth() * scale)),
                    Math.max(1, Math.round(frame.getHeight() * scale)),
                    true);
            if (scaled != frame) frame.recycle();
            return scaled;
        } catch (Throwable ignored) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Throwable ignored) {
            }
        }
    }

    // ---- Detail viewers ----

    private void openImage(MediaItem item) {
        Activity activity = getActivity();
        if (activity == null) return;
        ImageView large = new ImageView(activity);
        large.setAdjustViewBounds(true);
        large.setLayoutParams(new ViewGroup.LayoutParams(-1, -2));
        DialogView dialog = new DialogView(activity)
                .setTitle(item.name)
                .setBodyView(large);
        dialog.show();
        ContentResolver resolver = activity.getContentResolver();
        new Thread(() -> {
            Bitmap bitmap = decodeBounded(resolver, item.uri, 2048);
            if (bitmap == null) return;
            activity.runOnUiThread(() -> {
                if (!destroyed) large.setImageBitmap(bitmap);
            });
        }).start();
    }

    private static Bitmap decodeBounded(ContentResolver resolver, Uri uri, int limitPx) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream input = resolver.openInputStream(uri)) {
                if (input == null) return null;
                BitmapFactory.decodeStream(input, null, bounds);
            }
            int largest = Math.max(bounds.outWidth, bounds.outHeight);
            int sample = 1;
            while (largest / sample > limitPx) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            try (InputStream input = resolver.openInputStream(uri)) {
                if (input == null) return null;
                return BitmapFactory.decodeStream(input, null, options);
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void openVideo(MediaItem item) {
        Activity activity = getActivity();
        if (activity == null) return;

        // jp24.13.24.7.3: the framework MediaController is replaced with an
        // in-layout controller. Anchored into the dialog's body container,
        // the stock controller rendered the whole control block shifted
        // right of the video and its seek thumb off the track's start
        // (device report, 2026-11-21); its fixed layout also pins the
        // current/total times to the seek row's ends, while the user wants
        // one centered readout. The custom controller is an ordinary
        // vertical layout under the video, so it follows the same layout
        // pass as the rest of the dialog and cannot drift: transport
        // buttons centered, one centered "current / total" label, and a
        // full-width SeekBar whose thumb tracks the position through the
        // standard SeekBar padding. Behavior kept from the stock
        // controller: play/pause, seek by dragging, prev/next jumps,
        // position updates while playing, stop on dialog dismiss. The
        // stock controller's tap-to-show / auto-hide is not reproduced;
        // the controls stay visible while the dialog is open.
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new ViewGroup.LayoutParams(-1, -2));

        VideoView video = new VideoView(activity);
        video.setLayoutParams(new LinearLayout.LayoutParams(-1, dpToPx(activity, 240f)));
        root.addView(video);

        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);
        buttons.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        TextView prevButton = playerButton(activity, "⏮");
        TextView playButton = playerButton(activity, "⏸");
        TextView nextButton = playerButton(activity, "⏭");
        buttons.addView(prevButton);
        buttons.addView(playButton);
        buttons.addView(nextButton);
        root.addView(buttons);

        TextView timeLabel = new TextView(activity);
        timeLabel.setGravity(Gravity.CENTER);
        timeLabel.setText("00:00 / 00:00");
        timeLabel.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        root.addView(timeLabel);

        SeekBar seekBar = new SeekBar(activity);
        seekBar.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        root.addView(seekBar);

        Handler handler = new Handler(Looper.getMainLooper());
        boolean[] seeking = {false};
        Runnable[] ticker = new Runnable[1];
        ticker[0] = () -> {
            int duration = video.getDuration();
            int position = video.getCurrentPosition();
            if (duration > 0) {
                seekBar.setMax(duration);
                if (!seeking[0]) seekBar.setProgress(position);
                timeLabel.setText(formatPlayerTime(position)
                        + " / " + formatPlayerTime(duration));
            }
            playButton.setText(video.isPlaying() ? "⏸" : "▶");
            handler.postDelayed(ticker[0], 500);
        };

        prevButton.setOnClickListener(view -> video.seekTo(0));
        nextButton.setOnClickListener(view -> {
            int duration = video.getDuration();
            if (duration > 0) video.seekTo(duration);
        });
        playButton.setOnClickListener(view -> {
            if (video.isPlaying()) {
                video.pause();
                playButton.setText("▶");
            } else {
                video.start();
                playButton.setText("⏸");
            }
        });
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) {
                    int duration = video.getDuration();
                    timeLabel.setText(formatPlayerTime(progress) + " / "
                            + formatPlayerTime(Math.max(duration, 0)));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                seeking[0] = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                seeking[0] = false;
                video.seekTo(bar.getProgress());
            }
        });
        video.setOnCompletionListener(player -> {
            playButton.setText("▶");
            int duration = video.getDuration();
            if (duration > 0) {
                seekBar.setProgress(duration);
                timeLabel.setText(formatPlayerTime(duration)
                        + " / " + formatPlayerTime(duration));
            }
        });

        video.setVideoURI(item.uri);
        DialogView dialog = new DialogView(activity)
                .setTitle(item.name)
                .setBodyView(root);
        dialog.getDialog().setOnDismissListener(ignored -> {
            handler.removeCallbacks(ticker[0]);
            video.stopPlayback();
        });
        dialog.show();
        video.start();
        handler.post(ticker[0]);
    }

    private TextView playerButton(Activity activity, String glyph) {
        TextView button = new TextView(activity);
        button.setText(glyph);
        button.setTextSize(24f);
        button.setGravity(Gravity.CENTER);
        int pad = dpToPx(activity, 12f);
        button.setPadding(pad, pad / 2, pad, pad / 2);
        button.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        return button;
    }

    private static String formatPlayerTime(int millis) {
        if (millis < 0) millis = 0;
        int totalSeconds = millis / 1000;
        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format(java.util.Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(java.util.Locale.ROOT, "%02d:%02d", minutes, seconds);
    }

    // ---- Helpers ----

    private void setStatus(String text) {
        if (statusText != null) statusText.setText(text);
    }

    private Context requireContext() {
        Context context = getActivity();
        if (context == null) throw new IllegalStateException("Saved media activity is missing");
        return context;
    }

    private static int dpToPx(Context context, float dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp,
                context.getResources().getDisplayMetrics());
    }
}
