/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.contacts.vcard;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.RawContactsEntity;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.android.contacts.R;
import com.android.vcard.VCardComposer;
import com.android.vcard.VCardConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Exports all contacts, with their photos, to a secret GitHub gist and imports them back,
 * encrypted with a password if one is given. Contacts already on the phone aren't imported
 * again.
 */
public class GistBackupActivity extends Activity {
    private static final String TAG = "GistBackup";

    public static final String EXTRA_IMPORT = "import";

    private static final String PREFS = "gist_backup";
    private static final String PREF_TOKEN = "token";
    private static final String PREF_GIST_ID = "gist_id";

    private static final String IMPORT_FILE = "gist_contacts.vcf";

    // The photo column only has the 96px thumbnail, so the full photo is read from its file.
    // 720px is what the contacts provider keeps as the display photo.
    private static final VCardComposer.PhotoOptions PHOTO_OPTIONS =
            new VCardComposer.PhotoOptions(720, 200 * 1024);
    private static final int REQUEST_IMPORT = 1;

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private boolean mImport;
    private AlertDialog mDialog;

    /** A failure with a message for the user. */
    private static class BackupException extends Exception {
        final int messageId;

        BackupException(int messageId) {
            this.messageId = messageId;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mImport = getIntent().getBooleanExtra(EXTRA_IMPORT, false);
        if (savedInstanceState == null) {
            showInputDialog();
        }
    }

    @Override
    protected void onDestroy() {
        mExecutor.shutdownNow();
        if (mDialog != null) {
            mDialog.dismiss();
        }
        super.onDestroy();
    }

    private void showInputDialog() {
        final View view = getLayoutInflater().inflate(R.layout.gist_backup_dialog, null);
        final TextView message = view.findViewById(R.id.gist_message);
        final EditText tokenView = view.findViewById(R.id.gist_token);
        final EditText passwordView = view.findViewById(R.id.gist_password);
        message.setText(mImport ? R.string.gist_import_message : R.string.gist_export_message);
        tokenView.setText(getPrefs().getString(PREF_TOKEN, null));
        // Password fields default to monospace, which cuts off the hints
        tokenView.setTypeface(Typeface.DEFAULT);
        passwordView.setTypeface(Typeface.DEFAULT);

        mDialog = new AlertDialog.Builder(this)
                .setTitle(mImport ? R.string.import_from_gist : R.string.export_to_gist)
                .setView(view)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(mImport ? R.string.gist_import_button
                        : R.string.gist_export_button, null)
                .setOnDismissListener(dialog -> {
                    if (mDialog == dialog) {
                        finish();
                    }
                })
                .create();
        mDialog.setOnShowListener(dialog -> {
            // Kept open on a missing token instead of closing on the button
            final Button button = mDialog.getButton(AlertDialog.BUTTON_POSITIVE);
            button.setOnClickListener(v -> {
                final String token = tokenView.getText().toString().trim();
                if (token.isEmpty()) {
                    tokenView.setError(getString(R.string.gist_error_token_empty));
                    return;
                }
                final String password = passwordView.getText().toString();
                getPrefs().edit().putString(PREF_TOKEN, token).apply();
                startWork(token, password);
            });
        });
        mDialog.show();
    }

