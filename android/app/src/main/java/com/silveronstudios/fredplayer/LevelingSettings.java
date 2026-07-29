package com.silveronstudios.fredplayer;

final class LevelingSettings {
    static final float DEFAULT_ANALYSIS_SECONDS = 10f;
    static final float DEFAULT_LEVEL_ATTACK_MS = 15f;
    static final float DEFAULT_LEVEL_RELEASE_MS = 750f;
    static final float DEFAULT_GAIN_DOWN_MS = 40f;
    static final float DEFAULT_GAIN_UP_MS = 2800f;
    static final float DEFAULT_COMPRESSOR_THRESHOLD = 0.68f;
    static final float DEFAULT_OUTPUT_CEILING = 0.96f;

    final float analysisSeconds;
    final float levelAttackMs;
    final float levelReleaseMs;
    final float gainDownMs;
    final float gainUpMs;
    final float compressorThreshold;
    final float outputCeiling;

    LevelingSettings(
            float analysisSeconds,
            float levelAttackMs,
            float levelReleaseMs,
            float gainDownMs,
            float gainUpMs,
            float compressorThreshold,
            float outputCeiling) {
        this.analysisSeconds = clamp(analysisSeconds, 0f, 45f);
        this.levelAttackMs = clamp(levelAttackMs, 1f, 250f);
        this.levelReleaseMs = clamp(levelReleaseMs, 100f, 5000f);
        this.gainDownMs = clamp(gainDownMs, 5f, 500f);
        this.gainUpMs = clamp(gainUpMs, 500f, 10000f);
        this.compressorThreshold = clamp(compressorThreshold, 0.3f, 0.95f);
        this.outputCeiling = clamp(outputCeiling, 0.5f, 1.0f);
    }

    static LevelingSettings defaults() {
        return new LevelingSettings(
                DEFAULT_ANALYSIS_SECONDS,
                DEFAULT_LEVEL_ATTACK_MS,
                DEFAULT_LEVEL_RELEASE_MS,
                DEFAULT_GAIN_DOWN_MS,
                DEFAULT_GAIN_UP_MS,
                DEFAULT_COMPRESSOR_THRESHOLD,
                DEFAULT_OUTPUT_CEILING);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
