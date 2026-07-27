package com.fredplayer.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTimestamp;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.util.Log;

final class BluetoothDelayCalibrator {
    private static final int SAMPLE_RATE = 48_000;
    private static final int PRE_ROLL_FRAMES = SAMPLE_RATE * 350 / 1000;
    private static final int CHIRP_FRAMES = SAMPLE_RATE * 300 / 1000;
    private static final int POST_ROLL_FRAMES = SAMPLE_RATE * 350 / 1000;
    private static final int CAPTURE_FRAMES = SAMPLE_RATE * 3;
    private static final int DOWNSAMPLE = 8;
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private BluetoothDelayCalibrator() {
    }

    static Result calibrate(Context context) throws Exception {
        short[] chirp = buildChirp();
        short[] output = buildStereoOutput(chirp);
        short[] capture = new short[CAPTURE_FRAMES];
        AudioRecord recorder = null;
        AudioTrack track = null;
        try {
            recorder = createRecorder(context);
            track = createTrack(output.length * 2);
            int written = track.write(output, 0, output.length, AudioTrack.WRITE_BLOCKING);
            if (written != output.length) {
                throw new IllegalStateException("Could not prepare calibration sound");
            }

            recorder.startRecording();
            long recordStartNs = System.nanoTime();
            long trackStartNs = System.nanoTime();
            track.play();

            AudioTimestamp outputTimestamp = new AudioTimestamp();
            AudioTimestamp recordTimestamp = new AudioTimestamp();
            boolean recordTimestampValid = false;
            long outputClockFrame = 0L;
            long outputClockNs = trackStartNs;
            long lastOutputTimestampFrame = -1L;
            long lastPlaybackHead = -1L;
            AudioDeviceInfo routedDevice = null;
            int captured = 0;
            short[] chunk = new short[960];
            while (captured < capture.length) {
                int wanted = Math.min(chunk.length, capture.length - captured);
                int read = recorder.read(chunk, 0, wanted, AudioRecord.READ_BLOCKING);
                if (read < 0) {
                    throw new IllegalStateException("Microphone read failed");
                }
                if (read == 0) {
                    continue;
                }
                System.arraycopy(chunk, 0, capture, captured, read);
                captured += read;
                if (track.getTimestamp(outputTimestamp)
                        && outputTimestamp.framePosition > lastOutputTimestampFrame) {
                    lastOutputTimestampFrame = outputTimestamp.framePosition;
                    outputClockFrame = outputTimestamp.framePosition;
                    outputClockNs = outputTimestamp.nanoTime;
                } else {
                    long playbackHead = track.getPlaybackHeadPosition() & 0xFFFFFFFFL;
                    if (lastOutputTimestampFrame < 0L && playbackHead > lastPlaybackHead) {
                        lastPlaybackHead = playbackHead;
                        outputClockFrame = playbackHead;
                        outputClockNs = System.nanoTime();
                    }
                }
                if (recorder.getTimestamp(recordTimestamp, AudioTimestamp.TIMEBASE_MONOTONIC)
                        == AudioRecord.SUCCESS) {
                    recordTimestampValid = true;
                }
                AudioDeviceInfo currentRoute = track.getRoutedDevice();
                if (currentRoute != null) {
                    routedDevice = currentRoute;
                }
            }

            if (routedDevice == null || !AudioOutputRoute.isBluetooth(routedDevice)) {
                throw new IllegalStateException("Connect and select a Bluetooth speaker first");
            }

            long expectedChirpNs = outputClockNs
                    + framesToNanos(PRE_ROLL_FRAMES - outputClockFrame);
            long expectedRecordFrame = recordTimestampValid
                    ? recordTimestamp.framePosition
                    + nanosToFrames(expectedChirpNs - recordTimestamp.nanoTime)
                    : nanosToFrames(expectedChirpNs - recordStartNs);

            Match match = findChirp(capture, captured, chirp, expectedRecordFrame);
            Log.i("FredPlayerAudio", "Calibration match: expectedFrame=" + expectedRecordFrame
                    + ", matchedFrame=" + match.frame + ", score=" + match.score
                    + ", candidateDelayMs="
                    + Math.round((match.frame - expectedRecordFrame) * 1000.0 / SAMPLE_RATE));
            if (match.score < 0.12) {
                throw new IllegalStateException(
                        "Calibration sound was not clear enough; move closer or raise the speaker volume");
            }
            int delayMs = (int) Math.round(
                    (match.frame - expectedRecordFrame) * 1000.0 / SAMPLE_RATE);
            if (delayMs < 0 || delayMs > 1500) {
                throw new IllegalStateException("Measured delay was outside the supported range");
            }
            return new Result(
                    AudioOutputRoute.key(routedDevice),
                    AudioOutputRoute.label(routedDevice),
                    delayMs,
                    match.score);
        } finally {
            if (track != null) {
                try {
                    track.pause();
                    track.flush();
                } catch (RuntimeException ignored) {
                }
                track.release();
            }
            if (recorder != null) {
                try {
                    recorder.stop();
                } catch (RuntimeException ignored) {
                }
                recorder.release();
            }
        }
    }

