/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.contacts.duplicates;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentProviderOperation;
import android.content.ContentUris;
import android.content.OperationApplicationException;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.provider.ContactsContract;
import android.provider.ContactsContract.RawContacts;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.android.contacts.R;
import com.android.contacts.activities.RequestPermissionsActivity;
import com.android.contacts.model.account.AccountWithDataSet;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lists the copies of contacts saved on the device that hold nothing the contact they duplicate
 * doesn't and removes the selected ones. Contacts of other accounts are left alone, since
 * removing them would also remove them from those accounts.
 */
public class DuplicateCleanupActivity extends Activity {
    private static final String TAG = "DuplicateCleanup";

    private static final int DELETE_BATCH_SIZE = 100;

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private AlertDialog mDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (RequestPermissionsActivity.startPermissionActivityIfNeeded(this)) {
            return;
        }
        if (savedInstanceState == null) {
            search();
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

    private void search() {
        showProgress(R.string.duplicates_searching);
        mExecutor.execute(() -> {
            List<DuplicateFinder.Group> groups = null;
            try {
                groups = findDuplicates();
            } catch (RuntimeException e) {
                Log.e(TAG, "Can't look for duplicates", e);
            }
            final List<DuplicateFinder.Group> result = groups;
            mHandler.post(() -> onSearchFinished(result));
        });
    }

    private List<DuplicateFinder.Group> findDuplicates() {
        final AccountWithDataSet local = AccountWithDataSet.getLocalAccount(this);
        final List<DuplicateFinder.Group> groups = DuplicateFinder.find(
                RawContactRecords.load(getContentResolver(), (name, type, dataSet) ->
                        (name == null && type == null)
                                || (Objects.equals(name, local.name)
                                        && Objects.equals(type, local.type))).values());
        groups.sort(Comparator.comparing(
                group -> DuplicateFinder.normalizeName(group.keeper.name)));
        return groups;
    }

    private void onSearchFinished(List<DuplicateFinder.Group> groups) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (groups == null) {
            finishWithMessage(getString(R.string.duplicates_failed));
        } else if (groups.isEmpty()) {
            finishWithMessage(getString(R.string.duplicates_none));
        } else {
            showSelection(groups);
        }
    }

    private void showSelection(List<DuplicateFinder.Group> groups) {
        final CharSequence[] items = new CharSequence[groups.size()];
        final boolean[] selected = new boolean[groups.size()];
        for (int i = 0; i < items.length; i++) {
            final DuplicateFinder.Group group = groups.get(i);
            final String name = TextUtils.isEmpty(group.keeper.displayPhone) ? group.keeper.name
                    : group.keeper.name + " (" + group.keeper.displayPhone + ")";
            items[i] = getString(R.string.duplicates_item, name, group.duplicates.size());
            selected[i] = true;
        }
        replaceDialog(new AlertDialog.Builder(this)
                .setTitle(R.string.duplicates_title)
                .setMultiChoiceItems(items, selected, (dialog, which, isChecked) ->
                        selected[which] = isChecked)
                .setPositiveButton(R.string.duplicates_delete_button, (dialog, which) ->
                        confirm(groups, selected))
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> finish())
                .setOnCancelListener(dialog -> finish())
                .create());
    }

    private void confirm(List<DuplicateFinder.Group> groups, boolean[] selected) {
        final ArrayList<Long> ids = new ArrayList<>();
        for (int i = 0; i < selected.length; i++) {
            if (selected[i]) {
                for (DuplicateFinder.Record duplicate : groups.get(i).duplicates) {
                    ids.add(duplicate.id);
                }
            }
        }
        if (ids.isEmpty()) {
            finish();
            return;
        }
        replaceDialog(new AlertDialog.Builder(this)
                .setTitle(R.string.duplicates_title)
                .setMessage(getResources().getQuantityString(
                        R.plurals.duplicates_confirm, ids.size(), ids.size()))
                .setPositiveButton(R.string.duplicates_delete_button, (dialog, which) ->
                        delete(ids))
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> finish())
                .setOnCancelListener(dialog -> finish())
                .create());
    }

    private void delete(List<Long> ids) {
        showProgress(R.string.duplicates_removing);
        mExecutor.execute(() -> {
            int deleted = 0;
            boolean failed = false;
            try {
                for (int start = 0; start < ids.size(); start += DELETE_BATCH_SIZE) {
                    final List<Long> batch = ids.subList(start,
                            Math.min(start + DELETE_BATCH_SIZE, ids.size()));
                    final ArrayList<ContentProviderOperation> operations = new ArrayList<>();
                    for (long id : batch) {
                        operations.add(ContentProviderOperation.newDelete(
                                ContentUris.withAppendedId(RawContacts.CONTENT_URI, id))
                                .build());
                    }
                    getContentResolver().applyBatch(ContactsContract.AUTHORITY, operations);
                    deleted += batch.size();
                }
            } catch (RemoteException | OperationApplicationException | RuntimeException e) {
                Log.e(TAG, "Can't delete duplicates", e);
                failed = true;
            }
            final String message = failed ? getString(R.string.duplicates_failed)
                    : getResources().getQuantityString(
                            R.plurals.duplicates_removed, deleted, deleted);
            mHandler.post(() -> finishWithMessage(message));
        });
    }

    private void showProgress(int messageId) {
        final View view = getLayoutInflater().inflate(R.layout.gist_backup_dialog, null);
        view.findViewById(R.id.gist_token).setVisibility(View.GONE);
        view.findViewById(R.id.gist_password).setVisibility(View.GONE);
        view.findViewById(R.id.gist_progress).setVisibility(View.VISIBLE);
        ((TextView) view.findViewById(R.id.gist_message)).setText(messageId);
        replaceDialog(new AlertDialog.Builder(this)
                .setTitle(R.string.duplicates_title)
                .setView(view)
                .setCancelable(false)
                .create());
    }

    private void replaceDialog(AlertDialog dialog) {
        if (mDialog != null) {
            mDialog.dismiss();
        }
        mDialog = dialog;
        dialog.show();
    }

    private void finishWithMessage(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        finish();
    }
}
