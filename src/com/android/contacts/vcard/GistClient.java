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

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Minimal GitHub Gist API client for the contacts backup. The backup lives in its own secret
 * gist, found by its description, so it never touches other gists of the account.
 */
final class GistClient {
    static final String FILE_PLAIN = "contacts.vcf";
    static final String FILE_ENCRYPTED = "contacts.vcf.enc";

    private static final String API = "https://api.github.com";
    private static final String DESCRIPTION = "Contacts backup (LineageOS)";
    private static final int TIMEOUT_MS = 30000;
    private static final int MAX_PAGES = 10;

    /** An HTTP error from GitHub. */
    static class HttpException extends IOException {
        final int code;

        HttpException(int code, String message) {
            super("HTTP " + code + ": " + message);
            this.code = code;
        }
    }

    /** A backup downloaded from the gist. */
    static class Backup {
        final String content;
        final boolean encrypted;

        Backup(String content, boolean encrypted) {
            this.content = content;
            this.encrypted = encrypted;
        }
    }

    private final String mToken;

    GistClient(String token) {
        mToken = token;
    }

    /**
     * Returns the id of the backup gist, or null if there is none. {@code knownId} is the id
     * found last time, which saves listing all gists.
     */
    String findBackupGist(String knownId) throws IOException, JSONException {
        if (knownId != null) {
            try {
                if (isBackupGist(new JSONObject(request("GET", API + "/gists/" + knownId, null)))) {
                    return knownId;
                }
            } catch (HttpException e) {
                if (e.code != HttpURLConnection.HTTP_NOT_FOUND) {
                    throw e;
                }
            }
        }
        for (int page = 1; page <= MAX_PAGES; page++) {
            final JSONArray gists = new JSONArray(
                    request("GET", API + "/gists?per_page=100&page=" + page, null));
            for (int i = 0; i < gists.length(); i++) {
                final JSONObject gist = gists.getJSONObject(i);
                if (isBackupGist(gist)) {
                    return gist.getString("id");
                }
            }
            if (gists.length() < 100) {
                break;
            }
        }
        return null;
    }

    /** Uploads the backup and returns the gist id. */
    String upload(String gistId, String content, boolean encrypted)
            throws IOException, JSONException {
        final String fileName = encrypted ? FILE_ENCRYPTED : FILE_PLAIN;
        final JSONObject files = new JSONObject()
                .put(fileName, new JSONObject().put("content", content));
        if (gistId == null) {
            final JSONObject body = new JSONObject()
                    .put("description", DESCRIPTION)
                    .put("public", false)
                    .put("files", files);
            return new JSONObject(request("POST", API + "/gists", body.toString()))
                    .getString("id");
        }
        // Drop the other variant, so an old unencrypted copy doesn't stay next to an encrypted one
        final String otherName = encrypted ? FILE_PLAIN : FILE_ENCRYPTED;
        final JSONObject gist = new JSONObject(request("GET", API + "/gists/" + gistId, null));
        if (gist.getJSONObject("files").has(otherName)) {
            files.put(otherName, JSONObject.NULL);
        }
        request("PATCH", API + "/gists/" + gistId,
                new JSONObject().put("files", files).toString());
        return gistId;
    }

    Backup download(String gistId) throws IOException, JSONException {
        final JSONObject files = new JSONObject(request("GET", API + "/gists/" + gistId, null))
                .getJSONObject("files");
        final boolean encrypted = files.has(FILE_ENCRYPTED);
        final JSONObject file = files.getJSONObject(encrypted ? FILE_ENCRYPTED : FILE_PLAIN);
        // Large files are truncated in the API response, the raw URL always has all of it
        final String content = file.optBoolean("truncated", false) || !file.has("content")
                ? request("GET", file.getString("raw_url"), null)
                : file.getString("content");
        return new Backup(content, encrypted);
    }

    private static boolean isBackupGist(JSONObject gist) throws JSONException {
        final JSONObject files = gist.optJSONObject("files");
        return DESCRIPTION.equals(gist.optString("description")) && files != null
                && (files.has(FILE_PLAIN) || files.has(FILE_ENCRYPTED));
    }

    private String request(String method, String url, String body) throws IOException {
        final HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Authorization", "Bearer " + mToken);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            connection.setRequestProperty("User-Agent", "LineageOS-Contacts");
            if (body != null) {
                final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(bytes.length);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(bytes);
                }
            }
            final int code = connection.getResponseCode();
            if (code >= 400) {
                throw new HttpException(code, read(connection.getErrorStream()));
            }
            return read(connection.getInputStream());
        } finally {
            connection.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        try (InputStream stream = in) {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            final byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                bytes.write(buffer, 0, count);
            }
            return bytes.toString(StandardCharsets.UTF_8.name());
        }
    }
}