    private void startWork(String token, String password) {
        final AlertDialog inputDialog = mDialog;
        mDialog = null;
        inputDialog.dismiss();

        final View view = getLayoutInflater().inflate(R.layout.gist_backup_dialog, null);
        view.findViewById(R.id.gist_token).setVisibility(View.GONE);
        view.findViewById(R.id.gist_password).setVisibility(View.GONE);
        view.findViewById(R.id.gist_progress).setVisibility(View.VISIBLE);
        ((TextView) view.findViewById(R.id.gist_message)).setText(
                mImport ? R.string.gist_importing : R.string.gist_exporting);
        final AlertDialog progress = new AlertDialog.Builder(this)
                .setTitle(mImport ? R.string.import_from_gist : R.string.export_to_gist)
                .setView(view)
                .setCancelable(false)
                .create();
        mDialog = progress;
        progress.show();

        mExecutor.execute(() -> {
            String result;
            Uri importUri = null;
            try {
                if (mImport) {
                    importUri = downloadContacts(token, password);
                    result = null;
                } else {
                    final int count = exportContacts(token, password);
                    result = getResources().getQuantityString(R.plurals.gist_export_done,
                            count, count);
                }
            } catch (BackupException e) {
                result = getString(e.messageId);
            } catch (GistClient.HttpException e) {
                Log.e(TAG, "GitHub request failed", e);
                result = getString(e.code == HttpURLConnection.HTTP_UNAUTHORIZED
                        || e.code == HttpURLConnection.HTTP_FORBIDDEN
                        || e.code == HttpURLConnection.HTTP_NOT_FOUND
                        ? R.string.gist_error_token : R.string.gist_error_failed);
            } catch (IOException e) {
                Log.e(TAG, "GitHub request failed", e);
                result = getString(R.string.gist_error_network);
            } catch (Exception e) {
                Log.e(TAG, "Gist backup failed", e);
                result = getString(R.string.gist_error_failed);
            }
            final String message = result;
            final Uri uri = importUri;
            mHandler.post(() -> {
                if (isDestroyed()) {
                    return;
                }
                if (uri != null) {
                    // ImportVCardActivity asks for the account and imports in the background
                    startActivityForResult(new Intent(this, ImportVCardActivity.class)
                            .setData(uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), REQUEST_IMPORT);
                    return;
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                finish();
            });
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_IMPORT) {
            // ImportVCardActivity copied the file before returning
            new File(getCacheDir(), IMPORT_FILE).delete();
            finish();
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private int exportContacts(String token, String password) throws Exception {
        final int vcardType = VCardConfig.getVCardTypeFromString(
                getString(R.string.config_export_vcard_type));
        final VCardComposer composer = new HighResPhotoComposer(this, vcardType);
        final StringBuilder vcards = new StringBuilder();
        final int count;
        try {
            if (!composer.init(Contacts.CONTENT_URI, new String[] {Contacts._ID}, null, null,
                    null, RawContactsEntity.CONTENT_URI)) {
                Log.e(TAG, "Can't initialize the vCard composer: " + composer.getErrorReason());
                throw new BackupException(R.string.gist_error_failed);
            }
            count = composer.getCount();
            if (count == 0) {
                throw new BackupException(R.string.gist_error_no_contacts);
            }
            while (!composer.isAfterLast()) {
                vcards.append(composer.createOneEntry());
            }
        } finally {
            composer.terminate();
        }

        final boolean encrypted = !TextUtils.isEmpty(password);
        final String content = encrypted
                ? GistCrypto.encrypt(vcards.toString(), password) : vcards.toString();
        final GistClient client = new GistClient(token);
        final String gistId = client.upload(
                client.findBackupGist(getPrefs().getString(PREF_GIST_ID, null)), content,
                encrypted);
        getPrefs().edit().putString(PREF_GIST_ID, gistId).apply();
        return count;
    }

    private Uri downloadContacts(String token, String password) throws Exception {
        final GistClient client = new GistClient(token);
        final String gistId = client.findBackupGist(getPrefs().getString(PREF_GIST_ID, null));
        if (gistId == null) {
            throw new BackupException(R.string.gist_error_not_found);
        }
        getPrefs().edit().putString(PREF_GIST_ID, gistId).apply();

        final GistClient.Backup backup = client.download(gistId);
        final String vcards;
        if (backup.encrypted) {
            if (TextUtils.isEmpty(password)) {
                throw new BackupException(R.string.gist_error_password_needed);
            }
            try {
                vcards = GistCrypto.decrypt(backup.content, password);
            } catch (GistCrypto.WrongPasswordException e) {
                throw new BackupException(R.string.gist_error_wrong_password);
            }
        } else {
            vcards = backup.content;
        }

        final GistDuplicateFilter.Result filtered = GistDuplicateFilter.filter(
                getContentResolver(), vcards, VCardConfig.getVCardTypeFromString(
                        getString(R.string.config_export_vcard_type)));
        if (filtered.imported == 0) {
            throw new BackupException(R.string.gist_error_all_exist);
        }
        if (filtered.skipped > 0) {
            final String skipped = getResources().getQuantityString(
                    R.plurals.gist_import_skipped, filtered.skipped, filtered.skipped);
            mHandler.post(() -> Toast.makeText(this, skipped, Toast.LENGTH_LONG).show());
        }

        final File file = new File(getCacheDir(), IMPORT_FILE);
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(filtered.vcards.getBytes(StandardCharsets.UTF_8));
        }
        return FileProvider.getUriForFile(this,
                getString(R.string.contacts_file_provider_authority), file);
    }

    private static class HighResPhotoComposer extends VCardComposer {
        HighResPhotoComposer(Context context, int vcardType) {
            super(context, vcardType, true);
        }

        @Override
        public String buildVCard(Map<String, List<ContentValues>> contentValuesListMap) {
            return buildVCard(contentValuesListMap, PHOTO_OPTIONS);
        }
    }

    private SharedPreferences getPrefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }
}
