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

import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts a contacts backup with a password: AES-256-GCM with a key derived by
 * PBKDF2-HMAC-SHA256, stored as JSON since gists only hold text.
 */
final class GistCrypto {
    static final String FORMAT = "lineage-contacts-backup";

    private static final int VERSION = 1;
    private static final String KDF = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 310000;
    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    /** Thrown when the password doesn't match the backup. */
    static class WrongPasswordException extends Exception {}

    private GistCrypto() {}

    static String encrypt(String plainText, String password)
            throws GeneralSecurityException, JSONException {
        final SecureRandom random = new SecureRandom();
        final byte[] salt = new byte[SALT_BYTES];
        final byte[] iv = new byte[IV_BYTES];
        random.nextBytes(salt);
        random.nextBytes(iv);

        final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt, ITERATIONS),
                new GCMParameterSpec(TAG_BITS, iv));
        final byte[] data = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

        return new JSONObject()
                .put("format", FORMAT)
                .put("version", VERSION)
                .put("kdf", KDF)
                .put("iterations", ITERATIONS)
                .put("salt", encode(salt))
                .put("iv", encode(iv))
                .put("data", encode(data))
                .toString();
    }

    static String decrypt(String encrypted, String password)
            throws GeneralSecurityException, JSONException, WrongPasswordException {
        final JSONObject json = new JSONObject(encrypted);
        if (!FORMAT.equals(json.optString("format"))) {
            throw new JSONException("Not a contacts backup");
        }
        final byte[] salt = decode(json.getString("salt"));
        final byte[] iv = decode(json.getString("iv"));
        final byte[] data = decode(json.getString("data"));

        final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,
                deriveKey(password, salt, json.optInt("iterations", ITERATIONS)),
                new GCMParameterSpec(TAG_BITS, iv));
        try {
            return new String(cipher.doFinal(data), StandardCharsets.UTF_8);
        } catch (javax.crypto.AEADBadTagException e) {
            throw new WrongPasswordException();
        }
    }

    private static SecretKeySpec deriveKey(String password, byte[] salt, int iterations)
            throws GeneralSecurityException {
        final PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS);
        try {
            final byte[] key = SecretKeyFactory.getInstance(KDF).generateSecret(spec).getEncoded();
            return new SecretKeySpec(key, "AES");
        } finally {
            spec.clearPassword();
        }
    }

    private static String encode(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    private static byte[] decode(String text) {
        return Base64.decode(text, Base64.NO_WRAP);
    }
}
