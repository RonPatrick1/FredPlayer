package com.silveronstudios.fredplayer;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.InputType;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@SuppressLint("SetTextI18n")
public class MainActivity extends Activity {
    private static final int REQUEST_PICK_AUDIO = 1001;
    private static final int REQUEST_NOTIFICATIONS = 1002;
    private static final int REQUEST_PICK_FOLDER = 1003;
    private static final int REQUEST_AUDIO_CALIBRATION = 1004;
    private static final int MAX_FOLDER_DEPTH = 12;

    private static final class ServerBrowserEntry {
        final String folderPath;
        final int folderTrackCount;
        final int trackIndex;

        private ServerBrowserEntry(String folderPath, int folderTrackCount, int trackIndex) {
            this.folderPath = folderPath;
            this.folderTrackCount = folderTrackCount;
            this.trackIndex = trackIndex;
        }

        static ServerBrowserEntry folder(String path, int count) {
            return new ServerBrowserEntry(path, count, -1);
        }

        static ServerBrowserEntry track(int index) {
            return new ServerBrowserEntry(null, 0, index);
        }

        boolean isFolder() {
            return folderPath != null;
        }
    }

    private static final String STATE_SHOWING_PHONE_LYRICS = "showingPhoneLyrics";

    private final ArrayList<String> playlist = new ArrayList<>();
    private final LinkedHashMap<String, ArrayList<String>> playlists = new LinkedHashMap<>();
    private String activePlaylistName = PlaylistStore.DEFAULT_PLAYLIST_NAME;
    private boolean receiverRegistered;
    private boolean playing;
    private boolean shuffleEnabled = true;
    private String currentTrackUri = "";
    private String currentTrackName = "";
    private String currentTrackArtist = "";
    private String currentTrackAlbum = "";
    private int repeatMode = SleepMusicService.REPEAT_ALL;
    private int currentIndex = -1;
    private int[] shuffleBag = new int[0];
    private int[] playHistory = new int[0];
    private int historyIndex = -1;
    private boolean showingSettings;
    private boolean showingLyrics;
    private boolean showingWhatsNext;
    private boolean showingPlaylistEditor;
    private boolean showingPlaylistMenu;
    private boolean showingSharedPlaylists;
    private LinearLayout lyricsPhrasesContainer;
    private ScrollView lyricsScrollView;
    private TextView lyricsStatusText;
    private TextView lyricsSubtitleView;
    private ImageButton lyricsPlayButton;
    private List<LyricsPhrase> lyricsPhrases = new ArrayList<>();
    private List<TextView> lyricsPhraseViews = new ArrayList<>();
    private int lyricsActivePhraseIndex = -1;
    private LinearLayout whatsNextListContainer;
    private EditText whatsNextSearch;
    private ScrollView whatsNextScroll;
    // Only materializes this many "Up Next" rows as real views at a time —
    // building one per remaining track (thousands, for a large playlist)
    // is what made this screen slow to open/refresh. Scrolling near the
    // bottom grows the limit and re-renders, so the extra work only
    // happens once the user actually asks to see further ahead.
    private int whatsNextUpcomingLimit = 50;
    private int whatsNextUpcomingTotal = 0;
    private String lyricsLoadedForTrackUri = "";
    private long lyricsLastKnownPositionMs;
    private long lyricsLastKnownElapsedRealtime;
    private boolean lyricsLastKnownPlaying;
    private final Handler lyricsTickHandler = new Handler(Looper.getMainLooper());
    private final Runnable lyricsTick = this::updateLyricsHighlightTick;
    private boolean userSeeking;
    private boolean metadataRefreshStarted;

