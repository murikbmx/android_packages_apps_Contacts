/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.contacts.duplicates;

import android.content.ContentResolver;
import android.database.Cursor;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.Photo;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.RawContacts;
import android.text.TextUtils;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Reads raw contacts into the records {@link DuplicateFinder} works on. */
public final class RawContactRecords {
    /** Selects the accounts whose raw contacts are read. */
    public interface AccountFilter {
        boolean accept(String name, String type, String dataSet);
    }

    private static final String[] RAW_CONTACT_PROJECTION = {
            RawContacts._ID,
            RawContacts.ACCOUNT_NAME,
            RawContacts.ACCOUNT_TYPE,
            RawContacts.DATA_SET,
            RawContacts.DISPLAY_NAME_PRIMARY,
            RawContacts.STARRED,
            RawContacts.CUSTOM_RINGTONE,
            RawContacts.SEND_TO_VOICEMAIL,
    };

    private static final String[] DATA_PROJECTION = {
            Data.RAW_CONTACT_ID, Data.MIMETYPE,
            Data.DATA1, Data.DATA2, Data.DATA3, Data.DATA4, Data.DATA5, Data.DATA6, Data.DATA7,
            Data.DATA8, Data.DATA9, Data.DATA10, Data.DATA11, Data.DATA12, Data.DATA13,
            Data.DATA14,
    };
    private static final int FIRST_DATA_COLUMN = 2;

    private RawContactRecords() {}

    public static Map<Long, DuplicateFinder.Record> load(ContentResolver resolver,
            AccountFilter filter) {
        final Map<Long, DuplicateFinder.Record> records = new HashMap<>();
        try (Cursor cursor = resolver.query(RawContacts.CONTENT_URI, RAW_CONTACT_PROJECTION,
                RawContacts.DELETED + "=0", null, null)) {
            while (cursor != null && cursor.moveToNext()) {
                if (filter.accept(cursor.getString(1), cursor.getString(2),
                        cursor.getString(3))) {
                    final DuplicateFinder.Record record =
                            new DuplicateFinder.Record(cursor.getLong(0), cursor.getString(4));
                    // What a copy would lose when it goes
                    if (cursor.getInt(5) != 0) {
                        record.data.add("s:starred");
                    }
                    if (!cursor.isNull(6)) {
                        record.data.add("s:ringtone:" + cursor.getString(6));
                    }
                    if (cursor.getInt(7) != 0) {
                        record.data.add("s:voicemail");
                    }
                    records.put(record.id, record);
                }
            }
        }
        if (records.isEmpty()) {
            return records;
        }
        loadData(resolver, records);
        loadPhotos(resolver, records);
        return records;
    }

    private static void loadData(ContentResolver resolver,
            Map<Long, DuplicateFinder.Record> records) {
        try (Cursor cursor = resolver.query(Data.CONTENT_URI, DATA_PROJECTION,
                Data.MIMETYPE + "!=?", new String[] {Photo.CONTENT_ITEM_TYPE}, null)) {
            while (cursor != null && cursor.moveToNext()) {
                final DuplicateFinder.Record record = records.get(cursor.getLong(0));
                if (record != null) {
                    addData(record, cursor);
                }
            }
        }
    }

    private static void addData(DuplicateFinder.Record record, Cursor cursor) {
        final String mimeType = cursor.getString(1);
        final String data1 = cursor.getString(FIRST_DATA_COLUMN);
        if (Phone.CONTENT_ITEM_TYPE.equals(mimeType)) {
            final String key = DuplicateFinder.phoneKey(data1);
            if (key != null) {
                record.data.add(key);
                if (record.displayPhone == null) {
                    record.displayPhone = data1;
                }
                return;
            }
        } else if (Email.CONTENT_ITEM_TYPE.equals(mimeType)) {
            final String key = DuplicateFinder.emailKey(data1);
            if (key != null) {
                record.data.add(key);
            }
            return;
        } else if (StructuredName.CONTENT_ITEM_TYPE.equals(mimeType)) {
            final int given = cursor.getColumnIndexOrThrow(StructuredName.GIVEN_NAME);
            final int family = cursor.getColumnIndexOrThrow(StructuredName.FAMILY_NAME);
            record.splitName = !TextUtils.isEmpty(cursor.getString(given))
                    && !TextUtils.isEmpty(cursor.getString(family));
            final String phonetic = TextUtils.join("|", new String[] {
                    nullToEmpty(cursor.getString(
                            cursor.getColumnIndexOrThrow(StructuredName.PHONETIC_GIVEN_NAME))),
                    nullToEmpty(cursor.getString(
                            cursor.getColumnIndexOrThrow(StructuredName.PHONETIC_MIDDLE_NAME))),
                    nullToEmpty(cursor.getString(
                            cursor.getColumnIndexOrThrow(StructuredName.PHONETIC_FAMILY_NAME))),
            });
            if (!"||".equals(phonetic)) {
                record.data.add("n:" + phonetic);
            }
            return;
        }

        // Anything else has to match in full
        final StringBuilder key = new StringBuilder();
        boolean hasValue = false;
        for (int i = FIRST_DATA_COLUMN; i < DATA_PROJECTION.length; i++) {
            final String value = cursor.getType(i) == Cursor.FIELD_TYPE_BLOB
                    ? String.valueOf(Arrays.hashCode(cursor.getBlob(i)))
                    : cursor.getString(i);
            hasValue |= !TextUtils.isEmpty(value);
            key.append('|').append(nullToEmpty(value).trim().toLowerCase(Locale.ROOT));
        }
        if (hasValue) {
            record.data.add("x:" + mimeType + key);
        }
    }

    private static void loadPhotos(ContentResolver resolver,
            Map<Long, DuplicateFinder.Record> records) {
        try (Cursor cursor = resolver.query(Data.CONTENT_URI,
                new String[] {Data.RAW_CONTACT_ID, Photo.PHOTO, Photo.PHOTO_FILE_ID},
                Data.MIMETYPE + "=?", new String[] {Photo.CONTENT_ITEM_TYPE}, null)) {
            while (cursor != null && cursor.moveToNext()) {
                final DuplicateFinder.Record record = records.get(cursor.getLong(0));
                if (record == null) {
                    continue;
                }
                final byte[] thumbnail = cursor.getBlob(1);
                if (thumbnail != null) {
                    record.photoKey = String.valueOf(Arrays.hashCode(thumbnail));
                } else if (!cursor.isNull(2)) {
                    record.photoKey = "file:" + cursor.getLong(2);
                }
            }
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
