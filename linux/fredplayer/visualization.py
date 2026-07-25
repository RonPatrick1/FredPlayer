from __future__ import annotations

from collections import deque
from dataclasses import dataclass
import math
import time
from typing import Callable

try:
    import numpy as np
except Exception:  # pragma: no cover - optional runtime acceleration
    np = None


DEFAULT_FFT_SIZE = 4096
FFT_SIZE_OPTIONS = (512, 1024, 2048, DEFAULT_FFT_SIZE, 8192, 16384, 32768)
DEFAULT_WAVEFORM_POINTS = 512


@dataclass(frozen=True)
class VisualizationFrame:
    waveform: tuple[float, ...]
    spectrum: tuple[float, ...]
    peak: float
    rms: float


@dataclass(frozen=True)
class SpectrumProfile:
    sample_rate: int
    fps: float
    fft_size: int
    bands: int
    fft_scale: str
    frame_count: int
    payload: bytes

    def matches(self, settings: "VisualizationSettings", sample_rate: int) -> bool:
        return (
            self.sample_rate == sample_rate
            and abs(self.fps - settings.update_fps) < 0.001
            and self.fft_size == settings.fft_size
            and self.bands == settings.fft_columns
            and self.fft_scale == settings.fft_scale
            and len(self.payload) == self.frame_count * self.bands
        )

    def spectrum_at_sample(self, sample_index: int, sample_rate: int) -> tuple[float, ...]:
        if self.frame_count <= 0 or self.bands <= 0:
            return ()
        seconds = max(0.0, sample_index / max(1, sample_rate))
        frame_index = max(0, min(self.frame_count - 1, int(round(seconds * self.fps))))
        start = frame_index * self.bands
        end = start + self.bands
        return tuple(value / 255.0 for value in self.payload[start:end])

    def to_dict(self) -> dict:
        return {
            "sample_rate": self.sample_rate,
            "fps": self.fps,
            "fft_size": self.fft_size,
            "bands": self.bands,
            "fft_scale": self.fft_scale,
            "frame_count": self.frame_count,
        }

    @classmethod
    def from_dict(cls, data: object, payload: bytes) -> "SpectrumProfile | None":
        if not isinstance(data, dict):
            return None
        try:
            profile = cls(
                sample_rate=int(data["sample_rate"]),
                fps=float(data["fps"]),
                fft_size=int(data["fft_size"]),
                bands=int(data["bands"]),
                fft_scale=str(data["fft_scale"]),
                frame_count=int(data["frame_count"]),
                payload=payload,
            )
        except (KeyError, TypeError, ValueError):
            return None
        if len(payload) != profile.frame_count * profile.bands:
            return None
        return profile


@dataclass(frozen=True)
class WaveformProfile:
    sample_rate: int
    fps: float
    waveform_window_ms: float
    waveform_points: int
    frame_count: int
    payload: bytes

    def matches(
        self,
        settings: "VisualizationSettings",
        sample_rate: int,
        waveform_points: int = DEFAULT_WAVEFORM_POINTS,
    ) -> bool:
        return (
            self.sample_rate == sample_rate
            and abs(self.fps - settings.update_fps) < 0.001
            and abs(self.waveform_window_ms - settings.waveform_window_ms) < 0.001
            and self.waveform_points == waveform_points
            and len(self.payload) == self.frame_count * self.waveform_points
        )

    def waveform_at_sample(self, sample_index: int, sample_rate: int) -> tuple[float, ...]:
        if self.frame_count <= 0 or self.waveform_points <= 0:
            return ()
        seconds = max(0.0, sample_index / max(1, sample_rate))
        frame_index = max(0, min(self.frame_count - 1, int(round(seconds * self.fps))))
        start = frame_index * self.waveform_points
        end = start + self.waveform_points
        row = self.payload[start:end]
        if np is not None:
            return tuple((np.frombuffer(row, dtype=np.int8).astype(np.float32) / 127.0).tolist())
        return tuple(((value - 256) if value >= 128 else value) / 127.0 for value in row)

    def to_dict(self) -> dict:
        return {
            "sample_rate": self.sample_rate,
            "fps": self.fps,
            "waveform_window_ms": self.waveform_window_ms,
            "waveform_points": self.waveform_points,
            "frame_count": self.frame_count,
        }

    @classmethod
    def from_dict(cls, data: object, payload: bytes) -> "WaveformProfile | None":
        if not isinstance(data, dict):
            return None
        try:
            profile = cls(
                sample_rate=int(data["sample_rate"]),
                fps=float(data["fps"]),
                waveform_window_ms=float(data["waveform_window_ms"]),
                waveform_points=int(data["waveform_points"]),
                frame_count=int(data["frame_count"]),
                payload=payload,
            )
        except (KeyError, TypeError, ValueError):
            return None
        if len(payload) != profile.frame_count * profile.waveform_points:
            return None
        return profile


