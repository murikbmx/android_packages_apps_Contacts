/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.contacts.duplicates;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Finds contact records that can go without losing anything. A record is a duplicate of another
 * one only if it has the same name and everything it holds is in that other record too, so two
 * people with the same name and different numbers stay apart.
 */
public final class DuplicateFinder {
    /** A photo that is there but can't be compared, it's treated as matching any other photo. */
    public static final String ANY_PHOTO = "*";

    // Numbers are compared by their last digits, so +7 701... and 8 701... match
    private static final int PHONE_DIGITS = 10;

    private static final Comparator<Record> BEST_FIRST = Comparator
            .comparingInt((Record r) -> -r.data.size())
            .thenComparing(r -> r.photoKey == null)
            .thenComparing(r -> r.removable)
            .thenComparing(r -> !r.splitName)
            .thenComparingLong(r -> r.id);

    /** A raw contact on the phone or a contact about to be imported. */
    public static final class Record {
        public final long id;
        public final String name;
        /** Everything the record holds other than its name and photo. */
        public final Set<String> data = new HashSet<>();
        /** Identifies the photo, null if there is none. */
        public String photoKey;
        /** Whether the given and family names are separate rather than all in a single field. */
        public boolean splitName;
        /** Whether the record may be dropped, otherwise it only serves as a match for others. */
        public boolean removable = true;
        /** A phone number to tell the record apart from others of the same name. */
        public String displayPhone;

        public Record(long id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    /** A record to keep and its duplicates. */
    public static final class Group {
        public final Record keeper;
        public final List<Record> duplicates = new ArrayList<>();

        Group(Record keeper) {
            this.keeper = keeper;
        }
    }

    private DuplicateFinder() {}

    public static List<Group> find(Collection<Record> records) {
        final Map<String, List<Record>> byName = new HashMap<>();
        for (Record record : records) {
            final String key = normalizeName(record.name);
            if (!key.isEmpty()) {
                byName.computeIfAbsent(key, k -> new ArrayList<>()).add(record);
            }
        }

        final List<Group> result = new ArrayList<>();
        for (List<Record> sameName : byName.values()) {
            sameName.sort(BEST_FIRST);
            final List<Group> kept = new ArrayList<>();
            for (Record record : sameName) {
                final Group group = record.removable ? findCovering(kept, record) : null;
                if (group != null) {
                    group.duplicates.add(record);
                } else {
                    kept.add(new Group(record));
                }
            }
            for (Group group : kept) {
                if (!group.duplicates.isEmpty()) {
                    result.add(group);
                }
            }
        }
        return result;
    }

    private static Group findCovering(List<Group> kept, Record record) {
        for (Group group : kept) {
            if (covers(group.keeper, record)) {
                return group;
            }
        }
        return null;
    }

    private static boolean covers(Record keeper, Record record) {
        if (!keeper.data.containsAll(record.data)) {
            return false;
        }
        if (record.photoKey == null) {
            return true;
        }
        return keeper.photoKey != null && (keeper.photoKey.equals(record.photoKey)
                || ANY_PHOTO.equals(keeper.photoKey) || ANY_PHOTO.equals(record.photoKey));
    }

    public static String normalizeName(String name) {
        return name == null ? "" : name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** Returns the key of a phone number, null if it has no digits. */
    public static String phoneKey(String number) {
        if (number == null) {
            return null;
        }
        final String digits = number.replaceAll("[^0-9]", "");
        return digits.isEmpty() ? null
                : "p:" + digits.substring(Math.max(0, digits.length() - PHONE_DIGITS));
    }

    /** Returns the key of an email address, null if it's blank. */
    public static String emailKey(String address) {
        return address == null || address.trim().isEmpty() ? null
                : "e:" + address.trim().toLowerCase(Locale.ROOT);
    }
}
