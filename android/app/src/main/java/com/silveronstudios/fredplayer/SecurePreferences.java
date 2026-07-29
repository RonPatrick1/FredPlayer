package com.silveronstudios.fredplayer;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class SecurePreferences {
    private static final String PREFS = "fred_player_secure";
    private static final String KEY_ALIAS = "fredplayer_server_credentials";
    private static final String KEY_SERVER_TOKEN = "server_token";
    private static final String KEY_SERVER_TOKEN_IV = "server_token_iv";

    private SecurePreferences() {
    }

    static synchronized String loadServerToken(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String encoded = preferences.getString(KEY_SERVER_TOKEN, "");
        String encodedIv = preferences.getString(KEY_SERVER_TOKEN_IV, "");
        if (encoded == null || encoded.isEmpty() || encodedIv == null || encodedIv.isEmpty()) {
            return "";
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    key(),
                    new GCMParameterSpec(128, Base64.decode(encodedIv, Base64.NO_WRAP)));
            byte[] plaintext = cipher.doFinal(Base64.decode(encoded, Base64.NO_WRAP));
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            preferences.edit().remove(KEY_SERVER_TOKEN).remove(KEY_SERVER_TOKEN_IV).apply();
            return "";
        }
    }

    static synchronized void saveServerToken(Context context, String token) {
        String value = token == null ? "" : token.trim();
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (value.isEmpty()) {
            preferences.edit().remove(KEY_SERVER_TOKEN).remove(KEY_SERVER_TOKEN_IV).apply();
            return;
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            preferences.edit()
                    .putString(KEY_SERVER_TOKEN, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
                    .putString(KEY_SERVER_TOKEN_IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                    .apply();
        } catch (Exception e) {
            throw new IllegalStateException("Could not protect the Fred Server token", e);
        }
    }

    private static SecretKey key() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        java.security.Key existing = keyStore.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) {
            return (SecretKey) existing;
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }
}
