from __future__ import annotations

from dataclasses import dataclass
import math
import struct
from typing import Optional


TARGET_RMS = 0.18
SILENCE_FLOOR = 0.004


def clamp(value: float, minimum: float, maximum: float) -> float:
    return max(minimum, min(maximum, value))


@dataclass(frozen=True)
class LevelingSettings:
    analysis_seconds: float = 10.0
    level_attack_ms: float = 15.0
    level_release_ms: float = 750.0
    gain_down_ms: float = 40.0
    gain_up_ms: float = 2800.0
    compressor_threshold: float = 0.68
    output_ceiling: float = 0.96

    def __post_init__(self) -> None:
        object.__setattr__(self, "analysis_seconds", clamp(float(self.analysis_seconds), 0.0, 45.0))
        object.__setattr__(self, "level_attack_ms", clamp(float(self.level_attack_ms), 1.0, 250.0))
        object.__setattr__(self, "level_release_ms", clamp(float(self.level_release_ms), 100.0, 5000.0))
        object.__setattr__(self, "gain_down_ms", clamp(float(self.gain_down_ms), 5.0, 500.0))
        object.__setattr__(self, "gain_up_ms", clamp(float(self.gain_up_ms), 500.0, 10000.0))
        object.__setattr__(
            self,
            "compressor_threshold",
            clamp(float(self.compressor_threshold), 0.3, 0.95),
        )
        object.__setattr__(self, "output_ceiling", clamp(float(self.output_ceiling), 0.5, 1.0))

    @classmethod
    def from_dict(cls, data: dict) -> "LevelingSettings":
        return cls(
            analysis_seconds=data.get("analysis_seconds", cls().analysis_seconds),
            level_attack_ms=data.get("level_attack_ms", cls().level_attack_ms),
            level_release_ms=data.get("level_release_ms", cls().level_release_ms),
            gain_down_ms=data.get("gain_down_ms", cls().gain_down_ms),
            gain_up_ms=data.get("gain_up_ms", cls().gain_up_ms),
            compressor_threshold=data.get("compressor_threshold", cls().compressor_threshold),
            output_ceiling=data.get("output_ceiling", cls().output_ceiling),
        )

    def to_dict(self) -> dict:
        return {
            "analysis_seconds": self.analysis_seconds,
            "level_attack_ms": self.level_attack_ms,
            "level_release_ms": self.level_release_ms,
            "gain_down_ms": self.gain_down_ms,
            "gain_up_ms": self.gain_up_ms,
            "compressor_threshold": self.compressor_threshold,
            "output_ceiling": self.output_ceiling,
        }


@dataclass(frozen=True)
class TrackProfile:
    rms: float
    peak: float

    @classmethod
    def from_dict(cls, data: dict) -> Optional["TrackProfile"]:
        try:
            return cls(float(data["rms"]), float(data["peak"]))
        except (KeyError, TypeError, ValueError):
            return None

    def to_dict(self) -> dict:
        return {"rms": self.rms, "peak": self.peak}


class ProfileMeter:
    def __init__(self) -> None:
        self.sum_squares = 0.0
        self.peak = 0.0
        self.sample_count = 0

    def accept_f32le_stereo(self, data: bytes) -> None:
        usable = len(data) - (len(data) % 4)
        for (sample,) in struct.iter_unpack("<f", data[:usable]):
            amount = abs(sample)
            if amount > self.peak:
                self.peak = amount
            if amount >= SILENCE_FLOOR:
                self.sum_squares += sample * sample
                self.sample_count += 1

    def to_profile(self) -> TrackProfile:
        if self.sample_count == 0:
            return TrackProfile(TARGET_RMS, self.peak)
        return TrackProfile(math.sqrt(self.sum_squares / self.sample_count), self.peak)


class VolumeNormalizer:
    MIN_LEVEL = 0.012
    MAX_GAIN = 4.5
    MIN_GAIN = 0.2
    COMPRESSOR_RATIO = 6.0

    def __init__(self, sample_rate: int, profile: Optional[TrackProfile], settings: LevelingSettings) -> None:
        self.sample_rate = max(1, int(sample_rate))
        self.settings: Optional[LevelingSettings] = None
        self.level_attack = 0.0
        self.level_release = 0.0
        self.gain_down = 0.0
        self.gain_up = 0.0
        self.compressor_threshold = settings.compressor_threshold
        self.output_ceiling = settings.output_ceiling
        self.envelope = TARGET_RMS
        self.gain = 1.0
        self.update_settings(settings)
        if profile is not None:
            measured = max(profile.rms, profile.peak * 0.35)
            self.envelope = max(self.MIN_LEVEL, min(0.8, measured))
            self.gain = max(self.MIN_GAIN, min(self.MAX_GAIN, TARGET_RMS / max(self.MIN_LEVEL, self.envelope)))

    def update_settings(self, settings: LevelingSettings) -> None:
        if settings == self.settings:
            return
        self.settings = settings
        self.level_attack = self._coefficient(settings.level_attack_ms / 1000.0)
        self.level_release = self._coefficient(settings.level_release_ms / 1000.0)
        self.gain_down = self._coefficient(settings.gain_down_ms / 1000.0)
        self.gain_up = self._coefficient(settings.gain_up_ms / 1000.0)
        self.compressor_threshold = settings.compressor_threshold
        self.output_ceiling = settings.output_ceiling

    def next_gain(self, left: float, right: float, strength: float) -> float:
        rms = math.sqrt((left * left + right * right) * 0.5)
        peak = max(abs(left), abs(right))
        instant = max(rms, peak * 0.35)
        level_alpha = self.level_attack if instant > self.envelope else self.level_release
        self.envelope += (instant - self.envelope) * level_alpha

        desired = TARGET_RMS / max(self.MIN_LEVEL, self.envelope)
        desired = max(self.MIN_GAIN, min(self.MAX_GAIN, desired))
        desired = 1.0 + ((desired - 1.0) * clamp(strength, 0.0, 1.0))
        gain_alpha = self.gain_down if desired < self.gain else self.gain_up
        self.gain += (desired - self.gain) * gain_alpha
        return float(self.gain)

    def protect(self, value: float) -> float:
        sign = -1.0 if value < 0.0 else 1.0
        amount = abs(value)
        if amount > self.compressor_threshold:
            amount = self.compressor_threshold + ((amount - self.compressor_threshold) / self.COMPRESSOR_RATIO)
        if amount > self.output_ceiling:
            amount = self.output_ceiling
        return sign * amount

    def _coefficient(self, seconds: float) -> float:
        return 1.0 - math.exp(-1.0 / (self.sample_rate * max(seconds, 0.000001)))
