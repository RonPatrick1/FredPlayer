package com.fredplayer.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int REQUEST_PICK_AUDIO = 1001;
    private static final int REQUEST_NOTIFICATIONS = 1002;
    private static final int REQUEST_PICK_FOLDER = 1003;
    private static final int MAX_FOLDER_DEPTH = 12;

    private final ArrayList<String> playlist = new ArrayList<>();
    private final LinkedHashMap<String, ArrayList<String>> playlists = new LinkedHashMap<>();
    private String activePlaylistName = PlaylistStore.DEFAULT_PLAYLIST_NAME;
    private boolean receiverRegistered;
    private boolean playing;
    private boolean showingSettings;
    private boolean userSeeking;

    private TextView nowPlayingText;
    private TextView playlistText;
    private TextView elapsedTimeText;
    private TextView durationTimeText;
    private TextView stateText;
    private TextView outputText;
    private TextView levelingText;
    private TextView cacheText;
    private ImageButton playButton;
    private SeekBar outputSlider;
    private SeekBar levelingSlider;
    private SeekBar trackSeekBar;
    private TextView playlistEditorTitle;
    private LinearLayout playlistFoldersContainer;
    private LinearLayout playlistFilesContainer;
    private VisualizerView visualizerView;
    private LevelingSettings levelingSettings;
    private VisualizationSettings visualizationSettings;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (SleepMusicService.ACTION_VISUALIZATION_CHANGED.equals(intent.getAction())) {
                if (visualizerView != null) {
                    visualizerView.update(
                            intent.getByteArrayExtra(SleepMusicService.EXTRA_WAVEFORM),
                            intent.getByteArrayExtra(SleepMusicService.EXTRA_SPECTRUM));
                }
                return;
            }
            if (!SleepMusicService.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                return;
            }
            playing = intent.getBooleanExtra(SleepMusicService.EXTRA_IS_PLAYING, false);
            String track = intent.getStringExtra(SleepMusicService.EXTRA_TRACK_NAME);
            String artist = intent.getStringExtra(SleepMusicService.EXTRA_TRACK_ARTIST);
            String album = intent.getStringExtra(SleepMusicService.EXTRA_TRACK_ALBUM);
            String message = intent.getStringExtra(SleepMusicService.EXTRA_MESSAGE);
            int count = intent.getIntExtra(SleepMusicService.EXTRA_PLAYLIST_COUNT, playlist.size());
            int cacheCount = intent.getIntExtra(SleepMusicService.EXTRA_CACHE_COUNT, -1);
            int cachePruneAbove = intent.getIntExtra(SleepMusicService.EXTRA_CACHE_PRUNE_ABOVE, 5000);
            int cacheKeep = intent.getIntExtra(SleepMusicService.EXTRA_CACHE_KEEP, 4000);
            long cacheBytes = intent.getLongExtra(SleepMusicService.EXTRA_CACHE_BYTES, 0L);
            int visualCacheCount = intent.getIntExtra(SleepMusicService.EXTRA_VISUAL_CACHE_COUNT, 0);
            int visualCachePruneAbove = intent.getIntExtra(SleepMusicService.EXTRA_VISUAL_CACHE_PRUNE_ABOVE, 5000);
            int visualCacheKeep = intent.getIntExtra(SleepMusicService.EXTRA_VISUAL_CACHE_KEEP, 4500);
            long visualCacheBytes = intent.getLongExtra(SleepMusicService.EXTRA_VISUAL_CACHE_BYTES, 0L);
            int cacheProgressDone = intent.getIntExtra(SleepMusicService.EXTRA_CACHE_PROGRESS_DONE, 0);
            int cacheProgressTotal = intent.getIntExtra(SleepMusicService.EXTRA_CACHE_PROGRESS_TOTAL, 0);
            long positionMs = intent.getLongExtra(SleepMusicService.EXTRA_POSITION_MS, 0L);
            long durationMs = intent.getLongExtra(SleepMusicService.EXTRA_DURATION_MS, 0L);

            updatePlayButtonIcon();
            if (nowPlayingText != null) {
                nowPlayingText.setText(formatTrackText(track, artist, album));
            }
            if (stateText != null) {
                stateText.setText(message == null || message.isEmpty() ? (playing ? "Playing" : "Paused") : message);
            }
            if (playlistText != null) {
                playlistText.setText(playlistSummary(count));
            }
            updateTrackProgress(positionMs, durationMs);
            if (cacheCount >= 0) {
                updateCacheText(
                        cacheCount,
                        cachePruneAbove,
                        cacheKeep,
                        cacheBytes,
                        visualCacheCount,
                        visualCachePruneAbove,
                        visualCacheKeep,
                        visualCacheBytes,
                        cacheProgressDone,
                        cacheProgressTotal);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        playlists.putAll(PlaylistStore.loadPlaylists(this));
        activePlaylistName = PlaylistStore.loadActivePlaylistName(this, playlists);
        ArrayList<String> activePlaylist = playlists.get(activePlaylistName);
        if (activePlaylist != null) {
            playlist.addAll(activePlaylist);
        }
        levelingSettings = PlaylistStore.loadLevelingSettings(this);
        visualizationSettings = PlaylistStore.loadVisualizationSettings(this);
        setContentView(buildContentView());
        requestNotificationPermission();
        updatePlaylistText();
    }

    @Override
    public void onBackPressed() {
        if (showingSettings) {
            showPlayerScreen();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerStateReceiver();
        playlists.clear();
        playlists.putAll(PlaylistStore.loadPlaylists(this));
        activePlaylistName = PlaylistStore.loadActivePlaylistName(this, playlists);
        playlist.clear();
        ArrayList<String> activePlaylist = playlists.get(activePlaylistName);
        if (activePlaylist != null) {
            playlist.addAll(activePlaylist);
        }
        updatePlaylistText();
        if (!playlist.isEmpty()) {
            sendPlaylistToService(false);
            sendVisualizationSettingsToService();
        }
    }

    @Override
    protected void onStop() {
        if (receiverRegistered) {
            unregisterReceiver(stateReceiver);
            receiverRegistered = false;
        }
        super.onStop();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) {
            return;
        }

        if (requestCode == REQUEST_PICK_AUDIO) {
            addPickedAudioFiles(data);
        } else if (requestCode == REQUEST_PICK_FOLDER) {
            addPickedFolder(data);
        }
    }

    private void addPickedAudioFiles(Intent data) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(playlist);
        ClipData clipData = data.getClipData();
        if (clipData != null) {
            for (int i = 0; i < clipData.getItemCount(); i++) {
                Uri uri = clipData.getItemAt(i).getUri();
                persistReadPermission(uri, data.getFlags());
                merged.add(uri.toString());
            }
        } else if (data.getData() != null) {
            Uri uri = data.getData();
            persistReadPermission(uri, data.getFlags());
            merged.add(uri.toString());
        }

        saveMergedPlaylist(merged, "Added audio files");
    }

    private void addPickedFolder(Intent data) {
        Uri treeUri = data.getData();
        if (treeUri == null) {
            return;
        }
        persistReadPermission(treeUri, data.getFlags());
        Toast.makeText(this, "Scanning folder", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            LinkedHashSet<String> found = new LinkedHashSet<>();
            collectAudioFromTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri), found, 0);

            runOnUiThread(() -> {
                if (found.isEmpty()) {
                    Toast.makeText(this, "No supported audio found in folder", Toast.LENGTH_LONG).show();
                    return;
                }
                LinkedHashSet<String> merged = new LinkedHashSet<>(playlist);
                merged.addAll(found);
                saveMergedPlaylist(merged, "Added " + found.size() + " audio files from folder");
            });
        }, "FredPlayerFolderScan").start();
    }

    private void saveMergedPlaylist(LinkedHashSet<String> merged, String toastText) {
        int previousCount = playlist.size();
        playlist.clear();
        playlist.addAll(merged);
        persistActivePlaylist();
        updatePlaylistText();
        sendPlaylistToService(false);
        int added = playlist.size() - previousCount;
        if (added > 0) {
            Toast.makeText(this, toastText, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Those files are already in the list", Toast.LENGTH_SHORT).show();
        }
    }

    private void openServerLibraryDialog() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int horizontal = dp(20);
        container.setPadding(horizontal, dp(8), horizontal, dp(8));

        EditText urlInput = new EditText(this);
        urlInput.setSingleLine(true);
        urlInput.setHint("Server URL, e.g. https://host/fredplayer-media");
        urlInput.setText(PlaylistStore.loadServerBaseUrl(this));
        container.addView(urlInput);

        EditText tokenInput = new EditText(this);
        tokenInput.setSingleLine(true);
        tokenInput.setHint("Access token");
        tokenInput.setText(PlaylistStore.loadServerToken(this));
        container.addView(tokenInput, topMargin(10));

        new AlertDialog.Builder(this)
                .setTitle("Add from server")
                .setView(container)
                .setPositiveButton("Fetch", (dialog, which) -> {
                    String url = urlInput.getText().toString().trim();
                    String token = tokenInput.getText().toString().trim();
                    if (url.isEmpty()) {
                        Toast.makeText(this, "Enter a server URL", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    fetchServerLibrary(url, token);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void fetchServerLibrary(String url, String token) {
        Toast.makeText(this, "Fetching library…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                JSONArray tracks = RemoteLibraryClient.fetchLibrary(url, token);
                runOnUiThread(() -> {
                    PlaylistStore.saveServerBaseUrl(this, url);
                    PlaylistStore.saveServerToken(this, token);
                    if (tracks.length() == 0) {
                        Toast.makeText(this, "Server library is empty", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    showServerTrackPicker(tracks, url);
                });
            } catch (Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(this, "Could not reach server: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "FredPlayerServerFetch").start();
    }

    private void showServerTrackPicker(JSONArray tracks, String baseUrl) {
        LinkedHashMap<String, ArrayList<Integer>> folders = new LinkedHashMap<>();
        for (int i = 0; i < tracks.length(); i++) {
            JSONObject track = tracks.optJSONObject(i);
            String path = track == null ? "" : track.optString("path", "");
            int slash = path.indexOf('/');
            String folder = slash > 0 ? path.substring(0, slash) : "(other)";
            ArrayList<Integer> indices = folders.get(folder);
            if (indices == null) {
                indices = new ArrayList<>();
                folders.put(folder, indices);
            }
            indices.add(i);
        }
        ArrayList<String> folderNames = new ArrayList<>(folders.keySet());
        Collections.sort(folderNames, String.CASE_INSENSITIVE_ORDER);
        String[] labels = new String[folderNames.size()];
        boolean[] checked = new boolean[folderNames.size()];
        for (int i = 0; i < folderNames.size(); i++) {
            labels[i] = folderNames.get(i) + " (" + folders.get(folderNames.get(i)).size() + ")";
        }

        new AlertDialog.Builder(this)
                .setTitle("Choose folders (" + tracks.length() + " songs total)")
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("Add Selected", (dialog, which) -> {
                    LinkedHashSet<String> merged = new LinkedHashSet<>(playlist);
                    int added = 0;
                    for (int i = 0; i < folderNames.size(); i++) {
                        if (!checked[i]) {
                            continue;
                        }
                        for (int trackIndex : folders.get(folderNames.get(i))) {
                            if (addServerTrack(merged, tracks, trackIndex, baseUrl)) {
                                added++;
                            }
                        }
                    }
                    reportServerAdd(merged, added);
                })
                .setNeutralButton("Add All", (dialog, which) -> {
                    LinkedHashSet<String> merged = new LinkedHashSet<>(playlist);
                    int added = 0;
                    for (int i = 0; i < tracks.length(); i++) {
                        if (addServerTrack(merged, tracks, i, baseUrl)) {
                            added++;
                        }
                    }
                    reportServerAdd(merged, added);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private boolean addServerTrack(LinkedHashSet<String> merged, JSONArray tracks, int index, String baseUrl) {
        JSONObject track = tracks.optJSONObject(index);
        String path = track == null ? null : track.optString("path", null);
        if (path == null || path.isEmpty()) {
            return false;
        }
        return merged.add(RemoteLibraryClient.buildStreamUrl(baseUrl, path));
    }

    private void reportServerAdd(LinkedHashSet<String> merged, int added) {
        if (added == 0) {
            Toast.makeText(this, "No songs selected", Toast.LENGTH_SHORT).show();
            return;
        }
        saveMergedPlaylist(merged, "Added " + added + " songs from server");
    }

    private void openAskLiamDialog() {
        String url = PlaylistStore.loadServerBaseUrl(this);
        String token = PlaylistStore.loadServerToken(this);
        if (url.isEmpty()) {
            Toast.makeText(this, "Set up a server URL first via \"Add from server\"", Toast.LENGTH_LONG).show();
            return;
        }

        EditText input = new EditText(this);
        input.setHint("e.g. Make me a playlist of upbeat piano music");
        input.setMinLines(2);
        int horizontal = dp(20);
        input.setPadding(horizontal, dp(12), horizontal, dp(12));

        new AlertDialog.Builder(this)
                .setTitle("Ask Liam")
                .setView(input)
                .setPositiveButton("Ask", (dialog, which) -> {
                    String message = input.getText().toString().trim();
                    if (message.isEmpty()) {
                        Toast.makeText(this, "Type a question first", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    askLiam(url, token, message);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private AlertDialog askLiamProgressDialog;

    private void askLiam(String url, String token, String message) {
        // Liam can take a while (up to 3 auto-retried attempts against a
        // slow local model) — a Toast disappears in a couple seconds and
        // gives no sense of whether it's still working, so this is a
        // dialog that stays up for the whole wait and is always followed
        // by an explicit result dialog, never a Toast-only outcome that's
        // easy to miss.
        TextView progressText = text("Asking Liam… this can take a minute or two.", 15, Color.rgb(214, 210, 200));
        int horizontal = dp(20);
        progressText.setPadding(horizontal, dp(16), horizontal, dp(16));
        askLiamProgressDialog = new AlertDialog.Builder(this)
                .setTitle("Ask Liam")
                .setView(progressText)
                .setCancelable(false)
                .show();

        String deviceId = deviceId();
        new Thread(() -> {
            try {
                JSONObject response = RemoteLibraryClient.askLiam(url, token, deviceId, message);
                runOnUiThread(() -> {
                    dismissAskLiamProgress();
                    handleLiamResponse(url, response);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    dismissAskLiamProgress();
                    new AlertDialog.Builder(this)
                            .setTitle("Liam")
                            .setMessage("Could not reach Liam: " + e.getMessage())
                            .setPositiveButton("OK", null)
                            .show();
                });
            }
        }, "FredPlayerAskLiam").start();
    }

    private void dismissAskLiamProgress() {
        if (askLiamProgressDialog != null) {
            askLiamProgressDialog.dismiss();
            askLiamProgressDialog = null;
        }
    }

    private void handleLiamResponse(String baseUrl, JSONObject response) {
        String reply = response.optString("reply", "");
        JSONObject playlist = response.optJSONObject("playlist");
        if (playlist == null) {
            new AlertDialog.Builder(this)
                    .setTitle("Liam")
                    .setMessage(reply.isEmpty() ? "Liam didn't reply." : reply)
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        String requestedName = playlist.optString("name", "New Playlist").trim();
        String name = requestedName.isEmpty() ? "New Playlist" : requestedName;
        JSONArray tracks = playlist.optJSONArray("tracks");
        ArrayList<String> urls = new ArrayList<>();
        if (tracks != null) {
            for (int i = 0; i < tracks.length(); i++) {
                String path = tracks.optString(i, "");
                if (!path.isEmpty()) {
                    urls.add(RemoteLibraryClient.buildStreamUrl(baseUrl, path));
                }
            }
        }
        if (urls.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Liam")
                    .setMessage("Liam didn't include any tracks." + (reply.isEmpty() ? "" : "\n\n" + reply))
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        String localName = uniquePlaylistName(name);
        persistActivePlaylist();
        playlists.put(localName, urls);
        PlaylistStore.savePlaylists(this, playlists);
        switchPlaylist(localName);
        new AlertDialog.Builder(this)
                .setTitle("Liam")
                .setMessage("Created \"" + localName + "\" (" + urls.size() + " songs) — just on this device.")
                .setPositiveButton("OK", null)
                .show();
    }

    private String uniquePlaylistName(String base) {
        if (isAvailablePlaylistName(base, null)) {
            return base;
        }
        for (int suffix = 2; suffix < 1000; suffix++) {
            String candidate = base + " (" + suffix + ")";
            if (isAvailablePlaylistName(candidate, null)) {
                return candidate;
            }
        }
        return base + " (" + System.currentTimeMillis() + ")";
    }

    private String deviceId() {
        String id = android.provider.Settings.Secure.getString(getContentResolver(), android.provider.Settings.Secure.ANDROID_ID);
        return id == null || id.isEmpty() ? "fredplayer-unknown-device" : id;
    }

    private View buildContentView() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Color.rgb(17, 19, 21));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(28));
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(header, matchWrap());

        TextView title = text("FredPlayer", 32, Color.rgb(245, 243, 237));
        header.addView(title, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f));

        Button settingsButton = button("Settings");
        settingsButton.setContentDescription("Open settings");
        settingsButton.setOnClickListener(view -> showSettingsScreen());
        header.addView(settingsButton, new LinearLayout.LayoutParams(
                dp(112),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        stateText = text("Paused", 17, Color.rgb(183, 182, 173));
        stateText.setGravity(Gravity.CENTER);
        root.addView(stateText, topMargin(8));

        nowPlayingText = text("No song selected", 20, Color.rgb(245, 243, 237));
        nowPlayingText.setGravity(Gravity.CENTER);
        nowPlayingText.setSingleLine(false);
        root.addView(nowPlayingText, topMargin(28));

        playlistText = text("", 15, Color.rgb(183, 182, 173));
        playlistText.setGravity(Gravity.CENTER);
        root.addView(playlistText, topMargin(8));

        trackSeekBar = new SeekBar(this);
        trackSeekBar.setMax(1);
        trackSeekBar.setProgress(0);
        trackSeekBar.setEnabled(false);
        trackSeekBar.setContentDescription("Track position");
        trackSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && elapsedTimeText != null) {
                    elapsedTimeText.setText(formatTime(progress));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                userSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                long positionMs = seekBar.getProgress();
                userSeeking = false;
                sendSeekCommand(positionMs);
            }
        });
        root.addView(trackSeekBar, topMargin(14));

        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        elapsedTimeText = text("0:00", 13, Color.rgb(183, 182, 173));
        durationTimeText = text("0:00", 13, Color.rgb(183, 182, 173));
        timeRow.addView(elapsedTimeText, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f));
        durationTimeText.setGravity(Gravity.END);
        timeRow.addView(durationTimeText, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f));
        root.addView(timeRow, matchWrap());

        visualizerView = new VisualizerView(this);
        visualizerView.setSmoothing(visualizationSettings.smoothing);
        LinearLayout.LayoutParams visualizerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(168));
        visualizerParams.topMargin = dp(18);
        root.addView(visualizerView, visualizerParams);

        LinearLayout mainButtons = new LinearLayout(this);
        mainButtons.setOrientation(LinearLayout.HORIZONTAL);
        mainButtons.setGravity(Gravity.CENTER);
        root.addView(mainButtons, topMargin(28));

        ImageButton previousButton = transportButton(android.R.drawable.ic_media_previous, "Previous");
        previousButton.setOnClickListener(view -> sendServiceCommand(SleepMusicService.ACTION_PREVIOUS));
        mainButtons.addView(previousButton, transportButtonParams(false));

        playButton = transportButton(android.R.drawable.ic_media_play, "Play");
        playButton.setOnClickListener(view -> {
            if (playlist.isEmpty()) {
                Toast.makeText(this, "Add audio files first", Toast.LENGTH_SHORT).show();
                openAudioPicker();
                return;
            }
            sendPlaylistToService(false);
            sendServiceCommand(SleepMusicService.ACTION_TOGGLE_PLAY);
        });
        mainButtons.addView(playButton, transportButtonParams(true));
        updatePlayButtonIcon();

        ImageButton skipButton = transportButton(android.R.drawable.ic_media_next, "Next");
        skipButton.setOnClickListener(view -> sendServiceCommand(SleepMusicService.ACTION_SKIP));
        mainButtons.addView(skipButton, transportButtonParams(false));

        ImageButton stopButton = transportButton(android.R.drawable.ic_menu_close_clear_cancel, "Stop");
        stopButton.setOnClickListener(view -> sendServiceCommand(SleepMusicService.ACTION_STOP));
        mainButtons.addView(stopButton, transportButtonParams(false));

        Button playlistsButton = button("Choose or manage playlists");
        playlistsButton.setOnClickListener(view -> showPlaylistMenu());
        root.addView(playlistsButton, topMargin(18));

        LinearLayout addButtons = new LinearLayout(this);
        addButtons.setOrientation(LinearLayout.HORIZONTAL);
        addButtons.setGravity(Gravity.CENTER);
        root.addView(addButtons, topMargin(18));

        Button addButton = button("Add files");
        addButton.setOnClickListener(view -> openAudioPicker());
        addButtons.addView(addButton, weightedButton());

        Button addFolderButton = button("Add folder");
        addFolderButton.setOnClickListener(view -> openFolderPicker());
        addButtons.addView(addFolderButton, weightedButton());

        Button addFromServerButton = button("Add from server");
        addFromServerButton.setOnClickListener(view -> openServerLibraryDialog());
        root.addView(addFromServerButton, topMargin(10));

        Button askLiamButton = button("Ask Liam");
        askLiamButton.setOnClickListener(view -> openAskLiamDialog());
        root.addView(askLiamButton, topMargin(10));

        Button clearButton = button("Clear list");
        clearButton.setOnClickListener(view -> {
            playlist.clear();
            persistActivePlaylist();
            updatePlaylistText();
            sendServiceCommand(SleepMusicService.ACTION_CLEAR);
        });
        root.addView(clearButton, topMargin(10));

        Button shuffleButton = button("Shuffle list");
        shuffleButton.setOnClickListener(view -> shufflePlaylist());
        root.addView(shuffleButton, topMargin(10));

        addPlaylistEditor(root);

        return scrollView;
    }

    private void showPlayerScreen() {
        showingSettings = false;
        setContentView(buildContentView());
        updatePlaylistText();
        sendServiceCommand(SleepMusicService.ACTION_REQUEST_STATE);
    }

    private void showSettingsScreen() {
        showingSettings = true;
        setContentView(buildSettingsView());
    }

    private View buildSettingsView() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Color.rgb(17, 19, 21));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(32));
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(header, matchWrap());

        Button backButton = button("Back");
        backButton.setOnClickListener(view -> showPlayerScreen());
        header.addView(backButton, new LinearLayout.LayoutParams(
                dp(92),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = text("Settings", 28, Color.rgb(245, 243, 237));
        title.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        header.addView(title, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f));

        TextView playbackTitle = text("Playback", 20, Color.rgb(245, 243, 237));
        root.addView(playbackTitle, topMargin(30));
        addPrimarySettingsControls(root);
        addAdvancedLevelingControls(root);
        addVisualizationControls(root);

        cacheText = text("", 13, Color.rgb(183, 182, 173));
        root.addView(cacheText, topMargin(22));
        NormalizingAudioPlayer.CacheStats stats = NormalizingAudioPlayer.profileCacheStats(this);
        NormalizingAudioPlayer.CacheStats visualStats = NormalizingAudioPlayer.visualCacheStats(this);
        updateCacheText(
                stats.count,
                stats.pruneAbove,
                stats.keep,
                stats.approximateBytes,
                visualStats.count,
                visualStats.pruneAbove,
                visualStats.keep,
                visualStats.approximateBytes,
                0,
                0);
        return scrollView;
    }

    private void addPrimarySettingsControls(LinearLayout root) {
        outputText = text("", 15, Color.rgb(245, 243, 237));
        root.addView(outputText, topMargin(12));
        outputSlider = new SeekBar(this);
        outputSlider.setMax(100);
        outputSlider.setMin(10);
        outputSlider.setProgress(Math.round(PlaylistStore.loadOutputLevel(this) * 100f));
        outputSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float value = Math.max(10, progress) / 100f;
                outputText.setText("Output level: " + Math.round(value * 100f) + "%");
                if (fromUser) {
                    PlaylistStore.saveOutputLevel(MainActivity.this, value);
                    sendFloatCommand(
                            SleepMusicService.ACTION_SET_OUTPUT_LEVEL,
                            SleepMusicService.EXTRA_OUTPUT_LEVEL,
                            value);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        root.addView(outputSlider, matchWrap());
        outputText.setText("Output level: " + outputSlider.getProgress() + "%");

        levelingText = text("", 15, Color.rgb(245, 243, 237));
        root.addView(levelingText, topMargin(16));
        levelingSlider = new SeekBar(this);
        levelingSlider.setMax(100);
        levelingSlider.setProgress(Math.round(PlaylistStore.loadLevelingStrength(this) * 100f));
        levelingSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float value = progress / 100f;
                levelingText.setText("Leveling strength: " + progress + "%");
                if (fromUser) {
                    PlaylistStore.saveLevelingStrength(MainActivity.this, value);
                    sendFloatCommand(
                            SleepMusicService.ACTION_SET_LEVELING_STRENGTH,
                            SleepMusicService.EXTRA_LEVELING_STRENGTH,
                            value);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        root.addView(levelingSlider, matchWrap());
        levelingText.setText("Leveling strength: " + levelingSlider.getProgress() + "%");
    }

    private void addAdvancedLevelingControls(LinearLayout root) {
        TextView title = text("Advanced leveling", 18, Color.rgb(245, 243, 237));
        root.addView(title, topMargin(28));

        addSettingsSlider(root, "Startup scan", 0, 45, Math.round(levelingSettings.analysisSeconds),
                value -> value == 0 ? "Off" : value + " s",
                value -> updateLevelingSettings(new LevelingSettings(
                        value,
                        levelingSettings.levelAttackMs,
                        levelingSettings.levelReleaseMs,
                        levelingSettings.gainDownMs,
                        levelingSettings.gainUpMs,
                        levelingSettings.compressorThreshold,
                        levelingSettings.outputCeiling)));

        addSettingsSlider(root, "Attack", 1, 250, Math.round(levelingSettings.levelAttackMs),
                value -> value + " ms",
                value -> updateLevelingSettings(new LevelingSettings(
                        levelingSettings.analysisSeconds,
                        value,
                        levelingSettings.levelReleaseMs,
                        levelingSettings.gainDownMs,
                        levelingSettings.gainUpMs,
                        levelingSettings.compressorThreshold,
                        levelingSettings.outputCeiling)));

        addSettingsSlider(root, "Decay", 100, 5000, Math.round(levelingSettings.levelReleaseMs),
                value -> value + " ms",
                value -> updateLevelingSettings(new LevelingSettings(
                        levelingSettings.analysisSeconds,
                        levelingSettings.levelAttackMs,
                        value,
                        levelingSettings.gainDownMs,
                        levelingSettings.gainUpMs,
                        levelingSettings.compressorThreshold,
                        levelingSettings.outputCeiling)));

        addSettingsSlider(root, "Cut speed", 5, 500, Math.round(levelingSettings.gainDownMs),
                value -> value + " ms",
                value -> updateLevelingSettings(new LevelingSettings(
                        levelingSettings.analysisSeconds,
                        levelingSettings.levelAttackMs,
                        levelingSettings.levelReleaseMs,
                        value,
                        levelingSettings.gainUpMs,
                        levelingSettings.compressorThreshold,
                        levelingSettings.outputCeiling)));

        addSettingsSlider(root, "Recovery", 5, 100, Math.round(levelingSettings.gainUpMs / 100f),
                value -> String.format(Locale.US, "%.1f s", value / 10f),
                value -> updateLevelingSettings(new LevelingSettings(
                        levelingSettings.analysisSeconds,
                        levelingSettings.levelAttackMs,
                        levelingSettings.levelReleaseMs,
                        levelingSettings.gainDownMs,
                        value * 100f,
                        levelingSettings.compressorThreshold,
                        levelingSettings.outputCeiling)));

        addSettingsSlider(root, "Compressor threshold", 30, 95, Math.round(levelingSettings.compressorThreshold * 100f),
                value -> value + "%",
                value -> updateLevelingSettings(new LevelingSettings(
                        levelingSettings.analysisSeconds,
                        levelingSettings.levelAttackMs,
                        levelingSettings.levelReleaseMs,
                        levelingSettings.gainDownMs,
                        levelingSettings.gainUpMs,
                        value / 100f,
                        levelingSettings.outputCeiling)));

        addSettingsSlider(root, "Output ceiling", 50, 100, Math.round(levelingSettings.outputCeiling * 100f),
                value -> value + "%",
                value -> updateLevelingSettings(new LevelingSettings(
                        levelingSettings.analysisSeconds,
                        levelingSettings.levelAttackMs,
                        levelingSettings.levelReleaseMs,
                        levelingSettings.gainDownMs,
                        levelingSettings.gainUpMs,
                        levelingSettings.compressorThreshold,
                        value / 100f)));
    }

    private void addVisualizationControls(LinearLayout root) {
        TextView title = text("Visualization", 18, Color.rgb(245, 243, 237));
        root.addView(title, topMargin(28));

        addSettingsSlider(root, "Update FPS", 5, 30, visualizationSettings.fps,
                value -> value + " fps",
                value -> updateVisualizationSettings(new VisualizationSettings(
                        value,
                        visualizationSettings.waveformMs,
                        visualizationSettings.fftSize,
                        visualizationSettings.fftBars,
                        visualizationSettings.smoothing,
                        visualizationSettings.logScale)));

        addSettingsSlider(root, "Waveform window", 30, 250, visualizationSettings.waveformMs,
                value -> value + " ms",
                value -> updateVisualizationSettings(new VisualizationSettings(
                        visualizationSettings.fps,
                        value,
                        visualizationSettings.fftSize,
                        visualizationSettings.fftBars,
                        visualizationSettings.smoothing,
                        visualizationSettings.logScale)));

        addSettingsSlider(root, "FFT size", 0, 2, fftSizeIndex(visualizationSettings.fftSize),
                value -> String.valueOf(fftSizeForIndex(value)),
                value -> updateVisualizationSettings(new VisualizationSettings(
                        visualizationSettings.fps,
                        visualizationSettings.waveformMs,
                        fftSizeForIndex(value),
                        visualizationSettings.fftBars,
                        visualizationSettings.smoothing,
                        visualizationSettings.logScale)));

        addSettingsSlider(root, "FFT bars", 16, 64, visualizationSettings.fftBars,
                value -> String.valueOf(value),
                value -> updateVisualizationSettings(new VisualizationSettings(
                        visualizationSettings.fps,
                        visualizationSettings.waveformMs,
                        visualizationSettings.fftSize,
                        value,
                        visualizationSettings.smoothing,
                        visualizationSettings.logScale)));

        addSettingsSlider(root, "FFT smoothing", 0, 90, Math.round(visualizationSettings.smoothing * 100f),
                value -> value + "%",
                value -> updateVisualizationSettings(new VisualizationSettings(
                        visualizationSettings.fps,
                        visualizationSettings.waveformMs,
                        visualizationSettings.fftSize,
                        visualizationSettings.fftBars,
                        value / 100f,
                        visualizationSettings.logScale)));

        Button scaleButton = button(scaleButtonText());
        scaleButton.setOnClickListener(view -> {
            updateVisualizationSettings(new VisualizationSettings(
                    visualizationSettings.fps,
                    visualizationSettings.waveformMs,
                    visualizationSettings.fftSize,
                    visualizationSettings.fftBars,
                    visualizationSettings.smoothing,
                    !visualizationSettings.logScale));
            scaleButton.setText(scaleButtonText());
        });
        root.addView(scaleButton, topMargin(10));
    }

    private void addPlaylistEditor(LinearLayout root) {
        playlistEditorTitle = text(activePlaylistName, 18, Color.rgb(245, 243, 237));
        root.addView(playlistEditorTitle, topMargin(28));

        TextView foldersTitle = text("Folders", 15, Color.rgb(183, 182, 173));
        root.addView(foldersTitle, topMargin(12));
        playlistFoldersContainer = new LinearLayout(this);
        playlistFoldersContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(playlistFoldersContainer, matchWrap());

        TextView filesTitle = text("Files", 15, Color.rgb(183, 182, 173));
        root.addView(filesTitle, topMargin(12));
        playlistFilesContainer = new LinearLayout(this);
        playlistFilesContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(playlistFilesContainer, matchWrap());

        updatePlaylistEditor();
    }

    private void showPlaylistMenu() {
        ArrayList<String> names = new ArrayList<>(playlists.keySet());
        new AlertDialog.Builder(this)
                .setTitle("Playlists")
                .setItems(names.toArray(new String[0]), (dialog, which) -> {
                    if (which >= 0 && which < names.size()) {
                        switchPlaylist(names.get(which));
                    }
                })
                .setPositiveButton("New", (dialog, which) -> showCreatePlaylistDialog())
                .setNeutralButton("Rename", (dialog, which) -> showRenamePlaylistDialog())
                .setNegativeButton("Delete", (dialog, which) -> confirmDeletePlaylist())
                .show();
    }

    private void showCreatePlaylistDialog() {
        EditText input = playlistNameInput("");
        new AlertDialog.Builder(this)
                .setTitle("New playlist")
                .setView(input)
                .setPositiveButton("Create", (dialog, which) -> {
                    String name = cleanPlaylistName(input.getText().toString());
                    if (!isAvailablePlaylistName(name, null)) {
                        Toast.makeText(this, "Choose a unique playlist name", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    persistActivePlaylist();
                    playlists.put(name, new ArrayList<>());
                    PlaylistStore.savePlaylists(this, playlists);
                    switchPlaylist(name);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showRenamePlaylistDialog() {
        EditText input = playlistNameInput(activePlaylistName);
        input.selectAll();
        new AlertDialog.Builder(this)
                .setTitle("Rename playlist")
                .setView(input)
                .setPositiveButton("Rename", (dialog, which) -> {
                    String name = cleanPlaylistName(input.getText().toString());
                    if (!isAvailablePlaylistName(name, activePlaylistName)) {
                        Toast.makeText(this, "Choose a unique playlist name", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (name.equals(activePlaylistName)) {
                        return;
                    }
                    persistActivePlaylist();
                    LinkedHashMap<String, ArrayList<String>> renamed = new LinkedHashMap<>();
                    for (Map.Entry<String, ArrayList<String>> entry : playlists.entrySet()) {
                        renamed.put(
                                entry.getKey().equals(activePlaylistName) ? name : entry.getKey(),
                                new ArrayList<>(entry.getValue()));
                    }
                    playlists.clear();
                    playlists.putAll(renamed);
                    activePlaylistName = name;
                    PlaylistStore.savePlaylists(this, playlists);
                    PlaylistStore.saveActivePlaylistName(this, activePlaylistName);
                    updatePlaylistText();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDeletePlaylist() {
        if (playlists.size() <= 1) {
            Toast.makeText(this, "Keep at least one playlist", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Delete " + activePlaylistName + "?")
                .setMessage("The playlist will be removed. Your audio files will stay on this device.")
                .setPositiveButton("Delete", (dialog, which) -> {
                    playlists.remove(activePlaylistName);
                    activePlaylistName = playlists.keySet().iterator().next();
                    playlist.clear();
                    playlist.addAll(playlists.get(activePlaylistName));
                    PlaylistStore.savePlaylists(this, playlists);
                    PlaylistStore.saveActivePlaylistName(this, activePlaylistName);
                    updatePlaylistText();
                    sendPlaylistToService(false);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void switchPlaylist(String name) {
        if (!playlists.containsKey(name)) {
            return;
        }
        persistActivePlaylist();
        activePlaylistName = name;
        PlaylistStore.saveActivePlaylistName(this, activePlaylistName);
        playlist.clear();
        playlist.addAll(playlists.get(activePlaylistName));
        updatePlaylistText();
        sendPlaylistToService(false);
    }

    private void persistActivePlaylist() {
        playlists.put(activePlaylistName, new ArrayList<>(playlist));
        PlaylistStore.savePlaylists(this, playlists);
        PlaylistStore.saveActivePlaylistName(this, activePlaylistName);
    }

    private EditText playlistNameInput(String initialValue) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(initialValue);
        input.setHint("Playlist name");
        int horizontal = dp(20);
        input.setPadding(horizontal, dp(8), horizontal, dp(8));
        return input;
    }

    private String cleanPlaylistName(String value) {
        String cleaned = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        return cleaned.length() > 60 ? cleaned.substring(0, 60).trim() : cleaned;
    }

    private boolean isAvailablePlaylistName(String name, String currentName) {
        if (name.isEmpty()) {
            return false;
        }
        for (String existing : playlists.keySet()) {
            if (existing.equals(currentName)) {
                continue;
            }
            if (existing.equalsIgnoreCase(name)) {
                return false;
            }
        }
        return true;
    }

    private void openAudioPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "audio/mpeg",
                "audio/flac",
                "audio/x-flac"
        });
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_PICK_AUDIO);
    }

    private void openFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_PICK_FOLDER);
    }

    private void persistReadPermission(Uri uri, int intentFlags) {
        try {
            int flags = intentFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION;
            if (flags == 0) {
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
            }
            getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (RuntimeException ignored) {
        }
    }

    private void collectAudioFromTree(Uri treeUri, String documentId, LinkedHashSet<String> result, int depth) {
        if (depth > MAX_FOLDER_DEPTH) {
            return;
        }

        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId);
        String[] projection = new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
        };

        try (Cursor cursor = getContentResolver().query(childrenUri, projection, null, null, null)) {
            if (cursor == null) {
                return;
            }
            int idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);

            while (cursor.moveToNext()) {
                String childId = idIndex >= 0 ? cursor.getString(idIndex) : null;
                if (childId == null || childId.isEmpty()) {
                    continue;
                }
                String name = nameIndex >= 0 ? cursor.getString(nameIndex) : "";
                String mimeType = mimeIndex >= 0 ? cursor.getString(mimeIndex) : "";
                Uri childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId);

                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                    collectAudioFromTree(treeUri, childId, result, depth + 1);
                } else if (isSupportedAudio(name, mimeType)) {
                    result.add(childUri.toString());
                }
            }
        } catch (RuntimeException ignored) {
        }
    }

    private boolean isSupportedAudio(String name, String mimeType) {
        if (mimeType != null && mimeType.startsWith("audio/")) {
            return true;
        }
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp3")
                || lower.endsWith(".flac")
                || lower.endsWith(".m4a")
                || lower.endsWith(".aac")
                || lower.endsWith(".wav")
                || lower.endsWith(".ogg")
                || lower.endsWith(".opus");
    }

    private void shufflePlaylist() {
        if (playlist.size() < 2) {
            Toast.makeText(this, "Add at least two files first", Toast.LENGTH_SHORT).show();
            return;
        }
        Collections.shuffle(playlist);
        savePlaylistChange("Playlist shuffled");
    }

    private void removePlaylistFolder(String folderKey) {
        int before = playlist.size();
        Iterator<String> iterator = playlist.iterator();
        while (iterator.hasNext()) {
            if (folderKey.equals(playlistFolderKey(iterator.next()))) {
                iterator.remove();
            }
        }
        int removed = before - playlist.size();
        if (removed > 0) {
            savePlaylistChange("Removed " + removed + " files");
        }
    }

    private void removePlaylistFile(String uriString) {
        if (playlist.remove(uriString)) {
            savePlaylistChange("Removed file");
        }
    }

    private void savePlaylistChange(String message) {
        persistActivePlaylist();
        updatePlaylistText();
        if (playlist.isEmpty()) {
            sendServiceCommand(SleepMusicService.ACTION_CLEAR);
        } else {
            sendPlaylistToService(false);
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void updatePlaylistEditor() {
        if (playlistFoldersContainer == null || playlistFilesContainer == null) {
            return;
        }
        if (playlistEditorTitle != null) {
            playlistEditorTitle.setText(activePlaylistName);
        }
        playlistFoldersContainer.removeAllViews();
        playlistFilesContainer.removeAllViews();

        if (playlist.isEmpty()) {
            playlistFoldersContainer.addView(text("No folders in playlist", 14, Color.rgb(183, 182, 173)), matchWrap());
            playlistFilesContainer.addView(text("No files in playlist", 14, Color.rgb(183, 182, 173)), matchWrap());
            return;
        }

        LinkedHashMap<String, Integer> folderCounts = new LinkedHashMap<>();
        for (String item : playlist) {
            String folderKey = playlistFolderKey(item);
            Integer count = folderCounts.get(folderKey);
            folderCounts.put(folderKey, count == null ? 1 : count + 1);
        }

        for (Map.Entry<String, Integer> entry : folderCounts.entrySet()) {
            String folderKey = entry.getKey();
            String label = friendlyFolderLabel(folderKey) + " (" + entry.getValue() + ")";
            addActionRow(playlistFoldersContainer, label, "Remove", view -> removePlaylistFolder(folderKey));
        }

        for (String item : new ArrayList<>(playlist)) {
            addActionRow(playlistFilesContainer, PlaylistStore.displayName(this, item), "Remove", view -> removePlaylistFile(item));
        }
    }

    private void addActionRow(LinearLayout parent, String label, String action, View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(3), 0, dp(3));

        TextView labelView = text(label, 13, Color.rgb(245, 243, 237));
        labelView.setSingleLine(false);
        row.addView(labelView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button button = button(action);
        button.setTextSize(13);
        button.setMinHeight(dp(38));
        button.setOnClickListener(listener);
        row.addView(button, new LinearLayout.LayoutParams(dp(104), ViewGroup.LayoutParams.WRAP_CONTENT));

        parent.addView(row, matchWrap());
    }

    private String playlistFolderKey(String uriString) {
        Uri uri = Uri.parse(uriString);
        String documentId = null;
        try {
            documentId = DocumentsContract.getDocumentId(uri);
        } catch (RuntimeException ignored) {
        }

        String path = documentId;
        if (path == null || path.isEmpty()) {
            path = uri.getLastPathSegment();
        }
        if (path == null || path.isEmpty()) {
            return "Selected files";
        }

        path = Uri.decode(path);
        int colon = path.indexOf(':');
        if (colon >= 0 && colon < path.length() - 1) {
            path = path.substring(colon + 1);
        }
        int slash = path.lastIndexOf('/');
        if (slash <= 0) {
            return "Selected files";
        }
        return path.substring(0, slash);
    }

    private String friendlyFolderLabel(String folderKey) {
        if (folderKey == null || folderKey.trim().isEmpty()) {
            return "Selected files";
        }
        String label = folderKey;
        if (label.startsWith("Music/")) {
            label = label.substring("Music/".length());
        }
        return label.isEmpty() ? "Music" : label;
    }

    private void addSettingsSlider(
            LinearLayout root,
            String label,
            int min,
            int max,
            int initial,
            SliderFormatter formatter,
            SliderChangeListener listener) {
        TextView labelView = text("", 14, Color.rgb(245, 243, 237));
        root.addView(labelView, topMargin(12));

        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(max);
        seekBar.setMin(min);
        int safeInitial = Math.max(min, Math.min(max, initial));
        seekBar.setProgress(safeInitial);
        labelView.setText(label + ": " + formatter.format(safeInitial));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int value = Math.max(min, progress);
                labelView.setText(label + ": " + formatter.format(value));
                if (fromUser) {
                    listener.onChanged(value);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        root.addView(seekBar, matchWrap());
    }

    private void updateLevelingSettings(LevelingSettings settings) {
        levelingSettings = settings;
        PlaylistStore.saveLevelingSettings(this, settings);

        Intent intent = new Intent(this, SleepMusicService.class);
        intent.setAction(SleepMusicService.ACTION_SET_LEVELING_SETTINGS);
        intent.putExtra(SleepMusicService.EXTRA_ANALYSIS_SECONDS, settings.analysisSeconds);
        intent.putExtra(SleepMusicService.EXTRA_LEVEL_ATTACK_MS, settings.levelAttackMs);
        intent.putExtra(SleepMusicService.EXTRA_LEVEL_RELEASE_MS, settings.levelReleaseMs);
        intent.putExtra(SleepMusicService.EXTRA_GAIN_DOWN_MS, settings.gainDownMs);
        intent.putExtra(SleepMusicService.EXTRA_GAIN_UP_MS, settings.gainUpMs);
        intent.putExtra(SleepMusicService.EXTRA_COMPRESSOR_THRESHOLD, settings.compressorThreshold);
        intent.putExtra(SleepMusicService.EXTRA_OUTPUT_CEILING, settings.outputCeiling);
        startServiceCompat(intent);
    }

    private void updateVisualizationSettings(VisualizationSettings settings) {
        visualizationSettings = settings;
        PlaylistStore.saveVisualizationSettings(this, settings);
        if (visualizerView != null) {
            visualizerView.setSmoothing(settings.smoothing);
        }
        sendVisualizationSettingsToService();
    }

    private void sendVisualizationSettingsToService() {
        Intent intent = new Intent(this, SleepMusicService.class);
        intent.setAction(SleepMusicService.ACTION_SET_VISUALIZATION_SETTINGS);
        intent.putExtra(SleepMusicService.EXTRA_VISUAL_FPS, visualizationSettings.fps);
        intent.putExtra(SleepMusicService.EXTRA_VISUAL_WAVEFORM_MS, visualizationSettings.waveformMs);
        intent.putExtra(SleepMusicService.EXTRA_VISUAL_FFT_SIZE, visualizationSettings.fftSize);
        intent.putExtra(SleepMusicService.EXTRA_VISUAL_FFT_BARS, visualizationSettings.fftBars);
        intent.putExtra(SleepMusicService.EXTRA_VISUAL_SMOOTHING, visualizationSettings.smoothing);
        intent.putExtra(SleepMusicService.EXTRA_VISUAL_LOG_SCALE, visualizationSettings.logScale);
        startServiceCompat(intent);
    }

    private String formatTrackText(String title, String artist, String album) {
        if (title == null || title.trim().isEmpty()) {
            return "No song selected";
        }
        String detail;
        if (artist != null && !artist.trim().isEmpty() && album != null && !album.trim().isEmpty()) {
            detail = artist.trim() + "\n" + album.trim();
        } else if (artist != null && !artist.trim().isEmpty()) {
            detail = artist.trim();
        } else {
            detail = album == null ? "" : album.trim();
        }
        return detail.isEmpty() ? title : title + "\n" + detail;
    }

    private void updateCacheText(
            int count,
            int pruneAbove,
            int keep,
            long bytes,
            int visualCount,
            int visualPruneAbove,
            int visualKeep,
            long visualBytes,
            int progressDone,
            int progressTotal) {
        if (cacheText == null) {
            return;
        }
        String progress = progressTotal > 0
                ? ", preparing " + Math.min(progressDone, progressTotal) + "/" + progressTotal
                : "";
        cacheText.setText("Loudness cache: " + count
                + " tracks, prunes above " + pruneAbove
                + ", keeps newest " + keep
                + ", about " + formatBytes(bytes)
                + "\nVisual cache: " + visualCount
                + " tracks, prunes above " + visualPruneAbove
                + ", keeps newest " + visualKeep
                + ", about " + formatBytes(visualBytes)
                + progress);
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024f);
        }
        return String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f));
    }

    private int fftSizeIndex(int fftSize) {
        if (fftSize <= 512) {
            return 0;
        }
        if (fftSize <= 1024) {
            return 1;
        }
        return 2;
    }

    private int fftSizeForIndex(int index) {
        if (index <= 0) {
            return 512;
        }
        if (index == 1) {
            return 1024;
        }
        return 2048;
    }

    private String scaleButtonText() {
        return "FFT scale: " + (visualizationSettings.logScale ? "Log" : "Linear");
    }

    private void sendPlaylistToService(boolean startPlaying) {
        // The playlist itself is not attached to this Intent — for a large
        // remote-URL playlist that can exceed Android's Binder transaction
        // size limit and silently fail. The service re-reads the current
        // playlist from PlaylistStore instead, which every caller of this
        // method has already persisted to before reaching here.
        Intent intent = new Intent(this, SleepMusicService.class);
        intent.setAction(SleepMusicService.ACTION_SET_PLAYLIST);
        intent.putExtra(SleepMusicService.EXTRA_START_PLAYING, startPlaying);
        startServiceCompat(intent);
    }

    private void sendServiceCommand(String action) {
        Intent intent = new Intent(this, SleepMusicService.class);
        intent.setAction(action);
        startServiceCompat(intent);
    }

    private void sendSeekCommand(long positionMs) {
        Intent intent = new Intent(this, SleepMusicService.class);
        intent.setAction(SleepMusicService.ACTION_SEEK);
        intent.putExtra(SleepMusicService.EXTRA_POSITION_MS, Math.max(0L, positionMs));
        startServiceCompat(intent);
    }

    private void sendFloatCommand(String action, String extra, float value) {
        Intent intent = new Intent(this, SleepMusicService.class);
        intent.setAction(action);
        intent.putExtra(extra, value);
        startServiceCompat(intent);
    }

    private void startServiceCompat(Intent intent) {
        startForegroundService(intent);
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerStateReceiver() {
        if (receiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(SleepMusicService.ACTION_STATE_CHANGED);
        filter.addAction(SleepMusicService.ACTION_VISUALIZATION_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, filter);
        }
        receiverRegistered = true;
    }

    private void updatePlaylistText() {
        if (playlistText != null) {
            playlistText.setText(playlistSummary(playlist.size()));
        }
        if (playlist.isEmpty()) {
            if (nowPlayingText != null) {
                nowPlayingText.setText("No song selected");
            }
            playing = false;
            updatePlayButtonIcon();
            updateTrackProgress(0L, 0L);
        }
        updatePlaylistEditor();
    }

    private String playlistSummary(int count) {
        return activePlaylistName + "  •  " + count + (count == 1 ? " song" : " songs");
    }

    private void updateTrackProgress(long positionMs, long durationMs) {
        if (trackSeekBar == null || elapsedTimeText == null || durationTimeText == null) {
            return;
        }
        long safeDuration = Math.max(0L, durationMs);
        long maxDuration = Math.min(Integer.MAX_VALUE, safeDuration);
        long safePosition = Math.max(0L, Math.min(positionMs, maxDuration));
        if (safeDuration <= 0L) {
            trackSeekBar.setEnabled(false);
            trackSeekBar.setMax(1);
            if (!userSeeking) {
                trackSeekBar.setProgress(0);
                elapsedTimeText.setText("0:00");
            }
            durationTimeText.setText("0:00");
            return;
        }
        trackSeekBar.setEnabled(true);
        trackSeekBar.setMax((int) Math.max(1L, maxDuration));
        if (!userSeeking) {
            trackSeekBar.setProgress((int) safePosition);
            elapsedTimeText.setText(formatTime(safePosition));
        }
        durationTimeText.setText(formatTime(safeDuration));
    }

    private String formatTime(long milliseconds) {
        long totalSeconds = Math.max(0L, milliseconds) / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0L) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(16);
        button.setMinHeight(dp(48));
        return button;
    }

    private ImageButton transportButton(int iconResId, String description) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(iconResId);
        button.setContentDescription(description);
        button.setColorFilter(Color.rgb(245, 243, 237));
        button.setBackground(transportBackground(false));
        button.setScaleType(ImageButton.ScaleType.CENTER);
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        return button;
    }

    private void updatePlayButtonIcon() {
        if (playButton == null) {
            return;
        }
        playButton.setImageResource(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
        playButton.setContentDescription(playing ? "Pause" : "Play");
        playButton.setBackground(transportBackground(true));
    }

    private GradientDrawable transportBackground(boolean primary) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(primary ? Color.rgb(45, 112, 91) : Color.rgb(35, 41, 46));
        drawable.setStroke(dp(1), primary ? Color.rgb(118, 222, 190) : Color.rgb(82, 91, 99));
        return drawable;
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setIncludeFontPadding(true);
        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams topMargin(int dp) {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(dp);
        return params;
    }

    private LinearLayout.LayoutParams weightedButton() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.leftMargin = dp(5);
        params.rightMargin = dp(5);
        return params;
    }

    private LinearLayout.LayoutParams transportButtonParams(boolean primary) {
        int size = primary ? dp(72) : dp(58);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        params.leftMargin = dp(7);
        params.rightMargin = dp(7);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private interface SliderFormatter {
        String format(int value);
    }

    private interface SliderChangeListener {
        void onChanged(int value);
    }
}
