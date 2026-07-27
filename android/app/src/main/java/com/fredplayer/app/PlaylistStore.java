package com.fredplayer.app;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class PlaylistStore {
    private static final String PREFS = "fred_player";
    private static final String KEY_PLAYLIST = "playlist";
    private static final String KEY_PLAYLISTS = "named_playlists";
    private static final String KEY_ACTIVE_PLAYLIST = "active_playlist";
    static final String DEFAULT_PLAYLIST_NAME = "My Playlist";
    private static final String KEY_OUTPUT_LEVEL = "output_level";
    private static final String KEY_LEVELING_STRENGTH = "leveling_strength";
    private static final String KEY_ANALYSIS_SECONDS = "analysis_seconds";
    private static final String KEY_LEVEL_ATTACK_MS = "level_attack_ms";
    private static final String KEY_LEVEL_RELEASE_MS = "level_release_ms";
    private static final String KEY_GAIN_DOWN_MS = "gain_down_ms";
    private static final String KEY_GAIN_UP_MS = "gain_up_ms";
    private static final String KEY_COMPRESSOR_THRESHOLD = "compressor_threshold";
    private static final String KEY_OUTPUT_CEILING = "output_ceiling";
    private static final String KEY_VISUAL_FPS = "visual_fps";
    private static final String KEY_VISUAL_WAVEFORM_MS = "visual_waveform_ms";
    private static final String KEY_VISUAL_FFT_SIZE = "visual_fft_size";
    private static final String KEY_VISUAL_FFT_BARS = "visual_fft_bars";
    private static final String KEY_VISUAL_SMOOTHING = "visual_smoothing";
    private static final String KEY_VISUAL_LOG_SCALE = "visual_log_scale";
    private static final String KEY_BLUETOOTH_VISUAL_DELAYS = "bluetooth_visual_delays";
    private static final String KEY_BLUETOOTH_VISUAL_DELAY_LABELS =
            "bluetooth_visual_delay_labels";
    private static final String KEY_SHUFFLE_ENABLED = "shuffle_enabled";
    private static final String KEY_SERVER_BASE_URL = "server_base_url";
    private static final String KEY_SERVER_TOKEN = "server_token";
    private static final String KEY_TRACK_METADATA = "track_metadata";

    private PlaylistStore() {
    }

    static ArrayList<String> loadPlaylist(Context context) {
        LinkedHashMap<String, ArrayList<String>> playlists = loadPlaylists(context);
        String activeName = loadActivePlaylistName(context, playlists);
        ArrayList<String> active = playlists.get(activeName);
        return active == null ? new ArrayList<>() : new ArrayList<>(active);
    }

    static LinkedHashMap<String, ArrayList<String>> loadPlaylists(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        LinkedHashMap<String, ArrayList<String>> result = new LinkedHashMap<>();
        String savedPlaylists = prefs.getString(KEY_PLAYLISTS, "");
        if (savedPlaylists != null && !savedPlaylists.isEmpty()) {
            try {
                JSONArray entries = new JSONArray(savedPlaylists);
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject entry = entries.optJSONObject(i);
                    if (entry == null) {
                        continue;
                    }
                    String name = entry.optString("name", "").trim();
                    if (name.isEmpty() || result.containsKey(name)) {
                        continue;
                    }
                    result.put(name, readTrackArray(entry.optJSONArray("tracks")));
                }
            } catch (JSONException ignored) {
            }
        }

        if (result.isEmpty()) {
            String legacy = prefs.getString(KEY_PLAYLIST, "[]");
            try {
                result.put(DEFAULT_PLAYLIST_NAME, readTrackArray(new JSONArray(legacy)));
            } catch (JSONException ignored) {
                result.put(DEFAULT_PLAYLIST_NAME, new ArrayList<>());
            }
            savePlaylists(context, result);
            saveActivePlaylistName(context, DEFAULT_PLAYLIST_NAME);
        }
        return result;
    }

    static String loadActivePlaylistName(
            Context context,
            LinkedHashMap<String, ArrayList<String>> playlists) {
        String saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ACTIVE_PLAYLIST, DEFAULT_PLAYLIST_NAME);
        if (saved != null && playlists.containsKey(saved)) {
            return saved;
        }
        return playlists.isEmpty() ? DEFAULT_PLAYLIST_NAME : playlists.keySet().iterator().next();
    }

    static void saveActivePlaylistName(Context context, String name) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ACTIVE_PLAYLIST, name)
                .apply();
    }

    static void savePlaylists(
            Context context,
            Map<String, ? extends List<String>> playlists) {
        JSONArray entries = new JSONArray();
        for (Map.Entry<String, ? extends List<String>> playlist : playlists.entrySet()) {
            String name = playlist.getKey() == null ? "" : playlist.getKey().trim();
            if (name.isEmpty()) {
                continue;
            }
            JSONObject entry = new JSONObject();
            try {
                entry.put("name", name);
                entry.put("tracks", writeTrackArray(playlist.getValue()));
                entries.put(entry);
            } catch (JSONException ignored) {
            }
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PLAYLISTS, entries.toString())
                .apply();
    }

    private static ArrayList<String> readTrackArray(JSONArray array) {
        ArrayList<String> result = new ArrayList<>();
        if (array == null) {
            return result;
        }
        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i, "");
            if (!value.isEmpty()) {
                result.add(value);
            }
        }
        return result;
    }

    private static JSONArray writeTrackArray(List<String> playlist) {
        JSONArray array = new JSONArray();
        if (playlist == null) {
            return array;
        }
        for (String item : playlist) {
            array.put(item);
        }
        return array;
    }

    static void savePlaylist(Context context, List<String> playlist) {
        LinkedHashMap<String, ArrayList<String>> playlists = loadPlaylists(context);
        String activeName = loadActivePlaylistName(context, playlists);
        playlists.put(activeName, new ArrayList<>(playlist));
        savePlaylists(context, playlists);
        // Keep the original value updated so existing installs can safely roll back.
        JSONArray legacy = writeTrackArray(playlist);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PLAYLIST, legacy.toString())
                .apply();
    }

    static float loadOutputLevel(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getFloat(KEY_OUTPUT_LEVEL, 0.55f);
    }

    static void saveOutputLevel(Context context, float value) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putFloat(KEY_OUTPUT_LEVEL, clamp(value, 0.1f, 1.0f))
                .apply();
    }

    static float loadLevelingStrength(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getFloat(KEY_LEVELING_STRENGTH, 0.9f);
    }

    static void saveLevelingStrength(Context context, float value) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putFloat(KEY_LEVELING_STRENGTH, clamp(value, 0.0f, 1.0f))
                .apply();
    }

    static LevelingSettings loadLevelingSettings(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new LevelingSettings(
                prefs.getFloat(KEY_ANALYSIS_SECONDS, LevelingSettings.DEFAULT_ANALYSIS_SECONDS),
                prefs.getFloat(KEY_LEVEL_ATTACK_MS, LevelingSettings.DEFAULT_LEVEL_ATTACK_MS),
                prefs.getFloat(KEY_LEVEL_RELEASE_MS, LevelingSettings.DEFAULT_LEVEL_RELEASE_MS),
                prefs.getFloat(KEY_GAIN_DOWN_MS, LevelingSettings.DEFAULT_GAIN_DOWN_MS),
                prefs.getFloat(KEY_GAIN_UP_MS, LevelingSettings.DEFAULT_GAIN_UP_MS),
                prefs.getFloat(KEY_COMPRESSOR_THRESHOLD, LevelingSettings.DEFAULT_COMPRESSOR_THRESHOLD),
                prefs.getFloat(KEY_OUTPUT_CEILING, LevelingSettings.DEFAULT_OUTPUT_CEILING));
    }

    static void saveLevelingSettings(Context context, LevelingSettings settings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putFloat(KEY_ANALYSIS_SECONDS, settings.analysisSeconds)
                .putFloat(KEY_LEVEL_ATTACK_MS, settings.levelAttackMs)
                .putFloat(KEY_LEVEL_RELEASE_MS, settings.levelReleaseMs)
                .putFloat(KEY_GAIN_DOWN_MS, settings.gainDownMs)
                .putFloat(KEY_GAIN_UP_MS, settings.gainUpMs)
                .putFloat(KEY_COMPRESSOR_THRESHOLD, settings.compressorThreshold)
                .putFloat(KEY_OUTPUT_CEILING, settings.outputCeiling)
                .apply();
    }

    static VisualizationSettings loadVisualizationSettings(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new VisualizationSettings(
                prefs.getInt(KEY_VISUAL_FPS, VisualizationSettings.DEFAULT_FPS),
                prefs.getInt(KEY_VISUAL_WAVEFORM_MS, VisualizationSettings.DEFAULT_WAVEFORM_MS),
                prefs.getInt(KEY_VISUAL_FFT_SIZE, VisualizationSettings.DEFAULT_FFT_SIZE),
                prefs.getInt(KEY_VISUAL_FFT_BARS, VisualizationSettings.DEFAULT_FFT_BARS),
                prefs.getFloat(KEY_VISUAL_SMOOTHING, VisualizationSettings.DEFAULT_SMOOTHING),
                prefs.getBoolean(KEY_VISUAL_LOG_SCALE, VisualizationSettings.DEFAULT_LOG_SCALE));
    }

    static void saveVisualizationSettings(Context context, VisualizationSettings settings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_VISUAL_FPS, settings.fps)
                .putInt(KEY_VISUAL_WAVEFORM_MS, settings.waveformMs)
                .putInt(KEY_VISUAL_FFT_SIZE, settings.fftSize)
                .putInt(KEY_VISUAL_FFT_BARS, settings.fftBars)
                .putFloat(KEY_VISUAL_SMOOTHING, settings.smoothing)
                .putBoolean(KEY_VISUAL_LOG_SCALE, settings.logScale)
                .apply();
    }

    static int loadBluetoothVisualDelay(Context context, String routeKey) {
        if (routeKey == null || routeKey.isEmpty()) {
            return 0;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return clamp(readJsonObject(prefs, KEY_BLUETOOTH_VISUAL_DELAYS)
                .optInt(routeKey, 0), 0, 1500);
    }

    static void saveBluetoothVisualDelay(Context context, String routeKey, int delayMs) {
        saveBluetoothVisualDelay(context, routeKey, routeLabel(routeKey), delayMs);
    }

    static void saveBluetoothVisualDelay(
            Context context,
            String routeKey,
            String routeLabel,
            int delayMs) {
        if (routeKey == null || routeKey.isEmpty()) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONObject delays = readJsonObject(prefs, KEY_BLUETOOTH_VISUAL_DELAYS);
        JSONObject labels = readJsonObject(prefs, KEY_BLUETOOTH_VISUAL_DELAY_LABELS);
        try {
            delays.put(routeKey, clamp(delayMs, 0, 1500));
            labels.put(routeKey, routeLabel == null || routeLabel.trim().isEmpty()
                    ? routeLabel(routeKey)
                    : routeLabel.trim());
            prefs.edit()
                    .putString(KEY_BLUETOOTH_VISUAL_DELAYS, delays.toString())
                    .putString(KEY_BLUETOOTH_VISUAL_DELAY_LABELS, labels.toString())
                    .apply();
        } catch (JSONException ignored) {
        }
    }

    static ArrayList<BluetoothVisualDelayEntry> loadBluetoothVisualDelayEntries(
            Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONObject delays = readJsonObject(prefs, KEY_BLUETOOTH_VISUAL_DELAYS);
        JSONObject labels = readJsonObject(prefs, KEY_BLUETOOTH_VISUAL_DELAY_LABELS);
        ArrayList<BluetoothVisualDelayEntry> entries = new ArrayList<>();
        Iterator<String> keys = delays.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            entries.add(new BluetoothVisualDelayEntry(
                    key,
                    labels.optString(key, routeLabel(key)),
                    clamp(delays.optInt(key, 0), 0, 1500)));
        }
        entries.sort((left, right) -> left.label.compareToIgnoreCase(right.label));
        return entries;
    }

    static void clearBluetoothVisualDelay(Context context, String routeKey) {
        if (routeKey == null || routeKey.isEmpty()) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONObject delays = readJsonObject(prefs, KEY_BLUETOOTH_VISUAL_DELAYS);
        JSONObject labels = readJsonObject(prefs, KEY_BLUETOOTH_VISUAL_DELAY_LABELS);
        delays.remove(routeKey);
        labels.remove(routeKey);
        prefs.edit()
                .putString(KEY_BLUETOOTH_VISUAL_DELAYS, delays.toString())
                .putString(KEY_BLUETOOTH_VISUAL_DELAY_LABELS, labels.toString())
                .apply();
    }

    static void clearAllBluetoothVisualDelays(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_BLUETOOTH_VISUAL_DELAYS)
                .remove(KEY_BLUETOOTH_VISUAL_DELAY_LABELS)
                .apply();
    }

    private static JSONObject readJsonObject(SharedPreferences prefs, String key) {
        String stored = prefs.getString(key, "");
        if (stored == null || stored.isEmpty()) {
            return new JSONObject();
        }
        try {
            return new JSONObject(stored);
        } catch (JSONException ignored) {
            return new JSONObject();
        }
    }

    private static String routeLabel(String routeKey) {
        int separator = routeKey == null ? -1 : routeKey.indexOf(':');
        if (separator >= 0 && separator + 1 < routeKey.length()) {
            return routeKey.substring(separator + 1);
        }
        return "Bluetooth audio";
    }

    static final class BluetoothVisualDelayEntry {
        final String key;
        final String label;
        final int delayMs;

        private BluetoothVisualDelayEntry(String key, String label, int delayMs) {
            this.key = key;
            this.label = label;
            this.delayMs = delayMs;
        }
    }

    static boolean loadShuffleEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_SHUFFLE_ENABLED, true);
    }

    static void saveShuffleEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_SHUFFLE_ENABLED, enabled)
                .apply();
    }

    static String loadServerBaseUrl(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_SERVER_BASE_URL, "");
    }

    static void saveServerBaseUrl(Context context, String url) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SERVER_BASE_URL, url == null ? "" : url.trim())
                .apply();
    }

    static String loadServerToken(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_SERVER_TOKEN, "");
    }

    static void saveServerToken(Context context, String token) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SERVER_TOKEN, token == null ? "" : token.trim())
                .apply();
    }

    /**
     * Returns cached {title, artist, album} for a track URI (as fetched once
     * from the server's /api/library when the track was added), or null if
     * nothing is cached — the caller should fall back to on-device tag
     * extraction in that case.
     */
    static String[] loadTrackMetadata(Context context, String uriString) {
        String stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_TRACK_METADATA, "");
        if (stored == null || stored.isEmpty()) {
            return null;
        }
        try {
            JSONObject entry = new JSONObject(stored).optJSONObject(uriString);
            if (entry == null) {
                return null;
            }
            return new String[]{
                    entry.optString("title", ""),
                    entry.optString("artist", ""),
                    entry.optString("album", "")
            };
        } catch (JSONException e) {
            return null;
        }
    }

    static Map<String, String[]> loadAllTrackMetadata(Context context) {
        LinkedHashMap<String, String[]> result = new LinkedHashMap<>();
        String stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_TRACK_METADATA, "");
        if (stored == null || stored.isEmpty()) {
            return result;
        }
        try {
            JSONObject all = new JSONObject(stored);
            java.util.Iterator<String> keys = all.keys();
            while (keys.hasNext()) {
                String uriString = keys.next();
                JSONObject entry = all.optJSONObject(uriString);
                if (entry == null) {
                    continue;
                }
                result.put(uriString, new String[]{
                        entry.optString("title", ""),
                        entry.optString("artist", ""),
                        entry.optString("album", "")
                });
            }
        } catch (JSONException e) {
            return result;
        }
        return result;
    }

    static void saveTrackMetadata(Context context, Map<String, String[]> entries) {
        if (entries.isEmpty()) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String stored = prefs.getString(KEY_TRACK_METADATA, "");
        JSONObject all;
        try {
            all = (stored == null || stored.isEmpty()) ? new JSONObject() : new JSONObject(stored);
        } catch (JSONException e) {
            all = new JSONObject();
        }
        try {
            for (Map.Entry<String, String[]> entry : entries.entrySet()) {
                String[] fields = entry.getValue();
                if (fields == null || fields.length < 3) {
                    continue;
                }
                JSONObject value = new JSONObject();
                value.put("title", fields[0]);
                value.put("artist", fields[1]);
                value.put("album", fields[2]);
                all.put(entry.getKey(), value);
            }
            prefs.edit().putString(KEY_TRACK_METADATA, all.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    static String displayName(Context context, String uriString) {
        Uri uri = Uri.parse(uriString);
        ContentResolver resolver = context.getContentResolver();
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.trim().isEmpty()) {
                        return name;
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }

        String last = uri.getLastPathSegment();
        if (last == null || last.trim().isEmpty()) {
            return "Selected song";
        }
        int slash = last.lastIndexOf('/');
        return slash >= 0 ? last.substring(slash + 1) : last;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