    private static AudioRecord createRecorder(Context context) {
        AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        boolean unprocessed = manager != null && "true".equalsIgnoreCase(
                manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED));
        if (unprocessed) {
            AudioRecord recorder = buildRecorder(MediaRecorder.AudioSource.UNPROCESSED);
            if (recorder.getState() == AudioRecord.STATE_INITIALIZED) {
                return recorder;
            }
            recorder.release();
        }
        AudioRecord recorder = buildRecorder(MediaRecorder.AudioSource.MIC);
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            recorder.release();
            throw new IllegalStateException("Microphone is unavailable");
        }
        return recorder;
    }

    private static AudioRecord buildRecorder(int source) {
        int minimum = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        int bufferBytes = Math.max(4096, minimum * 2);
        return new AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build())
                .setBufferSizeInBytes(bufferBytes)
                .build();
    }

    private static AudioTrack createTrack(int bufferBytes) {
        return new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(bufferBytes)
                .build();
    }

    private static short[] buildChirp() {
        short[] chirp = new short[CHIRP_FRAMES];
        double duration = CHIRP_FRAMES / (double) SAMPLE_RATE;
        double startHz = 700.0;
        double endHz = 6500.0;
        double sweep = (endHz - startHz) / duration;
        for (int frame = 0; frame < chirp.length; frame++) {
            double time = frame / (double) SAMPLE_RATE;
            double phase = 2.0 * Math.PI * (startHz * time + 0.5 * sweep * time * time);
            double envelope = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * frame / (chirp.length - 1));
            chirp[frame] = (short) Math.round(Math.sin(phase) * envelope * 11_000.0);
        }
        return chirp;
    }

    private static short[] buildStereoOutput(short[] chirp) {
        int totalFrames = PRE_ROLL_FRAMES + chirp.length + POST_ROLL_FRAMES;
        short[] output = new short[totalFrames * 2];
        for (int frame = 0; frame < chirp.length; frame++) {
            int outputFrame = PRE_ROLL_FRAMES + frame;
            output[outputFrame * 2] = chirp[frame];
            output[outputFrame * 2 + 1] = chirp[frame];
        }
        return output;
    }

    private static Match findChirp(
            short[] capture,
            int capturedFrames,
            short[] chirp,
            long expectedFrame) {
        int referenceCount = chirp.length / DOWNSAMPLE;
        double referenceEnergy = 0.0;
        double[] reference = new double[referenceCount];
        for (int index = 0; index < referenceCount; index++) {
            double sample = chirp[index * DOWNSAMPLE];
            reference[index] = sample;
            referenceEnergy += sample * sample;
        }

        int earliest = (int) Math.max(0L, expectedFrame - SAMPLE_RATE / 5L);
        int latest = (int) Math.min(
                capturedFrames - chirp.length,
                expectedFrame + SAMPLE_RATE * 3L / 2L);
        int firstCandidate = Math.max(0, earliest / DOWNSAMPLE);
        int lastCandidate = Math.max(firstCandidate, latest / DOWNSAMPLE);
        double bestScore = 0.0;
        int bestFrame = firstCandidate * DOWNSAMPLE;
        for (int candidate = firstCandidate; candidate <= lastCandidate; candidate++) {
            int captureStart = candidate * DOWNSAMPLE;
            double dot = 0.0;
            double captureEnergy = 0.0;
            for (int index = 0; index < referenceCount; index++) {
                double sample = capture[captureStart + index * DOWNSAMPLE];
                dot += reference[index] * sample;
                captureEnergy += sample * sample;
            }
            if (captureEnergy <= 0.0) {
                continue;
            }
            double score = Math.abs(dot) / Math.sqrt(referenceEnergy * captureEnergy);
            if (score > bestScore) {
                bestScore = score;
                bestFrame = captureStart;
            }
        }
        return new Match(bestFrame, bestScore);
    }

    private static long framesToNanos(long frames) {
        return frames * NANOS_PER_SECOND / SAMPLE_RATE;
    }

    private static long nanosToFrames(long nanoseconds) {
        return nanoseconds * SAMPLE_RATE / NANOS_PER_SECOND;
    }

    static final class Result {
        final String routeKey;
        final String routeLabel;
        final int delayMs;
        final double confidence;

        private Result(String routeKey, String routeLabel, int delayMs, double confidence) {
            this.routeKey = routeKey;
            this.routeLabel = routeLabel;
            this.delayMs = delayMs;
            this.confidence = confidence;
        }
    }

    private static final class Match {
        final int frame;
        final double score;

        private Match(int frame, double score) {
            this.frame = frame;
            this.score = score;
        }
    }
}
