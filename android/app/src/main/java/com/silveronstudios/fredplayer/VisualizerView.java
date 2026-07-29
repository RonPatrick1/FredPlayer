package com.silveronstudios.fredplayer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

final class VisualizerView extends View {
    private static final int BACKGROUND = Color.rgb(14, 17, 20);
    private static final int GRID = Color.rgb(45, 51, 57);
    private static final int WAVE = Color.rgb(118, 222, 190);
    private static final int EMPTY = Color.rgb(86, 93, 101);

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path wavePath = new Path();
    private byte[] waveform = new byte[0];
    private float[] spectrum = new float[0];
    private float smoothing = VisualizationSettings.DEFAULT_SMOOTHING;

    VisualizerView(Context context) {
        super(context);
        setWillNotDraw(false);
    }

    void setSmoothing(float smoothing) {
        this.smoothing = Math.max(0f, Math.min(0.9f, smoothing));
    }

    void update(byte[] nextWaveform, byte[] nextSpectrum) {
        if (nextWaveform != null) {
            waveform = nextWaveform;
        }
        if (nextSpectrum != null) {
            if (spectrum.length != nextSpectrum.length) {
                spectrum = new float[nextSpectrum.length];
            }
            for (int i = 0; i < nextSpectrum.length; i++) {
                float target = (nextSpectrum[i] & 0xFF) / 100f;
                spectrum[i] = spectrum[i] * smoothing + target * (1f - smoothing);
            }
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(BACKGROUND);
        canvas.drawRoundRect(0, 0, width, height, 8, 8, paint);

        float waveTop = 8f;
        float waveHeight = height * 0.44f;
        float spectrumTop = waveTop + waveHeight + 12f;
        float spectrumHeight = height - spectrumTop - 10f;

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1f);
        paint.setColor(GRID);
        canvas.drawLine(10f, waveTop + waveHeight / 2f, width - 10f, waveTop + waveHeight / 2f, paint);

        drawWaveform(canvas, width, waveTop, waveHeight);
        drawSpectrum(canvas, width, spectrumTop, spectrumHeight);
    }

    private void drawWaveform(Canvas canvas, int width, float top, float height) {
        if (waveform.length < 2) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2f);
            paint.setColor(EMPTY);
            canvas.drawLine(12f, top + height / 2f, width - 12f, top + height / 2f, paint);
            return;
        }

        wavePath.reset();
        float usableWidth = Math.max(1f, width - 24f);
        float center = top + height / 2f;
        float amplitude = height * 0.45f;
        for (int i = 0; i < waveform.length; i++) {
            float x = 12f + usableWidth * i / Math.max(1, waveform.length - 1);
            float y = center - (waveform[i] / 127f) * amplitude;
            if (i == 0) {
                wavePath.moveTo(x, y);
            } else {
                wavePath.lineTo(x, y);
            }
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3f);
        paint.setColor(WAVE);
        canvas.drawPath(wavePath, paint);
    }

    private void drawSpectrum(Canvas canvas, int width, float top, float height) {
        int bars = spectrum.length;
        if (bars == 0) {
            return;
        }
        float gap = Math.max(1f, width / 220f);
        float left = 12f;
        float usableWidth = Math.max(1f, width - 24f);
        float barWidth = Math.max(2f, (usableWidth - gap * (bars - 1)) / bars);

        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < bars; i++) {
            float value = Math.max(0.02f, Math.min(1f, spectrum[i]));
            float barHeight = value * height;
            float x = left + i * (barWidth + gap);
            float hue = 165f + (130f * i / Math.max(1, bars - 1));
            paint.setColor(Color.HSVToColor(new float[]{hue, 0.72f, 0.92f}));
            canvas.drawRoundRect(x, top + height - barHeight, x + barWidth, top + height, 3f, 3f, paint);
        }
    }
}
