package com.silveronstudios.fredplayer;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.session.MediaSession;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.media.MediaBrowserServiceCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SleepMusicService extends MediaBrowserServiceCompat implements AudioManager.OnAudioFocusChangeListener {
    private static final String BROWSE_ROOT_ID = "root";
    private static final String PLAYLIST_ID_PREFIX = "playlist:";
    private static final String PLAY_PLAYLIST_ID_PREFIX = "play-playlist:";
    private static final String TRACK_ID_PREFIX = "track:";
    public static final String ACTION_SET_PLAYLIST = "com.silveronstudios.fredplayer.SET_PLAYLIST";
    public static final String ACTION_PLAY_URI = "com.silveronstudios.fredplayer.PLAY_URI";
    public static final String ACTION_TOGGLE_PLAY = "com.silveronstudios.fredplayer.TOGGLE_PLAY";
    public static final String ACTION_SKIP = "com.silveronstudios.fredplayer.SKIP";
    public static final String ACTION_PREVIOUS = "com.silveronstudios.fredplayer.PREVIOUS";
    public static final String ACTION_STOP = "com.silveronstudios.fredplayer.STOP";
    public static final String ACTION_CLEAR = "com.silveronstudios.fredplayer.CLEAR";
    public static final String ACTION_REMOVE_CURRENT = "com.silveronstudios.fredplayer.REMOVE_CURRENT";
    public static final String ACTION_TOGGLE_SHUFFLE = "com.silveronstudios.fredplayer.TOGGLE_SHUFFLE";
    public static final String ACTION_CYCLE_REPEAT = "com.silveronstudios.fredplayer.CYCLE_REPEAT";
    public static final String ACTION_SEEK = "com.silveronstudios.fredplayer.SEEK";
    public static final String ACTION_SET_OUTPUT_LEVEL = "com.silveronstudios.fredplayer.SET_OUTPUT_LEVEL";
    public static final String ACTION_SET_LEVELING_STRENGTH = "com.silveronstudios.fredplayer.SET_LEVELING_STRENGTH";
    public static final String ACTION_SET_LEVELING_SETTINGS = "com.silveronstudios.fredplayer.SET_LEVELING_SETTINGS";
    public static final String ACTION_SET_VISUALIZATION_SETTINGS = "com.silveronstudios.fredplayer.SET_VISUALIZATION_SETTINGS";
    public static final String ACTION_SET_OUTPUT_VISUAL_DELAY = "com.silveronstudios.fredplayer.SET_OUTPUT_VISUAL_DELAY";
    public static final String ACTION_CALIBRATE_OUTPUT_DELAY = "com.silveronstudios.fredplayer.CALIBRATE_OUTPUT_DELAY";
    public static final String ACTION_CLEAR_OUTPUT_VISUAL_DELAY =
            "com.silveronstudios.fredplayer.CLEAR_OUTPUT_VISUAL_DELAY";
    public static final String ACTION_REQUEST_STATE = "com.silveronstudios.fredplayer.REQUEST_STATE";
    public static final String ACTION_STATE_CHANGED = "com.silveronstudios.fredplayer.STATE_CHANGED";
    public static final String ACTION_VISUALIZATION_CHANGED = "com.silveronstudios.fredplayer.VISUALIZATION_CHANGED";

    public static final String EXTRA_PLAYLIST = "playlist";
    public static final String EXTRA_START_PLAYING = "start_playing";
    public static final String EXTRA_IS_PLAYING = "is_playing";
    public static final String EXTRA_TRACK_NAME = "track_name";
    public static final String EXTRA_TRACK_ARTIST = "track_artist";
    public static final String EXTRA_TRACK_ALBUM = "track_album";
    public static final String EXTRA_TRACK_URI = "track_uri";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_PLAYLIST_COUNT = "playlist_count";
    public static final String EXTRA_POSITION_MS = "position_ms";
    public static final String EXTRA_DURATION_MS = "duration_ms";
    public static final String EXTRA_OUTPUT_LEVEL = "output_level";
    public static final String EXTRA_LEVELING_STRENGTH = "leveling_strength";
    public static final String EXTRA_ANALYSIS_SECONDS = "analysis_seconds";
    public static final String EXTRA_LEVEL_ATTACK_MS = "level_attack_ms";
    public static final String EXTRA_LEVEL_RELEASE_MS = "level_release_ms";
    public static final String EXTRA_GAIN_DOWN_MS = "gain_down_ms";
    public static final String EXTRA_GAIN_UP_MS = "gain_up_ms";
    public static final String EXTRA_COMPRESSOR_THRESHOLD = "compressor_threshold";
    public static final String EXTRA_OUTPUT_CEILING = "output_ceiling";
    public static final String EXTRA_VISUAL_FPS = "visual_fps";
    public static final String EXTRA_VISUAL_WAVEFORM_MS = "visual_waveform_ms";
    public static final String EXTRA_VISUAL_FFT_SIZE = "visual_fft_size";
    public static final String EXTRA_VISUAL_FFT_BARS = "visual_fft_bars";
    public static final String EXTRA_VISUAL_SMOOTHING = "visual_smoothing";
    public static final String EXTRA_VISUAL_LOG_SCALE = "visual_log_scale";
    public static final String EXTRA_OUTPUT_VISUAL_DELAY_MS = "output_visual_delay_ms";
    public static final String EXTRA_OUTPUT_ROUTE_NAME = "output_route_name";
    public static final String EXTRA_OUTPUT_ROUTE_KEY = "output_route_key";
    public static final String EXTRA_OUTPUT_ROUTE_BLUETOOTH = "output_route_bluetooth";
    public static final String EXTRA_OUTPUT_DELAY_CALIBRATING = "output_delay_calibrating";
    public static final String EXTRA_WAVEFORM = "waveform";
    public static final String EXTRA_SPECTRUM = "spectrum";
    public static final String EXTRA_CACHE_COUNT = "cache_count";
    public static final String EXTRA_CACHE_PRUNE_ABOVE = "cache_prune_above";
    public static final String EXTRA_CACHE_KEEP = "cache_keep";
    public static final String EXTRA_CACHE_BYTES = "cache_bytes";
    public static final String EXTRA_CACHE_PROGRESS_DONE = "cache_progress_done";
    public static final String EXTRA_CACHE_PROGRESS_TOTAL = "cache_progress_total";
    public static final String EXTRA_VISUAL_CACHE_COUNT = "visual_cache_count";
    public static final String EXTRA_VISUAL_CACHE_PRUNE_ABOVE = "visual_cache_prune_above";
    public static final String EXTRA_VISUAL_CACHE_KEEP = "visual_cache_keep";
    public static final String EXTRA_VISUAL_CACHE_BYTES = "visual_cache_bytes";
    public static final String EXTRA_CONTROL_TOKEN = "control_token";
    public static final String EXTRA_SHUFFLE_ENABLED = "shuffle_enabled";
    public static final String EXTRA_REPEAT_MODE = "repeat_mode";
    public static final String EXTRA_CURRENT_INDEX = "current_index";
    public static final String EXTRA_SHUFFLE_BAG = "shuffle_bag";
    public static final String EXTRA_PLAY_HISTORY = "play_history";
    public static final String EXTRA_HISTORY_INDEX = "history_index";

    public static final int REPEAT_OFF = 0;
    public static final int REPEAT_ALL = 1;
    public static final int REPEAT_ONE = 2;

    private static final String CHANNEL_ID = "fred_player_playback";
    private static final int NOTIFICATION_ID = 41;
    private static final int CACHE_LOOKAHEAD_TRACKS = 2;
    // Small in-memory bound — this is a session-lifetime cache of decoded
    // bitmaps (not the on-disk server cache), just enough to make repeat
    // plays of recently-heard albums instant without holding onto every
    // album ever played in this process's memory.
    private static final int ARTWORK_MEMORY_CACHE_MAX = 24;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final ArrayList<String> playlist = new ArrayList<>();
    private final ArrayList<Integer> shuffleBag = new ArrayList<>();
    private final ArrayList<Integer> playHistory = new ArrayList<>();
    private final Map<String, Bitmap> artworkMemoryCache = new LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
            return size() > ARTWORK_MEMORY_CACHE_MAX;
        }
    };

    private NormalizingAudioPlayer player;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private MediaSessionCompat mediaSession;
    private ExecutorService browseExecutor;
    private ExecutorService artworkExecutor;
    private ExecutorService calibrationExecutor;
    private volatile Bitmap currentArtworkBitmap;
    private Bitmap placeholderArtworkBitmap;
    private String artworkRequestKey = "";
    private String lastPublishedMetadataKey = "";
    private Bitmap lastPublishedArtworkBitmap;
    private String activePlaylistName = PlaylistStore.DEFAULT_PLAYLIST_NAME;
    private boolean shuffleEnabled = true;
    private int repeatMode = REPEAT_ALL;
    private int currentIndex = -1;
    // Position within playHistory that represents "what's currently
    // playing" — browser-back/forward style, so Previous/Next can retrace
    // actual play order instead of just picking an adjacent raw index
    // (meaningless in shuffle mode) or drawing a fresh random pick every
    // time Next is pressed after a Previous.
    private int historyIndex = -1;
    private boolean playbackRequested;
    private boolean audioActuallyPlaying;
    private String currentTrackName = "";
    private String currentTrackArtist = "";
    private String currentTrackAlbum = "";
    private String currentTrackUri = "";
    private String message = "Paused";
    private int cacheProgressDone;
    private int cacheProgressTotal;
    private boolean outputDelayCalibrating;
    private String lastCalibratedRouteKey = "";
    private String lastCalibratedRouteName = "";
    private int lastCalibratedDelayMs;
    private final Runnable progressPublisher = new Runnable() {
        @Override
        public void run() {
            if (player != null && currentIndex >= 0) {
                publishProgress();
            }
            mainHandler.postDelayed(this, 500L);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        browseExecutor = Executors.newSingleThreadExecutor();
        artworkExecutor = Executors.newSingleThreadExecutor();
        calibrationExecutor = Executors.newSingleThreadExecutor();
        LinkedHashMap<String, ArrayList<String>> playlists = PlaylistStore.loadPlaylists(this);
        activePlaylistName = PlaylistStore.loadActivePlaylistName(this, playlists);
        ArrayList<String> activeTracks = playlists.get(activePlaylistName);
        if (activeTracks != null) {
            playlist.addAll(activeTracks);
        }
        shuffleEnabled = PlaylistStore.loadShuffleEnabled(this);
        repeatMode = PlaylistStore.loadRepeatMode(this);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        createNotificationChannel();
        createMediaSession();
        NormalizingAudioPlayer.pruneProfileCache(this);

        player = new NormalizingAudioPlayer(this, new NormalizingAudioPlayer.Callback() {
            @Override
            public void onTrackStarted() {
                mainHandler.post(() -> {
                    audioActuallyPlaying = playbackRequested;
                    message = playbackRequested ? "Playing" : "Paused";
                    publishState();
                });
            }

            @Override
            public void onTrackFinished() {
                mainHandler.post(() -> {
                    audioActuallyPlaying = false;
                    if (playbackRequested && !playlist.isEmpty()) {
                        handleTrackFinished();
                    } else {
                        message = "Paused";
                        publishState();
                    }
                });
            }

            @Override
            public void onError(String error) {
                mainHandler.post(() -> {
                    audioActuallyPlaying = false;
                    message = error;
                    if (playbackRequested && playlist.size() > 1) {
                        playRandomTrack();
                    } else {
                        playbackRequested = false;
                        abandonAudioFocus();
                        publishState();
                    }
                });
            }

            @Override
            public void onVisualization(byte[] waveform, byte[] spectrum) {
                publishVisualization(waveform, spectrum);
            }

            @Override
            public void onCacheProgress(int done, int total) {
                mainHandler.post(() -> {
                    cacheProgressDone = done;
                    cacheProgressTotal = total;
                    publishState();
                });
            }
        });
        player.setOutputLevel(PlaylistStore.loadOutputLevel(this));
        player.setLevelingStrength(PlaylistStore.loadLevelingStrength(this));
        player.setLevelingSettings(PlaylistStore.loadLevelingSettings(this));
        player.setVisualizationSettings(PlaylistStore.loadVisualizationSettings(this));
        if (!playlist.isEmpty()) {
            warmCacheLookahead();
        }

        startForegroundCompat();
        publishState();
        mainHandler.postDelayed(progressPublisher, 500L);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            publishState();
            return START_STICKY;
        }

        String action = intent.getAction();
        if (action.startsWith(getPackageName() + ".")
                && !PlaylistStore.loadControlToken(this).equals(
                        intent.getStringExtra(EXTRA_CONTROL_TOKEN))) {
            Log.w("FredPlayerAudio", "Ignored an unauthorized playback command");
            return START_NOT_STICKY;
        }
        if (ACTION_SET_PLAYLIST.equals(action)) {
            // The playlist itself is NOT carried on this Intent — for a large
            // remote-URL playlist (long https:// strings, thousands of
            // tracks) that blows well past Android's Binder transaction size
            // limit (~1MB) and silently fails with TransactionTooLargeException,
            // leaving this service's playlist stale while the caller's own
            // copy and on-disk state both look correct. The caller (MainActivity)
            // already persists the full playlist via PlaylistStore before
            // sending this action, so re-reading it here has no size limit —
            // SharedPreferences access isn't a Binder IPC call.
            ArrayList<String> incoming = PlaylistStore.loadPlaylist(this);
            String currentItem = currentIndex >= 0 && currentIndex < playlist.size() ? playlist.get(currentIndex) : null;

            // Diff against the outgoing playlist so playHistory (and the
            // redo cursor) can drop/shift entries for whatever tracks are
            // now gone, instead of silently pointing at the wrong song
            // afterward. The shuffle bag doesn't need the same diffing —
            // it's always rebuilt fresh below regardless of what changed.
            HashSet<String> incomingSet = new HashSet<>(incoming);
            ArrayList<Integer> removedAscending = new ArrayList<>();
            for (int i = 0; i < playlist.size(); i++) {
                if (!incomingSet.contains(playlist.get(i))) {
                    removedAscending.add(i);
                }
            }
            remapStoredIndicesAfterRemoval(removedAscending);

            playlist.clear();
            playlist.addAll(incoming);
            currentIndex = currentItem == null ? -1 : playlist.indexOf(currentItem);
            refillShuffleBag();
            warmCacheLookahead();

            if (playlist.isEmpty()) {
                playbackRequested = false;
                audioActuallyPlaying = false;
                currentIndex = -1;
                historyIndex = -1;
                playHistory.clear();
                currentTrackName = "";
                currentTrackArtist = "";
                currentTrackAlbum = "";
                currentTrackUri = "";
                currentArtworkBitmap = null;
                if (player != null) {
                    player.stop();
                }
                abandonAudioFocus();
                message = "No songs";
                publishState();
                return START_STICKY;
            }

            if (currentItem != null && currentIndex < 0) {
                if (playbackRequested) {
                    playRandomTrack();
                    return START_STICKY;
                }
                player.stop();
                audioActuallyPlaying = false;
                currentTrackName = "";
                currentTrackArtist = "";
                currentTrackAlbum = "";
                currentTrackUri = "";
                currentArtworkBitmap = null;
                message = "Paused";
            }
            if (intent.getBooleanExtra(EXTRA_START_PLAYING, false)) {
                startOrResume();
            } else {
                publishState();
            }
        } else if (ACTION_TOGGLE_PLAY.equals(action)) {
            if (playbackRequested) {
                pausePlayback();
            } else {
                startOrResume();
            }
        } else if (ACTION_SKIP.equals(action)) {
            if (!playlist.isEmpty()) {
                if (!requestAudioFocus()) {
                    playbackRequested = false;
                    message = "Audio focus unavailable";
                    publishState();
                    return START_STICKY;
                }
                playbackRequested = true;
                playRandomTrack();
            }
        } else if (ACTION_PREVIOUS.equals(action)) {
            playPreviousTrack();
        } else if (ACTION_STOP.equals(action)) {
            stopPlayback();
        } else if (ACTION_CLEAR.equals(action)) {
            clearPlaylist();
        } else if (ACTION_REMOVE_CURRENT.equals(action)) {
            removeCurrentTrack();
        } else if (ACTION_PLAY_URI.equals(action)) {
            playSpecificUri(intent.getStringExtra(EXTRA_TRACK_URI));
        } else if (ACTION_TOGGLE_SHUFFLE.equals(action)) {
            toggleShuffle();
        } else if (ACTION_CYCLE_REPEAT.equals(action)) {
            cycleRepeatMode();
        } else if (ACTION_SEEK.equals(action)) {
            seekTo(intent.getLongExtra(EXTRA_POSITION_MS, 0L));
        } else if (ACTION_REQUEST_STATE.equals(action)) {
            publishState();
        } else if (ACTION_SET_OUTPUT_LEVEL.equals(action)) {
            float value = intent.getFloatExtra(EXTRA_OUTPUT_LEVEL, PlaylistStore.loadOutputLevel(this));
            PlaylistStore.saveOutputLevel(this, value);
            player.setOutputLevel(value);
            publishState();
        } else if (ACTION_SET_LEVELING_STRENGTH.equals(action)) {
            float value = intent.getFloatExtra(EXTRA_LEVELING_STRENGTH, PlaylistStore.loadLevelingStrength(this));
            PlaylistStore.saveLevelingStrength(this, value);
            player.setLevelingStrength(value);
            publishState();
        } else if (ACTION_SET_LEVELING_SETTINGS.equals(action)) {
            LevelingSettings current = PlaylistStore.loadLevelingSettings(this);
            LevelingSettings settings = new LevelingSettings(
                    intent.getFloatExtra(EXTRA_ANALYSIS_SECONDS, current.analysisSeconds),
                    intent.getFloatExtra(EXTRA_LEVEL_ATTACK_MS, current.levelAttackMs),
                    intent.getFloatExtra(EXTRA_LEVEL_RELEASE_MS, current.levelReleaseMs),
                    intent.getFloatExtra(EXTRA_GAIN_DOWN_MS, current.gainDownMs),
                    intent.getFloatExtra(EXTRA_GAIN_UP_MS, current.gainUpMs),
                    intent.getFloatExtra(EXTRA_COMPRESSOR_THRESHOLD, current.compressorThreshold),
                    intent.getFloatExtra(EXTRA_OUTPUT_CEILING, current.outputCeiling));
            PlaylistStore.saveLevelingSettings(this, settings);
            player.setLevelingSettings(settings);
            publishState();
        } else if (ACTION_SET_VISUALIZATION_SETTINGS.equals(action)) {
            VisualizationSettings current = PlaylistStore.loadVisualizationSettings(this);
            VisualizationSettings settings = new VisualizationSettings(
                    intent.getIntExtra(EXTRA_VISUAL_FPS, current.fps),
                    intent.getIntExtra(EXTRA_VISUAL_WAVEFORM_MS, current.waveformMs),
                    intent.getIntExtra(EXTRA_VISUAL_FFT_SIZE, current.fftSize),
                    intent.getIntExtra(EXTRA_VISUAL_FFT_BARS, current.fftBars),
                    intent.getFloatExtra(EXTRA_VISUAL_SMOOTHING, current.smoothing),
                    intent.getBooleanExtra(EXTRA_VISUAL_LOG_SCALE, current.logScale));
            PlaylistStore.saveVisualizationSettings(this, settings);
            player.setVisualizationSettings(settings);
            if (!playlist.isEmpty()) {
                warmCacheLookahead();
            }
            publishState();
        } else if (ACTION_SET_OUTPUT_VISUAL_DELAY.equals(action)) {
            if (!AudioOutputRoute.needsManualCalibration(this)) {
                message = "Android handles Bluetooth latency on this device";
                publishState();
                return START_STICKY;
            }
            int delayMs = intent.getIntExtra(EXTRA_OUTPUT_VISUAL_DELAY_MS, 0);
            String routeKey = intent.getStringExtra(EXTRA_OUTPUT_ROUTE_KEY);
            String routeName = intent.getStringExtra(EXTRA_OUTPUT_ROUTE_NAME);
            boolean saved = player.setCurrentOutputVisualDelayMs(delayMs);
            if (!saved && routeKey != null && !routeKey.isEmpty()) {
                PlaylistStore.saveBluetoothVisualDelay(
                        this,
                        routeKey,
                        routeName,
                        delayMs);
                player.refreshOutputVisualDelay();
                lastCalibratedRouteKey = routeKey;
                lastCalibratedRouteName = routeName == null || routeName.isEmpty()
                        ? routeKey
                        : routeName;
                lastCalibratedDelayMs = Math.max(0, Math.min(1500, delayMs));
                saved = true;
            }
            if (!saved) {
                message = "Play through a Bluetooth output first";
            }
            publishState();
        } else if (ACTION_CALIBRATE_OUTPUT_DELAY.equals(action)) {
            startOutputDelayCalibration();
        } else if (ACTION_CLEAR_OUTPUT_VISUAL_DELAY.equals(action)) {
            String routeKey = intent.getStringExtra(EXTRA_OUTPUT_ROUTE_KEY);
            if (routeKey == null || routeKey.isEmpty()) {
                PlaylistStore.clearAllBluetoothVisualDelays(this);
                lastCalibratedRouteKey = "";
                lastCalibratedRouteName = "";
                lastCalibratedDelayMs = 0;
                message = "All Bluetooth calibrations cleared";
            } else {
                PlaylistStore.clearBluetoothVisualDelay(this, routeKey);
                if (routeKey.equals(lastCalibratedRouteKey)) {
                    lastCalibratedRouteKey = "";
                    lastCalibratedRouteName = "";
                    lastCalibratedDelayMs = 0;
                }
                message = "Bluetooth calibration cleared";
            }
            player.refreshOutputVisualDelay();
            publishState();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        mainHandler.removeCallbacks(progressPublisher);
        if (browseExecutor != null) {
            browseExecutor.shutdownNow();
            browseExecutor = null;
        }
        if (artworkExecutor != null) {
            artworkExecutor.shutdownNow();
            artworkExecutor = null;
        }
        if (calibrationExecutor != null) {
            calibrationExecutor.shutdownNow();
            calibrationExecutor = null;
        }
        if (player != null) {
            player.release();
        }
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        abandonAudioFocus();
        super.onDestroy();
    }

    @Override
    public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        return new BrowserRoot(BROWSE_ROOT_ID, null);
    }

    @Override
    public void onLoadChildren(String parentId, Result<List<MediaBrowserCompat.MediaItem>> result) {
        if (BROWSE_ROOT_ID.equals(parentId)) {
            LinkedHashMap<String, ArrayList<String>> playlists = PlaylistStore.loadPlaylists(this);
            ArrayList<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
            for (Map.Entry<String, ArrayList<String>> entry : playlists.entrySet()) {
                int count = entry.getValue().size();
                MediaDescriptionCompat description = new MediaDescriptionCompat.Builder()
                        .setMediaId(PLAYLIST_ID_PREFIX + entry.getKey())
                        .setTitle(entry.getKey())
                        .setSubtitle(count + (count == 1 ? " song" : " songs"))
                        .build();
                int flags = MediaBrowserCompat.MediaItem.FLAG_BROWSABLE;
                if (count > 0) {
                    flags |= MediaBrowserCompat.MediaItem.FLAG_PLAYABLE;
                }
                items.add(new MediaBrowserCompat.MediaItem(description, flags));
            }
            result.sendResult(items);
            return;
        }

        if (!parentId.startsWith(PLAYLIST_ID_PREFIX)) {
            result.sendResult(Collections.emptyList());
            return;
        }
        String playlistName = parentId.substring(PLAYLIST_ID_PREFIX.length());
        ArrayList<String> tracks = PlaylistStore.loadPlaylists(this).get(playlistName);
        if (tracks == null) {
            result.sendResult(Collections.emptyList());
            return;
        }
        ArrayList<String> snapshot = new ArrayList<>(tracks);
        result.detach();
        browseExecutor.execute(() -> {
            ArrayList<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
            if (!snapshot.isEmpty()) {
                MediaDescriptionCompat playDescription = new MediaDescriptionCompat.Builder()
                        .setMediaId(PLAY_PLAYLIST_ID_PREFIX + playlistName)
                        .setTitle(shuffleEnabled ? "Shuffle playlist" : "Play playlist")
                        .setSubtitle(snapshot.size() + (snapshot.size() == 1 ? " song" : " songs"))
                        .build();
                items.add(new MediaBrowserCompat.MediaItem(
                        playDescription, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE));
            }
            for (int i = 0; i < snapshot.size(); i++) {
                // Browsing must never open every remote audio file just to
                // populate the list. Cached metadata is normally present for
                // server tracks; a filename is an immediate safe fallback.
                TrackMetadata metadata = resolveBrowseMetadata(snapshot.get(i));
                MediaDescriptionCompat.Builder descriptionBuilder = new MediaDescriptionCompat.Builder()
                        .setMediaId(TRACK_ID_PREFIX + playlistName + "#" + i)
                        .setTitle(metadata.title.isEmpty() ? "FredPlayer" : metadata.title)
                        .setSubtitle(metadata.detailLine());
                // Only ever a synchronous in-memory lookup — browsing must stay
                // fast for a whole playlist, so this never triggers a network
                // fetch. Coverage grows naturally as albums get played and
                // land in the cache; an uncached track just browses with no
                // icon, which is fine.
                Bitmap icon;
                synchronized (artworkMemoryCache) {
                    icon = artworkMemoryCache.get(artworkKey(metadata.artist, metadata.album));
                }
                if (icon != null) {
                    descriptionBuilder.setIconBitmap(icon);
                }
                items.add(new MediaBrowserCompat.MediaItem(
                        descriptionBuilder.build(), MediaBrowserCompat.MediaItem.FLAG_PLAYABLE));
            }
            result.sendResult(items);
        });
    }

    private TrackMetadata resolveBrowseMetadata(String uri) {
        String[] cached = PlaylistStore.loadTrackMetadata(this, uri);
        if (cached != null) {
            return new TrackMetadata(cached[0], cached[1], cached[2]);
        }
        return new TrackMetadata(PlaylistStore.displayName(this, uri), "", "");
    }

    private static String parsePlaylistName(String mediaId) {
        if (mediaId == null) {
            return null;
        }
        if (mediaId.startsWith(PLAY_PLAYLIST_ID_PREFIX)) {
            return mediaId.substring(PLAY_PLAYLIST_ID_PREFIX.length());
        }
        if (mediaId.startsWith(PLAYLIST_ID_PREFIX)) {
            return mediaId.substring(PLAYLIST_ID_PREFIX.length());
        }
        return null;
    }

    private static String parseTrackPlaylistName(String mediaId) {
        if (mediaId == null || !mediaId.startsWith(TRACK_ID_PREFIX)) {
            return null;
        }
        String rest = mediaId.substring(TRACK_ID_PREFIX.length());
        int separator = rest.lastIndexOf('#');
        return separator < 0 ? null : rest.substring(0, separator);
    }

    private static int parseTrackIndex(String mediaId) {
        if (mediaId == null || !mediaId.startsWith(TRACK_ID_PREFIX)) {
            return -1;
        }
        String rest = mediaId.substring(TRACK_ID_PREFIX.length());
        int separator = rest.lastIndexOf('#');
        if (separator < 0) {
            return -1;
        }
        try {
            return Integer.parseInt(rest.substring(separator + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            pausePlayback();
        } else if (focusChange == AudioManager.AUDIOFOCUS_GAIN && playbackRequested && player != null) {
            player.resume();
            audioActuallyPlaying = true;
            message = "Playing";
            publishState();
        }
    }

    private void startOrResume() {
        if (playlist.isEmpty()) {
            playbackRequested = false;
            message = "Add music first";
            publishState();
            return;
        }
        if (!requestAudioFocus()) {
            playbackRequested = false;
            message = "Audio focus unavailable";
            publishState();
            return;
        }
        playbackRequested = true;
        if (player != null && player.isPaused()) {
            player.resume();
            audioActuallyPlaying = true;
            message = "Playing";
            publishState();
        } else {
            playRandomTrack();
        }
    }

    private void pausePlayback() {
        playbackRequested = false;
        audioActuallyPlaying = false;
        if (player != null) {
            player.pause();
        }
        abandonAudioFocus();
        message = "Paused";
        publishState();
    }

    private void clearPlaylist() {
        playbackRequested = false;
        audioActuallyPlaying = false;
        playlist.clear();
        shuffleBag.clear();
        playHistory.clear();
        currentIndex = -1;
        historyIndex = -1;
        currentTrackName = "";
        currentTrackArtist = "";
        currentTrackAlbum = "";
        currentTrackUri = "";
        currentArtworkBitmap = null;
        PlaylistStore.savePlaylist(this, playlist);
        if (player != null) {
            player.stop();
        }
        abandonAudioFocus();
        message = "No songs";
        publishState();
    }

    private void playSpecificUri(String uri) {
        if (uri == null || playlist.isEmpty()) {
            return;
        }
        int index = playlist.indexOf(uri);
        if (index < 0) {
            return;
        }
        playbackRequested = true;
        if (!requestAudioFocus()) {
            playbackRequested = false;
            message = "Audio focus unavailable";
            publishState();
            return;
        }
        // A manual jump (from the playlist editor or the What's Next list)
        // pulls the track out of the remaining shuffle order so it doesn't
        // also play again later this pass, and starts a fresh history
        // branch — same as a browser tab navigating somewhere new drops
        // whatever "forward" history it had.
        shuffleBag.remove(Integer.valueOf(index));
        recordHistory(index);
        playTrackAt(index);
    }

    private void removeCurrentTrack() {
        if (currentIndex < 0 || currentIndex >= playlist.size()) {
            return;
        }
        int removedIndex = currentIndex;
        playlist.remove(removedIndex);
        // Surgical, not a wipe: only the deleted track drops out of the
        // shuffle bag/history, everything else keeps its place — so
        // deleting a song doesn't also reset your shuffle order/history.
        remapStoredIndicesAfterRemoval(Collections.singletonList(removedIndex));
        PlaylistStore.savePlaylist(this, playlist);
        if (playlist.isEmpty()) {
            playbackRequested = false;
            audioActuallyPlaying = false;
            currentIndex = -1;
            currentTrackName = "";
            currentTrackArtist = "";
            currentTrackAlbum = "";
            currentTrackUri = "";
            currentArtworkBitmap = null;
            if (player != null) {
                player.stop();
            }
            abandonAudioFocus();
            message = "No songs";
            publishState();
            return;
        }
        // With shuffle on, the next track after a deletion should be a new
        // random pick, not just whatever slid into the deleted slot — that
        // was always "the next sequential track" regardless of the shuffle
        // setting, which is only correct when shuffle is off.
        currentIndex = Math.min(removedIndex, playlist.size() - 1);
        int nextIndex = shuffleEnabled ? chooseNextIndex() : currentIndex;
        recordHistory(nextIndex);
        if (playbackRequested) {
            playTrackAt(nextIndex);
            return;
        }
        currentIndex = nextIndex;
        String item = playlist.get(nextIndex);
        TrackMetadata metadata = resolveMetadata(item);
        currentTrackName = metadata.title;
        currentTrackArtist = metadata.artist;
        currentTrackAlbum = metadata.album;
        currentTrackUri = item;
        updateArtworkForCurrentTrack(item, metadata.artist, metadata.album);
        message = "Paused";
        publishState();
    }

    private void stopPlayback() {
        playbackRequested = false;
        audioActuallyPlaying = false;
        if (player != null) {
            player.stop();
        }
        abandonAudioFocus();
        message = "Stopped";
        publishState();
    }

    private void switchActivePlaylist(String name) {
        LinkedHashMap<String, ArrayList<String>> playlists = PlaylistStore.loadPlaylists(this);
        ArrayList<String> tracks = playlists.get(name);
        if (tracks == null || name.equals(activePlaylistName)) {
            return;
        }
        PlaylistStore.savePlaylist(this, playlist);
        playlist.clear();
        playlist.addAll(tracks);
        activePlaylistName = name;
        PlaylistStore.saveActivePlaylistName(this, name);
        currentIndex = -1;
        historyIndex = -1;
        shuffleBag.clear();
        playHistory.clear();
        warmCacheLookahead();
    }

    private void toggleShuffle() {
        shuffleEnabled = !shuffleEnabled;
        // Re-shuffle immediately on toggle-on rather than leaving the bag
        // empty until the next skip — otherwise the What's Next list would
        // show nothing until then.
        if (shuffleEnabled) {
            refillShuffleBag();
        } else {
            shuffleBag.clear();
        }
        PlaylistStore.saveShuffleEnabled(this, shuffleEnabled);
        mediaSession.setShuffleMode(shuffleEnabled
                ? PlaybackStateCompat.SHUFFLE_MODE_ALL
                : PlaybackStateCompat.SHUFFLE_MODE_NONE);
        publishState();
    }

    private void cycleRepeatMode() {
        repeatMode = repeatMode == REPEAT_OFF ? REPEAT_ALL
                : repeatMode == REPEAT_ALL ? REPEAT_ONE : REPEAT_OFF;
        PlaylistStore.saveRepeatMode(this, repeatMode);
        mediaSession.setRepeatMode(toPlaybackRepeatMode(repeatMode));
        publishState();
    }

    // Called only when a track finishes playing on its own — manual skip
    // (ACTION_SKIP / MediaSession next) always advances and wraps regardless
    // of repeat mode; only the natural end-of-track path should honor
    // "repeat one" (replay) or "repeat off" (stop instead of wrapping).
    private void handleTrackFinished() {
        if (repeatMode == REPEAT_ONE) {
            if (currentIndex >= 0 && currentIndex < playlist.size()) {
                playTrackAt(currentIndex);
            }
            return;
        }
        if (repeatMode == REPEAT_OFF) {
            boolean atEnd = shuffleEnabled
                    ? shuffleBag.isEmpty()
                    : currentIndex >= playlist.size() - 1;
            if (atEnd) {
                playbackRequested = false;
                message = "Paused";
                publishState();
                return;
            }
        }
        playRandomTrack();
    }

    // If Previous was pressed earlier and hasn't been followed by a new
    // manual pick, historyIndex sits behind the end of playHistory — in
    // that case this just replays forward through the same recorded path
    // instead of drawing a fresh pick, so it actually undoes Previous
    // rather than landing on an unrelated track. Only once we're back at
    // the end of history does this fall through to a new shuffle/
    // sequential pick.
    private void playRandomTrack() {
        if (playlist.isEmpty() || player == null) {
            playbackRequested = false;
            message = "No songs";
            publishState();
            return;
        }
        int nextIndex;
        if (historyIndex >= 0 && historyIndex < playHistory.size() - 1) {
            historyIndex++;
            nextIndex = playHistory.get(historyIndex);
        } else {
            nextIndex = chooseNextIndex();
            recordHistory(nextIndex);
        }
        playTrackAt(nextIndex);
    }

    // Walks back through the actual play history (the same list the What's
    // Next screen's recently-played section shows) by moving historyIndex
    // rather than recomputing an index — in shuffle mode currentIndex-1
    // has no relation to what really played before this track.
    private void playPreviousTrack() {
        if (playlist.isEmpty() || player == null) {
            playbackRequested = false;
            message = "No songs";
            publishState();
            return;
        }
        if (historyIndex <= 0) {
            return;
        }
        playbackRequested = true;
        if (!requestAudioFocus()) {
            playbackRequested = false;
            message = "Audio focus unavailable";
            publishState();
            return;
        }
        historyIndex--;
        int target = playHistory.get(historyIndex);
        if (shuffleEnabled) {
            shuffleBag.remove(Integer.valueOf(target));
        }
        playTrackAt(target);
    }

    private TrackMetadata resolveMetadata(String uri) {
        String[] cached = PlaylistStore.loadTrackMetadata(this, uri);
        if (cached != null) {
            return new TrackMetadata(cached[0], cached[1], cached[2]);
        }
        return TrackMetadata.from(this, uri);
    }

    private void playTrackAt(int nextIndex) {
        if (nextIndex < 0 || nextIndex >= playlist.size() || player == null) {
            playbackRequested = false;
            message = "No songs";
            publishState();
            return;
        }
        currentIndex = nextIndex;
        String item = playlist.get(nextIndex);
        TrackMetadata metadata = resolveMetadata(item);
        currentTrackName = metadata.title;
        currentTrackArtist = metadata.artist;
        currentTrackAlbum = metadata.album;
        currentTrackUri = item;
        audioActuallyPlaying = false;
        message = "Leveling";
        updateArtworkForCurrentTrack(item, metadata.artist, metadata.album);
        publishState();
        player.play(Uri.parse(item));
        warmCacheLookahead();
    }

    private static String artworkKey(String artist, String album) {
        String normalizedArtist = artist == null ? "" : artist.trim().toLowerCase(Locale.US);
        String normalizedAlbum = album == null ? "" : album.trim().toLowerCase(Locale.US);
        return normalizedArtist + "|" + normalizedAlbum;
    }

    // Called on the main thread from playTrackAt(), right before the
    // publishState() that pushes fresh MediaMetadata out — a synchronous
    // cache hit lands in that same publish; a miss clears any stale art
    // from the previous track and kicks off a background fetch that
    // publishes again once (and only if) it lands before the user has
    // already skipped to something else.
    private void updateArtworkForCurrentTrack(String uriString, String artist, String album) {
        String key = artworkKey(artist, album);
        artworkRequestKey = key;
        Bitmap cached;
        synchronized (artworkMemoryCache) {
            cached = artworkMemoryCache.get(key);
        }
        if (cached != null) {
            currentArtworkBitmap = cached;
            return;
        }
        currentArtworkBitmap = null;
        if (artist == null || artist.isEmpty() || album == null || album.isEmpty()) {
            return;
        }
        String artworkUrl = RemoteLibraryClient.artworkUrlFromTrackUri(uriString);
        if (artworkUrl == null || artworkExecutor == null) {
            return;
        }
        String token = PlaylistStore.loadServerToken(this);
        artworkExecutor.execute(() -> fetchArtwork(artworkUrl, token, key));
    }

    private void fetchArtwork(String urlString, String token, String key) {
        Bitmap bitmap = null;
        try {
            byte[] data = RemoteLibraryClient.fetchBytes(urlString, token);
            bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
        } catch (Exception ignored) {
            // Best-effort — playback must not depend on this succeeding.
        }
        if (bitmap == null) {
            return;
        }
        synchronized (artworkMemoryCache) {
            artworkMemoryCache.put(key, bitmap);
        }
        Bitmap decoded = bitmap;
        mainHandler.post(() -> {
            // Staleness guard — the user may have already skipped to
            // another track by the time this (server-fetched, so not
            // instant on a cold miss) request comes back.
            if (key.equals(artworkRequestKey)) {
                currentArtworkBitmap = decoded;
                publishState();
            }
        });
    }

    private void warmCacheLookahead() {
        if (player == null || playlist.isEmpty()) {
            if (player != null) {
                player.warmLoudnessCache(Collections.emptyList());
            }
            return;
        }

        ArrayList<String> upcoming = new ArrayList<>();
        if (currentIndex < 0) {
            for (int index = 0;
                 index < playlist.size() && upcoming.size() < CACHE_LOOKAHEAD_TRACKS;
                 index++) {
                upcoming.add(playlist.get(index));
            }
        } else if (shuffleEnabled) {
            if (shuffleBag.isEmpty()) {
                refillShuffleBag();
            }
            for (int index : shuffleBag) {
                if (index != currentIndex && index >= 0 && index < playlist.size()) {
                    upcoming.add(playlist.get(index));
                    if (upcoming.size() >= CACHE_LOOKAHEAD_TRACKS) {
                        break;
                    }
                }
            }
        } else {
            for (int offset = 1;
                 offset <= CACHE_LOOKAHEAD_TRACKS && offset < playlist.size();
                 offset++) {
                upcoming.add(playlist.get((currentIndex + offset) % playlist.size()));
            }
        }
        player.warmLoudnessCache(upcoming);
    }

    private void seekTo(long requestedPositionMs) {
        if (player == null || currentIndex < 0 || currentIndex >= playlist.size()) {
            return;
        }
        long duration = player.getDurationMs();
        long position = Math.max(0L, duration > 0L
                ? Math.min(requestedPositionMs, Math.max(0L, duration - 1L))
                : requestedPositionMs);
        boolean startPaused = !playbackRequested;
        audioActuallyPlaying = false;
        message = "Seeking";
        publishState();
        player.play(Uri.parse(playlist.get(currentIndex)), position, startPaused);
    }

    private void startOutputDelayCalibration() {
        if (outputDelayCalibrating) {
            return;
        }
        if (!AudioOutputRoute.needsManualCalibration(this)) {
            message = "Android handles Bluetooth latency on this device";
            publishState();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            message = "Microphone permission is needed for calibration";
            publishState();
            return;
        }
        if (calibrationExecutor == null || !requestAudioFocus()) {
            message = "Audio focus unavailable";
            publishState();
            return;
        }

        final boolean resumeAfterCalibration = playbackRequested && player != null;
        if (player != null) {
            player.pause();
        }
        playbackRequested = false;
        audioActuallyPlaying = false;
        outputDelayCalibrating = true;
        message = "Calibrating Bluetooth delay";
        startForegroundCompat();
        publishState();

        calibrationExecutor.execute(() -> {
            BluetoothDelayCalibrator.Result result = null;
            String error = null;
            try {
                result = BluetoothDelayCalibrator.calibrate(this);
            } catch (Exception e) {
                error = e.getMessage();
                if (error == null || error.trim().isEmpty()) {
                    error = "Bluetooth delay calibration failed";
                }
            }
            BluetoothDelayCalibrator.Result finalResult = result;
            String finalError = error;
            mainHandler.post(() -> {
                if (finalResult != null) {
                    PlaylistStore.saveBluetoothVisualDelay(
                            this,
                            finalResult.routeKey,
                            finalResult.routeLabel,
                            finalResult.delayMs);
                    lastCalibratedRouteKey = finalResult.routeKey;
                    lastCalibratedRouteName = finalResult.routeLabel;
                    lastCalibratedDelayMs = finalResult.delayMs;
                    Log.i("FredPlayerAudio", "Bluetooth calibration for "
                            + finalResult.routeLabel + ": " + finalResult.delayMs
                            + " ms, confidence=" + finalResult.confidence);
                    if (player != null) {
                        player.refreshOutputVisualDelay();
                    }
                    message = "Bluetooth delay calibrated: " + finalResult.delayMs + " ms";
                } else {
                    message = finalError;
                }
                outputDelayCalibrating = false;
                startForegroundCompat();
                if (resumeAfterCalibration && player != null) {
                    playbackRequested = true;
                    audioActuallyPlaying = true;
                    player.resume();
                } else {
                    abandonAudioFocus();
                }
                publishState();
            });
        });
    }

    // Appends a newly-started track to the play-history log, first
    // discarding any stale "forward" entries beyond the current position —
    // the same browser-back/forward-style branching used by manual jumps,
    // so picking an out-of-order track (or advancing past a Previous)
    // keeps the log consistent with what actually played instead of
    // leaving a dangling, no-longer-true future behind.
    private void recordHistory(int trackIndex) {
        if (trackIndex < 0 || trackIndex >= playlist.size()) {
            return;
        }
        while (playHistory.size() > historyIndex + 1) {
            playHistory.remove(playHistory.size() - 1);
        }
        playHistory.add(trackIndex);
        historyIndex = playHistory.size() - 1;
        if (playHistory.size() > 200) {
            playHistory.remove(0);
            historyIndex--;
        }
    }

    // Called right after tracks are erased from `playlist`. `removedAscending`
    // holds their ORIGINAL indices, sorted ascending. Every other stored
    // index (the shuffle bag, the play history log) needs the same
    // treatment `playlist` just got: drop anything that pointed at a
    // removed track, and shift everything above it down to match — that
    // way a deletion only removes the deleted track from the What's Next
    // order, instead of resetting the whole thing.
    private void remapStoredIndicesAfterRemoval(List<Integer> removedAscending) {
        ArrayList<Integer> newBag = new ArrayList<>(shuffleBag.size());
        for (int idx : shuffleBag) {
            int mapped = remapIndexAfterRemoval(idx, removedAscending);
            if (mapped >= 0) {
                newBag.add(mapped);
            }
        }
        shuffleBag.clear();
        shuffleBag.addAll(newBag);

        // If the cursor's own entry (the currently-playing track) is one
        // of the ones being removed, only the history *before* it can
        // still be considered valid — any recorded "forward" entries past
        // it get dropped along with it, same as a fresh manual pick would
        // do. Stop remapping at that point rather than continuing past
        // it, otherwise there's nothing left to point the cursor at and
        // it falls back to -1, which then makes the next recordHistory()
        // call think there's no history at all and erase the part that
        // should have been preserved.
        boolean currentEntryRemoved = historyIndex >= 0 && historyIndex < playHistory.size()
                && remapIndexAfterRemoval(playHistory.get(historyIndex), removedAscending) < 0;
        int scanLimit = currentEntryRemoved ? historyIndex : playHistory.size();

        ArrayList<Integer> newHistory = new ArrayList<>(playHistory.size());
        int newHistoryIndex = -1;
        for (int i = 0; i < scanLimit; i++) {
            int mapped = remapIndexAfterRemoval(playHistory.get(i), removedAscending);
            if (mapped < 0) {
                continue;
            }
            newHistory.add(mapped);
            if (i == historyIndex) {
                newHistoryIndex = newHistory.size() - 1;
            }
        }
        if (currentEntryRemoved) {
            newHistoryIndex = newHistory.size() - 1;
        }
        playHistory.clear();
        playHistory.addAll(newHistory);
        historyIndex = newHistoryIndex;
    }

    private static int remapIndexAfterRemoval(int index, List<Integer> removedAscending) {
        if (index < 0) {
            return -1;
        }
        int shift = 0;
        for (int removed : removedAscending) {
            if (removed == index) {
                return -1;
            }
            if (removed < index) {
                shift++;
            }
        }
        return index - shift;
    }

    private int chooseNextIndex() {
        if (playlist.size() == 1) {
            return 0;
        }
        if (!shuffleEnabled) {
            return currentIndex < 0 ? 0 : (currentIndex + 1) % playlist.size();
        }
        if (shuffleBag.isEmpty()) {
            refillShuffleBag();
        }
        while (!shuffleBag.isEmpty()) {
            int next = shuffleBag.remove(0);
            if (next != currentIndex || playlist.size() == 1) {
                return next;
            }
        }
        refillShuffleBag();
        if (!shuffleBag.isEmpty() && shuffleBag.get(0) == currentIndex && shuffleBag.size() > 1) {
            Collections.swap(shuffleBag, 0, 1);
        }
        return shuffleBag.isEmpty() ? random.nextInt(playlist.size()) : shuffleBag.remove(0);
    }

    private void refillShuffleBag() {
        shuffleBag.clear();
        for (int i = 0; i < playlist.size(); i++) {
            shuffleBag.add(i);
        }
        Collections.shuffle(shuffleBag, random);
        if (shuffleBag.size() > 1 && shuffleBag.get(0) == currentIndex) {
            Collections.swap(shuffleBag, 0, 1);
        }
    }

    private boolean requestAudioFocus() {
        if (audioManager == null) {
            return true;
        }
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(this, mainHandler)
                .build();
        return audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void abandonAudioFocus() {
        if (audioManager == null || focusRequest == null) {
            return;
        }
        audioManager.abandonAudioFocusRequest(focusRequest);
        focusRequest = null;
    }

    private void publishState() {
        updateMediaSessionState();
        updateNotification();
        Intent state = new Intent(ACTION_STATE_CHANGED);
        state.setPackage(getPackageName());
        state.putExtra(EXTRA_IS_PLAYING, playbackRequested);
        state.putExtra(EXTRA_TRACK_NAME, currentTrackName);
        state.putExtra(EXTRA_TRACK_ARTIST, currentTrackArtist);
        state.putExtra(EXTRA_TRACK_ALBUM, currentTrackAlbum);
        state.putExtra(EXTRA_TRACK_URI, currentTrackUri);
        state.putExtra(EXTRA_MESSAGE, message);
        state.putExtra(EXTRA_PLAYLIST_COUNT, playlist.size());
        state.putExtra(EXTRA_POSITION_MS, player == null ? 0L : player.getCurrentPositionMs());
        state.putExtra(EXTRA_DURATION_MS, player == null ? 0L : player.getDurationMs());
        NormalizingAudioPlayer.CacheStats stats = NormalizingAudioPlayer.profileCacheStats(this);
        NormalizingAudioPlayer.CacheStats visualStats = NormalizingAudioPlayer.visualCacheStats(this);
        state.putExtra(EXTRA_CACHE_COUNT, stats.count);
        state.putExtra(EXTRA_CACHE_PRUNE_ABOVE, stats.pruneAbove);
        state.putExtra(EXTRA_CACHE_KEEP, stats.keep);
        state.putExtra(EXTRA_CACHE_BYTES, stats.approximateBytes);
        state.putExtra(EXTRA_VISUAL_CACHE_COUNT, visualStats.count);
        state.putExtra(EXTRA_VISUAL_CACHE_PRUNE_ABOVE, visualStats.pruneAbove);
        state.putExtra(EXTRA_VISUAL_CACHE_KEEP, visualStats.keep);
        state.putExtra(EXTRA_VISUAL_CACHE_BYTES, visualStats.approximateBytes);
        state.putExtra(EXTRA_CACHE_PROGRESS_DONE, cacheProgressDone);
        state.putExtra(EXTRA_CACHE_PROGRESS_TOTAL, cacheProgressTotal);
        state.putExtra(EXTRA_SHUFFLE_ENABLED, shuffleEnabled);
        state.putExtra(EXTRA_REPEAT_MODE, repeatMode);
        state.putExtra(EXTRA_CURRENT_INDEX, currentIndex);
        state.putExtra(EXTRA_SHUFFLE_BAG, toIntArray(shuffleBag));
        state.putExtra(EXTRA_PLAY_HISTORY, toIntArray(playHistory));
        state.putExtra(EXTRA_HISTORY_INDEX, historyIndex);
        putOutputRouteState(state);
        sendBroadcast(state);
    }

    private static int[] toIntArray(ArrayList<Integer> values) {
        int[] result = new int[values.size()];
        for (int i = 0; i < values.size(); i++) {
            result[i] = values.get(i);
        }
        return result;
    }

    private void publishProgress() {
        updateMediaSessionState();
        Intent state = new Intent(ACTION_STATE_CHANGED);
        state.setPackage(getPackageName());
        state.putExtra(EXTRA_IS_PLAYING, playbackRequested);
        state.putExtra(EXTRA_TRACK_NAME, currentTrackName);
        state.putExtra(EXTRA_TRACK_ARTIST, currentTrackArtist);
        state.putExtra(EXTRA_TRACK_ALBUM, currentTrackAlbum);
        state.putExtra(EXTRA_TRACK_URI, currentTrackUri);
        state.putExtra(EXTRA_MESSAGE, message);
        state.putExtra(EXTRA_PLAYLIST_COUNT, playlist.size());
        state.putExtra(EXTRA_POSITION_MS, player == null ? 0L : player.getCurrentPositionMs());
        state.putExtra(EXTRA_DURATION_MS, player == null ? 0L : player.getDurationMs());
        state.putExtra(EXTRA_SHUFFLE_ENABLED, shuffleEnabled);
        state.putExtra(EXTRA_REPEAT_MODE, repeatMode);
        putOutputRouteState(state);
        sendBroadcast(state);
    }

    private void putOutputRouteState(Intent state) {
        NormalizingAudioPlayer.OutputRouteInfo route = player == null
                ? NormalizingAudioPlayer.OutputRouteInfo.none()
                : player.getOutputRouteInfo();
        boolean useCalibrated = route.key.isEmpty() && !lastCalibratedRouteKey.isEmpty();
        state.putExtra(EXTRA_OUTPUT_ROUTE_KEY, useCalibrated ? lastCalibratedRouteKey : route.key);
        state.putExtra(EXTRA_OUTPUT_ROUTE_NAME, useCalibrated ? lastCalibratedRouteName : route.label);
        state.putExtra(EXTRA_OUTPUT_ROUTE_BLUETOOTH, useCalibrated || route.bluetooth);
        state.putExtra(EXTRA_OUTPUT_VISUAL_DELAY_MS,
                useCalibrated ? lastCalibratedDelayMs : route.visualDelayMs);
        state.putExtra(EXTRA_OUTPUT_DELAY_CALIBRATING, outputDelayCalibrating);
    }

    private void publishVisualization(byte[] waveform, byte[] spectrum) {
        Intent intent = new Intent(ACTION_VISUALIZATION_CHANGED);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_WAVEFORM, waveform);
        intent.putExtra(EXTRA_SPECTRUM, spectrum);
        sendBroadcast(intent);
    }

    private void createMediaSession() {
        mediaSession = new MediaSessionCompat(this, "FredPlayer");
        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setSessionActivity(openMainActivityPendingIntent());
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                mainHandler.post(() -> {
                    if (!playbackRequested) {
                        startOrResume();
                    }
                });
            }

            @Override
            public void onPause() {
                mainHandler.post(() -> {
                    if (playbackRequested) {
                        pausePlayback();
                    }
                });
            }

            @Override
            public void onStop() {
                mainHandler.post(() -> stopPlayback());
            }

            @Override
            public void onSkipToNext() {
                mainHandler.post(() -> {
                    if (!playlist.isEmpty()) {
                        if (!requestAudioFocus()) {
                            playbackRequested = false;
                            message = "Audio focus unavailable";
                            publishState();
                            return;
                        }
                        playbackRequested = true;
                        playRandomTrack();
                    }
                });
            }

            @Override
            public void onSkipToPrevious() {
                mainHandler.post(() -> playPreviousTrack());
            }

            @Override
            public void onSeekTo(long pos) {
                mainHandler.post(() -> seekTo(pos));
            }

            @Override
            public void onPlayFromMediaId(String mediaId, Bundle extras) {
                mainHandler.post(() -> {
                    String directPlaylistName = parsePlaylistName(mediaId);
                    if (directPlaylistName != null) {
                        switchActivePlaylist(directPlaylistName);
                        if (playlist.isEmpty()) {
                            playbackRequested = false;
                            message = "Playlist is empty";
                            publishState();
                            return;
                        }
                        if (!requestAudioFocus()) {
                            playbackRequested = false;
                            message = "Audio focus unavailable";
                            publishState();
                            return;
                        }
                        currentIndex = -1;
                        historyIndex = -1;
                        playHistory.clear();
                        shuffleBag.clear();
                        playbackRequested = true;
                        audioActuallyPlaying = false;
                        playRandomTrack();
                        return;
                    }
                    String playlistName = parseTrackPlaylistName(mediaId);
                    int index = parseTrackIndex(mediaId);
                    if (playlistName == null || index < 0) {
                        return;
                    }
                    switchActivePlaylist(playlistName);
                    if (index >= playlist.size()) {
                        return;
                    }
                    if (!requestAudioFocus()) {
                        playbackRequested = false;
                        message = "Audio focus unavailable";
                        publishState();
                        return;
                    }
                    playbackRequested = true;
                    shuffleBag.remove(Integer.valueOf(index));
                    recordHistory(index);
                    playTrackAt(index);
                });
            }

            @Override
            public void onPlayFromSearch(String query, Bundle extras) {
                String wanted = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
                if (wanted.isEmpty()) {
                    mainHandler.post(SleepMusicService.this::startOrResume);
                    return;
                }
                ArrayList<String> snapshot = new ArrayList<>(playlist);
                browseExecutor.execute(() -> {
                    int match = -1;
                    for (int index = 0; index < snapshot.size(); index++) {
                        TrackMetadata metadata = resolveMetadata(snapshot.get(index));
                        String searchable = (metadata.title + " " + metadata.artist + " "
                                + metadata.album).toLowerCase(Locale.ROOT);
                        if (searchable.contains(wanted)) {
                            match = index;
                            break;
                        }
                    }
                    int matchedIndex = match;
                    mainHandler.post(() -> {
                        if (matchedIndex < 0 || matchedIndex >= playlist.size()) {
                            message = "No matching song found";
                            publishState();
                            return;
                        }
                        if (!requestAudioFocus()) {
                            playbackRequested = false;
                            message = "Audio focus unavailable";
                            publishState();
                            return;
                        }
                        playbackRequested = true;
                        shuffleBag.remove(Integer.valueOf(matchedIndex));
                        recordHistory(matchedIndex);
                        playTrackAt(matchedIndex);
                    });
                });
            }

            @Override
            public void onSetShuffleMode(int shuffleMode) {
                mainHandler.post(() -> {
                    shuffleEnabled = shuffleMode != PlaybackStateCompat.SHUFFLE_MODE_NONE;
                    if (shuffleEnabled) {
                        refillShuffleBag();
                    } else {
                        shuffleBag.clear();
                    }
                    PlaylistStore.saveShuffleEnabled(SleepMusicService.this, shuffleEnabled);
                    mediaSession.setShuffleMode(shuffleEnabled
                            ? PlaybackStateCompat.SHUFFLE_MODE_ALL
                            : PlaybackStateCompat.SHUFFLE_MODE_NONE);
                    publishState();
                });
            }

            @Override
            public void onSetRepeatMode(int newRepeatMode) {
                mainHandler.post(() -> {
                    repeatMode = fromPlaybackRepeatMode(newRepeatMode);
                    PlaylistStore.saveRepeatMode(SleepMusicService.this, repeatMode);
                    mediaSession.setRepeatMode(toPlaybackRepeatMode(repeatMode));
                    publishState();
                });
            }
        }, mainHandler);
        setSessionToken(mediaSession.getSessionToken());
        mediaSession.setShuffleMode(shuffleEnabled
                ? PlaybackStateCompat.SHUFFLE_MODE_ALL
                : PlaybackStateCompat.SHUFFLE_MODE_NONE);
        mediaSession.setRepeatMode(toPlaybackRepeatMode(repeatMode));
        mediaSession.setActive(true);
    }

    private static int toPlaybackRepeatMode(int mode) {
        if (mode == REPEAT_ONE) return PlaybackStateCompat.REPEAT_MODE_ONE;
        if (mode == REPEAT_OFF) return PlaybackStateCompat.REPEAT_MODE_NONE;
        return PlaybackStateCompat.REPEAT_MODE_ALL;
    }

    private static int fromPlaybackRepeatMode(int mode) {
        if (mode == PlaybackStateCompat.REPEAT_MODE_ONE) return REPEAT_ONE;
        if (mode == PlaybackStateCompat.REPEAT_MODE_NONE) return REPEAT_OFF;
        return REPEAT_ALL;
    }

    private Bitmap placeholderArtwork() {
        if (placeholderArtworkBitmap == null) {
            placeholderArtworkBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.no_album_art);
        }
        return placeholderArtworkBitmap;
    }

    private void updateMediaSessionState() {
        if (mediaSession == null) {
            return;
        }

        long actions = PlaybackStateCompat.ACTION_PLAY
                | PlaybackStateCompat.ACTION_PAUSE
                | PlaybackStateCompat.ACTION_PLAY_PAUSE
                | PlaybackStateCompat.ACTION_STOP
                | PlaybackStateCompat.ACTION_SEEK_TO
                | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                | PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
                | PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH
                | PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE
                | PlaybackStateCompat.ACTION_SET_REPEAT_MODE;
        int state;
        if (playbackRequested && audioActuallyPlaying) {
            state = PlaybackStateCompat.STATE_PLAYING;
        } else if (playbackRequested) {
            state = PlaybackStateCompat.STATE_BUFFERING;
        } else {
            state = PlaybackStateCompat.STATE_PAUSED;
        }

        mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(
                        state,
                        player == null ? PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN : player.getCurrentPositionMs(),
                        playbackRequested ? 1.0f : 0.0f)
                .build());

        String mediaId = TRACK_ID_PREFIX + activePlaylistName + "#" + currentIndex;
        String title = currentTrackName == null || currentTrackName.isEmpty() ? "FredPlayer" : currentTrackName;
        String artist = currentTrackArtist == null ? "" : currentTrackArtist;
        String album = currentTrackAlbum == null ? "" : currentTrackAlbum;
        long duration = player == null ? 0L : Math.max(0L, player.getDurationMs());
        // Read straight from the lock screen / notification / Android
        // Auto's now-playing template — none of those need any
        // FredPlayer-specific code, they all already render whatever
        // bitmap sits in this metadata key. Falls back to the branded
        // placeholder so those surfaces never show a blank art slot.
        Bitmap art = currentArtworkBitmap != null ? currentArtworkBitmap : placeholderArtwork();
        String metadataKey = mediaId + '\u0000' + currentTrackUri + '\u0000' + title + '\u0000'
                + artist + '\u0000' + album + '\u0000' + duration;

        // Playback position is refreshed every 500 ms, but Android Auto
        // treats setMetadata() as a full now-playing refresh. Republishing
        // the identical bitmap on every tick makes full-screen artwork
        // visibly blink, so only send metadata when it actually changes.
        if (!metadataKey.equals(lastPublishedMetadataKey) || art != lastPublishedArtworkBitmap) {
            MediaMetadataCompat.Builder metadata = new MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, mediaId)
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title);
            if (!artist.isEmpty()) {
                metadata.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist);
            }
            if (!album.isEmpty()) {
                metadata.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album);
            }
            if (duration > 0L) {
                metadata.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration);
            }
            if (art != null) {
                metadata.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art);
            }
            mediaSession.setMetadata(metadata.build());
            lastPublishedMetadataKey = metadataKey;
            lastPublishedArtworkBitmap = art;
        }
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "FredPlayer playback",
                NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null);
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private void startForegroundCompat() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
            startForeground(NOTIFICATION_ID, notification, type);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    private Notification buildNotification() {
        PendingIntent openPendingIntent = openMainActivityPendingIntent();

        PendingIntent toggleIntent = servicePendingIntent(ACTION_TOGGLE_PLAY, 2);
        PendingIntent skipIntent = servicePendingIntent(ACTION_SKIP, 3);
        PendingIntent previousIntent = servicePendingIntent(ACTION_PREVIOUS, 4);
        PendingIntent stopIntent = servicePendingIntent(ACTION_STOP, 5);

        String title = currentTrackName == null || currentTrackName.isEmpty() ? "FredPlayer" : currentTrackName;
        String detail = trackDetailLine();
        String status = message == null || message.isEmpty() ? (audioActuallyPlaying ? "Playing" : "Paused") : message;
        String text = detail.isEmpty() ? status : detail;

        Notification.MediaStyle style = new Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2);
        if (mediaSession != null) {
            style.setMediaSession((MediaSession.Token) mediaSession.getSessionToken().getToken());
        }

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_music)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openPendingIntent)
                .setOngoing(playbackRequested)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .addAction(android.R.drawable.ic_media_previous, "Previous", previousIntent)
                .addAction(playbackRequested ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        playbackRequested ? "Pause" : "Play",
                        toggleIntent)
                .addAction(android.R.drawable.ic_media_next, "Skip", skipIntent)
                .addAction(R.drawable.ic_stop, "Stop", stopIntent)
                .setSubText(status)
                .setStyle(style);
        return builder.build();
    }

    private String trackDetailLine() {
        if (currentTrackArtist != null && !currentTrackArtist.isEmpty()
                && currentTrackAlbum != null && !currentTrackAlbum.isEmpty()) {
            return currentTrackArtist + " - " + currentTrackAlbum;
        }
        if (currentTrackArtist != null && !currentTrackArtist.isEmpty()) {
            return currentTrackArtist;
        }
        return currentTrackAlbum == null ? "" : currentTrackAlbum;
    }

    private PendingIntent openMainActivityPendingIntent() {
        Intent openIntent = new Intent(this, MainActivity.class);
        return PendingIntent.getActivity(
                this,
                1,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent servicePendingIntent(String action, int requestCode) {
        Intent intent = new Intent(this, SleepMusicService.class);
        intent.setAction(action);
        intent.putExtra(EXTRA_CONTROL_TOKEN, PlaylistStore.loadControlToken(this));
        return PendingIntent.getService(
                this,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
