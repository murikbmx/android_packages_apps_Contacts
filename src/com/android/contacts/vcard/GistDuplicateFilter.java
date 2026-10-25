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

import android.content.ContentResolver;
import android.database.Cursor;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.Contacts;
import android.provider.ContactsContract.Data;
import android.util.Log;

import com.android.vcard.VCardEntry;
import com.android.vcard.VCardEntryConstructor;
import com.android.vcard.VCardEntryHandler;
import com.android.vcard.VCardParser;
import com.android.vcard.VCardParser_V21;
import com.android.vcard.VCardParser_V30;
import com.android.vcard.VCardParser_V40;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Drops the vCards of contacts that are already on the phone, so importing a backup again
 * doesn't duplicate them. A contact is already there when one with the same name shares a
 * phone number or email, or has the same name and neither has any.
 */
final class GistDuplicateFilter {
    private static final String TAG = "GistBackup";

    private static final Pattern VCARD = Pattern.compile(
            "^BEGIN:VCARD\\s*$.*?^END:VCARD\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL);
    private static final Pattern VERSION = Pattern.compile(
            "^VERSION:\\s*(\\S+)", Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    // Numbers are compared by their last digits, so +7 701... and 8 701... match
    private static final int PHONE_DIGITS = 10;

    /** The vCards to import and how many were skipped. */
    static class Result {
        final String vcards;
        final int imported;
        final int skipped;

        Result(String vcards, int imported, int skipped) {
            this.vcards = vcards;
            this.imported = imported;
            this.skipped = skipped;
        }
    }

    private GistDuplicateFilter() {}

    static Result filter(ContentResolver resolver, String vcards, int vcardType) {
        final List<String> blocks = new ArrayList<>();
        final Matcher matcher = VCARD.matcher(vcards);
        while (matcher.find()) {
            blocks.add(matcher.group());
        }

        final Map<String, Set<String>> existing = loadExisting(resolver);
        final StringBuilder kept = new StringBuilder();
        int skipped = 0;
        for (String block : blocks) {
            final VCardEntry entry = parse(block, vcardType);
            if (entry != null && isDuplicate(entry, existing)) {
                skipped++;
                continue;
            }
            kept.append(block).append("\r\n");
        }
        return new Result(kept.toString(), blocks.size() - skipped, skipped);
    }

    private static boolean isDuplicate(VCardEntry entry, Map<String, Set<String>> existing) {
        final Set<String> keys = existing.get(normalizeName(entry.getDisplayName()));
        if (keys == null) {
            return false;
        }
        final Set<String> entryKeys = keysOf(entry);
        if (entryKeys.isEmpty()) {
            return true;
        }
        for (String key : entryKeys) {
            if (keys.contains(key)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> keysOf(VCardEntry entry) {
        final Set<String> keys = new HashSet<>();
        if (entry.getPhoneList() != null) {
            for (VCardEntry.PhoneData phone : entry.getPhoneList()) {
                addPhone(keys, phone.getNumber());
            }
        }
        if (entry.getEmailList() != null) {
            for (VCardEntry.EmailData email : entry.getEmailList()) {
                addEmail(keys, email.getAddress());
            }
        }
        return keys;
    }

    /** Maps the name of every contact on the phone to its phone numbers and emails. */
    private static Map<String, Set<String>> loadExisting(ContentResolver resolver) {
        final Map<String, Set<String>> existing = new HashMap<>();
        try (Cursor cursor = resolver.query(Contacts.CONTENT_URI,
                new String[] {Contacts.DISPLAY_NAME}, null, null, null)) {
            while (cursor != null && cursor.moveToNext()) {
                existing.computeIfAbsent(normalizeName(cursor.getString(0)),
                        name -> new HashSet<>());
            }
        }
        try (Cursor cursor = resolver.query(Data.CONTENT_URI,
                new String[] {Data.DISPLAY_NAME, Data.MIMETYPE, Data.DATA1},
                Data.MIMETYPE + " IN (?, ?)",
                new String[] {Phone.CONTENT_ITEM_TYPE, Email.CONTENT_ITEM_TYPE}, null)) {
            while (cursor != null && cursor.moveToNext()) {
                final Set<String> keys = existing.computeIfAbsent(
                        normalizeName(cursor.getString(0)), name -> new HashSet<>());
                if (Phone.CONTENT_ITEM_TYPE.equals(cursor.getString(1))) {
                    addPhone(keys, cursor.getString(2));
                } else {
                    addEmail(keys, cursor.getString(2));
                }
            }
        }
        return existing;
    }

    private static VCardEntry parse(String block, int vcardType) {
        final Matcher version = VERSION.matcher(block);
        final String v = version.find() ? version.group(1) : "2.1";
        final VCardParser parser = v.startsWith("4") ? new VCardParser_V40(vcardType)
                : v.startsWith("3") ? new VCardParser_V30(vcardType)
                : new VCardParser_V21(vcardType);
        final VCardEntryConstructor constructor = new VCardEntryConstructor(vcardType);
        final VCardEntry[] result = new VCardEntry[1];
        constructor.addEntryHandler(new VCardEntryHandler() {
            @Override
            public void onStart() {}

            @Override
            public void onEntryCreated(VCardEntry entry) {
                if (result[0] == null) {
                    result[0] = entry;
                }
            }

            @Override
            public void onEnd() {}
        });
        parser.addInterpreter(constructor);
        try {
            parser.parse(new ByteArrayInputStream(block.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            // Imported as is rather than lost
            Log.w(TAG, "Can't parse a vCard for the duplicate check", e);
            return null;
        }
        return result[0];
    }

    private static String normalizeName(String name) {
        return name == null ? "" : name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static void addPhone(Set<String> keys, String number) {
        if (number == null) {
            return;
        }
        final String digits = number.replaceAll("[^0-9]", "");
        if (!digits.isEmpty()) {
            keys.add("p:" + digits.substring(Math.max(0, digits.length() - PHONE_DIGITS)));
        }
    }

    private static void addEmail(Set<String> keys, String address) {
        if (address != null && !address.trim().isEmpty()) {
            keys.add("e:" + address.trim().toLowerCase(Locale.ROOT));
        }
    }
}
