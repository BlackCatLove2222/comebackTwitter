package app.morphe.extension.crimera.downloader;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.Nullable;

import app.morphe.extension.crimera.downloader.engine.DestinationCheck;

/**
 * Reconstruction of the shipped crimera FolderPickerActivity
 * (classes20), extended in jp24.13.24.1: when the launch intent
 * names destination settings keys, the picked tree is persisted
 * from inside onActivityResult, before (and independently of) the
 * callback dispatch. The intent survives process recreation, so a
 * grant completed after the app process was killed under the system
 * picker is still stored; the callback remains as a reload hint.
 */
public final class FolderPickerActivity extends Activity {
    private static final int PICK_TREE_REQUEST = 0x2b;

    private int token;
    private String treeKey;
    private String pathKey;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        int token = 0;
        if (intent != null) {
            token = intent.getIntExtra(FolderPicker.EXTRA_TOKEN, 0);
            treeKey = intent.getStringExtra(FolderPicker.EXTRA_TREE_KEY);
            pathKey = intent.getStringExtra(FolderPicker.EXTRA_PATH_KEY);
        }
        this.token = token;
        boolean hasPersistTarget = treeKey != null && pathKey != null;
        if (!FolderPicker.hasCallback(token) && !hasPersistTarget) {
            finish();
            return;
        }
        if (savedInstanceState == null) {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            pick.addFlags(0xc3);
            try {
                startActivityForResult(pick, PICK_TREE_REQUEST);
            } catch (RuntimeException e) {
                FolderPicker.Callback callback = FolderPicker.takeCallback(this.token);
                if (callback != null) {
                    callback.onCancelled();
                }
                finish();
            }
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_TREE_REQUEST) return;
        FolderPicker.Callback callback = FolderPicker.takeCallback(token);
        Uri tree = data == null ? null : data.getData();
        if (resultCode != RESULT_OK || tree == null) {
            if (callback != null) callback.onCancelled();
            finish();
            return;
        }
        boolean persisted;
        try {
            getContentResolver().takePersistableUriPermission(tree, 0x3);
            persisted = true;
        } catch (RuntimeException e) {
            persisted = false;
        }
        if (!persisted
                && !DestinationCheck.hasLiveTreeAccess(getContentResolver(), tree)) {
            if (callback != null) callback.onUnwritable();
            finish();
            return;
        }
        String displayPath = DestinationCheck.displayPathFor(tree);
        if (treeKey != null && pathKey != null) {
            FolderPicker.persistTreeGrant(treeKey, pathKey, tree, displayPath);
        }
        if (callback != null) callback.onPicked(tree, displayPath, persisted);
        finish();
    }
}
