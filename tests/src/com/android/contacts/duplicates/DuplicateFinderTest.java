/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.contacts.duplicates;

import junit.framework.TestCase;

import java.util.Arrays;
import java.util.List;

public class DuplicateFinderTest extends TestCase {
    private static long sNextId = 1;

    private static DuplicateFinder.Record record(String name, String... numbers) {
        final DuplicateFinder.Record record = new DuplicateFinder.Record(sNextId++, name);
        for (String number : numbers) {
            record.data.add(DuplicateFinder.phoneKey(number));
        }
        return record;
    }

    private static List<DuplicateFinder.Group> find(DuplicateFinder.Record... records) {
        return DuplicateFinder.find(Arrays.asList(records));
    }

    public void testKeepsTheSplitNameOfIdenticalCopies() {
        final DuplicateFinder.Record whole = record("Ivan Petrov", "+77011112233");
        final DuplicateFinder.Record split = record("Ivan Petrov", "87011112233");
        split.splitName = true;

        final List<DuplicateFinder.Group> groups = find(whole, split);

        assertEquals(1, groups.size());
        assertSame(split, groups.get(0).keeper);
        assertEquals(List.of(whole), groups.get(0).duplicates);
    }

    public void testNameIsComparedWithoutCaseOrExtraSpaces() {
        assertEquals(1, find(record("Ivan  Petrov", "1"), record(" ivan petrov ", "1")).size());
    }

    public void testSameNameWithOtherNumberIsKept() {
        assertTrue(find(record("Ivan", "+77011112233"), record("Ivan", "+77019998877")).isEmpty());
    }

    public void testSameNumberWithOtherNameIsKept() {
        assertTrue(find(record("Ivan", "+77011112233"), record("Anna", "+77011112233")).isEmpty());
    }

    public void testRecordWithMoreNumbersCoversTheOthers() {
        final DuplicateFinder.Record one = record("Ivan", "+77011112233");
        final DuplicateFinder.Record two = record("Ivan", "+77011112233", "+77019998877");

        final List<DuplicateFinder.Group> groups = find(one, two);

        assertEquals(1, groups.size());
        assertSame(two, groups.get(0).keeper);
    }

    public void testRecordWithExtraDataIsKept() {
        final DuplicateFinder.Record note = record("Ivan", "+77011112233");
        note.data.add("x:note|text");
        final DuplicateFinder.Record starred = record("Ivan", "+77011112233");
        starred.data.add("s:starred");

        assertTrue(find(note, starred).isEmpty());
    }

    public void testRecordWithOtherPhotoIsKept() {
        final DuplicateFinder.Record first = record("Ivan", "1");
        first.photoKey = "a";
        final DuplicateFinder.Record second = record("Ivan", "1");
        second.photoKey = "b";

        assertTrue(find(first, second).isEmpty());
    }

    public void testPhotoIsNotLost() {
        final DuplicateFinder.Record plain = record("Ivan", "1");
        final DuplicateFinder.Record photo = record("Ivan", "1");
        photo.photoKey = "a";

        final List<DuplicateFinder.Group> groups = find(plain, photo);

        assertEquals(1, groups.size());
        assertSame(photo, groups.get(0).keeper);
    }

    public void testRecordsThatCantBeRemovedStay() {
        final DuplicateFinder.Record existing = record("Ivan", "1");
        existing.removable = false;
        final DuplicateFinder.Record imported = record("Ivan", "1");
        imported.splitName = true;

        final List<DuplicateFinder.Group> groups = find(existing, imported);

        assertEquals(1, groups.size());
        assertSame(existing, groups.get(0).keeper);
        assertEquals(List.of(imported), groups.get(0).duplicates);
    }

    public void testNewNumberIsImportedNextToExistingContact() {
        final DuplicateFinder.Record existing = record("Ivan", "1");
        existing.removable = false;

        assertTrue(find(existing, record("Ivan", "1", "2")).isEmpty());
    }

    public void testUnnamedRecordsAreLeftAlone() {
        assertTrue(find(record(null, "1"), record("", "1")).isEmpty());
    }

    public void testNumbersMatchByLastDigits() {
        assertEquals(DuplicateFinder.phoneKey("+7 (701) 111-22-33"),
                DuplicateFinder.phoneKey("87011112233"));
        assertNull(DuplicateFinder.phoneKey("no digits"));
        assertNull(DuplicateFinder.phoneKey(null));
    }
}
