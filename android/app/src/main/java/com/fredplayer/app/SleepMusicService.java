package com.fredplayer.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
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

import androidx.media.MediaBrowserServiceCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SleepMusicService extends MediaBrowserServiceCompat implements AudioManager.OnAudioFocusChangeListener {
    private static final String BROWSE_ROOT_ID = "root";
    private static final String PLAYLIST_ID_PREFIX = "playlist:";
    private static final String TRACK_ID_PREFIX = "track:";
    public static final String ACTION_SET_PLAYLIST = "com.fredplayer.app.SET_PLAYLIST";
    public static final String ACTION_TOGGLE_PLAY = "com.fredplayer.app.TOGGLE_PLAY";
    public static final String ACTION_SKIP = "com.fredplayer.app.SKIP";
    public static final String ACTION_PREVIOUS = "com.fredplayer.app.PREVIOUS";
    public static final String ACTION_STOP = "com.fredplayer.app.STOP";
    public static final String ACTION_CLEAR = "com.fredplayer.app.CLEAR";
    public static final String ACTION_SEEK = "com.fredplayer.app.SEEK";
    public static final String ACTION_SET_OUTPUT_LEVEL = "com.fredplayer.app.SET_OUTPUT_LEVEL";
    public static final String ACTION_SET_LEVELING_STRENGTH = "com.fredplayer.app.SET_LEVELING_STRENGTH";
    public static final String ACTION_SET_LEVELING_SETTINGS = "com.fredplayer.app.SET_LEVELING_SETTINGS";
    public static final String ACTION_SET_VISUALIZATION_SETTINGS = "com.fredplayer.app.SET_VISUALIZATION_SETTINGS";
    public static final String ACTION_REQUEST_STATE = "com.fredplayer.app.REQUEST_STATE";
    public static final String ACTION_STATE_CHANGED = "com.fredplayer.app.STATE_CHANGED";
    public static final String ACTION_VISUALIZATION_CHANGED = "com.fredplayer.app.VISUALIZATION_CHANGED";

    public static final String EXTRA_PLAYLIST = "playlist";
    public static final String EXTRA_START_PLAYING = "start_playing";
    public static final String EXTRA_IS_PLAYING = "is_playing";
    public static final String EXTRA_TRACK_NAME = "track_name";
    public static final String EXTRA_TRACK_ARTIST = "track_artist";
    public static final String EXTRA_TRACK_ALBUM = "track_album";
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

    private static final String CHANNEL_ID = "fred_player_playback";
    private static final int NOTIFICATION_ID = 41;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final ArrayList<String> playlist = new ArrayList<>();
    private final ArrayList<Integer> shuffleBag = new ArrayList<>();

    private NormalizingAudioPlayer player;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private MediaSessionCompat mediaSession;
    private ExecutorService browseExecutor;
    private String activePlaylistName = PlaylistStore.DEFAULT_PLAYLIST_NAME;
    private boolean shuffleEnabled = true;
    private int currentIndex = -1;
    private int previousIndex = -1;
    private boolean playbackRequested;
    private boolean audioActuallyPlaying;
    private String currentTrackName = "";
    private String currentTrackArtist = "";
    private String currentTrackAlbum = "";
    private String message = "Paused";
    private int cacheProgressDone;
    private int cacheProgressTotal;
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
        LinkedHashMap<String, ArrayList<String>> playlists = PlaylistStore.loadPlaylists(this);
        activePlaylistName = PlaylistStore.loadActivePlaylistName(this, playlists);
        ArrayList<String> activeTracks = playlists.get(activePlaylistName);
        if (activeTracks != null) {
            playlist.addAll(activeTracks);
        }
        shuffleEnabled = PlaylistStore.loadShuffleEnabled(this);
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
                        playRandomTrack();
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
            player.warmLoudnessCache(playlist);
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
            playlist.clear();
            playlist.addAll(incoming);
            currentIndex = currentItem == null ? -1 : playlist.indexOf(currentItem);
            refillShuffleBag();
            player.warmLoudnessCache(playlist);

            if (playlist.isEmpty()) {
                playbackRequested = false;
                audioActuallyPlaying = false;
                currentIndex = -1;
                previousIndex = -1;
                currentTrackName = "";
                currentTrackArtist = "";
                currentTrackAlbum = "";
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
                player.warmLoudnessCache(playlist);
            }
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
                items.add(new MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE));
            }
            result.sendResult(items);
            return;
        }

        if (!parentId.startsWith(PLAYLIST_ID_PREFIX)) {
            result.sendResult(null);
            return;
        }
        String playlistName = parentId.substring(PLAYLIST_ID_PREFIX.length());
        ArrayList<String> tracks = PlaylistStore.loadPlaylists(this).get(playlistName);
        if (tracks == null) {
            result.sendResult(null);
            return;
        }
        ArrayList<String> snapshot = new ArrayList<>(tracks);
        result.detach();
        browseExecutor.execute(() -> {
            ArrayList<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
            for (int i = 0; i < snapshot.size(); i++) {
                TrackMetadata metadata = TrackMetadata.from(this, snapshot.get(i));
                MediaDescriptionCompat description = new MediaDescriptionCompat.Builder()
                        .setMediaId(TRACK_ID_PREFIX + playlistName + "#" + i)
                        .setTitle(metadata.title.isEmpty() ? "FredPlayer" : metadata.title)
                        .setSubtitle(metadata.detailLine())
                        .build();
                items.add(new MediaBrowserCompat.MediaItem(description, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE));
            }
            result.sendResult(items);
        });
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
        currentIndex = -1;
        previousIndex = -1;
        currentTrackName = "";
        currentTrackArtist = "";
        currentTrackAlbum = "";
        PlaylistStore.savePlaylist(this, playlist);
        if (player != null) {
            player.stop();
        }
        abandonAudioFocus();
        message = "No songs";
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
        previousIndex = -1;
        shuffleBag.clear();
        player.warmLoudnessCache(playlist);
    }

    private void playRandomTrack() {
        if (playlist.isEmpty() || player == null) {
            playbackRequested = false;
            message = "No songs";
            publishState();
            return;
        }
        playTrackAt(chooseNextIndex(), true);
    }

    private void playPreviousTrack() {
        if (playlist.isEmpty() || player == null) {
            playbackRequested = false;
            message = "No songs";
            publishState();
            return;
        }
        playbackRequested = true;
        if (!requestAudioFocus()) {
            playbackRequested = false;
            message = "Audio focus unavailable";
            publishState();
            return;
        }
        int target;
        if (!shuffleEnabled) {
            target = currentIndex < 0 ? 0 : (currentIndex - 1 + playlist.size()) % playlist.size();
        } else {
            target = previousIndex >= 0 && previousIndex < playlist.size()
                    ? previousIndex
                    : Math.max(0, currentIndex);
        }
        previousIndex = -1;
        playTrackAt(target, false);
    }

    private void playTrackAt(int nextIndex, boolean rememberPrevious) {
        if (nextIndex < 0 || nextIndex >= playlist.size() || player == null) {
            playbackRequested = false;
            message = "No songs";
            publishState();
            return;
        }
        if (rememberPrevious && currentIndex >= 0 && currentIndex < playlist.size() && currentIndex != nextIndex) {
            previousIndex = currentIndex;
        }
        currentIndex = nextIndex;
        String item = playlist.get(nextIndex);
        TrackMetadata metadata = TrackMetadata.from(this, item);
        currentTrackName = metadata.title;
        currentTrackArtist = metadata.artist;
        currentTrackAlbum = metadata.album;
        audioActuallyPlaying = false;
        message = "Leveling";
        publishState();
        player.play(Uri.parse(item));
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
        sendBroadcast(state);
    }

    private void publishProgress() {
        updateMediaSessionState();
        Intent state = new Intent(ACTION_STATE_CHANGED);
        state.setPackage(getPackageName());
        state.putExtra(EXTRA_IS_PLAYING, playbackRequested);
        state.putExtra(EXTRA_TRACK_NAME, currentTrackName);
        state.putExtra(EXTRA_TRACK_ARTIST, currentTrackArtist);
        state.putExtra(EXTRA_TRACK_ALBUM, currentTrackAlbum);
        state.putExtra(EXTRA_MESSAGE, message);
        state.putExtra(EXTRA_PLAYLIST_COUNT, playlist.size());
        state.putExtra(EXTRA_POSITION_MS, player == null ? 0L : player.getCurrentPositionMs());
        state.putExtra(EXTRA_DURATION_MS, player == null ? 0L : player.getDurationMs());
        sendBroadcast(state);
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
                    playTrackAt(index, true);
                });
            }

            @Override
            public void onSetShuffleMode(int shuffleMode) {
                mainHandler.post(() -> {
                    shuffleEnabled = shuffleMode != PlaybackStateCompat.SHUFFLE_MODE_NONE;
                    PlaylistStore.saveShuffleEnabled(SleepMusicService.this, shuffleEnabled);
                    mediaSession.setShuffleMode(shuffleEnabled
                            ? PlaybackStateCompat.SHUFFLE_MODE_ALL
                            : PlaybackStateCompat.SHUFFLE_MODE_NONE);
                    publishState();
                });
            }
        }, mainHandler);
        setSessionToken(mediaSession.getSessionToken());
        mediaSession.setShuffleMode(shuffleEnabled
                ? PlaybackStateCompat.SHUFFLE_MODE_ALL
                : PlaybackStateCompat.SHUFFLE_MODE_NONE);
        mediaSession.setActive(true);
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
                | PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE;
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

        MediaMetadataCompat.Builder metadata = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, TRACK_ID_PREFIX + activePlaylistName + "#" + currentIndex)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE,
                        currentTrackName == null || currentTrackName.isEmpty() ? "FredPlayer" : currentTrackName);
        if (currentTrackArtist != null && !currentTrackArtist.isEmpty()) {
            metadata.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentTrackArtist);
        }
        if (currentTrackAlbum != null && !currentTrackAlbum.isEmpty()) {
            metadata.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, currentTrackAlbum);
        }
        if (player != null && player.getDurationMs() > 0L) {
            metadata.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, player.getDurationMs());
        }
        mediaSession.setMetadata(metadata.build());
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
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
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
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
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
        return PendingIntent.getService(
                this,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