@dataclass(frozen=True)
class VisualizationSettings:
    update_fps: float = 30.0
    waveform_window_ms: float = 80.0
    fft_scale: str = "log"
    fft_columns: int = 96
    fft_size: int = DEFAULT_FFT_SIZE
    fft_smoothing: float = 15.0

    def __post_init__(self) -> None:
        object.__setattr__(self, "update_fps", _clamp(float(self.update_fps), 5.0, 144.0))
        object.__setattr__(
            self,
            "waveform_window_ms",
            _clamp(float(self.waveform_window_ms), 10.0, 500.0),
        )
        scale = str(self.fft_scale).lower()
        object.__setattr__(self, "fft_scale", "linear" if scale == "linear" else "log")
        object.__setattr__(self, "fft_columns", int(_clamp(float(self.fft_columns), 24.0, 256.0)))
        object.__setattr__(self, "fft_size", _nearest_fft_size(self.fft_size))
        object.__setattr__(self, "fft_smoothing", _clamp(float(self.fft_smoothing), 0.0, 100.0))

    @classmethod
    def from_dict(cls, data: object) -> "VisualizationSettings":
        if not isinstance(data, dict):
            return cls()
        return cls(
            update_fps=data.get("update_fps", cls().update_fps),
            waveform_window_ms=data.get("waveform_window_ms", cls().waveform_window_ms),
            fft_scale=data.get("fft_scale", cls().fft_scale),
            fft_columns=data.get("fft_columns", cls().fft_columns),
            fft_size=data.get("fft_size", cls().fft_size),
            fft_smoothing=data.get("fft_smoothing", cls().fft_smoothing),
        )

    def to_dict(self) -> dict:
        return {
            "update_fps": self.update_fps,
            "waveform_window_ms": self.waveform_window_ms,
            "fft_scale": self.fft_scale,
            "fft_columns": self.fft_columns,
            "fft_size": self.fft_size,
            "fft_smoothing": self.fft_smoothing,
        }


