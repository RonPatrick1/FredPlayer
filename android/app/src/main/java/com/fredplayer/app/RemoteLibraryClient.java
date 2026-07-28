package com.fredplayer.app;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

final class RemoteLibraryClient {
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 15000;
    // This request goes through nginx (proxy_read_timeout), so it must stay
    // above that ceiling — bumped alongside it to give handle_fredplayer_ask's
    // up-to-3 retry attempts room to actually finish.
    private static final int ASK_LIAM_READ_TIMEOUT_MS = 620000;

    private RemoteLibraryClient() {
    }

    static boolean isRemote(String uriString) {
        return uriString != null
                && (uriString.startsWith("http://") || uriString.startsWith("https://"));
    }

    static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null) {
            return "";
        }
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    static String buildStreamUrl(String baseUrl, String relativePath) {
        return normalizeBaseUrl(baseUrl) + "/stream/" + encodePath(relativePath);
    }

    private static String encodePath(String relativePath) {
        String[] segments = relativePath.split("/", -1);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                builder.append('/');
            }
            builder.append(Uri.encode(segments[i]));
        }
        return builder.toString();
    }

    static JSONArray fetchLibrary(String baseUrl, String token) throws IOException, JSONException {
        String body = get(normalizeBaseUrl(baseUrl) + "/api/library", token);
        return new JSONArray(body);
    }

    static JSONArray fetchPlaylists(String baseUrl, String token) throws IOException, JSONException {
        String body = get(normalizeBaseUrl(baseUrl) + "/api/playlists", token);
        return new JSONArray(body);
    }

    static JSONArray fetchPlaylistTracks(String baseUrl, String token, String name) throws IOException, JSONException {
        String body = get(normalizeBaseUrl(baseUrl) + "/api/playlists/" + Uri.encode(name), token);
        JSONObject playlist = new JSONObject(body);
        return playlist.getJSONArray("tracks");
    }

    static void sharePlaylist(String baseUrl, String token, String name, JSONArray tracks)
            throws IOException, JSONException {
        JSONObject requestBody = new JSONObject();
        requestBody.put("name", name);
        requestBody.put("tracks", tracks);
        post(
                normalizeBaseUrl(baseUrl) + "/api/playlists",
                token,
                requestBody.toString(),
                READ_TIMEOUT_MS);
    }

    static String serverPath(String baseUrl, String uriString) {
        if (uriString == null) {
            return null;
        }
        String prefix = normalizeBaseUrl(baseUrl) + "/stream/";
        if (!uriString.startsWith(prefix)) {
            return null;
        }
        String encodedPath = uriString.substring(prefix.length());
        return encodedPath.isEmpty() ? null : Uri.decode(encodedPath);
    }

    static JSONObject askLiam(String baseUrl, String token, String deviceId, String message)
            throws IOException, JSONException {
        JSONObject requestBody = new JSONObject();
        try {
            requestBody.put("device_id", deviceId);
            requestBody.put("message", message);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        String body = post(
                normalizeBaseUrl(baseUrl) + "/api/ask-liam",
                token,
                requestBody.toString(),
                ASK_LIAM_READ_TIMEOUT_MS);
        return new JSONObject(body);
    }

    private static String get(String urlString, String token) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Authorization", "Bearer " + token);
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("Server returned HTTP " + code);
            }
            return readAll(connection.getInputStream());
        } finally {
            connection.disconnect();
        }
    }

    private static String post(String urlString, String token, String jsonBody, int readTimeoutMs)
            throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(readTimeoutMs);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setDoOutput(true);
        byte[] body = jsonBody.getBytes(StandardCharsets.UTF_8);
        try {
            connection.getOutputStream().write(body);
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                String errorBody = readAll(connection.getErrorStream());
                throw new IOException("Server returned HTTP " + code + ": " + errorBody);
            }
            return readAll(connection.getInputStream());
        } finally {
            connection.disconnect();
        }
    }

    private static String readAll(InputStream input) throws IOException {
        if (input == null) {
            return "";
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = input.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toString(StandardCharsets.UTF_8.name());
    }
}
