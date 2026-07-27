package com.fredplayer.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

final class NormalizingAudioPlayer {
    interface Callback {
        void onTrackStarted();

        void onTrackFinished();

        void onError(String error);

        void onVisualization(byte[] waveform, byte[] spectrum);

        void onCacheProgress(int done, int total);
    }

    private static final long DEQUEUE_TIMEOUT_US = 10_000;
    private static final long ONE_SECOND_US = 1_000_000L;
    private static final String PROFILE_CACHE_PREFS = "fred_player_profile_cache";
    private static final int PROFILE_CACHE_PRUNE_ABOVE = 5000;
    private static final int PROFILE_CACHE_KEEP = 4000;
    private static final String VISUAL_CACHE_DIR = "visualization_cache";
    private static final int VISUAL_CACHE_MAGIC = 0x46565A32;
    private static final int VISUAL_CACHE_VERSION = 2;
    private static final int VISUAL_CACHE_PRUNE_ABOVE = 5000;
    private static final int VISUAL_CACHE_KEEP = 4500;
    private static final int VISUAL_WAVEFORM_POINTS = 96;
    private static final int VISUAL_HEADER_FRAME_COUNT_OFFSET = 20;
    private static final String REMOTE_STREAM_SEGMENT = "/stream/";
    private static final int REMOTE_CONNECT_TIMEOUT_MS = 4000;
    private static final int REMOTE_READ_TIMEOUT_MS = 6000;

    private final Context context;
    private final Callback callback;
    private final Object pauseLock = new Object();

    private volatile PlaybackRun activeRun;
    private volatile PlaybackRun cacheRun;
    private volatile Thread worker;
    private volatile Thread cacheWorker;
    private volatile AudioTrack activeTrack;
    private volatile boolean paused;
    private volatile boolean released;
    private volatile long currentBasePositionMs;
    private volatile long currentDurationMs;
    private volatile int currentSampleRate;
    private volatile float outputLevel = 0.55f;
    private volatile float levelingStrength = 0.9f;
    private volatile LevelingSettings levelingSettings = LevelingSettings.defaults();
    private volatile VisualizationSettings visualizationSettings = VisualizationSettings.defaults();

    NormalizingAudioPlayer(Context context, Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
    }

    void setOutputLevel(float outputLevel) {
        this.outputLevel = clamp(outputLevel, 0.1f, 1.0f);
    }

    void setLevelingStrength(float levelingStrength) {
        this.levelingStrength = clamp(levelingStrength, 0.0f, 1.0f);
    }

    void setLevelingSettings(LevelingSettings levelingSettings) {
        if (levelingSettings != null) {
            this.levelingSettings = levelingSettings;
        }
    }

    void setVisualizationSettings(VisualizationSettings visualizationSettings) {
        if (visualizationSettings != null) {
            this.visualizationSettings = visualizationSettings;
        }
    }

    boolean isPaused() {
        Thread thread = worker;
        return paused && thread != null && thread.isAlive();
    }

    void play(Uri uri) {
        play(uri, 0L, false);
    }

    void play(Uri uri, long positionMs, boolean startPaused) {
        stop();
        if (released) {
            return;
        }
        paused = false;
        currentBasePositionMs = Math.max(0L, positionMs);
        currentDurationMs = 0L;
        currentSampleRate = 0;
        PlaybackRun run = new PlaybackRun(currentBasePositionMs, startPaused);
        activeRun = run;
        Thread thread = new Thread(() -> runPlayback(uri, run), "FredPlayerAudio");
        worker = thread;
        thread.start();
    }

    long getCurrentPositionMs() {
        AudioTrack track = activeTrack;
        int sampleRate = currentSampleRate;
        long position = currentBasePositionMs;
        if (track != null && sampleRate > 0) {
            try {
                long framesPlayed = track.getPlaybackHeadPosition() & 0xFFFFFFFFL;
                position += framesPlayed * 1000L / sampleRate;
            } catch (IllegalStateException ignored) {
            }
        }
        long duration = currentDurationMs;
        return duration > 0L ? Math.min(position, duration) : position;
    }

    long getDurationMs() {
        return currentDurationMs;
    }

    void warmLoudnessCache(List<String> uriStrings) {
        if (uriStrings == null || uriStrings.isEmpty() || released) {
            callback.onCacheProgress(0, 0);
            return;
        }

        PlaybackRun previousRun = cacheRun;
        if (previousRun != null) {
            previousRun.stopRequested.set(true);
        }

        ArrayList<String> tracks = new ArrayList<>(uriStrings);
        PlaybackRun run = new PlaybackRun();
        cacheRun = run;
        Thread thread = new Thread(() -> runCacheWarmup(tracks, run), "FredPlayerCacheWarmup");
        thread.setPriority(Thread.MIN_PRIORITY);
        cacheWorker = thread;
        thread.start();
    }

    void pause() {
        paused = true;
        AudioTrack track = activeTrack;
        if (track != null) {
            try {
                track.pause();
            } catch (IllegalStateException ignored) {
            }
        }
    }

    void resume() {
        paused = false;
        synchronized (pauseLock) {
            pauseLock.notifyAll();
        }
        AudioTrack track = activeTrack;
        if (track != null) {
            try {
                track.play();
            } catch (IllegalStateException ignored) {
            }
        }
    }

    void stop() {
        PlaybackRun run = activeRun;
        if (run != null) {
            run.stopRequested.set(true);
        }
        paused = false;
        synchronized (pauseLock) {
            pauseLock.notifyAll();
        }
        AudioTrack track = activeTrack;
        if (track != null) {
            try {
                track.pause();
                track.flush();
            } catch (IllegalStateException ignored) {
            }
        }

        Thread thread = worker;
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(900);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        currentBasePositionMs = 0L;
        currentDurationMs = 0L;
        currentSampleRate = 0;
    }

    void release() {
        released = true;
        PlaybackRun run = cacheRun;
        if (run != null) {
            run.stopRequested.set(true);
        }
        stop();
    }

