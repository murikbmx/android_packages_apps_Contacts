/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.contacts.vcard;

import android.content.Context;
import android.text.TextUtils;

import com.android.contacts.duplicates.DuplicateFinder;
import com.android.contacts.duplicates.RawContactRecords;
import com.android.contacts.model.AccountTypeManager;
import com.android.vcard.VCardEntry;
import com.android.vcard.VCardEntryHandler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Goes through the entries of a vCard file ahead of importing it to find the ones to skip, as
 * they repeat another entry of the file or a contact that's already there. Done ahead of the
 * import so the best of the copies in the file is the one that gets imported, wherever it is.
 */
class VCardDuplicateScanner implements VCardEntryHandler {
    private final List<DuplicateFinder.Record> mEntries = new ArrayList<>();

    @Override
    public void onStart() {
        // The parser starts over when it retries with another vCard version
        mEntries.clear();
    }

    @Override
    public void onEnd() {}

    @Override
    public void onEntryCreated(VCardEntry entry) {
        mEntries.add(toRecord(entry, mEntries.size()));
    }

    /** Returns the positions in the file of the entries to skip. */
    Set<Integer> findDuplicates(Context context) {
        final List<DuplicateFinder.Record> records = new ArrayList<>(mEntries);
        // Read-only accounts such as messengers don't count: their contacts only exist
        // alongside one saved on the phone, so they aren't a copy of what is being imported.
        final AccountTypeManager accountTypes = AccountTypeManager.getInstance(context);
        for (DuplicateFinder.Record record : RawContactRecords.load(context.getContentResolver(),
                (name, type, dataSet) ->
                        accountTypes.getAccountType(type, dataSet).areContactsWritable())
                .values()) {
            record.removable = false;
            if (record.photoKey != null) {
                // A photo stored by the provider never has the same bytes as the one in a file
                record.photoKey = DuplicateFinder.ANY_PHOTO;
            }
            records.add(record);
        }

        final Set<Integer> duplicates = new HashSet<>();
        for (DuplicateFinder.Group group : DuplicateFinder.find(records)) {
            for (DuplicateFinder.Record duplicate : group.duplicates) {
                duplicates.add((int) duplicate.id);
            }
        }
        return duplicates;
    }

    private static DuplicateFinder.Record toRecord(VCardEntry entry, int position) {
        final DuplicateFinder.Record record =
                new DuplicateFinder.Record(position, entry.getDisplayName());
        record.splitName = !TextUtils.isEmpty(entry.getNameData().getGiven())
                && !TextUtils.isEmpty(entry.getNameData().getFamily());
        if (entry.getPhotoList() != null && !entry.getPhotoList().isEmpty()) {
            record.photoKey = String.valueOf(
                    Arrays.hashCode(entry.getPhotoList().get(0).getBytes()));
        }
        entry.iterateAllData(new VCardEntry.EntryElementIterator() {
            @Override
            public void onIterationStarted() {}

            @Override
            public void onIterationEnded() {}

            @Override
            public void onElementGroupStarted(VCardEntry.EntryLabel label) {}

            @Override
            public void onElementGroupEnded() {}

            @Override
            public boolean onElement(VCardEntry.EntryElement element) {
                addElement(record, element);
                return true;
            }
        });
        return record;
    }

    private static void addElement(DuplicateFinder.Record record,
            VCardEntry.EntryElement element) {
        final String key;
        if (element instanceof VCardEntry.NameData || element instanceof VCardEntry.PhotoData) {
            return;
        } else if (element instanceof VCardEntry.PhoneData) {
            key = DuplicateFinder.phoneKey(((VCardEntry.PhoneData) element).getNumber());
        } else if (element instanceof VCardEntry.EmailData) {
            key = DuplicateFinder.emailKey(((VCardEntry.EmailData) element).getAddress());
        } else {
            // These never match what the provider holds, so an entry with any of them is only
            // skipped if another entry of the file has it too
            key = "v:" + element.getEntryLabel() + ":" + element;
        }
        if (key != null) {
            record.data.add(key);
        }
    }
}