class RealtimeAnalyzer:
    def __init__(
        self,
        sample_rate: int,
        fft_size: int = 1024,
        waveform_points: int = 768,
        bands: int | None = None,
        settings: VisualizationSettings | None = None,
        max_fps: float | None = None,
    ) -> None:
        self.sample_rate = max(1, sample_rate)
        if settings is None:
            settings = VisualizationSettings(
                update_fps=max_fps or VisualizationSettings().update_fps,
                fft_size=fft_size,
            )
        elif max_fps is not None:
            settings = VisualizationSettings(
                update_fps=max_fps,
                waveform_window_ms=settings.waveform_window_ms,
                fft_scale=settings.fft_scale,
                fft_columns=settings.fft_columns,
                fft_size=settings.fft_size,
                fft_smoothing=settings.fft_smoothing,
            )
        self.settings = settings
        self.fft_size = _nearest_fft_size(settings.fft_size or fft_size)
        self.waveform_points = waveform_points
        self.waveform_window_samples = self._window_samples(settings.waveform_window_ms)
        self.bands = int(bands or settings.fft_columns)
        self.min_interval = 1.0 / settings.update_fps
        self.emit_sample_interval = max(1, int(self.sample_rate / max(30.0, settings.update_fps * 2.0)))
        self.cached_emit_sample_interval = max(1, int(round(self.sample_rate / settings.update_fps)))
        self.samples: deque[float] = deque(maxlen=max(self.fft_size, self.waveform_window_samples))
        self.frames_since_emit = 0
        self.last_emit_at = 0.0
        if np is not None:
            self.window = np.hanning(self.fft_size).astype(np.float32)
            self.window_sum = float(self.window.sum()) or 1.0
        else:
            self.window = tuple(
                0.5 - (0.5 * math.cos((2.0 * math.pi * index) / (self.fft_size - 1)))
                for index in range(self.fft_size)
            )
            self.window_sum = sum(self.window) or 1.0

    def reset(self) -> None:
        self.samples.clear()
        self.frames_since_emit = 0
        self.last_emit_at = 0.0

    def accept(self, left: float, right: float) -> VisualizationFrame | None:
        mono = max(-1.0, min(1.0, (left + right) * 0.5))
        self.samples.append(mono)
        self.frames_since_emit += 1
        if self.frames_since_emit < self.emit_sample_interval:
            return None
        now = time.monotonic()
        if now - self.last_emit_at < self.min_interval:
            return None
        self.frames_since_emit = 0
        self.last_emit_at = now
        return self.snapshot()

    def accept_with_spectrum(
        self,
        left: float,
        right: float,
        spectrum: tuple[float, ...],
    ) -> VisualizationFrame | None:
        mono = max(-1.0, min(1.0, (left + right) * 0.5))
        self.samples.append(mono)
        self.frames_since_emit += 1
        if self.frames_since_emit < self.cached_emit_sample_interval:
            return None
        self.frames_since_emit = 0
        return self.snapshot_with_spectrum(spectrum)

    def accept_cached_waveform(self, left: float, right: float) -> VisualizationFrame | None:
        mono = max(-1.0, min(1.0, (left + right) * 0.5))
        self.samples.append(mono)
        self.frames_since_emit += 1
        if self.frames_since_emit < self.cached_emit_sample_interval:
            return None
        self.frames_since_emit = 0
        return self.snapshot_with_spectrum(())

    def snapshot(self) -> VisualizationFrame:
        waveform = self._waveform()
        spectrum = self._spectrum()
        return self._frame_from_waveform_and_spectrum(waveform, spectrum)

    def snapshot_with_spectrum(self, spectrum: tuple[float, ...]) -> VisualizationFrame:
        return self._frame_from_waveform_and_spectrum(self._waveform(), list(spectrum))

    def _frame_from_waveform_and_spectrum(
        self,
        waveform: list[float],
        spectrum: list[float] | tuple[float, ...],
    ) -> VisualizationFrame:
        if waveform:
            peak = max(abs(value) for value in waveform)
            rms = math.sqrt(sum(value * value for value in waveform) / len(waveform))
        else:
            peak = 0.0
            rms = 0.0
        return VisualizationFrame(waveform=tuple(waveform), spectrum=tuple(spectrum), peak=peak, rms=rms)

    def silence(self) -> VisualizationFrame:
        return VisualizationFrame(
            waveform=tuple(0.0 for _ in range(self.waveform_points)),
            spectrum=tuple(0.0 for _ in range(self.bands)),
            peak=0.0,
            rms=0.0,
        )

    def _waveform(self) -> list[float]:
        values = list(self.samples)[-self.waveform_window_samples :]
        if len(values) < self.waveform_window_samples:
            values = ([0.0] * (self.waveform_window_samples - len(values))) + values
        if len(values) <= self.waveform_points:
            if len(values) < self.waveform_points:
                values = ([0.0] * (self.waveform_points - len(values))) + values
            return values

        # Preserve extrema when downsampling so short transients remain visible.
        result: list[float] = []
        step = len(values) / self.waveform_points
        for point in range(self.waveform_points):
            start = int(point * step)
            end = max(start + 1, int((point + 1) * step))
            bucket = values[start:end]
            positive = max(bucket)
            negative = min(bucket)
            result.append(positive if abs(positive) >= abs(negative) else negative)
        return result

    def _spectrum(self) -> list[float]:
        samples = list(self.samples)[-self.fft_size :]
        if len(samples) < self.fft_size:
            samples = ([0.0] * (self.fft_size - len(samples))) + samples

        if np is not None:
            windowed = np.asarray(samples, dtype=np.float32) * self.window
            magnitudes = (2.0 * np.abs(np.fft.rfft(windowed))[1:]) / self.window_sum
        else:
            values = [complex(sample * self.window[index], 0.0) for index, sample in enumerate(samples)]
            _fft_in_place(values)
            magnitudes = [
                (2.0 * abs(values[index])) / self.window_sum
                for index in range(1, self.fft_size // 2)
            ]

        nyquist = self.sample_rate * 0.5
        low_hz = 32.0
        high_hz = min(18_000.0, nyquist)
        if high_hz <= low_hz:
            high_hz = nyquist

        bands: list[float] = []
        for band in range(self.bands):
            if self.settings.fft_scale == "linear":
                start_hz = low_hz + ((high_hz - low_hz) * band / self.bands)
                end_hz = low_hz + ((high_hz - low_hz) * (band + 1) / self.bands)
            else:
                start_hz = low_hz * ((high_hz / low_hz) ** (band / self.bands))
                end_hz = low_hz * ((high_hz / low_hz) ** ((band + 1) / self.bands))
            start_bin = max(1, int(start_hz * self.fft_size / self.sample_rate))
            end_bin = max(start_bin + 1, int(end_hz * self.fft_size / self.sample_rate))
            end_bin = min(end_bin, len(magnitudes))
            if np is not None:
                amplitude = float(np.max(magnitudes[start_bin - 1 : end_bin], initial=0.0))
            else:
                amplitude = max(magnitudes[start_bin - 1 : end_bin], default=0.0)
            dbfs = 20.0 * math.log10(max(amplitude, 0.000001))
            bands.append(max(0.0, min(1.0, (dbfs + 80.0) / 80.0)))
        return bands

    def _window_samples(self, milliseconds: float) -> int:
        return max(1, int(self.sample_rate * milliseconds / 1000.0))


def _clamp(value: float, minimum: float, maximum: float) -> float:
    return max(minimum, min(maximum, value))


def _nearest_fft_size(value: object) -> int:
    try:
        requested = int(value)
    except (TypeError, ValueError):
        requested = DEFAULT_FFT_SIZE
    return min(FFT_SIZE_OPTIONS, key=lambda option: abs(option - requested))


def build_spectrum_profile(
    samples: object,
    sample_rate: int,
    settings: VisualizationSettings,
    progress: Callable[[float], None] | None = None,
) -> SpectrumProfile | None:
    if np is None:
        return None

    sample_rate = max(1, int(sample_rate))
    fft_size = _nearest_fft_size(settings.fft_size)
    bands = int(settings.fft_columns)
    fps = float(settings.update_fps)
    hop = max(1, int(round(sample_rate / fps)))
    mono = np.asarray(samples, dtype=np.float32)
    frame_count = max(1, int(math.ceil(len(mono) / hop)) + 1)

    window = np.hanning(fft_size).astype(np.float32)
    window_sum = float(window.sum()) or 1.0
    ranges = _spectrum_band_ranges(sample_rate, fft_size, bands, settings.fft_scale)
    output = bytearray(frame_count * bands)
    segment = np.zeros(fft_size, dtype=np.float32)
    if progress is not None:
        progress(0.0)

    for frame_index in range(frame_count):
        center = frame_index * hop
        _fill_centered_segment(segment, mono, center, fft_size)
        magnitudes = (2.0 * np.abs(np.fft.rfft(segment * window))[1:]) / window_sum
        row_start = frame_index * bands
        for band, (start_bin, end_bin) in enumerate(ranges):
            amplitude = float(np.max(magnitudes[start_bin - 1 : end_bin], initial=0.0))
            dbfs = 20.0 * math.log10(max(amplitude, 0.000001))
            value = max(0.0, min(1.0, (dbfs + 80.0) / 80.0))
            output[row_start + band] = int(round(value * 255.0))
        if progress is not None and (
            frame_index == frame_count - 1
            or frame_index % max(1, frame_count // 100) == 0
        ):
            progress((frame_index + 1) / frame_count)

    return SpectrumProfile(
        sample_rate=sample_rate,
        fps=fps,
        fft_size=fft_size,
        bands=bands,
        fft_scale=settings.fft_scale,
        frame_count=frame_count,
        payload=bytes(output),
    )


def _fill_centered_segment(segment: object, mono: object, center: int, fft_size: int) -> None:
    if np is None:
        return
    segment.fill(0.0)
    half = fft_size // 2
    start = center - half
    end = start + fft_size
    source_start = max(0, start)
    source_end = min(len(mono), end)
    if source_end <= source_start:
        return
    destination_start = source_start - start
    destination_end = destination_start + (source_end - source_start)
    segment[destination_start:destination_end] = mono[source_start:source_end]


def build_waveform_profile(
    samples: object,
    sample_rate: int,
    settings: VisualizationSettings,
    waveform_points: int = DEFAULT_WAVEFORM_POINTS,
    progress: Callable[[float], None] | None = None,
) -> WaveformProfile | None:
    if np is None:
        return None

    sample_rate = max(1, int(sample_rate))
    fps = float(settings.update_fps)
    hop = max(1, int(round(sample_rate / fps)))
    mono = np.asarray(samples, dtype=np.float32)
    frame_count = max(1, int(math.ceil(len(mono) / hop)) + 1)
    waveform_points = max(32, int(waveform_points))
    window_samples = max(1, int(sample_rate * settings.waveform_window_ms / 1000.0))
    output = np.zeros(frame_count * waveform_points, dtype=np.int8)

    if progress is not None:
        progress(0.0)
    for frame_index in range(frame_count):
        end = (frame_index * hop) + 1
        start = end - window_samples
        waveform = _downsample_waveform_window(mono, start, end, waveform_points)
        row_start = frame_index * waveform_points
        output[row_start : row_start + waveform_points] = np.rint(
            np.clip(waveform, -1.0, 1.0) * 127.0
        ).astype(np.int8)
        if progress is not None and (
            frame_index == frame_count - 1
            or frame_index % max(1, frame_count // 100) == 0
        ):
            progress((frame_index + 1) / frame_count)

    return WaveformProfile(
        sample_rate=sample_rate,
        fps=fps,
        waveform_window_ms=settings.waveform_window_ms,
        waveform_points=waveform_points,
        frame_count=frame_count,
        payload=output.tobytes(),
    )


def _downsample_waveform_window(
    mono: object,
    start: int,
    end: int,
    waveform_points: int,
) -> object:
    if np is None:
        return []
    result = np.zeros(waveform_points, dtype=np.float32)
    if end <= start:
        return result
    step = (end - start) / waveform_points
    sample_count = len(mono)
    for point in range(waveform_points):
        bucket_start = int(start + (point * step))
        bucket_end = max(bucket_start + 1, int(start + ((point + 1) * step)))
        source_start = max(0, bucket_start)
        source_end = min(sample_count, bucket_end)
        if source_end <= source_start:
            continue
        bucket = mono[source_start:source_end]
        positive = float(np.max(bucket, initial=0.0))
        negative = float(np.min(bucket, initial=0.0))
        result[point] = positive if abs(positive) >= abs(negative) else negative
    return result


def estimated_spectrum_cache_bytes(duration_seconds: float, settings: VisualizationSettings) -> int:
    frames = max(1, int(math.ceil(max(0.0, duration_seconds) * settings.update_fps)) + 1)
    return frames * settings.fft_columns


def estimated_waveform_cache_bytes(duration_seconds: float, settings: VisualizationSettings) -> int:
    frames = max(1, int(math.ceil(max(0.0, duration_seconds) * settings.update_fps)) + 1)
    return frames * DEFAULT_WAVEFORM_POINTS


def _spectrum_band_ranges(
    sample_rate: int,
    fft_size: int,
    bands: int,
    fft_scale: str,
) -> list[tuple[int, int]]:
    nyquist = sample_rate * 0.5
    low_hz = 32.0
    high_hz = min(18_000.0, nyquist)
    if high_hz <= low_hz:
        high_hz = nyquist

    ranges: list[tuple[int, int]] = []
    for band in range(bands):
        if fft_scale == "linear":
            start_hz = low_hz + ((high_hz - low_hz) * band / bands)
            end_hz = low_hz + ((high_hz - low_hz) * (band + 1) / bands)
        else:
            start_hz = low_hz * ((high_hz / low_hz) ** (band / bands))
            end_hz = low_hz * ((high_hz / low_hz) ** ((band + 1) / bands))
        start_bin = max(1, int(start_hz * fft_size / sample_rate))
        end_bin = max(start_bin + 1, int(end_hz * fft_size / sample_rate))
        end_bin = min(end_bin, fft_size // 2)
        ranges.append((start_bin, end_bin))
    return ranges


def _fft_in_place(values: list[complex]) -> None:
    size = len(values)
    j = 0
    for i in range(1, size):
        bit = size >> 1
        while j & bit:
            j ^= bit
            bit >>= 1
        j ^= bit
        if i < j:
            values[i], values[j] = values[j], values[i]

    length = 2
    while length <= size:
        angle = -2.0 * math.pi / length
        rotation = complex(math.cos(angle), math.sin(angle))
        for start in range(0, size, length):
            factor = 1.0 + 0.0j
            half = length // 2
            for offset in range(half):
                even = values[start + offset]
                odd = values[start + offset + half] * factor
                values[start + offset] = even + odd
                values[start + offset + half] = even - odd
                factor *= rotation
        length <<= 1