    private void runPlayback(Uri uri, PlaybackRun run) {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        AudioTrack audioTrack = null;
        boolean completed = false;
        boolean notifyFinished = false;
        try {
            TrackProfile trackProfile = profileForTrack(uri, run);
            if (run.stopRequested.get()) {
                return;
            }

            configureExtractor(extractor, uri);
            int trackIndex = selectAudioTrack(extractor);
            if (trackIndex < 0) {
                throw new IOException("No audio track");
            }

            MediaFormat inputFormat = extractor.getTrackFormat(trackIndex);
            String mime = inputFormat.getString(MediaFormat.KEY_MIME);
            if (mime == null) {
                throw new IOException("Unknown audio format");
            }
            if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                currentDurationMs = Math.max(0L, inputFormat.getLong(MediaFormat.KEY_DURATION) / 1000L);
            }

            extractor.selectTrack(trackIndex);
            if (run.startPositionMs > 0L) {
                extractor.seekTo(run.startPositionMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            }
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(inputFormat, null, null, 0);
            codec.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            StreamFormat streamFormat = null;
            VolumeNormalizer normalizer = null;
            VisualizationCollector visualizationCollector = null;
            VisualCachePlayback visualCachePlayback = null;
            long framesWritten = 0;
            int frameSizeBytes = 0;

            while (!outputDone && !run.stopRequested.get()) {
                waitIfPaused(run);

                if (!inputDone) {
                    int inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
                    if (inputIndex >= 0) {
                        ByteBuffer inputBuffer = codec.getInputBuffer(inputIndex);
                        if (inputBuffer == null) {
                            continue;
                        }
                        inputBuffer.clear();
                        int sampleSize = extractor.readSampleData(inputBuffer, 0);
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            long presentationTimeUs = extractor.getSampleTime();
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, presentationTimeUs, 0);
                            extractor.advance();
                        }
                    }
                }

                int outputIndex = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US);
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    streamFormat = StreamFormat.from(codec.getOutputFormat());
                    audioTrack = createAudioTrack(streamFormat);
                    activeTrack = audioTrack;
                    currentSampleRate = streamFormat.sampleRate;
                    currentBasePositionMs = run.startPositionMs;
                    frameSizeBytes = streamFormat.outputChannels * 2;
                    normalizer = new VolumeNormalizer(streamFormat.sampleRate, trackProfile, levelingSettings);
                    VisualCache visualCache = readVisualCache(uri, visualizationSettings);
                    if (visualCache == null) {
                        visualizationCollector = new VisualizationCollector(streamFormat.sampleRate, visualizationSettings);
                    } else {
                        visualCachePlayback = new VisualCachePlayback(
                                visualCache,
                                streamFormat.sampleRate,
                                run.startPositionMs);
                    }
                    if (run.startPaused) {
                        audioTrack.pause();
                        paused = true;
                    }
                    callback.onTrackStarted();
                } else if (outputIndex >= 0) {
                    if (streamFormat == null) {
                        streamFormat = StreamFormat.from(codec.getOutputFormat());
                        audioTrack = createAudioTrack(streamFormat);
                        activeTrack = audioTrack;
                        currentSampleRate = streamFormat.sampleRate;
                        currentBasePositionMs = run.startPositionMs;
                        frameSizeBytes = streamFormat.outputChannels * 2;
                        normalizer = new VolumeNormalizer(streamFormat.sampleRate, trackProfile, levelingSettings);
                        VisualCache visualCache = readVisualCache(uri, visualizationSettings);
                        if (visualCache == null) {
                            visualizationCollector = new VisualizationCollector(streamFormat.sampleRate, visualizationSettings);
                        } else {
                            visualCachePlayback = new VisualCachePlayback(
                                    visualCache,
                                    streamFormat.sampleRate,
                                    run.startPositionMs);
                        }
                        if (run.startPaused) {
                            audioTrack.pause();
                            paused = true;
                        }
                        callback.onTrackStarted();
                    }
                    ByteBuffer outputBuffer = codec.getOutputBuffer(outputIndex);
                    if (outputBuffer != null && info.size > 0 && normalizer != null) {
                        outputBuffer.position(info.offset);
                        outputBuffer.limit(info.offset + info.size);
                        ByteBuffer pcmSource = outputBuffer.slice();
                        int pcmSize = info.size;
                        long targetUs = run.startPositionMs * 1000L;
                        if (targetUs > 0L && info.presentationTimeUs < targetUs) {
                            int inputFrameSize = bytesPerSample(streamFormat.pcmEncoding)
                                    * streamFormat.inputChannels;
                            long framesToSkip = (targetUs - info.presentationTimeUs)
                                    * streamFormat.sampleRate / ONE_SECOND_US;
                            int skipBytes = (int) Math.min(
                                    pcmSize,
                                    framesToSkip * inputFrameSize);
                            skipBytes -= skipBytes % inputFrameSize;
                            pcmSource.position(skipBytes);
                            pcmSource = pcmSource.slice();
                            pcmSize -= skipBytes;
                        }
                        if (pcmSize > 0) {
                            byte[] pcm = processPcm(
                                    pcmSource,
                                    pcmSize,
                                    streamFormat,
                                    normalizer,
                                    visualizationCollector);
                            int written = writeFully(audioTrack, pcm, run);
                            framesWritten += written / frameSizeBytes;
                            if (visualCachePlayback != null) {
                                visualCachePlayback.emitUntil(framesWritten, callback);
                            }
                        }
                    }
                    outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    codec.releaseOutputBuffer(outputIndex, false);
                }
            }

            if (!run.stopRequested.get() && audioTrack != null && streamFormat != null) {
                waitForPlaybackTail(audioTrack, framesWritten, streamFormat.sampleRate, run);
                completed = !run.stopRequested.get();
                if (completed && currentDurationMs > 0L) {
                    currentBasePositionMs = currentDurationMs;
                }
            }
        } catch (Exception e) {
            if (!run.stopRequested.get() && !released && activeRun == run) {
                callback.onError(cleanError(e));
            }
        } finally {
            notifyFinished = completed && !released && activeRun == run;
            if (codec != null) {
                try {
                    codec.stop();
                } catch (RuntimeException ignored) {
                }
                try {
                    codec.release();
                } catch (RuntimeException ignored) {
                }
            }
            try {
                extractor.release();
            } catch (RuntimeException ignored) {
            }
            if (audioTrack != null) {
                try {
                    audioTrack.stop();
                } catch (IllegalStateException ignored) {
                }
                try {
                    audioTrack.release();
                } catch (RuntimeException ignored) {
                }
            }
            if (activeRun == run) {
                activeTrack = null;
                currentSampleRate = 0;
                worker = null;
                activeRun = null;
            }
        }

        if (notifyFinished) {
            callback.onTrackFinished();
        }
    }

    private void runCacheWarmup(ArrayList<String> tracks, PlaybackRun run) {
        int total = tracks.size();
        int done = 0;
        VisualizationSettings visualSettings = visualizationSettings;
        callback.onCacheProgress(done, total);
        try {
            for (String uriString : tracks) {
                if (run.stopRequested.get() || released) {
                    break;
                }
                try {
                    Uri uri = Uri.parse(uriString);
                    TrackProfile profile = null;
                    if (levelingSettings.analysisSeconds > 0f) {
                        profile = obtainProfile(uri, run, Math.round(levelingSettings.analysisSeconds * ONE_SECOND_US));
                    }
                    File visualFile = visualCacheFile(uri, visualSettings);
                    if (!run.stopRequested.get() && !visualFile.exists() && isRemote(uri)) {
                        fetchRemoteVisualQuietly(uri, visualFile);
                    }
                    if (!run.stopRequested.get() && !visualFile.exists()) {
                        writeVisualCache(uri, visualFile, visualSettings, run, profile);
                        if (!run.stopRequested.get() && visualFile.exists() && isRemote(uri)) {
                            uploadRemoteVisualQuietly(uri, visualFile);
                        }
                    }
                } catch (Exception ignored) {
                }
                done++;
                callback.onCacheProgress(done, total);
            }
        } finally {
            pruneProfileCache(context);
            pruneVisualCache(context);
            if (cacheRun == run) {
                cacheRun = null;
                cacheWorker = null;
            }
            callback.onCacheProgress(done, total);
        }
    }

    private TrackProfile profileForTrack(Uri uri, PlaybackRun run) throws IOException, InterruptedException {
        float analysisSeconds = levelingSettings.analysisSeconds;
        long analysisLimitUs = analysisSeconds > 0f ? Math.round(analysisSeconds * ONE_SECOND_US) : 0L;
        return obtainProfile(uri, run, analysisLimitUs);
    }

    private TrackProfile obtainProfile(Uri uri, PlaybackRun run, long analysisLimitUs) throws IOException, InterruptedException {
        String cacheKey = profileCacheKey(uri);
        TrackProfile cached = readCachedProfile(cacheKey);
        if (cached != null) {
            return cached;
        }

        if (isRemote(uri)) {
            TrackProfile remote = fetchRemoteProfile(uri);
            if (remote != null) {
                writeCachedProfile(cacheKey, remote);
                return remote;
            }
        }

        if (analysisLimitUs <= 0L) {
            return null;
        }

        TrackProfile profile = analyzeTrack(uri, run, analysisLimitUs);
        if (!run.stopRequested.get() && profile != null) {
            writeCachedProfile(cacheKey, profile);
            if (isRemote(uri)) {
                uploadRemoteProfile(uri, profile);
            }
        }
        return profile;
    }

    private TrackProfile analyzeTrack(Uri uri, PlaybackRun run, long analysisLimitUs) throws IOException, InterruptedException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        TrackProfile.Meter meter = new TrackProfile.Meter();
        try {
            configureExtractor(extractor, uri);
            int trackIndex = selectAudioTrack(extractor);
            if (trackIndex < 0) {
                throw new IOException("No audio track");
            }

            MediaFormat inputFormat = extractor.getTrackFormat(trackIndex);
            String mime = inputFormat.getString(MediaFormat.KEY_MIME);
            if (mime == null) {
                throw new IOException("Unknown audio format");
            }

            extractor.selectTrack(trackIndex);
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(inputFormat, null, null, 0);
            codec.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            StreamFormat streamFormat = null;

            while (!outputDone && !run.stopRequested.get()) {
                waitIfPaused(run);

                if (!inputDone) {
                    int inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
                    if (inputIndex >= 0) {
                        ByteBuffer inputBuffer = codec.getInputBuffer(inputIndex);
                        if (inputBuffer == null) {
                            continue;
                        }
                        inputBuffer.clear();
                        long sampleTimeUs = extractor.getSampleTime();
                        if (sampleTimeUs < 0 || (sampleTimeUs > analysisLimitUs && meter.hasSamples())) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            int sampleSize = extractor.readSampleData(inputBuffer, 0);
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone = true;
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, sampleSize, Math.max(0, sampleTimeUs), 0);
                                extractor.advance();
                            }
                        }
                    }
                }

                int outputIndex = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US);
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    streamFormat = StreamFormat.from(codec.getOutputFormat());
                } else if (outputIndex >= 0) {
                    if (streamFormat == null) {
                        streamFormat = StreamFormat.from(codec.getOutputFormat());
                    }
                    ByteBuffer outputBuffer = codec.getOutputBuffer(outputIndex);
                    if (outputBuffer != null && info.size > 0) {
                        outputBuffer.position(info.offset);
                        outputBuffer.limit(info.offset + info.size);
                        meter.accept(outputBuffer.slice(), info.size, streamFormat);
                    }
                    outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    codec.releaseOutputBuffer(outputIndex, false);
                }
            }
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (RuntimeException ignored) {
                }
                try {
                    codec.release();
                } catch (RuntimeException ignored) {
                }
            }
            try {
                extractor.release();
            } catch (RuntimeException ignored) {
            }
        }
        return meter.toProfile();
    }

    private TrackProfile readCachedProfile(String cacheKey) {
        SharedPreferences prefs = context.getSharedPreferences(PROFILE_CACHE_PREFS, Context.MODE_PRIVATE);
        String value = prefs.getString(cacheKey, null);
        if (value == null || value.isEmpty()) {
            return null;
        }
        String[] parts = value.split("\\|");
        if (parts.length < 2) {
            return null;
        }
        try {
            TrackProfile profile = new TrackProfile(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]));
            prefs.edit()
                    .putString(cacheKey, profile.rms + "|" + profile.peak + "|" + System.currentTimeMillis())
                    .apply();
            return profile;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void writeCachedProfile(String cacheKey, TrackProfile profile) {
        SharedPreferences prefs = context.getSharedPreferences(PROFILE_CACHE_PREFS, Context.MODE_PRIVATE);
        prefs.edit()
                .putString(cacheKey, profile.rms + "|" + profile.peak + "|" + System.currentTimeMillis())
                .apply();
        pruneProfileCache(context);
    }

    static CacheStats profileCacheStats(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PROFILE_CACHE_PREFS, Context.MODE_PRIVATE);
        Map<String, ?> entries = prefs.getAll();
        long approximateBytes = 0L;
        for (Map.Entry<String, ?> entry : entries.entrySet()) {
            approximateBytes += entry.getKey().length() * 2L;
            Object value = entry.getValue();
            if (value != null) {
                approximateBytes += value.toString().length() * 2L;
            }
        }
        return new CacheStats(entries.size(), PROFILE_CACHE_PRUNE_ABOVE, PROFILE_CACHE_KEEP, approximateBytes);
    }

    static void pruneProfileCache(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PROFILE_CACHE_PREFS, Context.MODE_PRIVATE);
        Map<String, ?> entries = prefs.getAll();
        if (entries.size() <= PROFILE_CACHE_PRUNE_ABOVE) {
            return;
        }

        ArrayList<CacheEntry> sorted = new ArrayList<>();
        for (Map.Entry<String, ?> entry : entries.entrySet()) {
            sorted.add(new CacheEntry(entry.getKey(), cacheTimestamp(entry.getValue())));
        }
        Collections.sort(sorted, Comparator.comparingLong(entry -> entry.timestamp));

        int removeCount = Math.max(0, sorted.size() - PROFILE_CACHE_KEEP);
        SharedPreferences.Editor editor = prefs.edit();
        for (int i = 0; i < removeCount; i++) {
            editor.remove(sorted.get(i).key);
        }
        editor.apply();
    }

    static CacheStats visualCacheStats(Context context) {
        File[] files = visualCacheDir(context).listFiles((dir, name) -> name.endsWith(".fvz"));
        int count = files == null ? 0 : files.length;
        long bytes = 0L;
        if (files != null) {
            for (File file : files) {
                bytes += file.length();
            }
        }
        return new CacheStats(count, VISUAL_CACHE_PRUNE_ABOVE, VISUAL_CACHE_KEEP, bytes);
    }

    static void pruneVisualCache(Context context) {
        File[] files = visualCacheDir(context).listFiles((dir, name) -> name.endsWith(".fvz"));
        if (files == null || files.length <= VISUAL_CACHE_PRUNE_ABOVE) {
            return;
        }

        ArrayList<CacheEntry> sorted = new ArrayList<>();
        for (File file : files) {
            sorted.add(new CacheEntry(file.getAbsolutePath(), file.lastModified()));
        }
        Collections.sort(sorted, Comparator.comparingLong(entry -> entry.timestamp));

        int removeCount = Math.max(0, sorted.size() - VISUAL_CACHE_KEEP);
        for (int i = 0; i < removeCount; i++) {
            new File(sorted.get(i).key).delete();
        }
    }

    private VisualCache readVisualCache(Uri uri, VisualizationSettings settings) {
        File file = visualCacheFile(uri, settings);
        if (!file.isFile() && isRemote(uri)) {
            fetchRemoteVisualQuietly(uri, file);
        }
        if (!file.isFile()) {
            return null;
        }

        try (DataInputStream input = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            int magic = input.readInt();
            int version = input.readInt();
            int fps = input.readInt();
            int waveformPoints = input.readInt();
            int bars = input.readInt();
            int frameCount = input.readInt();
            if (magic != VISUAL_CACHE_MAGIC
                    || version != VISUAL_CACHE_VERSION
                    || fps != settings.fps
                    || waveformPoints != VISUAL_WAVEFORM_POINTS
                    || bars != settings.fftBars
                    || frameCount <= 0) {
                return null;
            }

            int frameSize = waveformPoints + bars;
            long byteCount = (long) frameCount * frameSize;
            if (byteCount > Integer.MAX_VALUE) {
                return null;
            }

            byte[] frames = new byte[(int) byteCount];
            input.readFully(frames);
            file.setLastModified(System.currentTimeMillis());
            return new VisualCache(fps, waveformPoints, bars, frameCount, frames);
        } catch (IOException | RuntimeException ignored) {
            file.delete();
            return null;
        }
    }

    private void writeVisualCache(
            Uri uri,
            File cacheFile,
            VisualizationSettings settings,
            PlaybackRun run,
            TrackProfile profile) throws IOException, InterruptedException {
        File parent = cacheFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Visual cache directory unavailable");
        }

        File tempFile = new File(cacheFile.getAbsolutePath() + ".tmp");
        int[] frameCount = new int[]{0};
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tempFile)))) {
            output.writeInt(VISUAL_CACHE_MAGIC);
            output.writeInt(VISUAL_CACHE_VERSION);
            output.writeInt(settings.fps);
            output.writeInt(VISUAL_WAVEFORM_POINTS);
            output.writeInt(settings.fftBars);
            output.writeInt(0);
            decodeVisualization(uri, settings, run, profile, (waveform, spectrum) -> {
                output.write(waveform);
                output.write(spectrum);
                frameCount[0]++;
            });
        }

        if (run.stopRequested.get() || frameCount[0] == 0) {
            tempFile.delete();
            return;
        }

        try (RandomAccessFile randomAccessFile = new RandomAccessFile(tempFile, "rw")) {
            randomAccessFile.seek(VISUAL_HEADER_FRAME_COUNT_OFFSET);
            randomAccessFile.writeInt(frameCount[0]);
        }

        if (cacheFile.exists() && !cacheFile.delete()) {
            tempFile.delete();
            throw new IOException("Could not replace visual cache");
        }
        if (!tempFile.renameTo(cacheFile)) {
            tempFile.delete();
            throw new IOException("Could not save visual cache");
        }
    }

    private void decodeVisualization(
            Uri uri,
            VisualizationSettings settings,
            PlaybackRun run,
            TrackProfile profile,
            VisualizationSink sink) throws IOException, InterruptedException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            configureExtractor(extractor, uri);
            int trackIndex = selectAudioTrack(extractor);
            if (trackIndex < 0) {
                throw new IOException("No audio track");
            }

            MediaFormat inputFormat = extractor.getTrackFormat(trackIndex);
            String mime = inputFormat.getString(MediaFormat.KEY_MIME);
            if (mime == null) {
                throw new IOException("Unknown audio format");
            }

            extractor.selectTrack(trackIndex);
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(inputFormat, null, null, 0);
            codec.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            StreamFormat streamFormat = null;
            VisualizationCollector collector = null;
            VolumeNormalizer normalizer = null;

            while (!outputDone && !run.stopRequested.get()) {
                if (!inputDone) {
                    int inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US);
                    if (inputIndex >= 0) {
                        ByteBuffer inputBuffer = codec.getInputBuffer(inputIndex);
                        if (inputBuffer == null) {
                            continue;
                        }
                        inputBuffer.clear();
                        int sampleSize = extractor.readSampleData(inputBuffer, 0);
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            long presentationTimeUs = extractor.getSampleTime();
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, presentationTimeUs, 0);
                            extractor.advance();
                        }
                    }
                }

                int outputIndex = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US);
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    streamFormat = StreamFormat.from(codec.getOutputFormat());
                    collector = new VisualizationCollector(streamFormat.sampleRate, settings);
                    normalizer = new VolumeNormalizer(streamFormat.sampleRate, profile, levelingSettings);
                } else if (outputIndex >= 0) {
                    if (streamFormat == null) {
                        streamFormat = StreamFormat.from(codec.getOutputFormat());
                        collector = new VisualizationCollector(streamFormat.sampleRate, settings);
                        normalizer = new VolumeNormalizer(streamFormat.sampleRate, profile, levelingSettings);
                    }
                    ByteBuffer outputBuffer = codec.getOutputBuffer(outputIndex);
                    if (outputBuffer != null && info.size > 0 && collector != null) {
                        outputBuffer.position(info.offset);
                        outputBuffer.limit(info.offset + info.size);
                        acceptVisualizationPcm(outputBuffer.slice(), info.size, streamFormat, collector, normalizer, sink);
                    }
                    outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    codec.releaseOutputBuffer(outputIndex, false);
                }
            }
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (RuntimeException ignored) {
                }
                try {
                    codec.release();
                } catch (RuntimeException ignored) {
                }
            }
            try {
                extractor.release();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void acceptVisualizationPcm(
            ByteBuffer source,
            int size,
            StreamFormat format,
            VisualizationCollector collector,
            VolumeNormalizer normalizer,
            VisualizationSink sink) throws IOException {
        source.order(ByteOrder.LITTLE_ENDIAN);
        int bytesPerSample = bytesPerSample(format.pcmEncoding);
        int inputFrameSize = bytesPerSample * format.inputChannels;
        int frames = size / inputFrameSize;

        for (int frame = 0; frame < frames; frame++) {
            float left = 0f;
            float right = 0f;
            int leftCount = 0;
            int rightCount = 0;

            for (int channel = 0; channel < format.inputChannels; channel++) {
                float sample = readSample(source, format.pcmEncoding);
                if (format.inputChannels == 1) {
                    left += sample;
                    right += sample;
                    leftCount++;
                    rightCount++;
                } else if (channel % 2 == 0) {
                    left += sample;
                    leftCount++;
                } else {
                    right += sample;
                    rightCount++;
                }
            }

            if (leftCount > 0) {
                left /= leftCount;
            }
            if (rightCount > 0) {
                right /= rightCount;
            } else {
                right = left;
            }
            // Match the live playback path (processPcm): feed the collector
            // post-leveling audio, not the raw decode, so the cached/replayed
            // visualization matches what live analysis would have shown.
            float gain = normalizer.nextGain(left, right, levelingStrength);
            left = normalizer.protect(left * gain * outputLevel);
            right = normalizer.protect(right * gain * outputLevel);
            collector.accept((left + right) * 0.5f, sink);
        }
    }

    private static long cacheTimestamp(Object value) {
        if (value == null) {
            return 0L;
        }
        String[] parts = value.toString().split("\\|");
        if (parts.length < 3) {
            return 0L;
        }
        try {
            return Long.parseLong(parts[2]);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private String profileCacheKey(Uri uri) {
        if (isRemote(uri)) {
            return uri.toString();
        }
        long size = -1L;
        long modified = -1L;
        String[] projection = new String[]{
                OpenableColumns.SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
        };
        try (Cursor cursor = context.getContentResolver().query(uri, projection, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    size = cursor.getLong(sizeIndex);
                }
                int modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED);
                if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) {
                    modified = cursor.getLong(modifiedIndex);
                }
            }
        } catch (RuntimeException ignored) {
        }
        return uri.toString() + "|size=" + size + "|modified=" + modified;
    }

    private File visualCacheFile(Uri uri, VisualizationSettings settings) {
        String key = profileCacheKey(uri)
                + "|visualVersion=" + VISUAL_CACHE_VERSION
                + "|fps=" + settings.fps
                + "|waveformMs=" + settings.waveformMs
                + "|fftSize=" + settings.fftSize
                + "|bars=" + settings.fftBars
                + "|log=" + settings.logScale;
        return new File(visualCacheDir(context), sha256(key) + ".fvz");
    }

    private static File visualCacheDir(Context context) {
        return new File(context.getCacheDir(), VISUAL_CACHE_DIR);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                int number = b & 0xFF;
                if (number < 16) {
                    builder.append('0');
                }
                builder.append(Integer.toHexString(number));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ignored) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private void configureExtractor(MediaExtractor extractor, Uri uri) throws IOException {
        if (isRemote(uri)) {
            extractor.setDataSource(uri.toString(), remoteHeaders());
        } else {
            extractor.setDataSource(context, uri, null);
        }
    }

    private static boolean isRemote(Uri uri) {
        String scheme = uri.getScheme();
        return "http".equals(scheme) || "https".equals(scheme);
    }

    private Map<String, String> remoteHeaders() {
        Map<String, String> headers = new HashMap<>();
        String token = PlaylistStore.loadServerToken(context);
        if (!token.isEmpty()) {
            headers.put("Authorization", "Bearer " + token);
        }
        return headers;
    }

    private String remoteApiUrl(String apiPrefix, Uri uri) {
        String uriString = uri.toString();
        int index = uriString.indexOf(REMOTE_STREAM_SEGMENT);
        if (index < 0) {
            return null;
        }
        return uriString.substring(0, index) + apiPrefix
                + uriString.substring(index + REMOTE_STREAM_SEGMENT.length());
    }

    private HttpURLConnection openRemoteConnection(String urlString, String method) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
        connection.setConnectTimeout(REMOTE_CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(REMOTE_READ_TIMEOUT_MS);
        connection.setRequestMethod(method);
        String token = PlaylistStore.loadServerToken(context);
        if (!token.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }
        return connection;
    }

    private static String readAll(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = input.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toString(StandardCharsets.UTF_8.name());
    }

    private static byte[] readFileBytes(File file) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] chunk = new byte[8192];
            int read;
            while ((read = input.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
        }
        return buffer.toByteArray();
    }

    private TrackProfile fetchRemoteProfile(Uri uri) {
        String url = remoteApiUrl("/api/profile/", uri);
        if (url == null) {
            return null;
        }
        try {
            HttpURLConnection connection = openRemoteConnection(url, "GET");
            try {
                if (connection.getResponseCode() != 200) {
                    return null;
                }
                JSONObject json = new JSONObject(readAll(connection.getInputStream()));
                return new TrackProfile(json.getDouble("rms"), json.getDouble("peak"));
            } finally {
                connection.disconnect();
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private void uploadRemoteProfile(Uri uri, TrackProfile profile) {
        String url = remoteApiUrl("/api/profile/", uri);
        if (url == null) {
            return;
        }
        try {
            JSONObject json = new JSONObject();
            json.put("rms", profile.rms);
            json.put("peak", profile.peak);
            byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);
            HttpURLConnection connection = openRemoteConnection(url, "PUT");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            try {
                connection.getOutputStream().write(body);
                connection.getResponseCode();
            } finally {
                connection.disconnect();
            }
        } catch (Exception ignored) {
            // Best-effort — playback must not depend on this succeeding.
        }
    }

    private void fetchRemoteVisualQuietly(Uri uri, File destination) {
        String url = remoteApiUrl("/api/visual/", uri);
        if (url == null) {
            return;
        }
        try {
            HttpURLConnection connection = openRemoteConnection(url, "GET");
            try {
                if (connection.getResponseCode() != 200) {
                    return;
                }
                File parent = destination.getParentFile();
                if (parent != null && !parent.exists()) {
                    parent.mkdirs();
                }
                try (InputStream input = connection.getInputStream();
                     FileOutputStream output = new FileOutputStream(destination)) {
                    byte[] chunk = new byte[8192];
                    int read;
                    while ((read = input.read(chunk)) != -1) {
                        output.write(chunk, 0, read);
                    }
                }
            } finally {
                connection.disconnect();
            }
        } catch (Exception ignored) {
            destination.delete();
        }
    }

    private void uploadRemoteVisualQuietly(Uri uri, File source) {
        String url = remoteApiUrl("/api/visual/", uri);
        if (url == null) {
            return;
        }
        try {
            byte[] body = readFileBytes(source);
            HttpURLConnection connection = openRemoteConnection(url, "PUT");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/octet-stream");
            try {
                connection.getOutputStream().write(body);
                connection.getResponseCode();
            } finally {
                connection.disconnect();
            }
        } catch (Exception ignored) {
            // Best-effort — a failed upload just means the next device recomputes locally.
        }
    }

    private int selectAudioTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                return i;
            }
        }
        return -1;
    }

    private AudioTrack createAudioTrack(StreamFormat format) {
        int channelMask = format.outputChannels == 1
                ? AudioFormat.CHANNEL_OUT_MONO
                : AudioFormat.CHANNEL_OUT_STEREO;
        int minBuffer = AudioTrack.getMinBufferSize(
                format.sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) {
            minBuffer = format.sampleRate * format.outputChannels * 2 / 2;
        }
        int bufferSize = Math.max(minBuffer * 2, format.sampleRate * format.outputChannels * 2 / 5);

        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        AudioFormat audioFormat = new AudioFormat.Builder()
                .setSampleRate(format.sampleRate)
                .setChannelMask(channelMask)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build();
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(audioFormat)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferSize)
                .build();
        track.play();
        return track;
    }

    private byte[] processPcm(
            ByteBuffer source,
            int size,
            StreamFormat format,
            VolumeNormalizer normalizer,
            VisualizationCollector visualizationCollector) throws IOException {
        source.order(ByteOrder.LITTLE_ENDIAN);
        normalizer.updateSettings(levelingSettings);
        if (visualizationCollector != null) {
            visualizationCollector.updateSettings(visualizationSettings);
        }
        int bytesPerSample = bytesPerSample(format.pcmEncoding);
        int inputFrameSize = bytesPerSample * format.inputChannels;
        int frames = size / inputFrameSize;
        byte[] output = new byte[frames * format.outputChannels * 2];
        ByteBuffer out = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN);

        for (int frame = 0; frame < frames; frame++) {
            float left = 0f;
            float right = 0f;
            int leftCount = 0;
            int rightCount = 0;

            for (int channel = 0; channel < format.inputChannels; channel++) {
                float sample = readSample(source, format.pcmEncoding);
                if (format.inputChannels == 1) {
                    left += sample;
                    right += sample;
                    leftCount++;
                    rightCount++;
                } else if (channel % 2 == 0) {
                    left += sample;
                    leftCount++;
                } else {
                    right += sample;
                    rightCount++;
                }
            }

            if (leftCount > 0) {
                left /= leftCount;
            }
            if (rightCount > 0) {
                right /= rightCount;
            } else {
                right = left;
            }

            float gain = normalizer.nextGain(left, right, levelingStrength);
            left = normalizer.protect(left * gain * outputLevel);
            right = normalizer.protect(right * gain * outputLevel);

            out.putShort(floatToShort(left));
            if (format.outputChannels == 2) {
                out.putShort(floatToShort(right));
            }
            if (visualizationCollector != null) {
                visualizationCollector.accept((left + right) * 0.5f, callback::onVisualization);
            }
        }
        return output;
    }

    private int writeFully(AudioTrack track, byte[] pcm, PlaybackRun run) throws IOException, InterruptedException {
        int total = 0;
        while (total < pcm.length && !run.stopRequested.get()) {
            waitIfPaused(run);
            int written = track.write(pcm, total, pcm.length - total);
            if (written < 0) {
                throw new IOException("Audio output failed");
            }
            total += written;
        }
        return total;
    }

    private void waitForPlaybackTail(AudioTrack track, long framesWritten, int sampleRate, PlaybackRun run) throws InterruptedException {
        while (!run.stopRequested.get()) {
            waitIfPaused(run);
            long played = track.getPlaybackHeadPosition() & 0xFFFFFFFFL;
            long remaining = framesWritten - played;
            if (remaining <= 0) {
                return;
            }
            long sleepMs = Math.max(20, Math.min(200, remaining * 1000 / Math.max(1, sampleRate)));
            Thread.sleep(sleepMs);
        }
    }

    private void waitIfPaused(PlaybackRun run) throws InterruptedException {
        synchronized (pauseLock) {
            while (paused && !run.stopRequested.get()) {
                pauseLock.wait();
            }
        }
    }

    private static int bytesPerSample(int encoding) throws IOException {
        if (encoding == AudioFormat.ENCODING_PCM_16BIT) {
            return 2;
        }
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            return 4;
        }
        if (encoding == AudioFormat.ENCODING_PCM_8BIT) {
            return 1;
        }
        if (encoding == AudioFormat.ENCODING_PCM_24BIT_PACKED) {
            return 3;
        }
        if (encoding == AudioFormat.ENCODING_PCM_32BIT) {
            return 4;
        }
        throw new IOException("Unsupported PCM output");
    }

    private static float readSample(ByteBuffer input, int encoding) throws IOException {
        if (encoding == AudioFormat.ENCODING_PCM_16BIT) {
            return input.getShort() / 32768f;
        }
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            return clamp(input.getFloat(), -1f, 1f);
        }
        if (encoding == AudioFormat.ENCODING_PCM_8BIT) {
            return ((input.get() & 0xFF) - 128) / 128f;
        }
        if (encoding == AudioFormat.ENCODING_PCM_24BIT_PACKED) {
            int b0 = input.get() & 0xFF;
            int b1 = input.get() & 0xFF;
            int b2 = input.get();
            int value = b0 | (b1 << 8) | (b2 << 16);
            if ((value & 0x00800000) != 0) {
                value |= 0xFF000000;
            }
            return value / 8388608f;
        }
        if (encoding == AudioFormat.ENCODING_PCM_32BIT) {
            return input.getInt() / 2147483648f;
        }
        throw new IOException("Unsupported PCM output");
    }

    private static short floatToShort(float value) {
        float clamped = clamp(value, -1f, 1f);
        return (short) Math.round(clamped * 32767f);
    }

    private static String cleanError(Exception e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return "Playback failed";
        }
        return message.length() > 80 ? message.substring(0, 80) : message;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    static final class CacheStats {
        final int count;
        final int pruneAbove;
        final int keep;
        final long approximateBytes;

        private CacheStats(int count, int pruneAbove, int keep, long approximateBytes) {
            this.count = count;
            this.pruneAbove = pruneAbove;
            this.keep = keep;
            this.approximateBytes = approximateBytes;
        }
    }

    private static final class CacheEntry {
        final String key;
        final long timestamp;

        private CacheEntry(String key, long timestamp) {
            this.key = key;
            this.timestamp = timestamp;
        }
    }

    private interface VisualizationSink {
        void onVisualization(byte[] waveform, byte[] spectrum) throws IOException;
    }

    private static final class VisualCache {
        final int fps;
        final int waveformPoints;
        final int bars;
        final int frameCount;
        final int frameSize;
        final byte[] frames;

        private VisualCache(int fps, int waveformPoints, int bars, int frameCount, byte[] frames) {
            this.fps = fps;
            this.waveformPoints = waveformPoints;
            this.bars = bars;
            this.frameCount = frameCount;
            this.frameSize = waveformPoints + bars;
            this.frames = frames;
        }
    }

    private static final class VisualCachePlayback {
        private final VisualCache cache;
        private final int sampleRate;
        private final long baseFrames;
        private int nextFrame;

        private VisualCachePlayback(VisualCache cache, int sampleRate, long startPositionMs) {
            this.cache = cache;
            this.sampleRate = Math.max(1, sampleRate);
            baseFrames = Math.max(0L, startPositionMs) * this.sampleRate / 1000L;
            nextFrame = (int) Math.min(
                    cache.frameCount,
                    baseFrames * cache.fps / this.sampleRate);
        }

        void emitUntil(long framesWritten, Callback callback) {
            int targetFrame = (int) Math.min(
                    cache.frameCount,
                    (baseFrames + framesWritten) * cache.fps / sampleRate);
            while (nextFrame < targetFrame) {
                int offset = nextFrame * cache.frameSize;
                byte[] waveform = new byte[cache.waveformPoints];
                byte[] spectrum = new byte[cache.bars];
                System.arraycopy(cache.frames, offset, waveform, 0, waveform.length);
                System.arraycopy(cache.frames, offset + waveform.length, spectrum, 0, spectrum.length);
                callback.onVisualization(waveform, spectrum);
                nextFrame++;
            }
        }
    }

    private static final class VisualizationCollector {

        private final int sampleRate;
        private VisualizationSettings settings;
        private int framesPerEmit;
        private long framesSinceEmit;
        private float[] waveformRing;
        private int waveformWrite;
        private int waveformFilled;
        private float[] fftRing;
        private double[] fftWindow;
        private int fftWrite;
        private int fftFilled;

        private VisualizationCollector(int sampleRate, VisualizationSettings settings) {
            this.sampleRate = Math.max(1, sampleRate);
            updateSettings(settings);
        }

        void updateSettings(VisualizationSettings nextSettings) {
            VisualizationSettings safeSettings = nextSettings == null
                    ? VisualizationSettings.defaults()
                    : nextSettings;
            settings = safeSettings;
            framesPerEmit = Math.max(1, sampleRate / Math.max(1, safeSettings.fps));

            int waveformFrames = Math.max(VISUAL_WAVEFORM_POINTS, sampleRate * safeSettings.waveformMs / 1000);
            if (waveformRing == null || waveformRing.length != waveformFrames) {
                waveformRing = new float[waveformFrames];
                waveformWrite = 0;
                waveformFilled = 0;
            }
            if (fftRing == null || fftRing.length != safeSettings.fftSize) {
                fftRing = new float[safeSettings.fftSize];
                fftWindow = new double[safeSettings.fftSize];
                for (int i = 0; i < fftWindow.length; i++) {
                    fftWindow[i] = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / Math.max(1, fftWindow.length - 1));
                }
                fftWrite = 0;
                fftFilled = 0;
            }
        }

        void accept(float sample, VisualizationSink sink) throws IOException {
            float clipped = clamp(sample, -1f, 1f);
            waveformRing[waveformWrite] = clipped;
            waveformWrite = (waveformWrite + 1) % waveformRing.length;
            waveformFilled = Math.min(waveformFilled + 1, waveformRing.length);

            fftRing[fftWrite] = clipped;
            fftWrite = (fftWrite + 1) % fftRing.length;
            fftFilled = Math.min(fftFilled + 1, fftRing.length);

            framesSinceEmit++;
            if (framesSinceEmit < framesPerEmit || fftFilled < fftRing.length) {
                return;
            }
            framesSinceEmit = 0;
            sink.onVisualization(waveformSnapshot(), spectrumSnapshot());
        }

        private byte[] waveformSnapshot() {
            byte[] out = new byte[VISUAL_WAVEFORM_POINTS];
            if (waveformFilled == 0) {
                return out;
            }
            int start = (waveformWrite - waveformFilled + waveformRing.length) % waveformRing.length;
            for (int i = 0; i < out.length; i++) {
                int offset = out.length == 1 ? 0 : i * (waveformFilled - 1) / (out.length - 1);
                float value = waveformRing[(start + offset) % waveformRing.length];
                out[i] = (byte) Math.round(clamp(value, -1f, 1f) * 127f);
            }
            return out;
        }

        private byte[] spectrumSnapshot() {
            int fftSize = fftRing.length;
            int bars = settings.fftBars;
            byte[] out = new byte[bars];
            float[] ordered = new float[fftSize];
            for (int i = 0; i < fftSize; i++) {
                ordered[i] = fftRing[(fftWrite + i) % fftSize];
            }

            for (int bar = 0; bar < bars; bar++) {
                int bin = spectrumBin(bar, bars, fftSize, settings.logScale);
                double magnitude = goertzelMagnitude(ordered, bin);
                double normalized = settings.logScale
                        ? Math.log10(1.0 + magnitude * 85.0)
                        : magnitude * 12.0;
                out[bar] = (byte) Math.round(clamp((float) normalized, 0f, 1f) * 100f);
            }
            return out;
        }

        private int spectrumBin(int bar, int bars, int fftSize, boolean logScale) {
            int maxBin = Math.max(2, fftSize / 2 - 1);
            if (!logScale) {
                return 1 + Math.round((maxBin - 1) * bar / (float) Math.max(1, bars - 1));
            }

            double minBin = Math.max(1.0, 40.0 * fftSize / sampleRate);
            double ratio = bar / (double) Math.max(1, bars - 1);
            double logStart = Math.log(minBin);
            double logEnd = Math.log(maxBin);
            int bin = (int) Math.round(Math.exp(logStart + (logEnd - logStart) * ratio));
            return Math.max(1, Math.min(maxBin, bin));
        }

        private double goertzelMagnitude(float[] samples, int bin) {
            int size = samples.length;
            double omega = 2.0 * Math.PI * bin / size;
            double coeff = 2.0 * Math.cos(omega);
            double previous = 0.0;
            double previous2 = 0.0;

            for (int i = 0; i < size; i++) {
                double current = samples[i] * fftWindow[i] + coeff * previous - previous2;
                previous2 = previous;
                previous = current;
            }

            double power = previous2 * previous2 + previous * previous - coeff * previous * previous2;
            return Math.sqrt(Math.max(0.0, power)) / Math.max(1, size * 0.5);
        }
    }

    private static final class StreamFormat {
        final int sampleRate;
        final int inputChannels;
        final int outputChannels;
        final int pcmEncoding;

        private StreamFormat(int sampleRate, int inputChannels, int pcmEncoding) {
            this.sampleRate = sampleRate;
            this.inputChannels = Math.max(1, inputChannels);
            this.outputChannels = this.inputChannels == 1 ? 1 : 2;
            this.pcmEncoding = pcmEncoding;
        }

        static StreamFormat from(MediaFormat format) {
            int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int encoding = AudioFormat.ENCODING_PCM_16BIT;
            if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                encoding = format.getInteger(MediaFormat.KEY_PCM_ENCODING);
            }
            return new StreamFormat(sampleRate, channels, encoding);
        }
    }

    private static final class PlaybackRun {
        final AtomicBoolean stopRequested = new AtomicBoolean(false);
        final long startPositionMs;
        final boolean startPaused;

        private PlaybackRun() {
            this(0L, false);
        }

        private PlaybackRun(long startPositionMs, boolean startPaused) {
            this.startPositionMs = Math.max(0L, startPositionMs);
            this.startPaused = startPaused;
        }
    }

    private static final class TrackProfile {
        final double rms;
        final double peak;

        private TrackProfile(double rms, double peak) {
            this.rms = rms;
            this.peak = peak;
        }

        private static final class Meter {
            private static final double SILENCE_FLOOR = 0.004;
            private double sumSquares;
            private double peak;
            private long sampleCount;

            boolean hasSamples() {
                return sampleCount > 0;
            }

            void accept(ByteBuffer source, int size, StreamFormat format) throws IOException {
                source.order(ByteOrder.LITTLE_ENDIAN);
                int bytesPerSample = bytesPerSample(format.pcmEncoding);
                int inputFrameSize = bytesPerSample * format.inputChannels;
                int frames = size / inputFrameSize;
                for (int frame = 0; frame < frames; frame++) {
                    for (int channel = 0; channel < format.inputChannels; channel++) {
                        float sample = readSample(source, format.pcmEncoding);
                        double amount = Math.abs(sample);
                        peak = Math.max(peak, amount);
                        if (amount >= SILENCE_FLOOR) {
                            sumSquares += sample * sample;
                            sampleCount++;
                        }
                    }
                }
            }

            TrackProfile toProfile() {
                if (sampleCount == 0) {
                    return new TrackProfile(0.18, peak);
                }
                return new TrackProfile(Math.sqrt(sumSquares / sampleCount), peak);
            }
        }
    }

    private static final class VolumeNormalizer {
        private static final double TARGET_RMS = 0.18;
        private static final double MIN_LEVEL = 0.012;
        private static final double MAX_GAIN = 4.5;
        private static final double MIN_GAIN = 0.2;
        private static final float COMPRESSOR_RATIO = 6.0f;

        private final int sampleRate;
        private LevelingSettings settings;
        private double levelAttack;
        private double levelRelease;
        private double gainDown;
        private double gainUp;
        private float compressorThreshold;
        private float outputCeiling;
        private double envelope = TARGET_RMS;
        private double gain = 1.0;

        VolumeNormalizer(int sampleRate, TrackProfile profile, LevelingSettings settings) {
            this.sampleRate = sampleRate;
            updateSettings(settings);
            if (profile != null) {
                double measured = Math.max(profile.rms, profile.peak * 0.35);
                envelope = Math.max(MIN_LEVEL, Math.min(0.8, measured));
                gain = Math.max(MIN_GAIN, Math.min(MAX_GAIN, TARGET_RMS / Math.max(MIN_LEVEL, envelope)));
            }
        }

        void updateSettings(LevelingSettings settings) {
            LevelingSettings safeSettings = settings == null ? LevelingSettings.defaults() : settings;
            if (safeSettings == this.settings) {
                return;
            }
            this.settings = safeSettings;
            levelAttack = coefficient(sampleRate, safeSettings.levelAttackMs / 1000.0);
            levelRelease = coefficient(sampleRate, safeSettings.levelReleaseMs / 1000.0);
            gainDown = coefficient(sampleRate, safeSettings.gainDownMs / 1000.0);
            gainUp = coefficient(sampleRate, safeSettings.gainUpMs / 1000.0);
            compressorThreshold = safeSettings.compressorThreshold;
            outputCeiling = safeSettings.outputCeiling;
        }

        float nextGain(float left, float right, float strength) {
            double rms = Math.sqrt((left * left + right * right) * 0.5);
            double peak = Math.max(Math.abs(left), Math.abs(right));
            double instant = Math.max(rms, peak * 0.35);
            double levelAlpha = instant > envelope ? levelAttack : levelRelease;
            envelope += (instant - envelope) * levelAlpha;

            double desired = TARGET_RMS / Math.max(MIN_LEVEL, envelope);
            desired = Math.max(MIN_GAIN, Math.min(MAX_GAIN, desired));
            desired = 1.0 + ((desired - 1.0) * clamp(strength, 0f, 1f));
            double gainAlpha = desired < gain ? gainDown : gainUp;
            gain += (desired - gain) * gainAlpha;
            return (float) gain;
        }

        float protect(float value) {
            float sign = Math.signum(value);
            float amount = Math.abs(value);
            if (amount > compressorThreshold) {
                amount = compressorThreshold + ((amount - compressorThreshold) / COMPRESSOR_RATIO);
            }
            if (amount > outputCeiling) {
                amount = outputCeiling;
            }
            return sign * amount;
        }

        private static double coefficient(int sampleRate, double seconds) {
            return 1.0 - Math.exp(-1.0 / (Math.max(1, sampleRate) * seconds));
        }
    }
}