    private static final int ARTWORK_MEMORY_CACHE_MAX = 24;
    private final Map<String, Bitmap> artworkMemoryCache = new LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            return size() > ARTWORK_MEMORY_CACHE_MAX;
        }
    };
    private String artworkRequestKey = "";

    private ImageView artImageView;
    private View artScrim;
    private TextView nowPlayingText;
    private TextView playlistText;
    private TextView settingsPlaylistLabel;
    private TextView elapsedTimeText;
    private TextView durationTimeText;
    private TextView stateText;
    private TextView outputText;
    private TextView levelingText;
    private TextView cacheText;
    private ImageButton playButton;
    private ImageButton shuffleButton;
    private ImageButton repeatButton;
    private SeekBar outputSlider;
    private SeekBar levelingSlider;
    private SeekBar trackSeekBar;
    private VisualizerView visualizerView;
    private TextView bluetoothRouteText;
    private TextView bluetoothDelayText;
    private SeekBar bluetoothDelaySlider;
    private Button bluetoothCalibrateButton;
    private LinearLayout bluetoothSavedListContainer;
    private String bluetoothSavedListSignature;
    private LevelingSettings levelingSettings;
    private VisualizationSettings visualizationSettings;
    private String outputRouteKey = "";
    private String outputRouteName = "No active output";
    private boolean outputRouteBluetooth;
    private boolean outputDelayCalibrating;
    private int outputVisualDelayMs;

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
            String trackUri = intent.getStringExtra(SleepMusicService.EXTRA_TRACK_URI);
            currentTrackName = track == null ? "" : track;
            currentTrackArtist = artist == null ? "" : artist;
            currentTrackAlbum = album == null ? "" : album;
            currentTrackUri = trackUri == null ? "" : trackUri;
            lyricsLastKnownPositionMs = intent.getLongExtra(SleepMusicService.EXTRA_POSITION_MS, 0L);
            lyricsLastKnownElapsedRealtime = SystemClock.elapsedRealtime();
            lyricsLastKnownPlaying = playing;
            if (showingLyrics) {
                updateLyricsSubtitle();
                if (!currentTrackUri.equals(lyricsLoadedForTrackUri)) {
                    loadLyricsForCurrentTrack();
                }
            }
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
            outputRouteKey = intent.getStringExtra(SleepMusicService.EXTRA_OUTPUT_ROUTE_KEY);
            if (outputRouteKey == null) {
                outputRouteKey = "";
            }
            outputRouteName = intent.getStringExtra(SleepMusicService.EXTRA_OUTPUT_ROUTE_NAME);
            if (outputRouteName == null || outputRouteName.isEmpty()) {
                outputRouteName = "No active output";
            }
            outputRouteBluetooth = intent.getBooleanExtra(
                    SleepMusicService.EXTRA_OUTPUT_ROUTE_BLUETOOTH,
                    false);
            outputVisualDelayMs = intent.getIntExtra(
                    SleepMusicService.EXTRA_OUTPUT_VISUAL_DELAY_MS,
                    0);
            outputDelayCalibrating = intent.getBooleanExtra(
                    SleepMusicService.EXTRA_OUTPUT_DELAY_CALIBRATING,
                    false);
            shuffleEnabled = intent.getBooleanExtra(SleepMusicService.EXTRA_SHUFFLE_ENABLED, shuffleEnabled);
            repeatMode = intent.getIntExtra(SleepMusicService.EXTRA_REPEAT_MODE, repeatMode);
            currentIndex = intent.getIntExtra(SleepMusicService.EXTRA_CURRENT_INDEX, currentIndex);
            int[] receivedShuffleBag = intent.getIntArrayExtra(SleepMusicService.EXTRA_SHUFFLE_BAG);
            if (receivedShuffleBag != null) {
                shuffleBag = receivedShuffleBag;
            }
            int[] receivedPlayHistory = intent.getIntArrayExtra(SleepMusicService.EXTRA_PLAY_HISTORY);
            if (receivedPlayHistory != null) {
                playHistory = receivedPlayHistory;
            }
            historyIndex = intent.getIntExtra(SleepMusicService.EXTRA_HISTORY_INDEX, historyIndex);
            if (showingWhatsNext) {
                refreshWhatsNextList(true);
            }

            updatePlayButtonIcon();
            updateShuffleRepeatButtons();
            if (nowPlayingText != null) {
                nowPlayingText.setText(formatTrackText(track, artist, album));
            }
            updateArtwork(trackUri, artist, album);
            if (stateText != null) {
                stateText.setText(message == null || message.isEmpty() ? (playing ? "Playing" : "Paused") : message);
            }
            if (playlistText != null) {
                playlistText.setText(playlistSummary(count));
            }
            updateTrackProgress(positionMs, durationMs);
            updateBluetoothDelayControls();
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
        showingLyrics = savedInstanceState != null
                && savedInstanceState.getBoolean(STATE_SHOWING_PHONE_LYRICS, false)
                && !isTabletConfiguration();
        setContentView(showingLyrics ? buildLyricsView() : buildContentView());
        if (!showingLyrics) {
            updatePlaylistText();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(
                STATE_SHOWING_PHONE_LYRICS,
                showingLyrics && !isTabletConfiguration());
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onBackPressed() {
        if (showingPlaylistEditor || showingPlaylistMenu || showingSharedPlaylists) {
            showSettingsScreen();
            return;
        }
        if (showingSettings || showingLyrics || showingWhatsNext) {
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
        refreshRemoteMetadataIfNeeded();
        if (showingLyrics) {
            sendServiceCommand(SleepMusicService.ACTION_REQUEST_STATE);
            lyricsTickHandler.removeCallbacks(lyricsTick);
            lyricsTickHandler.post(lyricsTick);
        }
    }

    @Override
    protected void onStop() {
        if (receiverRegistered) {
            unregisterReceiver(stateReceiver);
            receiverRegistered = false;
        }
        lyricsTickHandler.removeCallbacks(lyricsTick);
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

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_AUDIO_CALIBRATION) {
            return;
        }
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            sendServiceCommand(SleepMusicService.ACTION_CALIBRATE_OUTPUT_DELAY);
        } else {
            Toast.makeText(
                    this,
                    "Microphone permission is only needed while calibrating",
                    Toast.LENGTH_LONG).show();
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
        String savedUrl = PlaylistStore.loadServerBaseUrl(this);
        if (!savedUrl.isEmpty()) {
            fetchServerLibrary(savedUrl, PlaylistStore.loadServerToken(this));
            return;
        }
        openServerConnectionDialog();
    }

    private void openServerConnectionDialog() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int horizontal = dp(20);
        container.setPadding(horizontal, dp(8), horizontal, dp(8));

        TextView serverDisclosure = text(
                "FredPlayer sends the token and playback requests only to this server. It may upload derived visualization, leveling, and shared-playlist data; microphone audio is never uploaded.",
                13,
                Color.rgb(183, 182, 173));
        container.addView(serverDisclosure);

        EditText urlInput = new EditText(this);
        urlInput.setSingleLine(true);
        urlInput.setHint("Server URL, e.g. https://host/fredplayer-media");
        urlInput.setText(PlaylistStore.loadServerBaseUrl(this));
        container.addView(urlInput, topMargin(8));

        EditText tokenInput = new EditText(this);
        tokenInput.setSingleLine(true);
        tokenInput.setHint("Access token");
        tokenInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        tokenInput.setText(PlaylistStore.loadServerToken(this));
        container.addView(tokenInput, topMargin(10));

        new AlertDialog.Builder(this)
                .setTitle("Server connection")
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
                PlaylistStore.saveTrackMetadata(this, serverMetadata(tracks, url));
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
        LinkedHashSet<Integer> selectedTracks = new LinkedHashSet<>();
        ArrayList<ServerBrowserEntry> entries = new ArrayList<>();
        String[] currentFolder = {""};

        // A plain AlertDialog puts its action buttons in a bar below the
        // custom view, and when that view (a whole server library browser)
        // is taller than the available space, the button bar can end up
        // scrolled off-screen along with the list instead of staying put.
        // Using a plain Dialog with the button row as a fixed, non-scrolling
        // sibling of a weighted ListView guarantees "Add selected"/"Add all
        // music"/"Cancel" are always visible without scrolling past tracks.
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout container = settingsCard();
        container.setPadding(dp(16), dp(16), dp(16), dp(14));

        TextView dialogTitle = text("Add from server", 20, Color.rgb(245, 243, 237));
        container.addView(dialogTitle, matchWrap());

        LinearLayout folderRow = new LinearLayout(this);
        folderRow.setOrientation(LinearLayout.HORIZONTAL);
        folderRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton dialogBackButton = transportButton(R.drawable.ic_back_arrow, "Close");
        dialogBackButton.setOnClickListener(view -> dialog.dismiss());
        folderRow.addView(dialogBackButton, headerIconButtonParams());
        ImageButton upButton = transportButton(R.drawable.ic_folder_up, "Up a folder");
        folderRow.addView(upButton, headerIconButtonParams());
        TextView folderLabel = text("All music", 16, Color.rgb(245, 243, 237));
        folderLabel.setSingleLine(true);
        folderRow.addView(folderLabel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView selectionLabel = text("0 selected", 13, Color.rgb(183, 182, 173));
        folderRow.addView(selectionLabel);
        container.addView(folderRow, topMargin(14));

        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Search titles, artists, albums, or folders");
        container.addView(search, topMargin(6));

        ListView list = new ListView(this);
        container.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        ArrayAdapter<ServerBrowserEntry> adapter = new ArrayAdapter<ServerBrowserEntry>(
                this, android.R.layout.simple_list_item_2, android.R.id.text1, entries) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View row = super.getView(position, convertView, parent);
                TextView primary = row.findViewById(android.R.id.text1);
                TextView secondary = row.findViewById(android.R.id.text2);
                ServerBrowserEntry entry = getItem(position);
                if (entry == null) {
                    return row;
                }
                if (entry.isFolder()) {
                    primary.setText("Folder  " + serverFolderName(entry.folderPath));
                    secondary.setText(entry.folderTrackCount +
                            (entry.folderTrackCount == 1 ? " track" : " tracks") + "  ›");
                } else {
                    JSONObject track = tracks.optJSONObject(entry.trackIndex);
                    String path = track == null ? "" : track.optString("path", "");
                    String title = track == null ? "" : track.optString("title", "");
                    if (title.isEmpty()) {
                        int slash = path.lastIndexOf('/');
                        title = slash >= 0 ? path.substring(slash + 1) : path;
                    }
                    primary.setText((selectedTracks.contains(entry.trackIndex) ? "✓  " : "○  ") + title);
                    String artist = track == null ? "" : track.optString("artist", "");
                    String album = track == null ? "" : track.optString("album", "");
                    String subtitle = artist;
                    if (!album.isEmpty()) {
                        subtitle += (subtitle.isEmpty() ? "" : " — ") + album;
                    }
                    secondary.setText(subtitle.isEmpty() ? serverTrackFolder(path) : subtitle);
                }
                return row;
            }
        };
        list.setAdapter(adapter);

        // Side by side (each taking half the row) instead of stacked, so
        // the button area takes less vertical space and leaves more room
        // for the file/folder list above. Wrap-content text inside a weighted
        // half-width slot will wrap to a second line rather than clip if a
        // folder name/count makes the label long.
        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER_VERTICAL);
        container.addView(buttonRow, topMargin(14));

        Button addSelectedButton = settingsButton("Add selected tracks", SETTINGS_STYLE_PRIMARY);
        LinearLayout.LayoutParams addSelectedParams =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        addSelectedParams.rightMargin = dp(8);
        buttonRow.addView(addSelectedButton, addSelectedParams);

        Button addFolderButton = settingsButton("Add all music", SETTINGS_STYLE_SECONDARY);
        buttonRow.addView(addFolderButton,
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        dialog.setContentView(container);

        Runnable updateActions = () -> {
            selectionLabel.setText(selectedTracks.size() + " selected");
            addSelectedButton.setText(selectedTracks.isEmpty()
                    ? "Add selected tracks"
                    : "Add selected (" + selectedTracks.size() + ")");
            addSelectedButton.setEnabled(!selectedTracks.isEmpty());
            addSelectedButton.setAlpha(selectedTracks.isEmpty() ? 0.5f : 1f);
            int count = countServerTracksInFolder(tracks, currentFolder[0]);
            addFolderButton.setText(currentFolder[0].isEmpty()
                    ? "Add all music (" + count + ")"
                    : "Add folder (" + count + ")");
            addFolderButton.setEnabled(count > 0);
            addFolderButton.setAlpha(count > 0 ? 1f : 0.5f);
        };

        Runnable refreshBrowser = () -> {
            entries.clear();
            String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            LinkedHashMap<String, Integer> childFolderCounts = new LinkedHashMap<>();
            ArrayList<Integer> visibleTracks = new ArrayList<>();
            for (int i = 0; i < tracks.length(); i++) {
                JSONObject track = tracks.optJSONObject(i);
                String path = track == null ? "" : track.optString("path", "");
                if (path.isEmpty() || !serverTrackIsInFolder(path, currentFolder[0])) {
                    continue;
                }
                if (!query.isEmpty()) {
                    if (serverTrackMatches(track, query)) {
                        visibleTracks.add(i);
                    }
                    continue;
                }
                String childFolder = immediateServerChildFolder(path, currentFolder[0]);
                if (childFolder != null) {
                    childFolderCounts.put(childFolder,
                            childFolderCounts.getOrDefault(childFolder, 0) + 1);
                } else {
                    visibleTracks.add(i);
                }
            }
            ArrayList<String> childFolders = new ArrayList<>(childFolderCounts.keySet());
            childFolders.sort(String.CASE_INSENSITIVE_ORDER);
            for (String child : childFolders) {
                entries.add(ServerBrowserEntry.folder(child, childFolderCounts.get(child)));
            }
            visibleTracks.sort(Comparator.comparing(index ->
                    serverTrackSortLabel(tracks.optJSONObject(index)), String.CASE_INSENSITIVE_ORDER));
            for (int index : visibleTracks) {
                entries.add(ServerBrowserEntry.track(index));
            }
            adapter.notifyDataSetChanged();
            list.setSelection(0);
            folderLabel.setText(currentFolder[0].isEmpty() ? "All music" : currentFolder[0]);
            upButton.setEnabled(!currentFolder[0].isEmpty());
            updateActions.run();
        };

        list.setOnItemClickListener((parent, view, position, id) -> {
            ServerBrowserEntry entry = entries.get(position);
            if (entry.isFolder()) {
                currentFolder[0] = entry.folderPath;
                search.setText("");
                refreshBrowser.run();
                return;
            }
            if (!selectedTracks.add(entry.trackIndex)) {
                selectedTracks.remove(entry.trackIndex);
            }
            adapter.notifyDataSetChanged();
            updateActions.run();
        });
        upButton.setOnClickListener(view -> {
            currentFolder[0] = parentServerFolder(currentFolder[0]);
            search.setText("");
            refreshBrowser.run();
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                refreshBrowser.run();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        addSelectedButton.setOnClickListener(view -> {
            addServerTrackIndices(tracks, baseUrl, new ArrayList<>(selectedTracks));
            dialog.dismiss();
        });
        addFolderButton.setOnClickListener(view -> {
            ArrayList<Integer> folderTracks = new ArrayList<>();
            for (int i = 0; i < tracks.length(); i++) {
                JSONObject track = tracks.optJSONObject(i);
                String path = track == null ? "" : track.optString("path", "");
                if (serverTrackIsInFolder(path, currentFolder[0])) {
                    folderTracks.add(i);
                }
            }
            addServerTrackIndices(tracks, baseUrl, folderTracks);
            dialog.dismiss();
        });
        refreshBrowser.run();

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        dialog.show();
        if (window != null) {
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.94f);
            int height = (int) (getResources().getDisplayMetrics().heightPixels * 0.85f);
            window.setLayout(width, height);
        }
    }

    private String serverTrackFolder(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private String serverFolderName(String folder) {
        int slash = folder.lastIndexOf('/');
        return slash < 0 ? folder : folder.substring(slash + 1);
    }

    private String parentServerFolder(String folder) {
        int slash = folder.lastIndexOf('/');
        return slash < 0 ? "" : folder.substring(0, slash);
    }

    private boolean serverTrackIsInFolder(String trackPath, String folder) {
        return !trackPath.isEmpty() &&
                (folder.isEmpty() || trackPath.startsWith(folder + "/"));
    }

    private String immediateServerChildFolder(String trackPath, String folder) {
        String trackFolder = serverTrackFolder(trackPath);
        if (trackFolder.equals(folder)) {
            return null;
        }
        String prefix = folder.isEmpty() ? "" : folder + "/";
        if (!trackFolder.startsWith(prefix)) {
            return null;
        }
        String remainder = trackFolder.substring(prefix.length());
        int slash = remainder.indexOf('/');
        String child = slash < 0 ? remainder : remainder.substring(0, slash);
        return child.isEmpty() ? null : prefix + child;
    }

    private int countServerTracksInFolder(JSONArray tracks, String folder) {
        int count = 0;
        for (int i = 0; i < tracks.length(); i++) {
            JSONObject track = tracks.optJSONObject(i);
            if (track != null && serverTrackIsInFolder(track.optString("path", ""), folder)) {
                count++;
            }
        }
        return count;
    }

    private boolean serverTrackMatches(JSONObject track, String query) {
        if (track == null) {
            return false;
        }
        String searchable = track.optString("path", "") + "\n" +
                track.optString("title", "") + "\n" +
                track.optString("artist", "") + "\n" +
                track.optString("album", "");
        return searchable.toLowerCase(Locale.ROOT).contains(query);
    }

    private String serverTrackSortLabel(JSONObject track) {
        if (track == null) {
            return "";
        }
        String title = track.optString("title", "");
        return title.isEmpty() ? track.optString("path", "") : title;
    }

    private void addServerTrackIndices(
            JSONArray tracks, String baseUrl, Iterable<Integer> indices) {
        LinkedHashSet<String> merged = new LinkedHashSet<>(playlist);
        Map<String, String[]> metadataOut = new HashMap<>();
        int added = 0;
        for (int index : indices) {
            if (addServerTrack(merged, tracks, index, baseUrl, metadataOut)) {
                added++;
            }
        }
        PlaylistStore.saveTrackMetadata(this, metadataOut);
        reportServerAdd(merged, added);
    }

    private boolean addServerTrack(LinkedHashSet<String> merged, JSONArray tracks, int index, String baseUrl,
                                    Map<String, String[]> metadataOut) {
        JSONObject track = tracks.optJSONObject(index);
        String path = track == null ? null : track.optString("path", null);
        if (path == null || path.isEmpty()) {
            return false;
        }
        String url = RemoteLibraryClient.buildStreamUrl(baseUrl, path);
        String title = track.optString("title", "");
        String artist = track.optString("artist", "");
        String album = track.optString("album", "");
        if (!title.isEmpty() || !artist.isEmpty() || !album.isEmpty()) {
            metadataOut.put(url, new String[]{title, artist, album});
        }
        return merged.add(url);
    }

    private void reportServerAdd(LinkedHashSet<String> merged, int added) {
        if (added == 0) {
            Toast.makeText(this, "No new songs were added", Toast.LENGTH_SHORT).show();
            return;
        }
        saveMergedPlaylist(merged, "Added " + added + " songs from server");
    }

    private Map<String, String[]> serverMetadata(JSONArray tracks, String baseUrl) {
        Map<String, String[]> metadata = new HashMap<>();
        for (int i = 0; i < tracks.length(); i++) {
            JSONObject track = tracks.optJSONObject(i);
            String path = track == null ? "" : track.optString("path", "");
            if (path.isEmpty()) {
                continue;
            }
            String title = track.optString("title", "");
            String artist = track.optString("artist", "");
            String album = track.optString("album", "");
            if (!title.isEmpty() || !artist.isEmpty() || !album.isEmpty()) {
                metadata.put(
                        RemoteLibraryClient.buildStreamUrl(baseUrl, path),
                        new String[]{title, artist, album});
            }
        }
        return metadata;
    }

    private void openSharedPlaylists() {
        String baseUrl = PlaylistStore.loadServerBaseUrl(this);
        String token = PlaylistStore.loadServerToken(this);
        if (baseUrl.isEmpty()) {
            Toast.makeText(
                    this,
                    "Set up the Fred Server first with Add from server",
                    Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(this, "Fetching shared playlists…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                JSONArray summaries = RemoteLibraryClient.fetchPlaylists(baseUrl, token);
                JSONArray library = RemoteLibraryClient.fetchLibrary(baseUrl, token);
                PlaylistStore.saveTrackMetadata(this, serverMetadata(library, baseUrl));
                runOnUiThread(() -> showSharedPlaylistsScreen(summaries, library, baseUrl, token));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(
                        this,
                        "Could not load shared playlists: " + e.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        }, "FredPlayerSharedPlaylists").start();
    }

    private View buildSharedPlaylistsView(
            JSONArray summaries,
            JSONArray library,
            String baseUrl,
            String token) {
        ArrayList<String> labels = new ArrayList<>();
        for (int i = 0; i < summaries.length(); i++) {
            JSONObject summary = summaries.optJSONObject(i);
            String name = summary == null ? "" : summary.optString("name", "");
            int count = summary == null ? 0 : summary.optInt("count", 0);
            labels.add(name + "  •  " + count + (count == 1 ? " song" : " songs"));
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(17, 19, 21));
        applySystemBarInsets(root, dp(20), dp(24), dp(20), dp(24));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(header, matchWrap());

        ImageButton backButton = transportButton(R.drawable.ic_back_arrow, "Back");
        backButton.setOnClickListener(view -> showSettingsScreen());
        header.addView(backButton, headerIconButtonParams());

        TextView title = text("Shared playlists", 26, Color.rgb(245, 243, 237));
        title.setGravity(Gravity.END);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        boolean hasShared = !labels.isEmpty();
        if (hasShared) {
            ListView list = new ListView(this);
            LinearLayout.LayoutParams listParams =
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            listParams.topMargin = dp(14);
            root.addView(list, listParams);
            ArrayAdapter<String> adapter = new ArrayAdapter<>(
                    this, android.R.layout.simple_list_item_1, android.R.id.text1, labels);
            list.setAdapter(adapter);
            list.setOnItemClickListener((parent, view, position, id) -> {
                JSONObject summary = summaries.optJSONObject(position);
                showSettingsScreen();
                if (summary != null) {
                    downloadSharedPlaylist(summary.optString("name", ""), library, baseUrl, token);
                }
            });
        } else {
            TextView emptyText = text(
                    "No playlists have been shared yet. Share \"" + activePlaylistName
                            + "\" to publish a server copy.",
                    14, Color.rgb(183, 182, 173));
            root.addView(emptyText, topMargin(16));
        }

        Button shareButton = settingsButton("Share \"" + activePlaylistName + "\"", SETTINGS_STYLE_PRIMARY);
        shareButton.setOnClickListener(view -> confirmShareCurrentPlaylist(summaries, baseUrl, token));
        root.addView(shareButton, actionButtonParams(16));

        return root;
    }

    private void confirmShareCurrentPlaylist(JSONArray summaries, String baseUrl, String token) {
        persistActivePlaylist();
        if (playlist.isEmpty()) {
            Toast.makeText(this, "Add songs to \"" + activePlaylistName + "\" before sharing",
                    Toast.LENGTH_LONG).show();
            return;
        }

        JSONArray serverPaths = new JSONArray();
        for (String item : playlist) {
            String path = RemoteLibraryClient.serverPath(baseUrl, item);
            if (path == null) {
                new AlertDialog.Builder(this)
                        .setTitle("Can’t share \"" + activePlaylistName + "\"")
                        .setMessage("Every song must come from this Fred Server. Local files and songs from another server cannot be played by the other devices.")
                        .setPositiveButton("OK", null)
                        .show();
                return;
            }
            serverPaths.put(path);
        }

        boolean replacesExisting = false;
        for (int i = 0; i < summaries.length(); i++) {
            JSONObject summary = summaries.optJSONObject(i);
            if (summary != null
                    && activePlaylistName.equalsIgnoreCase(summary.optString("name", ""))) {
                replacesExisting = true;
                break;
            }
        }
        if (!replacesExisting) {
            shareCurrentPlaylist(baseUrl, token, serverPaths);
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Update shared playlist?")
                .setMessage("Replace the server copy of \"" + activePlaylistName + "\" with the current "
                        + playlist.size() + (playlist.size() == 1 ? " song?" : " songs?"))
                .setPositiveButton("Update", (dialog, which) ->
                        shareCurrentPlaylist(baseUrl, token, serverPaths))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void shareCurrentPlaylist(String baseUrl, String token, JSONArray serverPaths) {
        String name = activePlaylistName;
        Toast.makeText(this, "Sharing " + name + "…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                RemoteLibraryClient.sharePlaylist(baseUrl, token, name, serverPaths);
                runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle("Playlist shared")
                        .setMessage("\"" + name + "\" is on the server for other devices to download. Deleting this device’s copy will not remove the server copy.")
                        .setPositiveButton("OK", null)
                        .show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(
                        this,
                        "Could not share playlist: " + e.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        }, "FredPlayerSharePlaylist").start();
    }

    private void downloadSharedPlaylist(
            String name,
            JSONArray library,
            String baseUrl,
            String token) {
        if (name.isEmpty()) {
            return;
        }
        Toast.makeText(this, "Downloading " + name + "…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                JSONArray paths = RemoteLibraryClient.fetchPlaylistTracks(baseUrl, token, name);
                Map<String, JSONObject> libraryByPath = new HashMap<>();
                for (int i = 0; i < library.length(); i++) {
                    JSONObject track = library.optJSONObject(i);
                    if (track != null) {
                        libraryByPath.put(track.optString("path", ""), track);
                    }
                }
                ArrayList<String> urls = new ArrayList<>();
                Map<String, String[]> metadata = new HashMap<>();
                for (int i = 0; i < paths.length(); i++) {
                    String path = paths.optString(i, "");
                    if (path.isEmpty()) {
                        continue;
                    }
                    String url = RemoteLibraryClient.buildStreamUrl(baseUrl, path);
                    urls.add(url);
                    JSONObject track = libraryByPath.get(path);
                    if (track != null) {
                        metadata.put(url, new String[]{
                                track.optString("title", ""),
                                track.optString("artist", ""),
                                track.optString("album", ""),
                        });
                    }
                }
                PlaylistStore.saveTrackMetadata(this, metadata);
                runOnUiThread(() -> installSharedPlaylist(name, urls));
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(
                        this,
                        "Could not download playlist: " + e.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        }, "FredPlayerDownloadPlaylist").start();
    }

    private void installSharedPlaylist(String sharedName, ArrayList<String> urls) {
        if (urls.isEmpty()) {
            Toast.makeText(this, "That shared playlist has no playable songs", Toast.LENGTH_LONG).show();
            return;
        }
        String localName = uniquePlaylistName(sharedName);
        persistActivePlaylist();
        playlists.put(localName, new ArrayList<>(urls));
        PlaylistStore.savePlaylists(this, playlists);
        switchPlaylist(localName);
        new AlertDialog.Builder(this)
                .setTitle("Playlist downloaded")
                .setMessage("Saved \"" + localName + "\" on this device. You can change or delete it without changing the shared server copy.")
                .setPositiveButton("OK", null)
                .show();
    }

    private void refreshRemoteMetadataIfNeeded() {
        if (metadataRefreshStarted) {
            return;
        }
        Map<String, String[]> cached = PlaylistStore.loadAllTrackMetadata(this);
        boolean missing = false;
        for (ArrayList<String> tracks : playlists.values()) {
            for (String item : tracks) {
                if (RemoteLibraryClient.isRemote(item) && !cached.containsKey(item)) {
                    missing = true;
                    break;
                }
            }
            if (missing) {
                break;
            }
        }
        String baseUrl = PlaylistStore.loadServerBaseUrl(this);
        if (!missing || baseUrl.isEmpty()) {
            return;
        }

        metadataRefreshStarted = true;
        String token = PlaylistStore.loadServerToken(this);
        new Thread(() -> {
            try {
                JSONArray tracks = RemoteLibraryClient.fetchLibrary(baseUrl, token);
                PlaylistStore.saveTrackMetadata(this, serverMetadata(tracks, baseUrl));
            } catch (Exception ignored) {
                // Playback metadata still falls back to the filename when the server is unavailable.
            }
        }, "FredPlayerMetadataRefresh").start();
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

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(horizontal, dp(8), horizontal, dp(8));
        TextView disclosure = text(
                "Your prompt and a random app identifier are sent only to your configured Fred Server.",
                13,
                Color.rgb(183, 182, 173));
        container.addView(disclosure);
        container.addView(input, topMargin(8));

        new AlertDialog.Builder(this)
                .setTitle("Ask Liam")
                .setView(container)
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
        refreshRemoteMetadataIfNeeded();
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
        return PlaylistStore.loadDeviceId(this);
    }

    private View buildContentView() {
        // Deliberately not a ScrollView — every element on this page is
        // sized (including the compact/landscape trims above) to always
        // fit the viewport, so this page never scrolls, in either
        // orientation. A ScrollView here would just be a way for a future
        // layout tweak to silently reintroduce spillover.
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(Color.rgb(17, 19, 21));
        applySystemBarInsets(root, dp(20), dp(28), dp(20), dp(28));

        boolean compactHeader = isCompactLandscapePhone();

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        if (!compactHeader) {
            root.addView(header, matchWrap());
        }
        // In compact landscape, buildNowPlayingSection places header itself
        // — floated over the art instead of in normal flow above it, so
        // the art fills that vertical space too instead of losing it.

        TextView title = text("FredPlayer", compactHeader ? 20 : 32, Color.rgb(245, 243, 237));
        if (compactHeader) {
            // Slid over next to Settings instead of anchored on the left.
            View headerSpacer = new View(this);
            header.addView(headerSpacer, new LinearLayout.LayoutParams(0, 0, 1f));
            LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            titleParams.rightMargin = dp(14);
            header.addView(title, titleParams);
        } else {
            header.addView(title, new LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f));
        }

        ImageButton lyricsButton = transportButton(R.drawable.ic_lyrics, "Lyrics");
        lyricsButton.setOnClickListener(view -> showLyricsScreen());
        header.addView(lyricsButton, headerIconButtonParams());

        ImageButton whatsNextButton = transportButton(R.drawable.ic_whats_next, "What's Next");
        whatsNextButton.setOnClickListener(view -> showWhatsNextScreen());
        header.addView(whatsNextButton, headerIconButtonParams());

        ImageButton settingsButton = transportButton(R.drawable.ic_settings_gear, "Settings");
        settingsButton.setOnClickListener(view -> showSettingsScreen());
        header.addView(settingsButton, headerIconButtonParams());

        stateText = text("Paused", 17, Color.rgb(183, 182, 173));
        stateText.setGravity(Gravity.CENTER);

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

        boolean compactLandscape = isCompactLandscapePhone();
        boolean isTablet = isTabletConfiguration();
        // Tablets have room to spare (168dp). A landscape phone puts the
        // visualizer beside the art/track-info column instead of stacking
        // it below (see buildNowPlayingSection), so it just fills whatever
        // height that row ends up with. A portrait phone only needs a
        // modest trim — just enough to cover the full-width square art
        // above without pushing the transport buttons off screen.
        int visualizerMinDp = isTablet ? 168 : (compactLandscape ? 72 : 130);

        visualizerView = new VisualizerView(this);
        visualizerView.setSmoothing(visualizationSettings.smoothing);
        visualizerView.setMinimumHeight(dp(visualizerMinDp));

        LinearLayout mainButtons = new LinearLayout(this);
        mainButtons.setOrientation(LinearLayout.HORIZONTAL);
        mainButtons.setGravity(Gravity.CENTER);

        computeTransportButtonSizes();

        shuffleButton = transportButton(R.drawable.ic_shuffle, "Shuffle");
        shuffleButton.setOnClickListener(view -> sendServiceCommand(SleepMusicService.ACTION_TOGGLE_SHUFFLE));
        mainButtons.addView(shuffleButton, transportButtonParams(false));

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
            requestNotificationPermission();
            sendPlaylistToService(false);
            sendServiceCommand(SleepMusicService.ACTION_TOGGLE_PLAY);
        });
        mainButtons.addView(playButton, transportButtonParams(true));
        updatePlayButtonIcon();

        ImageButton skipButton = transportButton(android.R.drawable.ic_media_next, "Next");
        skipButton.setOnClickListener(view -> sendServiceCommand(SleepMusicService.ACTION_SKIP));
        mainButtons.addView(skipButton, transportButtonParams(false));

        ImageButton stopButton = transportButton(R.drawable.ic_stop, "Stop");
        stopButton.setOnClickListener(view -> sendServiceCommand(SleepMusicService.ACTION_STOP));
        mainButtons.addView(stopButton, transportButtonParams(false));

        repeatButton = transportButton(R.drawable.ic_repeat, "Repeat");
        repeatButton.setOnClickListener(view -> sendServiceCommand(SleepMusicService.ACTION_CYCLE_REPEAT));
        mainButtons.addView(repeatButton, transportButtonParams(false));

        ImageButton removeButton = transportButton(android.R.drawable.ic_menu_delete, "Remove from playlist");
        removeButton.setOnClickListener(view -> confirmRemoveCurrentTrack());
        mainButtons.addView(removeButton, transportButtonParams(false));

        updateShuffleRepeatButtons();

        if (!compactLandscape) {
            root.addView(mainButtons, topMargin(28));
        }
        // In compact landscape, buildNowPlayingSection places mainButtons
        // itself — floated over the art/visualizer block's bottom edge
        // instead of in its own row below it, same idea as the header.

        // buildNowPlayingSection owns placement of timeRow, visualizerView,
        // header, and mainButtons (not just the art/title/seek bar)
        // because the landscape-phone layout floats the header and
        // transport buttons over one shared art+visualizer block instead
        // of stacking everything in separate rows, which the tablet and
        // portrait-phone layouts don't need.
        buildNowPlayingSection(root, header, mainButtons, stateText, trackSeekBar, timeRow, visualizerView);

        return root;
    }

    private void showPlayerScreen() {
        lyricsTickHandler.removeCallbacks(lyricsTick);
        showingSettings = false;
        showingLyrics = false;
        showingPlaylistEditor = false;
        showingPlaylistMenu = false;
        showingSharedPlaylists = false;
        showingWhatsNext = false;
        lyricsSubtitleView = null;
        lyricsPlayButton = null;
        whatsNextListContainer = null;
        whatsNextSearch = null;
        whatsNextScroll = null;
        setContentView(buildContentView());
        updatePlaylistText();
        sendServiceCommand(SleepMusicService.ACTION_REQUEST_STATE);
    }

    private void showSettingsScreen() {
        lyricsTickHandler.removeCallbacks(lyricsTick);
        showingSettings = true;
        showingLyrics = false;
        showingPlaylistEditor = false;
        showingPlaylistMenu = false;
        showingSharedPlaylists = false;
        showingWhatsNext = false;
        lyricsSubtitleView = null;
        lyricsPlayButton = null;
        whatsNextListContainer = null;
        whatsNextSearch = null;
        whatsNextScroll = null;
        setContentView(buildSettingsView());
        sendServiceCommand(SleepMusicService.ACTION_REQUEST_STATE);
    }

    private void showPlaylistEditorScreen() {
        lyricsTickHandler.removeCallbacks(lyricsTick);
        showingSettings = false;
        showingLyrics = false;
        showingPlaylistEditor = true;
        showingPlaylistMenu = false;
        showingSharedPlaylists = false;
        showingWhatsNext = false;
        whatsNextListContainer = null;
        whatsNextSearch = null;
        whatsNextScroll = null;
        setContentView(buildPlaylistEditorView());
    }

    private void showPlaylistMenuScreen() {
        lyricsTickHandler.removeCallbacks(lyricsTick);
        showingSettings = false;
        showingLyrics = false;
        showingPlaylistEditor = false;
        showingPlaylistMenu = true;
        showingSharedPlaylists = false;
        showingWhatsNext = false;
        whatsNextListContainer = null;
        whatsNextSearch = null;
        whatsNextScroll = null;
        setContentView(buildPlaylistMenuView());
    }

    private void showSharedPlaylistsScreen(
            JSONArray summaries, JSONArray library, String baseUrl, String token) {
        lyricsTickHandler.removeCallbacks(lyricsTick);
        showingSettings = false;
        showingLyrics = false;
        showingPlaylistEditor = false;
        showingPlaylistMenu = false;
        showingSharedPlaylists = true;
        showingWhatsNext = false;
        whatsNextListContainer = null;
        whatsNextSearch = null;
        whatsNextScroll = null;
        setContentView(buildSharedPlaylistsView(summaries, library, baseUrl, token));
    }

    private void showWhatsNextScreen() {
        lyricsTickHandler.removeCallbacks(lyricsTick);
        showingSettings = false;
        showingLyrics = false;
        showingPlaylistEditor = false;
        showingPlaylistMenu = false;
        showingSharedPlaylists = false;
        showingWhatsNext = true;
        lyricsSubtitleView = null;
        lyricsPlayButton = null;
        // On tablet the main player stays visible, shrunk to make room —
        // same side-panel treatment and same right-panel slot as Lyrics,
        // so swapping between the two just replaces what's in that slot
        // instead of the whole player jumping sides.
        setContentView(isTabletConfiguration() ? buildTabletWhatsNextSplitView() : buildWhatsNextView());
        sendServiceCommand(SleepMusicService.ACTION_REQUEST_STATE);
        refreshWhatsNextList(true);
    }

    private View buildPlaylistEditorView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(17, 19, 21));
        applySystemBarInsets(root, dp(20), dp(24), dp(20), dp(24));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(header, matchWrap());

        ImageButton backButton = transportButton(R.drawable.ic_back_arrow, "Back");
        backButton.setOnClickListener(view -> showSettingsScreen());
        header.addView(backButton, headerIconButtonParams());

        LinearLayout titleColumn = new LinearLayout(this);
        titleColumn.setOrientation(LinearLayout.VERTICAL);
        titleColumn.setGravity(Gravity.END);
        header.addView(titleColumn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = text("Edit Playlist", 26, Color.rgb(245, 243, 237));
        title.setGravity(Gravity.END);
        titleColumn.addView(title, matchWrap());

        TextView subtitle = text(
                activePlaylistName + " · " + playlist.size() + (playlist.size() == 1 ? " song" : " songs"),
                14, Color.rgb(183, 182, 173));
        subtitle.setGravity(Gravity.END);
        titleColumn.addView(subtitle, matchWrap());

        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Search this playlist");
        root.addView(search, topMargin(14));

        TextView emptyText = text("No songs match your search", 15, Color.rgb(183, 182, 173));
        emptyText.setGravity(Gravity.CENTER);
        emptyText.setVisibility(View.GONE);
        root.addView(emptyText, topMargin(24));

        ListView list = new ListView(this);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        listParams.topMargin = dp(10);
        root.addView(list, listParams);

        Map<String, String[]> metadata = PlaylistStore.loadAllTrackMetadata(this);
        List<String> fullOrder = new ArrayList<>(playlist);
        List<String> filtered = new ArrayList<>(fullOrder);

        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                this, android.R.layout.simple_list_item_2, android.R.id.text1, filtered) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View row = super.getView(position, convertView, parent);
                TextView primary = row.findViewById(android.R.id.text1);
                TextView secondary = row.findViewById(android.R.id.text2);
                String uri = getItem(position);
                String[] entry = uri == null ? null : metadata.get(uri);
                String trackTitle = entry != null && entry.length > 0 && !entry[0].trim().isEmpty()
                        ? entry[0].trim() : PlaylistStore.displayName(MainActivity.this, uri);
                String artist = entry != null && entry.length > 1 ? entry[1].trim() : "";
                String album = entry != null && entry.length > 2 ? entry[2].trim() : "";
                String detail = artist.isEmpty() ? album : album.isEmpty() ? artist : artist + " — " + album;
                primary.setText(trackTitle);
                secondary.setText(detail);
                return row;
            }
        };
        list.setAdapter(adapter);

        list.setOnItemClickListener((parent, view, position, id) -> {
            String uri = adapter.getItem(position);
            if (uri == null) {
                return;
            }
            Intent intent = new Intent(this, SleepMusicService.class);
            intent.setAction(SleepMusicService.ACTION_PLAY_URI);
            intent.putExtra(SleepMusicService.EXTRA_TRACK_URI, uri);
            startServiceCompat(intent);
            showPlayerScreen();
        });

        list.setOnItemLongClickListener((parent, view, position, id) -> {
            String uri = adapter.getItem(position);
            if (uri == null) {
                return true;
            }
            String label = PlaylistStore.displayName(this, uri);
            new AlertDialog.Builder(this)
                    .setTitle("Remove from playlist")
                    .setMessage("Remove \"" + label + "\" from this playlist?")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Remove", (dialog, which) -> {
                        fullOrder.remove(uri);
                        removePlaylistFile(uri);
                        filtered.remove(uri);
                        adapter.notifyDataSetChanged();
                        subtitle.setText(activePlaylistName + " · " + playlist.size()
                                + (playlist.size() == 1 ? " song" : " songs"));
                        emptyText.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
                    })
                    .show();
            return true;
        });

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s.toString().trim().toLowerCase(Locale.US);
                filtered.clear();
                for (String uri : fullOrder) {
                    if (query.isEmpty()) {
                        filtered.add(uri);
                        continue;
                    }
                    String[] entry = metadata.get(uri);
                    String haystack = PlaylistStore.displayName(MainActivity.this, uri).toLowerCase(Locale.US);
                    if (entry != null) {
                        for (String field : entry) {
                            if (field != null) {
                                haystack += " " + field.toLowerCase(Locale.US);
                            }
                        }
                    }
                    if (haystack.contains(query)) {
                        filtered.add(uri);
                    }
                }
                adapter.notifyDataSetChanged();
                emptyText.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
            }
        });

        return root;
    }

    private void showLyricsScreen() {
        showingSettings = false;
        showingLyrics = true;
        showingPlaylistEditor = false;
        showingWhatsNext = false;
        whatsNextListContainer = null;
        whatsNextSearch = null;
        whatsNextScroll = null;
        // On tablet the main player stays visible, shrunk to make room —
        // matches Settings' full-screen swap on phone, but a phone-style
        // full replacement would waste most of a tablet's width when a
        // side-by-side panel fits comfortably instead.
        setContentView(isTabletConfiguration() ? buildTabletLyricsSplitView() : buildLyricsView());
        sendServiceCommand(SleepMusicService.ACTION_REQUEST_STATE);
        loadLyricsForCurrentTrack();
        lyricsTickHandler.removeCallbacks(lyricsTick);
        lyricsTickHandler.post(lyricsTick);
    }

    private View buildTabletLyricsSplitView() {
        // Same right-panel/left-content arrangement as the What's Next
        // split (buildTabletWhatsNextSplitView) so toggling between the
        // two just swaps what's in the side panel in place, instead of
        // the whole player jumping from one side of the screen to the
        // other depending on which was opened.
        LinearLayout split = new LinearLayout(this);
        split.setOrientation(LinearLayout.HORIZONTAL);
        split.addView(buildContentView(), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 2f));
        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(45, 51, 56));
        split.addView(divider, new LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT));
        split.addView(buildLyricsView(), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        return split;
    }

    private View buildTabletWhatsNextSplitView() {
        LinearLayout split = new LinearLayout(this);
        split.setOrientation(LinearLayout.HORIZONTAL);
        split.addView(buildContentView(), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 2f));
        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(45, 51, 56));
        split.addView(divider, new LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT));
        split.addView(buildWhatsNextView(), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        return split;
    }

    private View buildWhatsNextView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(17, 19, 21));
        applySystemBarInsets(root, dp(20), dp(24), dp(20), dp(24));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(header, matchWrap());

        ImageButton backButton = transportButton(R.drawable.ic_back_arrow, "Back");
        backButton.setOnClickListener(view -> showPlayerScreen());
        header.addView(backButton, headerIconButtonParams());

        TextView title = text("What's Next", 26, Color.rgb(245, 243, 237));
        title.setGravity(Gravity.END);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        whatsNextSearch = new EditText(this);
        whatsNextSearch.setSingleLine(true);
        whatsNextSearch.setHint("Search history and up next");
        root.addView(whatsNextSearch, topMargin(14));
        whatsNextSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                refreshWhatsNextList(true);
            }
        });

        whatsNextScroll = new ScrollView(this);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scrollParams.topMargin = dp(10);
        root.addView(whatsNextScroll, scrollParams);

        whatsNextListContainer = new LinearLayout(this);
        whatsNextListContainer.setOrientation(LinearLayout.VERTICAL);
        whatsNextScroll.addView(whatsNextListContainer, matchWrap());

        whatsNextScroll.setOnScrollChangeListener((View v, int scrollX, int scrollY, int oldScrollX, int oldScrollY) -> {
            if (whatsNextUpcomingLimit >= whatsNextUpcomingTotal) {
                return;
            }
            int remaining = whatsNextListContainer.getHeight() - (scrollY + whatsNextScroll.getHeight());
            if (remaining > dp(200)) {
                return;
            }
            whatsNextUpcomingLimit += 50;
            refreshWhatsNextList(false);
        });

        refreshWhatsNextList(true);
        return root;
    }

    private void refreshWhatsNextList(boolean resetLimit) {
        if (whatsNextListContainer == null) {
            return;
        }
        if (resetLimit) {
            whatsNextUpcomingLimit = 50;
        }
        whatsNextListContainer.removeAllViews();
        if (playlist.isEmpty()) {
            TextView empty = text("Nothing queued.", 15, Color.rgb(183, 182, 173));
            empty.setGravity(Gravity.CENTER);
            whatsNextListContainer.addView(empty, topMargin(24));
            return;
        }

        Map<String, String[]> metadata = PlaylistStore.loadAllTrackMetadata(this);

        // playHistory[0..historyIndex) is everything played before the
        // current track, oldest-first already (it's an append-only log) —
        // cap to the most recent 20 for display.
        List<Integer> history = new ArrayList<>();
        for (int i = Math.max(0, historyIndex - 20); i < historyIndex && i < playHistory.length; i++) {
            int index = playHistory[i];
            if (index >= 0 && index < playlist.size()) {
                history.add(index);
            }
        }

        List<Integer> upcoming = new ArrayList<>();
        if (shuffleEnabled) {
            // If Previous was pressed earlier, playHistory already knows
            // exactly what played after this point — show that first
            // (it's what Next will actually replay), then keep going with
            // the rest of the predicted shuffle order. Anything already
            // in that forward slice was removed from the bag when it was
            // first picked, so this can't show the same track twice.
            for (int i = historyIndex + 1; i < playHistory.length; i++) {
                int index = playHistory[i];
                if (index >= 0 && index < playlist.size()) {
                    upcoming.add(index);
                }
            }
            for (int index : shuffleBag) {
                if (index >= 0 && index < playlist.size()) {
                    upcoming.add(index);
                }
            }
        } else if (currentIndex >= 0) {
            // Sequential order is fully determined by currentIndex alone,
            // so there's no separate "known forward history" case to
            // special-case here the way shuffle mode needs.
            for (int i = currentIndex + 1; i < playlist.size(); i++) {
                upcoming.add(i);
            }
            if (repeatMode == SleepMusicService.REPEAT_ALL) {
                for (int i = 0; i < currentIndex; i++) {
                    upcoming.add(i);
                }
            }
        }

        // Filters HISTORY/UP NEXT by title+artist. NOW PLAYING always
        // stays visible regardless of the query — it's a single status
        // row, not part of the searchable list.
        String query = whatsNextSearch == null || whatsNextSearch.getText() == null
                ? "" : whatsNextSearch.getText().toString().trim().toLowerCase(Locale.US);
        boolean searching = !query.isEmpty();
        if (searching) {
            history.removeIf(index -> !whatsNextMatchesQuery(index, metadata, query));
            upcoming.removeIf(index -> !whatsNextMatchesQuery(index, metadata, query));
        }
        whatsNextUpcomingTotal = upcoming.size();

        addWhatsNextSection("HISTORY");
        if (history.isEmpty()) {
            addWhatsNextEmptyRow(searching ? "No matches." : "No history yet.");
        } else {
            for (int index : history) {
                addWhatsNextRow(index, metadata, false);
            }
        }

        addWhatsNextSection("NOW PLAYING");
        if (currentIndex >= 0 && currentIndex < playlist.size()) {
            addWhatsNextRow(currentIndex, metadata, true);
        } else {
            addWhatsNextEmptyRow("Nothing playing.");
        }

        addWhatsNextSection("UP NEXT");
        if (upcoming.isEmpty()) {
            addWhatsNextEmptyRow(searching ? "No matches." : "End of playlist.");
        } else {
            int shown = searching ? upcoming.size() : Math.min(whatsNextUpcomingLimit, upcoming.size());
            for (int i = 0; i < shown; i++) {
                addWhatsNextRow(upcoming.get(i), metadata, false);
            }
        }
    }

    private void addWhatsNextSection(String label) {
        TextView section = text(label, 12, Color.rgb(183, 182, 173));
        section.setLetterSpacing(0.08f);
        whatsNextListContainer.addView(section, topMargin(18));
    }

    private void addWhatsNextEmptyRow(String message) {
        TextView row = text(message, 14, Color.rgb(183, 182, 173));
        whatsNextListContainer.addView(row, topMargin(6));
    }

    private boolean whatsNextMatchesQuery(int index, Map<String, String[]> metadata, String query) {
        if (index < 0 || index >= playlist.size()) {
            return false;
        }
        String uri = playlist.get(index);
        String[] entry = metadata.get(uri);
        String trackTitle = entry != null && entry.length > 0 && !entry[0].trim().isEmpty()
                ? entry[0].trim() : PlaylistStore.displayName(this, uri);
        String artist = entry != null && entry.length > 1 ? entry[1].trim() : "";
        return (trackTitle + "\n" + artist).toLowerCase(Locale.US).contains(query);
    }

    private void addWhatsNextRow(int index, Map<String, String[]> metadata, boolean current) {
        String uri = playlist.get(index);
        String[] entry = metadata.get(uri);
        String trackTitle = entry != null && entry.length > 0 && !entry[0].trim().isEmpty()
                ? entry[0].trim() : PlaylistStore.displayName(this, uri);
        String artist = entry != null && entry.length > 1 ? entry[1].trim() : "";

        // Same pill-shaped card treatment as the Settings buttons
        // (settingsButtonBackground) rather than bare rows, matching the
        // rounded button-style rows the Ubuntu What's Next window uses.
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackground(settingsButtonBackground(current ? SETTINGS_STYLE_PRIMARY : SETTINGS_STYLE_SECONDARY));
        row.setPadding(dp(16), dp(12), dp(16), dp(12));

        TextView titleView = text(trackTitle, 16, current ? Color.rgb(118, 222, 190) : Color.rgb(245, 243, 237));
        row.addView(titleView, matchWrap());
        if (!artist.isEmpty()) {
            TextView artistView = text(artist, 13, Color.rgb(183, 182, 173));
            row.addView(artistView, topMargin(2));
        }

        if (!current) {
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(view -> {
                Intent intent = new Intent(this, SleepMusicService.class);
                intent.setAction(SleepMusicService.ACTION_PLAY_URI);
                intent.putExtra(SleepMusicService.EXTRA_TRACK_URI, uri);
                startServiceCompat(intent);
            });
        }

        whatsNextListContainer.addView(row, topMargin(8));
    }

    private View buildLyricsView() {
        boolean compactPhoneLandscape = isCompactLandscapePhone();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(17, 19, 21));
        applySystemBarInsets(
                root,
                dp(compactPhoneLandscape ? 10 : 20),
                dp(compactPhoneLandscape ? 6 : 24),
                dp(compactPhoneLandscape ? 10 : 20),
                dp(compactPhoneLandscape ? 6 : 24));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(header, matchWrap());

        ImageButton backButton = transportButton(R.drawable.ic_back_arrow, "Back");
        if (compactPhoneLandscape) {
            backButton.setPadding(dp(6), dp(6), dp(6), dp(6));
        }
        backButton.setOnClickListener(view -> showPlayerScreen());
        header.addView(backButton, lyricsHeaderButtonParams(compactPhoneLandscape));

        LinearLayout titleColumn = new LinearLayout(this);
        titleColumn.setOrientation(LinearLayout.VERTICAL);
        titleColumn.setGravity(Gravity.CENTER);
        header.addView(
                titleColumn,
                new LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f));

        TextView title = text(
                "Lyrics",
                compactPhoneLandscape ? 19 : 26,
                Color.rgb(245, 243, 237));
        title.setGravity(Gravity.CENTER);
        titleColumn.addView(title, matchWrap());

        lyricsSubtitleView = text(
                "",
                compactPhoneLandscape ? 11 : 14,
                Color.rgb(183, 182, 173));
        lyricsSubtitleView.setGravity(Gravity.CENTER);
        lyricsSubtitleView.setMaxLines(compactPhoneLandscape ? 1 : 2);
        lyricsSubtitleView.setEllipsize(TextUtils.TruncateAt.END);
        titleColumn.addView(lyricsSubtitleView, matchWrap());
        updateLyricsSubtitle();

        if (!isTabletConfiguration()) {
            if (compactPhoneLandscape) {
                addLyricsTransportButtons(header, true);
            } else {
                LinearLayout lyricsTransportRow = new LinearLayout(this);
                lyricsTransportRow.setOrientation(LinearLayout.HORIZONTAL);
                lyricsTransportRow.setGravity(Gravity.CENTER);
                addLyricsTransportButtons(lyricsTransportRow, false);
                root.addView(lyricsTransportRow, topMargin(18));
            }
        }

        lyricsStatusText = text(
                "Loading lyrics…",
                compactPhoneLandscape ? 14 : 16,
                Color.rgb(183, 182, 173));
        lyricsStatusText.setGravity(Gravity.CENTER);
        root.addView(
                lyricsStatusText,
                topMargin(compactPhoneLandscape ? 6 : 40));

        lyricsScrollView = new ScrollView(this);
        lyricsScrollView.setFillViewport(true);
        lyricsScrollView.setClipToPadding(false);
        root.addView(
                lyricsScrollView,
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f));

        lyricsPhrasesContainer = new LinearLayout(this);
        lyricsPhrasesContainer.setOrientation(LinearLayout.VERTICAL);
        lyricsPhrasesContainer.setGravity(Gravity.CENTER_HORIZONTAL);
        lyricsPhrasesContainer.setClipChildren(false);
        lyricsPhrasesContainer.setClipToPadding(false);

        int horizontalPadding = dp(compactPhoneLandscape ? 64 : 44);
        lyricsPhrasesContainer.setPadding(
                horizontalPadding,
                dp(compactPhoneLandscape ? 24 : 120),
                horizontalPadding,
                dp(compactPhoneLandscape ? 64 : 220));

        lyricsScrollView.addView(
                lyricsPhrasesContainer,
                new ScrollView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        return root;
    }

    private void addLyricsTransportButtons(
            LinearLayout row,
            boolean compactPhoneLandscape) {
        ImageButton previousButton = transportButton(
                android.R.drawable.ic_media_previous,
                "Previous");
        ImageButton playPauseButton = transportButton(
                android.R.drawable.ic_media_play,
                "Play");
        ImageButton nextButton = transportButton(
                android.R.drawable.ic_media_next,
                "Next");

        if (compactPhoneLandscape) {
            int padding = dp(5);
            previousButton.setPadding(padding, padding, padding, padding);
            playPauseButton.setPadding(padding, padding, padding, padding);
            nextButton.setPadding(padding, padding, padding, padding);
        }

        previousButton.setOnClickListener(
                view -> sendServiceCommand(SleepMusicService.ACTION_PREVIOUS));
        playPauseButton.setOnClickListener(
                view -> sendServiceCommand(SleepMusicService.ACTION_TOGGLE_PLAY));
        nextButton.setOnClickListener(
                view -> sendServiceCommand(SleepMusicService.ACTION_SKIP));

        lyricsPlayButton = playPauseButton;
        row.addView(
                previousButton,
                lyricsTransportButtonParams(false, compactPhoneLandscape));
        row.addView(
                playPauseButton,
                lyricsTransportButtonParams(true, compactPhoneLandscape));
        row.addView(
                nextButton,
                lyricsTransportButtonParams(false, compactPhoneLandscape));
        updatePlayButtonIcon();
    }

    // The track just fetched for may no longer be current by the time the
    // background request returns (user hit next/previous while lyrics were
    // loading) — lyricsLoadedForTrackUri is rechecked before touching any
    // view so a stale response can never overwrite a newer request's UI.
    private void loadLyricsForCurrentTrack() {
        String trackUri = currentTrackUri;
        lyricsLoadedForTrackUri = trackUri;
        lyricsPhrases = new ArrayList<>();
        lyricsPhraseViews = new ArrayList<>();
        lyricsActivePhraseIndex = -1;
        if (lyricsPhrasesContainer != null) {
            lyricsPhrasesContainer.removeAllViews();
        }
        if (lyricsStatusText != null) {
            lyricsStatusText.setVisibility(View.VISIBLE);
            lyricsStatusText.setText(trackUri.isEmpty() ? "No song is currently playing" : "Loading lyrics…");
        }
        if (trackUri.isEmpty()) {
            return;
        }
        String token = PlaylistStore.loadServerToken(this);
        new Thread(() -> {
            JSONObject response = null;
            String error = null;
            try {
                response = RemoteLibraryClient.fetchLyrics(token, trackUri);
            } catch (Exception e) {
                error = e.getMessage();
            }
            List<LyricsPhrase> phrases = new ArrayList<>();
            if (response != null) {
                try {
                    phrases = LyricsPhrase.pickDisplaySection(response);
                } catch (JSONException e) {
                    error = "Malformed lyrics data";
                }
            }
            boolean noLyrics = response == null;
            String finalError = error;
            List<LyricsPhrase> finalPhrases = phrases;
            runOnUiThread(() -> {
                if (!showingLyrics || !trackUri.equals(lyricsLoadedForTrackUri)) {
                    return;
                }
                if (finalError != null) {
                    lyricsStatusText.setText("Couldn't load lyrics: " + finalError);
                    return;
                }
                if (noLyrics || finalPhrases.isEmpty()) {
                    lyricsStatusText.setText("No lyrics available for this track");
                    return;
                }
                lyricsStatusText.setVisibility(View.GONE);
                lyricsPhrases = finalPhrases;
                lyricsPhraseViews = new ArrayList<>();
                for (LyricsPhrase phrase : lyricsPhrases) {
                    TextView phraseView = new TextView(this);
                    phraseView.setText(phrase.text);
                    phraseView.setTextColor(Color.rgb(150, 150, 150));
                    phraseView.setTextSize(LYRICS_BASE_TEXT_SIZE_SP);
                    phraseView.setGravity(Gravity.CENTER);
                    phraseView.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
                    phraseView.setAlpha(LYRICS_INACTIVE_ALPHA);
                    phraseView.setScaleX(LYRICS_INACTIVE_SCALE);
                    phraseView.setScaleY(LYRICS_INACTIVE_SCALE);
                    phraseView.setPadding(0, dp(14), 0, dp(14));
                    lyricsPhrasesContainer.addView(phraseView, matchWrap());
                    lyricsPhraseViews.add(phraseView);
                }
            });
        }).start();
    }

    private void updateLyricsHighlightTick() {
        if (!showingLyrics) {
            return;
        }
        long estimatedPositionMs = lyricsLastKnownPositionMs;
        if (lyricsLastKnownPlaying) {
            estimatedPositionMs += SystemClock.elapsedRealtime() - lyricsLastKnownElapsedRealtime;
        }
        // Same calibrated output-route delay the visualizer already
        // compensates with (Bluetooth etc. have real audible latency past
        // what's been decoded) — without it lyrics highlight ahead of what's
        // actually audible, same class of bug already solved for the visualizer.
        long compensatedPositionMs = Math.max(0L, estimatedPositionMs - outputVisualDelayMs);
        applyLyricsHighlight(compensatedPositionMs / 1000.0);
        lyricsTickHandler.postDelayed(lyricsTick, 100L);
    }

    private static final float LYRICS_BASE_TEXT_SIZE_SP = 20f;
    private static final float LYRICS_INACTIVE_SCALE = 0.82f;
    private static final float LYRICS_ACTIVE_SCALE = 1.22f;
    private static final float LYRICS_INACTIVE_ALPHA = 0.6f;
    private static final float LYRICS_PAST_ALPHA = 0.4f;

    private void applyLyricsHighlight(double positionSeconds) {
        if (lyricsPhrases.isEmpty() || lyricsPhraseViews.size() != lyricsPhrases.size()) {
            return;
        }
        int activeIndex = -1;
        for (int i = 0; i < lyricsPhrases.size(); i++) {
            LyricsPhrase phrase = lyricsPhrases.get(i);
            if (phrase.startSeconds <= positionSeconds) {
                activeIndex = i;
            } else {
                break;
            }
        }
        if (activeIndex >= 0 && activeIndex < lyricsPhraseViews.size()) {
            lyricsPhraseViews.get(activeIndex).setText(
                    buildWordHighlightSpan(lyricsPhrases.get(activeIndex), positionSeconds));
        }
        if (activeIndex != lyricsActivePhraseIndex) {
            animateLyricsLineTransition(lyricsActivePhraseIndex, activeIndex);
            lyricsActivePhraseIndex = activeIndex;
            scrollToActiveLyricsPhrase();
        }
    }


    private void animateLyricsLineTransition(int oldIndex, int newIndex) {
        for (int i = 0; i < lyricsPhraseViews.size(); i++) {
            TextView view = lyricsPhraseViews.get(i);
            view.animate().cancel();
            view.setCameraDistance(getResources().getDisplayMetrics().density * 8000f);
            view.setPivotX(view.getWidth() / 2f);
            view.setPivotY(view.getHeight() / 2f);

            if (i != newIndex) {
                view.setText(lyricsPhrases.get(i).text);
            }

            boolean isPast = newIndex >= 0 && i < newIndex;
            float targetRotation = newIndex < 0 ? 0f : (isPast ? -18f : 18f);
            float targetTranslationY = newIndex < 0 ? 0f : (isPast ? -dp(8) : dp(8));
            float targetAlpha = isPast ? LYRICS_PAST_ALPHA : LYRICS_INACTIVE_ALPHA;

            if (i == newIndex) {
                view.setRotationX(65f);
                view.setTranslationY(dp(42));
                view.setTranslationZ(-dp(2));
                view.setScaleX(LYRICS_INACTIVE_SCALE);
                view.setScaleY(LYRICS_INACTIVE_SCALE);
                view.setAlpha(0.2f);
                view.animate()
                        .rotationX(0f)
                        .translationY(0f)
                        .translationZ(dp(8))
                        .scaleX(LYRICS_ACTIVE_SCALE)
                        .scaleY(LYRICS_ACTIVE_SCALE)
                        .alpha(1f)
                        .setDuration(420L)
                        .start();
            } else {
                long duration = i == oldIndex ? 420L : 300L;
                view.animate()
                        .rotationX(targetRotation)
                        .translationY(targetTranslationY)
                        .translationZ(-dp(2))
                        .scaleX(LYRICS_INACTIVE_SCALE)
                        .scaleY(LYRICS_INACTIVE_SCALE)
                        .alpha(targetAlpha)
                        .setDuration(duration)
                        .start();
            }
        }
    }

    private CharSequence buildWordHighlightSpan(LyricsPhrase phrase, double positionSeconds) {
        SpannableStringBuilder builder = new SpannableStringBuilder();
        for (int i = 0; i < phrase.words.size(); i++) {
            LyricsPhrase.Word word = phrase.words.get(i);
            if (i > 0) {
                builder.append(' ');
            }
            int start = builder.length();
            builder.append(word.text);
            int end = builder.length();
            // A word's own timestamp marks when Whisper detected it
            // starting, but that timestamp has a known tendency to
            // anticipate the word slightly rather than land exactly on its
            // audible onset. Using the NEXT word's start (or the phrase's
            // end for the last word) as the "fully sung" boundary instead
            // removes that early bias — matching when the word has
            // actually finished, not just begun.
            double sungBoundarySeconds = i + 1 < phrase.words.size()
                    ? phrase.words.get(i + 1).timeSeconds
                    : phrase.endSeconds;
            boolean sung = sungBoundarySeconds <= positionSeconds;
            builder.setSpan(
                    new ForegroundColorSpan(sung ? Color.rgb(245, 243, 237) : Color.rgb(120, 120, 120)),
                    start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return builder;
    }

    private void scrollToActiveLyricsPhrase() {
        if (lyricsScrollView == null || lyricsActivePhraseIndex < 0
                || lyricsActivePhraseIndex >= lyricsPhraseViews.size()) {
            return;
        }
        TextView activeView = lyricsPhraseViews.get(lyricsActivePhraseIndex);
        lyricsScrollView.post(() -> {
            int anchorOffset = isCompactLandscapePhone() ? dp(48) : dp(140);
            int targetY = Math.max(0, activeView.getTop() - anchorOffset);
            lyricsScrollView.smoothScrollTo(0, targetY);
        });
    }

    private View buildSettingsView() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Color.rgb(17, 19, 21));
        applySystemBarInsets(scrollView);

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

        ImageButton backButton = transportButton(R.drawable.ic_back_arrow, "Back");
        backButton.setOnClickListener(view -> showPlayerScreen());
        header.addView(backButton, headerIconButtonParams());

        TextView title = text("Settings", 28, Color.rgb(245, 243, 237));
        title.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        header.addView(title, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f));

        TextView playlistTitle = text("Playlist & library", 20, Color.rgb(245, 243, 237));
        root.addView(playlistTitle, topMargin(30));
        LinearLayout playlistCard = settingsCard();
        root.addView(playlistCard, topMargin(10));
        addPlaylistManagementControls(playlistCard);

        TextView playbackTitle = text("Playback", 20, Color.rgb(245, 243, 237));
        root.addView(playbackTitle, topMargin(30));
        LinearLayout playbackCard = settingsCard();
        root.addView(playbackCard, topMargin(10));
        addPrimarySettingsControls(playbackCard);
        addAdvancedLevelingControls(playbackCard);
        addVisualizationControls(playbackCard);

        TextView aboutTitle = text("About", 20, Color.rgb(245, 243, 237));
        root.addView(aboutTitle, topMargin(30));
        LinearLayout aboutCard = settingsCard();
        root.addView(aboutCard, topMargin(10));
        Button privacyButton = settingsButton("Privacy policy", SETTINGS_STYLE_SECONDARY);
        privacyButton.setOnClickListener(view -> openWebPage(
                "https://patrick-lamphier.com/fredplayer-privacy"));
        aboutCard.addView(privacyButton, actionButtonParams(0));
        Button supportButton = settingsButton("Support", SETTINGS_STYLE_SECONDARY);
        supportButton.setOnClickListener(view -> openWebPage(
                "https://patrick-lamphier.com/fredplayer-support"));
        aboutCard.addView(supportButton, actionButtonParams(8));

        cacheText = text("", 13, Color.rgb(183, 182, 173));
        aboutCard.addView(cacheText, topMargin(16));
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

    private void addPlaylistManagementControls(LinearLayout root) {
        addSettingsGroupLabel(root, "This playlist", 4);
        settingsPlaylistLabel = text(playlistSummary(playlist.size()), 15, Color.rgb(245, 243, 237));
        root.addView(settingsPlaylistLabel, topMargin(2));
        Button editButton = settingsButton(
                "Edit current playlist", R.drawable.ic_playlist_bars, SETTINGS_STYLE_PRIMARY);
        editButton.setOnClickListener(view -> showPlaylistEditorScreen());
        root.addView(editButton, actionButtonParams(8));

        Button playlistsButton = settingsButton("Choose or manage playlists", SETTINGS_STYLE_SECONDARY);
        playlistsButton.setOnClickListener(view -> showPlaylistMenuScreen());
        root.addView(playlistsButton, actionButtonParams(8));

        Button clearButton = settingsButton("Clear list", R.drawable.ic_trash, SETTINGS_STYLE_DESTRUCTIVE);
        clearButton.setOnClickListener(view -> {
            playlist.clear();
            persistActivePlaylist();
            updatePlaylistText();
            sendServiceCommand(SleepMusicService.ACTION_CLEAR);
        });
        root.addView(clearButton, actionButtonParams(8));

        addSettingsGroupLabel(root, "Add music", 22);
        LinearLayout addButtons = new LinearLayout(this);
        addButtons.setOrientation(LinearLayout.HORIZONTAL);
        addButtons.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        root.addView(addButtons, topMargin(4));

        Button addButton = settingsButton("Add files", R.drawable.ic_add_simple, SETTINGS_STYLE_SECONDARY);
        addButton.setOnClickListener(view -> openAudioPicker());
        addButtons.addView(addButton, settingsInlineParams());

        Button addFolderButton = settingsButton("Add folder", R.drawable.ic_add_simple, SETTINGS_STYLE_SECONDARY);
        addFolderButton.setOnClickListener(view -> openFolderPicker());
        addButtons.addView(addFolderButton, settingsInlineParams());

        Button addFromServerButton = settingsButton(
                "Add from server", R.drawable.ic_add_simple, SETTINGS_STYLE_SECONDARY);
        addFromServerButton.setOnClickListener(view -> openServerLibraryDialog());
        addFromServerButton.setOnLongClickListener(view -> {
            openServerConnectionDialog();
            return true;
        });
        root.addView(addFromServerButton, actionButtonParams(8));
        TextView addFromServerHint = text("Long-press to change the server URL or token", 12, Color.rgb(140, 138, 130));
        root.addView(addFromServerHint, topMargin(4));

        addSettingsGroupLabel(root, "Server", 22);
        Button rescanServerButton = settingsButton("Rescan server library", SETTINGS_STYLE_SECONDARY);
        rescanServerButton.setOnClickListener(view -> rescanServerLibrary());
        root.addView(rescanServerButton, actionButtonParams(8));

        Button sharedPlaylistsButton = settingsButton("Shared playlists", SETTINGS_STYLE_SECONDARY);
        sharedPlaylistsButton.setOnClickListener(view -> openSharedPlaylists());
        root.addView(sharedPlaylistsButton, actionButtonParams(8));

        Button askLiamButton = settingsButton("Ask Liam", SETTINGS_STYLE_SECONDARY);
        askLiamButton.setOnClickListener(view -> openAskLiamDialog());
        root.addView(askLiamButton, actionButtonParams(8));
    }

    // Small muted subheading used to break up a settings section into
    // clearly labeled groups instead of one undifferentiated stack of
    // identically-styled buttons.
    private void addSettingsGroupLabel(LinearLayout root, String label, int topMarginDp) {
        TextView groupLabel = text(label.toUpperCase(Locale.US), 12, Color.rgb(140, 138, 130));
        groupLabel.setLetterSpacing(0.08f);
        root.addView(groupLabel, topMargin(topMarginDp));
    }

    private void rescanServerLibrary() {
        String baseUrl = PlaylistStore.loadServerBaseUrl(this);
        String token = PlaylistStore.loadServerToken(this);
        if (baseUrl.isEmpty()) {
            Toast.makeText(
                    this,
                    "Set up the Fred Server first with Add from server",
                    Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(this, "Rescanning server library…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                int count = RemoteLibraryClient.rescanLibrary(baseUrl, token);
                runOnUiThread(() -> Toast.makeText(
                        this,
                        "Rescanned " + count + " tracks; missing playback data is queued",
                        Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(
                        this,
                        "Could not rescan server: " + e.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        }, "FredPlayerServerRescan").start();
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

        addSettingsSlider(root, "Update FPS", 5, 60, visualizationSettings.fps,
                value -> value + " fps",
                value -> updateVisualizationSettings(new VisualizationSettings(
                        value,
                        visualizationSettings.waveformMs,
                        visualizationSettings.fftSize,
                        visualizationSettings.fftBars,
                        visualizationSettings.smoothing,
                        visualizationSettings.logScale)));

        addSettingsSlider(root, "Waveform window", 20, 90, visualizationSettings.waveformMs,
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

        addSettingsSlider(root, "FFT smoothing", 0, 95, Math.round(visualizationSettings.smoothing * 100f),
                value -> value + "%",
                value -> updateVisualizationSettings(new VisualizationSettings(
                        visualizationSettings.fps,
                        visualizationSettings.waveformMs,
                        visualizationSettings.fftSize,
                        visualizationSettings.fftBars,
                        value / 100f,
                        visualizationSettings.logScale)));

        Button scaleButton = settingsButton(scaleButtonText(), SETTINGS_STYLE_SECONDARY);
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
        root.addView(scaleButton, actionButtonParams(10));

        addBluetoothDelayControls(root);
    }

    private void addBluetoothDelayControls(LinearLayout root) {
        TextView title = text("Bluetooth synchronization", 18, Color.rgb(245, 243, 237));
        root.addView(title, topMargin(28));

        if (AudioOutputRoute.needsManualCalibration(this)) {
            bluetoothRouteText = text("", 14, Color.rgb(183, 182, 173));
            root.addView(bluetoothRouteText, topMargin(10));

            bluetoothDelayText = text("", 14, Color.rgb(245, 243, 237));
            root.addView(bluetoothDelayText, topMargin(12));
            bluetoothDelaySlider = new SeekBar(this);
            bluetoothDelaySlider.setMin(0);
            bluetoothDelaySlider.setMax(1500);
            bluetoothDelaySlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    bluetoothDelayText.setText("Visualization delay: " + progress + " ms");
                    if (fromUser) {
                        outputVisualDelayMs = progress;
                        sendOutputVisualDelay(progress);
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                }
            });
            root.addView(bluetoothDelaySlider, matchWrap());

            bluetoothCalibrateButton = settingsButton("Calibrate with microphone", SETTINGS_STYLE_SECONDARY);
            bluetoothCalibrateButton.setOnClickListener(view -> confirmBluetoothCalibration());
            root.addView(bluetoothCalibrateButton, actionButtonParams(10));

            TextView privacy = text(
                    "Calibration plays a short chirp and measures it locally. No recording or timing data is uploaded.",
                    13,
                    Color.rgb(183, 182, 173));
            root.addView(privacy, topMargin(8));
        } else {
            bluetoothRouteText = null;
            bluetoothDelayText = null;
            bluetoothDelaySlider = null;
            bluetoothCalibrateButton = null;
            TextView automatic = text(
                    "Android supplies Bluetooth latency on this device, so manual calibration is not needed.",
                    14,
                    Color.rgb(183, 182, 173));
            root.addView(automatic, topMargin(10));
        }

        TextView savedTitle = text("Saved speaker calibrations", 15, Color.rgb(245, 243, 237));
        root.addView(savedTitle, topMargin(20));
        bluetoothSavedListContainer = new LinearLayout(this);
        bluetoothSavedListContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(bluetoothSavedListContainer, matchWrap());
        bluetoothSavedListSignature = null;
        updateBluetoothDelayControls();
    }

    private void updateBluetoothDelayControls() {
        if (bluetoothRouteText != null) {
            bluetoothRouteText.setText("Current output: " + outputRouteName);
        }
        if (bluetoothDelaySlider != null) {
            int delay = Math.max(0, Math.min(1500, outputVisualDelayMs));
            bluetoothDelaySlider.setEnabled(outputRouteBluetooth && !outputDelayCalibrating);
            if (!bluetoothDelaySlider.isPressed()) {
                bluetoothDelaySlider.setProgress(delay);
            }
        }
        if (bluetoothDelayText != null) {
            bluetoothDelayText.setText("Visualization delay: " + outputVisualDelayMs + " ms");
        }
        if (bluetoothCalibrateButton != null) {
            bluetoothCalibrateButton.setEnabled(!outputDelayCalibrating);
            bluetoothCalibrateButton.setText(
                    outputDelayCalibrating ? "Calibrating…" : "Calibrate with microphone");
        }
        updateSavedBluetoothDelayList();
    }

    private void updateSavedBluetoothDelayList() {
        if (bluetoothSavedListContainer == null) {
            return;
        }
        ArrayList<PlaylistStore.BluetoothVisualDelayEntry> entries =
                PlaylistStore.loadBluetoothVisualDelayEntries(this);
        StringBuilder signatureBuilder = new StringBuilder();
        for (PlaylistStore.BluetoothVisualDelayEntry entry : entries) {
            signatureBuilder.append(entry.key)
                    .append('\u0000')
                    .append(entry.label)
                    .append('\u0000')
                    .append(entry.delayMs)
                    .append('\u0001');
        }
        String signature = signatureBuilder.toString();
        if (signature.equals(bluetoothSavedListSignature)) {
            return;
        }
        bluetoothSavedListSignature = signature;
        bluetoothSavedListContainer.removeAllViews();
        if (entries.isEmpty()) {
            bluetoothSavedListContainer.addView(
                    text("No saved speaker calibrations", 13, Color.rgb(183, 182, 173)),
                    topMargin(8));
            return;
        }

        for (PlaylistStore.BluetoothVisualDelayEntry entry : entries) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            bluetoothSavedListContainer.addView(row, topMargin(8));

            TextView details = text(
                    entry.label + " · " + entry.delayMs + " ms",
                    14,
                    Color.rgb(245, 243, 237));
            row.addView(details, new LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f));

            Button clear = settingsButton("Clear", SETTINGS_STYLE_DESTRUCTIVE);
            clear.setOnClickListener(view -> confirmClearBluetoothCalibration(entry));
            row.addView(clear, inlineButton());
        }

        Button clearAll = settingsButton("Clear all speaker calibrations", SETTINGS_STYLE_DESTRUCTIVE);
        clearAll.setOnClickListener(view -> confirmClearAllBluetoothCalibrations());
        bluetoothSavedListContainer.addView(clearAll, actionButtonParams(12));
    }

    private void sendOutputVisualDelay(int delayMs) {
        Intent intent = new Intent(this, SleepMusicService.class);
        intent.setAction(SleepMusicService.ACTION_SET_OUTPUT_VISUAL_DELAY);
        intent.putExtra(SleepMusicService.EXTRA_OUTPUT_ROUTE_KEY, outputRouteKey);
        intent.putExtra(SleepMusicService.EXTRA_OUTPUT_ROUTE_NAME, outputRouteName);
        intent.putExtra(SleepMusicService.EXTRA_OUTPUT_VISUAL_DELAY_MS, delayMs);
        startServiceCompat(intent);
    }

    private void confirmClearBluetoothCalibration(
            PlaylistStore.BluetoothVisualDelayEntry entry) {
        new AlertDialog.Builder(this)
                .setTitle("Clear " + entry.label + "?")
                .setMessage("FredPlayer will stop applying its saved " + entry.delayMs
                        + " ms Bluetooth adjustment for this speaker.")
                .setPositiveButton("Clear", (dialog, which) ->
                        clearBluetoothCalibration(entry.key))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmClearAllBluetoothCalibrations() {
        new AlertDialog.Builder(this)
                .setTitle("Clear all speaker calibrations?")
                .setMessage("All locally saved Bluetooth synchronization adjustments will be removed.")
                .setPositiveButton("Clear all", (dialog, which) ->
                        clearBluetoothCalibration(""))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void clearBluetoothCalibration(String routeKey) {
        Intent intent = new Intent(this, SleepMusicService.class);
        intent.setAction(SleepMusicService.ACTION_CLEAR_OUTPUT_VISUAL_DELAY);
        intent.putExtra(SleepMusicService.EXTRA_OUTPUT_ROUTE_KEY, routeKey);
        startServiceCompat(intent);
    }

    private void confirmBluetoothCalibration() {
        new AlertDialog.Builder(this)
                .setTitle("Calibrate Bluetooth delay?")
                .setMessage("Music will pause while FredPlayer plays a short chirp through the selected Bluetooth speaker and listens for it with this device's microphone.")
                .setPositiveButton("Calibrate", (dialog, which) -> requestBluetoothCalibration())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void requestBluetoothCalibration() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            sendServiceCommand(SleepMusicService.ACTION_CALIBRATE_OUTPUT_DELAY);
            return;
        }
        requestPermissions(
                new String[]{Manifest.permission.RECORD_AUDIO},
                REQUEST_AUDIO_CALIBRATION);
    }

    private View buildPlaylistMenuView() {
        ArrayList<String> names = new ArrayList<>(playlists.keySet());
        ArrayList<String> labels = new ArrayList<>();
        for (String name : names) {
            labels.add(name.equals(activePlaylistName) ? name + "  (current)" : name);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(17, 19, 21));
        applySystemBarInsets(root, dp(20), dp(24), dp(20), dp(24));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(header, matchWrap());

        ImageButton backButton = transportButton(R.drawable.ic_back_arrow, "Back");
        backButton.setOnClickListener(view -> showSettingsScreen());
        header.addView(backButton, headerIconButtonParams());

        TextView title = text("Playlists", 26, Color.rgb(245, 243, 237));
        title.setGravity(Gravity.END);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ListView list = new ListView(this);
        LinearLayout.LayoutParams listParams =
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        listParams.topMargin = dp(14);
        root.addView(list, listParams);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_list_item_1, android.R.id.text1, labels);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            switchPlaylist(names.get(position));
            showSettingsScreen();
        });

        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(buttonRow, topMargin(14));

        Button newButton = settingsButton("New", SETTINGS_STYLE_PRIMARY);
        newButton.setOnClickListener(view -> {
            showSettingsScreen();
            showCreatePlaylistDialog();
        });
        LinearLayout.LayoutParams newParams =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        newParams.rightMargin = dp(8);
        buttonRow.addView(newButton, newParams);

        Button renameButton = settingsButton("Rename", SETTINGS_STYLE_SECONDARY);
        renameButton.setOnClickListener(view -> {
            showSettingsScreen();
            showRenamePlaylistDialog();
        });
        LinearLayout.LayoutParams renameParams =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        renameParams.rightMargin = dp(8);
        buttonRow.addView(renameButton, renameParams);

        Button deleteButton = settingsButton("Delete", SETTINGS_STYLE_DESTRUCTIVE);
        deleteButton.setOnClickListener(view -> {
            showSettingsScreen();
            confirmDeletePlaylist();
        });
        buttonRow.addView(deleteButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        return root;
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
                .setMessage("The playlist will be removed from this device. Its audio files and any shared server copy will stay.")
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

    // Tablets (and phones in landscape, which have the same wide/short
    // shape) put art beside the title/artist/album text — there's enough
    // width for both. Phones in portrait are narrow and tall, so instead
    // the art sits full-width behind the (still-centered) text with a
    // dark scrim, matching how most streaming apps handle a portrait
    // now-playing header rather than squeezing a side-by-side row into a
    // narrow column.
    private boolean isTabletConfiguration() {
        return getResources().getConfiguration().smallestScreenWidthDp >= 600;
    }

    private boolean isLandscapeConfiguration() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private boolean sideBySideArtLayout() {
        return isTabletConfiguration() || isLandscapeConfiguration();
    }

    // A phone in landscape has a tablet's aspect ratio but not its actual
    // height — often under 400dp usable. The spacious tablet sizing (168dp
    // art, an 168dp-minimum visualizer, generous margins) doesn't fit that
    // budget at all, so this scales everything below down specifically for
    // that one case rather than for tablets, which have room to spare.
    private boolean isCompactLandscapePhone() {
        return isLandscapeConfiguration() && !isTabletConfiguration();
    }

    private ViewOutlineProvider roundedOutline(int radiusDp) {
        return new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(radiusDp));
            }
        };
    }

    private void buildNowPlayingSection(
            LinearLayout root, LinearLayout header, LinearLayout mainButtons,
            TextView stateTextView, SeekBar seekBar,
            LinearLayout timeRow, VisualizerView visualizer) {
        artImageView = new ImageView(this);
        artImageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artImageView.setContentDescription("Album art");
        artImageView.setImageResource(R.drawable.no_album_art);
        artImageView.setClipToOutline(true);
        artImageView.setOutlineProvider(roundedOutline(14));
        artScrim = null;

        if (isCompactLandscapePhone()) {
            // A landscape phone is wide but short. The art fills the
            // entire left half as a background (not a small thumbnail
            // beside the text) — state line, title/artist/album, seek bar,
            // and elapsed time all overlay on top of it, centered, the
            // same "art behind everything" idea as the portrait layout
            // above but split left/right instead of stacked. The
            // visualizer takes the right half, so it gets real width
            // instead of a squashed sliver below everything.
            //
            // The header (FredPlayer/Settings) and the transport buttons
            // both float on top of this whole block instead of sitting in
            // their own rows above/below it, so the art extends behind
            // both of them too rather than losing that height.
            FrameLayout topFrame = new FrameLayout(this);
            LinearLayout.LayoutParams topFrameParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            root.addView(topFrame, topFrameParams);

            LinearLayout contentRow = new LinearLayout(this);
            contentRow.setOrientation(LinearLayout.HORIZONTAL);
            topFrame.addView(contentRow, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            FrameLayout.LayoutParams headerParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            headerParams.gravity = Gravity.TOP;
            topFrame.addView(header, headerParams);

            FrameLayout artFrame = new FrameLayout(this);
            LinearLayout.LayoutParams artFrameParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            contentRow.addView(artFrame, artFrameParams);

            artFrame.addView(artImageView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            artScrim = new View(this);
            artScrim.setBackgroundColor(Color.argb(150, 0, 0, 0));
            artFrame.addView(artScrim, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            // The transport buttons float over the bottom of this same art
            // frame (not the visualizer's half — that stays clear), so the
            // overlay column below gets top/bottom clearance sized to the
            // header and button rows to avoid the text and buttons
            // colliding.
            // MATCH_PARENT (not WRAP_CONTENT) so the weight-based button
            // widths in transportButtonParams() have a definite width to
            // divide up — with WRAP_CONTENT here, 0dp-width weighted
            // children would have nothing to distribute and collapse.
            FrameLayout.LayoutParams mainButtonsParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mainButtonsParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            mainButtonsParams.bottomMargin = dp(6);
            artFrame.addView(mainButtons, mainButtonsParams);

            LinearLayout overlayColumn = new LinearLayout(this);
            overlayColumn.setOrientation(LinearLayout.VERTICAL);
            overlayColumn.setGravity(Gravity.CENTER_HORIZONTAL);
            FrameLayout.LayoutParams overlayColumnParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            overlayColumnParams.gravity = Gravity.CENTER;
            overlayColumnParams.leftMargin = dp(14);
            overlayColumnParams.rightMargin = dp(14);
            overlayColumnParams.topMargin = dp(46);
            overlayColumnParams.bottomMargin = dp(66);
            artFrame.addView(overlayColumn, overlayColumnParams);

            overlayColumn.addView(stateTextView, matchWrap());

            // Wider overlay column than the old beside-a-thumbnail layout
            // had, and no artificially small line cap — that combination
            // is what was truncating titles that had plenty of room.
            nowPlayingText = text("No song selected", 17, Color.rgb(245, 243, 237));
            nowPlayingText.setGravity(Gravity.CENTER);
            nowPlayingText.setMaxLines(3);
            nowPlayingText.setEllipsize(TextUtils.TruncateAt.END);
            overlayColumn.addView(nowPlayingText, topMargin(4));

            playlistText = text("", 13, Color.rgb(183, 182, 173));
            playlistText.setGravity(Gravity.CENTER);
            playlistText.setMaxLines(1);
            playlistText.setEllipsize(TextUtils.TruncateAt.END);
            overlayColumn.addView(playlistText, topMargin(4));

            overlayColumn.addView(seekBar, topMargin(6));
            overlayColumn.addView(timeRow, matchWrap());

            LinearLayout.LayoutParams visualizerParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            visualizerParams.leftMargin = dp(14);
            contentRow.addView(visualizer, visualizerParams);
            return;
        }

        if (sideBySideArtLayout()) {
            // Tablet only at this point — isCompactLandscapePhone() above
            // already claimed the landscape-phone case.
            root.addView(stateTextView, topMargin(8));

            LinearLayout nowPlayingRow = new LinearLayout(this);
            nowPlayingRow.setOrientation(LinearLayout.HORIZONTAL);
            nowPlayingRow.setGravity(Gravity.CENTER_VERTICAL);
            root.addView(nowPlayingRow, topMargin(24));

            int artSize = dp(168);
            nowPlayingRow.addView(artImageView, new LinearLayout.LayoutParams(artSize, artSize));

            LinearLayout nowPlayingColumn = new LinearLayout(this);
            nowPlayingColumn.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams columnParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            columnParams.leftMargin = dp(18);
            nowPlayingRow.addView(nowPlayingColumn, columnParams);

            nowPlayingText = text("No song selected", 20, Color.rgb(245, 243, 237));
            nowPlayingText.setGravity(Gravity.START);
            nowPlayingText.setMaxLines(4);
            nowPlayingText.setEllipsize(TextUtils.TruncateAt.END);
            nowPlayingColumn.addView(nowPlayingText, matchWrap());

            playlistText = text("", 15, Color.rgb(183, 182, 173));
            playlistText.setGravity(Gravity.START);
            playlistText.setMaxLines(1);
            playlistText.setEllipsize(TextUtils.TruncateAt.END);
            nowPlayingColumn.addView(playlistText, topMargin(8));

            root.addView(seekBar, topMargin(14));
            root.addView(timeRow, matchWrap());

            LinearLayout.LayoutParams visualizerParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            visualizerParams.topMargin = dp(18);
            root.addView(visualizer, visualizerParams);
            return;
        }

        // Portrait phone: art is a full-width square — the actual point is
        // to give the art real presence on screen, not just a band sized
        // to hug the text. The "Playing"/"Leveling" state line, title/
        // artist/album, and the seek bar all overlay on top of it, centered
        // in whatever extra room the square has beyond what they need.
        //
        // A square this size necessarily eats into the rest of the page's
        // budget, so the visualizer's minimum height is trimmed for this
        // case (see buildContentView) to keep the transport buttons on
        // screen without scrolling — a deliberate trade the user asked for.
        int squareSize = dp(getResources().getConfiguration().screenWidthDp) - dp(40);
        FrameLayout nowPlayingFrame = new FrameLayout(this);
        root.addView(nowPlayingFrame, topMargin(16));

        FrameLayout.LayoutParams artFrameParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, squareSize);
        nowPlayingFrame.addView(artImageView, artFrameParams);

        artScrim = new View(this);
        artScrim.setBackgroundColor(Color.argb(150, 0, 0, 0));
        FrameLayout.LayoutParams scrimParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, squareSize);
        nowPlayingFrame.addView(artScrim, scrimParams);

        LinearLayout overlayColumn = new LinearLayout(this);
        overlayColumn.setOrientation(LinearLayout.VERTICAL);
        overlayColumn.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams columnFrameParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        columnFrameParams.gravity = Gravity.CENTER;
        columnFrameParams.leftMargin = dp(20);
        columnFrameParams.rightMargin = dp(20);
        nowPlayingFrame.addView(overlayColumn, columnFrameParams);

        overlayColumn.addView(stateTextView, matchWrap());

        nowPlayingText = text("No song selected", 20, Color.rgb(245, 243, 237));
        nowPlayingText.setGravity(Gravity.CENTER);
        nowPlayingText.setMaxLines(4);
        nowPlayingText.setEllipsize(TextUtils.TruncateAt.END);
        overlayColumn.addView(nowPlayingText, topMargin(6));

        playlistText = text("", 15, Color.rgb(183, 182, 173));
        playlistText.setGravity(Gravity.CENTER);
        playlistText.setMaxLines(1);
        playlistText.setEllipsize(TextUtils.TruncateAt.END);
        overlayColumn.addView(playlistText, topMargin(4));

        overlayColumn.addView(seekBar, topMargin(10));

        root.addView(timeRow, matchWrap());

        LinearLayout.LayoutParams visualizerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        visualizerParams.topMargin = dp(18);
        root.addView(visualizer, visualizerParams);
    }

    private static String artworkKey(String artist, String album) {
        String normalizedArtist = artist == null ? "" : artist.trim().toLowerCase(Locale.US);
        String normalizedAlbum = album == null ? "" : album.trim().toLowerCase(Locale.US);
        return normalizedArtist + "|" + normalizedAlbum;
    }

    // Independent of SleepMusicService's own artwork fetch/cache — same
    // server endpoint, same (artist, album) cache key, but this activity
    // and the service are only loosely coupled via broadcasts (per this
    // app's existing pattern), so each fetches and decodes its own copy
    // rather than trying to share a Bitmap across process/component
    // boundaries.
    private void updateArtwork(String trackUri, String artist, String album) {
        if (artImageView == null) {
            return;
        }
        String key = artworkKey(artist, album);
        artworkRequestKey = key;
        Bitmap cached;
        synchronized (artworkMemoryCache) {
            cached = artworkMemoryCache.get(key);
        }
        if (cached != null) {
            showArtwork(cached);
            return;
        }
        showPlaceholderArtwork();
        if (artist == null || artist.isEmpty() || album == null || album.isEmpty()) {
            return;
        }
        String artworkUrl = RemoteLibraryClient.artworkUrlFromTrackUri(trackUri);
        if (artworkUrl == null) {
            return;
        }
        String token = PlaylistStore.loadServerToken(this);
        new Thread(() -> fetchArtwork(artworkUrl, token, key), "FredPlayerArtworkFetch").start();
    }

    private void showArtwork(Bitmap bitmap) {
        if (artImageView == null) {
            return;
        }
        artImageView.setImageBitmap(bitmap);
        artImageView.setVisibility(View.VISIBLE);
        if (artScrim != null) {
            artScrim.setVisibility(View.VISIBLE);
        }
    }

    private void showPlaceholderArtwork() {
        if (artImageView == null) {
            return;
        }
        artImageView.setImageResource(R.drawable.no_album_art);
        artImageView.setVisibility(View.VISIBLE);
        if (artScrim != null) {
            artScrim.setVisibility(View.VISIBLE);
        }
    }

    private void fetchArtwork(String urlString, String token, String key) {
        Bitmap bitmap = null;
        try {
            byte[] data = RemoteLibraryClient.fetchBytes(urlString, token);
            bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
        } catch (Exception ignored) {
            // Best-effort — the player screen just stays without art.
        }
        if (bitmap == null) {
            return;
        }
        synchronized (artworkMemoryCache) {
            artworkMemoryCache.put(key, bitmap);
        }
        Bitmap decoded = bitmap;
        runOnUiThread(() -> {
            // Staleness guard — the user may have already skipped to
            // another track by the time this comes back.
            if (key.equals(artworkRequestKey)) {
                showArtwork(decoded);
            }
        });
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
                ? ", syncing next " + Math.min(progressDone, progressTotal) + "/" + progressTotal
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

    private void confirmRemoveCurrentTrack() {
        if (playlist.isEmpty()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Remove track")
                .setMessage("Remove the current track from \"" + activePlaylistName + "\"?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", (dialog, which) ->
                        sendServiceCommand(SleepMusicService.ACTION_REMOVE_CURRENT))
                .show();
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
        intent.putExtra(
                SleepMusicService.EXTRA_CONTROL_TOKEN,
                PlaylistStore.loadControlToken(this));
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
        if (settingsPlaylistLabel != null) {
            settingsPlaylistLabel.setText(playlistSummary(playlist.size()));
        }
        if (playlist.isEmpty()) {
            if (nowPlayingText != null) {
                nowPlayingText.setText("No song selected");
            }
            showPlaceholderArtwork();
            playing = false;
            updatePlayButtonIcon();
            updateTrackProgress(0L, 0L);
        }
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

    private void openWebPage(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (RuntimeException e) {
            Toast.makeText(this, "No web browser is available", Toast.LENGTH_LONG).show();
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

    private static final int SETTINGS_STYLE_PRIMARY = 0;
    private static final int SETTINGS_STYLE_SECONDARY = 1;
    private static final int SETTINGS_STYLE_DESTRUCTIVE = 2;

    // Pill-shaped action buttons for the Settings screen, styled with the
    // same teal-accent/slate palette as the transport controls (see
    // transportBackground()) instead of the stock gray Button chrome, so
    // Settings feels like part of the same app rather than a bare form.
    private Button settingsButton(String label, int style) {
        return settingsButton(label, 0, style);
    }

    private Button settingsButton(String label, int iconResId, int style) {
        Button button = button(label);
        button.setBackground(settingsButtonBackground(style));
        button.setTextColor(Color.rgb(245, 243, 237));
        button.setPadding(dp(16), dp(12), dp(16), dp(12));
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        if (iconResId != 0) {
            Drawable icon = getDrawable(iconResId);
            if (icon != null) {
                icon = icon.mutate();
                icon.setTint(Color.rgb(245, 243, 237));
                button.setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null);
                button.setCompoundDrawablePadding(dp(12));
            }
        }
        return button;
    }

    private GradientDrawable settingsButtonBackground(int style) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setCornerRadius(dp(14));
        if (style == SETTINGS_STYLE_PRIMARY) {
            drawable.setColor(Color.rgb(45, 112, 91));
            drawable.setStroke(dp(1), Color.rgb(118, 222, 190));
        } else if (style == SETTINGS_STYLE_DESTRUCTIVE) {
            drawable.setColor(Color.rgb(107, 45, 42));
            drawable.setStroke(dp(1), Color.rgb(214, 120, 110));
        } else {
            drawable.setColor(Color.rgb(35, 41, 46));
            drawable.setStroke(dp(1), Color.rgb(82, 91, 99));
        }
        return drawable;
    }

    // Rounded card surface used to visually separate each Settings section
    // from the plain background, instead of every section's controls
    // floating directly on the screen with only a text label between them.
    private LinearLayout settingsCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(18));
        background.setColor(Color.rgb(24, 27, 30));
        background.setStroke(dp(1), Color.rgb(41, 46, 51));
        card.setBackground(background);
        card.setPadding(dp(14), dp(14), dp(14), dp(16));
        return card;
    }

    private void applySystemBarInsets(View view) {
        applySystemBarInsets(view, 0, 0, 0, 0);
    }

    // basePadding is the view's own desired padding — setPadding() alone
    // isn't enough here because the returned listener below replaces
    // whatever padding is currently set every time insets are dispatched
    // (attach, rotation, ...), so any padding set separately would just get
    // silently wiped out the first time that fires.
    private void applySystemBarInsets(View view, int baseLeft, int baseTop, int baseRight, int baseBottom) {
        view.setOnApplyWindowInsetsListener((target, windowInsets) -> {
            int left;
            int top;
            int right;
            int bottom;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets safeInsets = windowInsets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                left = safeInsets.left;
                top = safeInsets.top;
                right = safeInsets.right;
                bottom = safeInsets.bottom;
            } else {
                left = windowInsets.getSystemWindowInsetLeft();
                top = windowInsets.getSystemWindowInsetTop();
                right = windowInsets.getSystemWindowInsetRight();
                bottom = windowInsets.getSystemWindowInsetBottom();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    DisplayCutout cutout = windowInsets.getDisplayCutout();
                    if (cutout != null) {
                        left = Math.max(left, cutout.getSafeInsetLeft());
                        top = Math.max(top, cutout.getSafeInsetTop());
                        right = Math.max(right, cutout.getSafeInsetRight());
                        bottom = Math.max(bottom, cutout.getSafeInsetBottom());
                    }
                }
            }
            target.setPadding(baseLeft + left, baseTop + top, baseRight + right, baseBottom + bottom);
            return windowInsets;
        });
        view.requestApplyInsets();
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
        if (playButton != null) {
            playButton.setImageResource(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
            playButton.setContentDescription(playing ? "Pause" : "Play");
            playButton.setBackground(transportBackground(true));
        }
        if (lyricsPlayButton != null) {
            lyricsPlayButton.setImageResource(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
            lyricsPlayButton.setContentDescription(playing ? "Pause" : "Play");
            lyricsPlayButton.setBackground(transportBackground(true));
        }
    }

    private void updateLyricsSubtitle() {
        if (lyricsSubtitleView == null) {
            return;
        }
        String subtitle = currentTrackName.isEmpty() ? ""
                : currentTrackArtist.isEmpty() ? currentTrackName : currentTrackArtist + " – " + currentTrackName;
        lyricsSubtitleView.setText(subtitle);
    }

    private void updateShuffleRepeatButtons() {
        if (shuffleButton != null) {
            shuffleButton.setBackground(transportBackground(shuffleEnabled));
            shuffleButton.setContentDescription(shuffleEnabled ? "Shuffle: on" : "Shuffle: off");
        }
        if (repeatButton != null) {
            repeatButton.setImageResource(
                    repeatMode == SleepMusicService.REPEAT_ONE ? R.drawable.ic_repeat_one : R.drawable.ic_repeat);
            repeatButton.setBackground(transportBackground(repeatMode != SleepMusicService.REPEAT_OFF));
            repeatButton.setContentDescription(
                    repeatMode == SleepMusicService.REPEAT_OFF ? "Repeat: off"
                            : repeatMode == SleepMusicService.REPEAT_ONE ? "Repeat: one track" : "Repeat: all");
        }
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

    private LinearLayout.LayoutParams lyricsHeaderButtonParams(
            boolean compactPhoneLandscape) {
        int size = dp(compactPhoneLandscape ? 36 : 48);
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(size, size);
        params.leftMargin = dp(compactPhoneLandscape ? 2 : 5);
        params.rightMargin = dp(compactPhoneLandscape ? 2 : 5);
        return params;
    }

    private LinearLayout.LayoutParams lyricsTransportButtonParams(
            boolean primary,
            boolean compactPhoneLandscape) {
        int sizeDp;
        if (compactPhoneLandscape) {
            sizeDp = primary ? 42 : 34;
        } else {
            sizeDp = primary ? 60 : 48;
        }
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
        int margin = dp(compactPhoneLandscape ? 2 : 5);
        params.leftMargin = margin;
        params.rightMargin = margin;
        return params;
    }

    private LinearLayout.LayoutParams headerIconButtonParams() {
        int size = dp(isCompactLandscapePhone() ? 40 : 48);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        params.leftMargin = dp(5);
        params.rightMargin = dp(5);
        return params;
    }

    private LinearLayout.LayoutParams inlineButton() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = dp(5);
        params.rightMargin = dp(5);
        return params;
    }

    // Right-margin-only spacing for a row of Settings pill buttons, so the
    // first button stays flush with the single-button rows above/below it
    // instead of picking up inlineButton()'s extra 5dp leading inset.
    private LinearLayout.LayoutParams settingsInlineParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.rightMargin = dp(10);
        return params;
    }

    private LinearLayout.LayoutParams actionButtonParams(int topMarginDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(topMarginDp);
        params.gravity = Gravity.START;
        return params;
    }

    private int transportRegularSizePx;
    private int transportPrimarySizePx;

    // Computes a fixed button size that's guaranteed to fit the row (now 7
    // buttons: shuffle, previous, play, next, stop, repeat, remove) in
    // whatever width is actually available, instead of a hardcoded
    // compact/non-compact size pair — a fixed pair tuned for the old
    // 5-button row overflowed off-screen on phones once shuffle/repeat were
    // added. Deliberately NOT weight-based stretch-to-fill: that fixed the
    // phone overflow but made the row stretch into oversized ovals spanning
    // the full width on tablets. Clamping to a max keeps buttons a normal,
    // compact, centered cluster on wide screens instead.
    private void computeTransportButtonSizes() {
        boolean compact = isCompactLandscapePhone();
        int screenWidthDp = getResources().getConfiguration().screenWidthDp;
        // Compact landscape splits the screen into an art half and a
        // visualizer half — mainButtons only gets the art half's width.
        int availableDp = compact ? (screenWidthDp - 40) / 2 : screenWidthDp - 40;
        int marginBudgetDp = 7 * 8;
        int regularDp = (int) Math.floor((availableDp - marginBudgetDp - 14) / 7.0);
        regularDp = Math.max(36, Math.min(58, regularDp));
        int primaryDp = Math.max(50, Math.min(72, regularDp + 14));
        transportRegularSizePx = dp(regularDp);
        transportPrimarySizePx = dp(primaryDp);
    }

    private LinearLayout.LayoutParams transportButtonParams(boolean primary) {
        int size = primary ? transportPrimarySizePx : transportRegularSizePx;
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        params.leftMargin = dp(4);
        params.rightMargin = dp(4);
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
