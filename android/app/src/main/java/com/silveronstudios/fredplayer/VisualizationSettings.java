package com.silveronstudios.fredplayer;

final class VisualizationSettings {
    static final int DEFAULT_FPS = 20;
    static final int DEFAULT_WAVEFORM_MS = 90;
    static final int DEFAULT_FFT_SIZE = 512;
    static final int DEFAULT_FFT_BARS = 32;
    static final float DEFAULT_SMOOTHING = 0.55f;
    static final boolean DEFAULT_LOG_SCALE = true;

    final int fps;
    final int waveformMs;
    final int fftSize;
    final int fftBars;
    final float smoothing;
    final boolean logScale;

    VisualizationSettings(
            int fps,
            int waveformMs,
            int fftSize,
            int fftBars,
            float smoothing,
            boolean logScale) {
        this.fps = clamp(fps, 5, 60);
        this.waveformMs = clamp(waveformMs, 20, 90);
        this.fftSize = nearestFftSize(fftSize);
        this.fftBars = clamp(fftBars, 16, 64);
        this.smoothing = clamp(smoothing, 0f, 0.95f);
        this.logScale = logScale;
    }

    static VisualizationSettings defaults() {
        return new VisualizationSettings(
                DEFAULT_FPS,
                DEFAULT_WAVEFORM_MS,
                DEFAULT_FFT_SIZE,
                DEFAULT_FFT_BARS,
                DEFAULT_SMOOTHING,
                DEFAULT_LOG_SCALE);
    }

    String remoteCacheKey() {
        return "fps" + fps
                + "-wave" + waveformMs
                + "-fft" + fftSize
                + "-bars" + fftBars
                + "-log" + (logScale ? 1 : 0);
    }

    private static int nearestFftSize(int value) {
        if (value <= 768) {
            return 512;
        }
        if (value <= 1536) {
            return 1024;
        }
        return 2048;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
