from __future__ import annotations

from dataclasses import dataclass
import os
from pathlib import Path
import struct
import time
import threading
from typing import Callable, Optional

import gi
try:
    import numpy as np
except Exception:  # pragma: no cover - optional visualization acceleration
    np = None

gi.require_version("Gst", "1.0")
gi.require_version("GstApp", "1.0")
from gi.repository import Gst  # noqa: E402

from . import remote
from .leveling import LevelingSettings, ProfileMeter, TrackProfile, VolumeNormalizer
from .store import ProfileCache, SpectrumCache, WaveformCache, normalize_path
from .visualization import (
    RealtimeAnalyzer,
    SpectrumProfile,
    VisualizationFrame,
    VisualizationSettings,
    WaveformProfile,
    build_spectrum_profile,
    build_waveform_profile,
    estimated_spectrum_cache_bytes,
    estimated_waveform_cache_bytes,
)


Gst.init(None)

SAMPLE_RATE = 48_000
CHANNELS = 2
BYTES_PER_SAMPLE = 4
FRAME_BYTES = CHANNELS * BYTES_PER_SAMPLE
PCM_CAPS = Gst.Caps.from_string(
    f"audio/x-raw,format=F32LE,channels={CHANNELS},rate={SAMPLE_RATE},layout=interleaved"
)
PULL_TIMEOUT_NS = 100 * Gst.MSECOND
OUTPUT_BUFFER_SECONDS = 0.2
OUTPUT_BUFFER_BYTES = int(SAMPLE_RATE * FRAME_BYTES * OUTPUT_BUFFER_SECONDS)
OUTPUT_BUFFER_TIME_NS = int(OUTPUT_BUFFER_SECONDS * Gst.SECOND)
ProgressCallback = Callable[[float], None]


def clean_error(error: BaseException) -> str:
    message = str(error).strip() or "Playback failed"
    return message[:120]


class PercentReporter:
    def __init__(self, label: str, callback: Callable[[str], None]) -> None:
        self.label = label
        self.callback = callback
        self.last_percent = -1
        self.last_at = 0.0

    def __call__(self, fraction: float) -> None:
        percent = max(0, min(100, int(round(fraction * 100.0))))
        now = time.monotonic()
        if percent == self.last_percent:
            return
        if percent < 100 and now - self.last_at < 0.35 and percent < self.last_percent + 2:
            return
        self.last_percent = percent
        self.last_at = now
        self.callback(f"{self.label} {percent}%")


def precompute_worker_count() -> int:
    raw = os.environ.get("FREDPLAYER_PRECOMPUTE_WORKERS", "").strip()
    if raw:
        try:
            return max(1, min(8, int(raw)))
        except ValueError:
            pass
    cpu_count = os.cpu_count() or 2
    return min(2, max(1, cpu_count // 8))


def set_low_thread_priority() -> None:
    try:
        os.setpriority(os.PRIO_PROCESS, threading.get_native_id(), 10)
    except Exception:
        pass


def analyze_loudness_profile(
    path: str,
    seconds: float,
    stop_event: threading.Event,
    progress: Optional[ProgressCallback] = None,
    token: str = "",
) -> Optional[TrackProfile]:
    pipeline, appsink = build_decode_pipeline(path, "fred-background-loudness-sink", token)
    meter = ProfileMeter()
    limit_frames = max(0, int(seconds * SAMPLE_RATE))
    decoded_frames = 0
    bus = pipeline.get_bus()
    eos_seen = False

    pipeline.set_state(Gst.State.PLAYING)
    if progress is not None:
        progress(0.0)
    try:
        while not stop_event.is_set() and decoded_frames < limit_frames:
            eos_seen = poll_bus(bus) or eos_seen
            sample = appsink.emit("try-pull-sample", PULL_TIMEOUT_NS)
            if sample is None:
                eos_seen = poll_bus(bus) or eos_seen
                if eos_seen:
                    break
                continue

            data = sample_bytes(sample)
            remaining_frames = limit_frames - decoded_frames
            usable_bytes = min(len(data) - (len(data) % FRAME_BYTES), remaining_frames * FRAME_BYTES)
            if usable_bytes <= 0:
                continue
            meter.accept_f32le_stereo(data[:usable_bytes])
            decoded_frames += usable_bytes // FRAME_BYTES
            if progress is not None and limit_frames > 0:
                progress(decoded_frames / limit_frames)
    finally:
        pipeline.set_state(Gst.State.NULL)

    if stop_event.is_set():
        return None
    if progress is not None:
        progress(1.0)
    return meter.to_profile()


def decode_mono_samples(
    path: str,
    sink_name: str,
    stop_event: threading.Event,
    progress: Optional[ProgressCallback] = None,
    token: str = "",
) -> Optional[object]:
    if np is None:
        return None
    pipeline, appsink = build_decode_pipeline(path, sink_name, token)
    bus = pipeline.get_bus()
    eos_seen = False
    chunks = []
    decoded_frames = 0
    duration_frames: Optional[int] = None

    pipeline.set_state(Gst.State.PLAYING)
    if progress is not None:
        progress(0.0)
    try:
        while not stop_event.is_set():
            eos_seen = poll_bus(bus) or eos_seen
            if duration_frames is None:
                duration_frames = query_duration_frames(pipeline)
            sample = appsink.emit("try-pull-sample", PULL_TIMEOUT_NS)
            if sample is None:
                eos_seen = poll_bus(bus) or eos_seen
                if eos_seen:
                    break
                continue

            data = sample_bytes(sample)
            usable = len(data) - (len(data) % FRAME_BYTES)
            if usable <= 0:
                continue
            frames = np.frombuffer(data[:usable], dtype="<f4").reshape(-1, CHANNELS)
            chunks.append(frames.mean(axis=1).astype(np.float32, copy=False))
            decoded_frames += usable // FRAME_BYTES
            if progress is not None and duration_frames:
                progress(decoded_frames / duration_frames)
    finally:
        pipeline.set_state(Gst.State.NULL)

    if stop_event.is_set():
        return None
    if progress is not None:
        progress(1.0)
    if not chunks:
        return np.zeros(1, dtype=np.float32)
    return np.concatenate(chunks)


def analyze_visual_profiles(
    path: str,
    settings: VisualizationSettings,
    stop_event: threading.Event,
    need_spectrum: bool,
    need_waveform: bool,
    progress: Optional[ProgressCallback] = None,
    token: str = "",
) -> tuple[Optional[SpectrumProfile], Optional[WaveformProfile]]:
    if np is None or (not need_spectrum and not need_waveform):
        return None, None

    samples = decode_mono_samples(
        path,
        "fred-background-visual-sink",
        stop_event,
        (lambda fraction: progress(0.45 * fraction)) if progress is not None else None,
        token,
    )
    if stop_event.is_set() or samples is None:
        return None, None
    if progress is not None:
        progress(0.45)

    remaining = int(need_spectrum) + int(need_waveform)
    span = 0.55 / max(1, remaining)
    cursor = 0.45
    spectrum: Optional[SpectrumProfile] = None
    waveform: Optional[WaveformProfile] = None

    if need_spectrum:
        start = cursor
        spectrum = build_spectrum_profile(
            samples,
            SAMPLE_RATE,
            settings,
            progress=(lambda fraction, start=start: progress(start + (span * fraction)))
            if progress is not None
            else None,
        )
        cursor += span
    if need_waveform:
        start = cursor
        waveform = build_waveform_profile(
            samples,
            SAMPLE_RATE,
            settings,
            progress=(lambda fraction, start=start: progress(start + (span * fraction)))
            if progress is not None
            else None,
        )

    if progress is not None:
        progress(1.0)
    return spectrum, waveform


def analyze_spectrum_profile(
    path: str,
    settings: VisualizationSettings,
    stop_event: threading.Event,
    progress: Optional[ProgressCallback] = None,
) -> Optional[SpectrumProfile]:
    spectrum, _waveform = analyze_visual_profiles(
        path,
        settings,
        stop_event,
        need_spectrum=True,
        need_waveform=False,
        progress=progress,
    )
    return spectrum


def _configure_source_uri(source: Gst.Element, path: str, token: str = "") -> None:
    """Points a uridecodebin's source at either a local file (unchanged
    behavior) or a remote http(s) URL, in which case the FredPlayer media
    server's bearer token is attached via uridecodebin's source-setup
    signal — souphttpsrc (the element it creates for http/https URIs)
    exposes an 'extra-headers' GstStructure property for exactly this."""
    if remote.is_remote(path):
        source.set_property("uri", path)
        if token:
            def _on_source_setup(_decodebin: Gst.Element, inner_source: Gst.Element) -> None:
                try:
                    if inner_source.find_property("extra-headers") is not None:
                        headers = Gst.Structure.new_empty("extra-headers")
                        headers.set_value("Authorization", f"Bearer {token}")
                        inner_source.set_property("extra-headers", headers)
                except Exception:
                    pass

            source.connect("source-setup", _on_source_setup)
    else:
        source.set_property("uri", Gst.filename_to_uri(path))


def build_decode_pipeline(path: str, sink_name: str, token: str = "") -> tuple[Gst.Pipeline, Gst.Element]:
    pipeline = Gst.Pipeline.new(None)
    source = make_element("uridecodebin", "source")
    queue = make_element("queue", "decode-queue")
    convert = make_element("audioconvert", "decode-convert")
    resample = make_element("audioresample", "decode-resample")
    capsfilter = make_element("capsfilter", "decode-caps")
    appsink = make_element("appsink", sink_name)

    _configure_source_uri(source, path, token)
    source.set_property("caps", Gst.Caps.from_string("audio/x-raw"))
    queue.set_property("max-size-buffers", 32)
    queue.set_property("max-size-bytes", 0)
    queue.set_property("max-size-time", 0)
    capsfilter.set_property("caps", PCM_CAPS)
    appsink.set_property("emit-signals", False)
    appsink.set_property("sync", False)
    appsink.set_property("max-buffers", 16)
    appsink.set_property("drop", False)

    for element in (source, queue, convert, resample, capsfilter, appsink):
        pipeline.add(element)
    if not queue.link(convert) or not convert.link(resample) or not resample.link(capsfilter) or not capsfilter.link(appsink):
        raise RuntimeError("Failed to build decode pipeline")
    source.connect("pad-added", on_decode_pad_added, queue)
    return pipeline, appsink


def on_decode_pad_added(_source: Gst.Element, pad: Gst.Pad, queue: Gst.Element) -> None:
    sink_pad = queue.get_static_pad("sink")
    if sink_pad is None or sink_pad.is_linked():
        return
    result = pad.link(sink_pad)
    if result != Gst.PadLinkReturn.OK:
        return


def make_element(factory: str, name: str) -> Gst.Element:
    element = Gst.ElementFactory.make(factory, name)
    if element is None:
        raise RuntimeError(f"Missing GStreamer element: {factory}")
    return element


def sample_bytes(sample: Gst.Sample) -> bytes:
    buffer = sample.get_buffer()
    if buffer is None:
        return b""
    ok, info = buffer.map(Gst.MapFlags.READ)
    if not ok:
        return b""
    try:
        return bytes(info.data)
    finally:
        buffer.unmap(info)


def poll_bus(bus: Gst.Bus) -> bool:
    saw_eos = False
    while True:
        message = bus.pop()
        if message is None:
            return saw_eos
        if message.type == Gst.MessageType.ERROR:
            error, _debug = message.parse_error()
            raise RuntimeError(error.message)
        if message.type == Gst.MessageType.EOS:
            saw_eos = True


def query_duration_frames(pipeline: Gst.Pipeline) -> Optional[int]:
    try:
        ok, duration = pipeline.query_duration(Gst.Format.TIME)
    except Exception:
        return None
    if not ok or duration <= 0:
        return None
    return max(1, int(duration * SAMPLE_RATE / Gst.SECOND))


@dataclass
class PlayerCallbacks:
    on_track_started: Callable[[str], None]
    on_track_finished: Callable[[str], None]
    on_error: Callable[[str, str], None]
    on_status: Callable[[str, str], None]
    on_visualization: Callable[[VisualizationFrame], None] = lambda _frame: None


@dataclass
class PrecomputeCallbacks:
    on_status: Callable[[str, str], None] = lambda _path, _status: None


class PlaybackRun:
    def __init__(self, path: str, start_position_ns: int = 0) -> None:
        self.stop_event = threading.Event()
        self.path = path
        self.start_position_ns = max(0, int(start_position_ns))


@dataclass(frozen=True)
class PlaybackSnapshot:
    position_ms: int = 0
    duration_ms: int = 0


class BackgroundPrecomputer:
    def __init__(self, callbacks: PrecomputeCallbacks) -> None:
        self.callbacks = callbacks
        self.profile_cache = ProfileCache()
        self.spectrum_cache = SpectrumCache()
        self.waveform_cache = WaveformCache()
        self.cache_lock = threading.Lock()
        self.condition = threading.Condition()
        self.stop_event = threading.Event()
        self.paths: tuple[str, ...] = ()
        self.leveling_settings = LevelingSettings()
        self.visualization_settings = VisualizationSettings()
        self.server_base_url = ""
        self.server_token = ""
        self.generation = 0
        self.next_index = 0
        self.worker_count = precompute_worker_count()
        self.workers = [
            threading.Thread(
                target=self._worker_loop,
                name=f"FredPlayerPrecompute-{index + 1}",
                daemon=True,
            )
            for index in range(self.worker_count)
        ]
        for worker in self.workers:
            worker.start()

    def update_playlist(
        self,
        paths: list[str] | tuple[str, ...],
        leveling_settings: LevelingSettings,
        visualization_settings: VisualizationSettings,
        server_base_url: str = "",
        server_token: str = "",
    ) -> None:
        unique_paths = tuple(dict.fromkeys(normalize_path(path) for path in paths))
        with self.condition:
            self.paths = unique_paths
            self.leveling_settings = leveling_settings
            self.visualization_settings = visualization_settings
            self.server_base_url = server_base_url
            self.server_token = server_token
            self.generation += 1
            self.next_index = 0
            self.condition.notify_all()

    def close(self) -> None:
        self.stop_event.set()
        with self.condition:
            self.condition.notify_all()
        for worker in self.workers:
            worker.join(timeout=1.5)

    def _worker_loop(self) -> None:
        set_low_thread_priority()
        while not self.stop_event.is_set():
            task = self._next_task()
            if task is None:
                continue
            generation, path, leveling_settings, visualization_settings, server_base_url, server_token = task
            try:
                self._precompute_track(path, leveling_settings, visualization_settings, server_base_url, server_token)
            except Exception:
                pass
            finally:
                time.sleep(0.05)
                if generation != self.generation:
                    continue

    def _next_task(
        self,
    ) -> tuple[int, str, LevelingSettings, VisualizationSettings, str, str] | None:
        with self.condition:
            while not self.stop_event.is_set():
                if self.next_index < len(self.paths):
                    path = self.paths[self.next_index]
                    self.next_index += 1
                    return (
                        self.generation,
                        path,
                        self.leveling_settings,
                        self.visualization_settings,
                        self.server_base_url,
                        self.server_token,
                    )
                self.condition.wait(timeout=1.0)
        return None

    def _precompute_track(
        self,
        path: str,
        leveling_settings: LevelingSettings,
        visualization_settings: VisualizationSettings,
        server_base_url: str = "",
        server_token: str = "",
    ) -> None:
        if self.stop_event.is_set():
            return
        is_remote_track = remote.is_remote(path) and bool(server_base_url)
        token = server_token if is_remote_track else ""

        if leveling_settings.analysis_seconds > 0:
            with self.cache_lock:
                has_loudness = self.profile_cache.get(path) is not None
            if not has_loudness and is_remote_track:
                remote_data = remote.fetch_profile(path, server_token)
                remote_profile = TrackProfile.from_dict(remote_data) if remote_data else None
                if remote_profile is not None:
                    with self.cache_lock:
                        if self.profile_cache.get(path) is None:
                            self.profile_cache.put(path, remote_profile)
                    has_loudness = True
            if not has_loudness:
                reporter = PercentReporter(
                    "Background cache: loudness",
                    lambda status: self.callbacks.on_status(path, status),
                )
                profile = analyze_loudness_profile(
                    path,
                    leveling_settings.analysis_seconds,
                    self.stop_event,
                    reporter,
                    token,
                )
                if profile is not None and not self.stop_event.is_set():
                    with self.cache_lock:
                        if self.profile_cache.get(path) is None:
                            self.profile_cache.put(path, profile)
                    if is_remote_track:
                        remote.upload_profile(path, server_token, profile.rms, profile.peak)

        if self.stop_event.is_set() or np is None:
            return
        with self.cache_lock:
            has_spectrum = self.spectrum_cache.get(path, visualization_settings) is not None
            has_waveform = self.waveform_cache.get(path, visualization_settings) is not None

        if is_remote_track and not has_spectrum:
            if self._fetch_remote_visual_cache(self.spectrum_cache, path, visualization_settings, ".fsp", ".spectrum", server_token):
                with self.cache_lock:
                    has_spectrum = self.spectrum_cache.get(path, visualization_settings) is not None
        if is_remote_track and not has_waveform:
            if self._fetch_remote_visual_cache(self.waveform_cache, path, visualization_settings, ".fwp", ".waveform", server_token):
                with self.cache_lock:
                    has_waveform = self.waveform_cache.get(path, visualization_settings) is not None
        if has_spectrum and has_waveform:
            return

        reporter = PercentReporter(
            "Background cache: visual",
            lambda status: self.callbacks.on_status(path, status),
        )
        spectrum, waveform = analyze_visual_profiles(
            path,
            visualization_settings,
            self.stop_event,
            need_spectrum=not has_spectrum,
            need_waveform=not has_waveform,
            progress=reporter,
            token=token,
        )
        if spectrum is not None and not self.stop_event.is_set():
            with self.cache_lock:
                if self.spectrum_cache.get(path, visualization_settings) is None:
                    self.spectrum_cache.put(path, visualization_settings, spectrum)
            if is_remote_track:
                self._upload_remote_visual_cache(self.spectrum_cache, path, visualization_settings, ".fsp", ".spectrum", server_token)
        if waveform is not None and not self.stop_event.is_set():
            with self.cache_lock:
                if self.waveform_cache.get(path, visualization_settings) is None:
                    self.waveform_cache.put(path, visualization_settings, waveform)
            if is_remote_track:
                self._upload_remote_visual_cache(self.waveform_cache, path, visualization_settings, ".fwp", ".waveform", server_token)

    @staticmethod
    def _fetch_remote_visual_cache(cache, path, settings, file_suffix, remote_key_suffix, token) -> bool:
        """Fetches a cached spectrum/waveform blob from the server and
        writes it to the exact local cache-file path so the existing
        get()'s on-disk parsing/validation (header + zlib payload) can
        read it back unchanged — same approach as the profile cache, just
        skipping straight to file bytes instead of a JSON dict."""
        data = remote.fetch_visual(path + remote_key_suffix, token)
        if not data:
            return False
        key = cache.cache_key(path, settings)
        if key is None:
            return False
        try:
            (cache.directory / f"{key}{file_suffix}").write_bytes(data)
        except OSError:
            return False
        return True

    @staticmethod
    def _upload_remote_visual_cache(cache, path, settings, file_suffix, remote_key_suffix, token) -> None:
        key = cache.cache_key(path, settings)
        if key is None:
            return
        try:
            data = (cache.directory / f"{key}{file_suffix}").read_bytes()
        except OSError:
            return
        remote.upload_visual(path + remote_key_suffix, token, data)


class NormalizingAudioPlayer:
    def __init__(self, callbacks: PlayerCallbacks) -> None:
        self.callbacks = callbacks
        self.profile_cache = ProfileCache()
        self.spectrum_cache = SpectrumCache()
        self.waveform_cache = WaveformCache()
        self.settings_lock = threading.Lock()
        self.pipeline_lock = threading.Lock()
        self.pause_condition = threading.Condition()
        self.playback_clock_lock = threading.Lock()

        self.output_level = 0.55
        self.leveling_strength = 0.9
        self.leveling_settings = LevelingSettings()
        self.visualization_settings = VisualizationSettings()
        self.server_base_url = ""
        self.server_token = ""
        self.paused = False
        self.released = False

        self.active_run: Optional[PlaybackRun] = None
        self.worker: Optional[threading.Thread] = None
        self.current_pipelines: list[Gst.Pipeline] = []
        self.playback_path = ""
        self.playback_position_base_ns = 0
        self.playback_duration_ns = 0
        self.playback_clock_pipeline: Optional[Gst.Pipeline] = None
        self.visual_clock_lock = threading.Lock()
        self.visual_clock_pipeline: Optional[Gst.Pipeline] = None
        self.visual_clock_profile: Optional[SpectrumProfile] = None
        self.visual_clock_waveform_profile: Optional[WaveformProfile] = None
        self.visual_clock_base_ns = 0
        self.visualizer = RealtimeAnalyzer(SAMPLE_RATE, settings=self.visualization_settings)

    def set_output_level(self, value: float) -> None:
        with self.settings_lock:
            self.output_level = max(0.1, min(1.0, float(value)))

    def set_leveling_strength(self, value: float) -> None:
        with self.settings_lock:
            self.leveling_strength = max(0.0, min(1.0, float(value)))

    def set_leveling_settings(self, settings: LevelingSettings) -> None:
        with self.settings_lock:
            self.leveling_settings = settings

    def set_server_config(self, base_url: str, token: str) -> None:
        with self.settings_lock:
            self.server_base_url = base_url
            self.server_token = token

    def set_visualization_settings(self, settings: VisualizationSettings) -> None:
        with self.settings_lock:
            self.visualization_settings = settings
            self.visualizer = RealtimeAnalyzer(SAMPLE_RATE, settings=settings)
        with self.visual_clock_lock:
            if (
                self.visual_clock_profile is not None
                and not self.visual_clock_profile.matches(settings, SAMPLE_RATE)
            ):
                self.visual_clock_profile = None
            if (
                self.visual_clock_waveform_profile is not None
                and not self.visual_clock_waveform_profile.matches(settings, SAMPLE_RATE)
            ):
                self.visual_clock_waveform_profile = None
            if self.visual_clock_profile is None and self.visual_clock_waveform_profile is None:
                self.visual_clock_pipeline = None

    def settings_snapshot(self) -> tuple[float, float, LevelingSettings, VisualizationSettings]:
        with self.settings_lock:
            return (
                self.output_level,
                self.leveling_strength,
                self.leveling_settings,
                self.visualization_settings,
            )

    def is_paused(self) -> bool:
        worker = self.worker
        return self.paused and worker is not None and worker.is_alive()

    def play(self, path: str | Path, position_ms: int = 0, paused: bool = False) -> None:
        self._stop(reset_clock=False)
        if self.released:
            return
        normalized = normalize_path(path)
        start_position_ns = max(0, int(position_ms)) * Gst.MSECOND
        run = PlaybackRun(normalized, start_position_ns)
        self.active_run = run
        with self.pause_condition:
            self.paused = bool(paused)
        with self.playback_clock_lock:
            previous_duration = (
                self.playback_duration_ns
                if self.playback_path == normalized
                else 0
            )
            self.playback_path = normalized
            self.playback_position_base_ns = start_position_ns
            self.playback_duration_ns = previous_duration
            self.playback_clock_pipeline = None
        self.worker = threading.Thread(
            target=self._run_playback,
            args=(normalized, run),
            name="FredPlayerAudio",
            daemon=True,
        )
        self.worker.start()

    def seek(self, position_ms: int) -> bool:
        with self.playback_clock_lock:
            path = self.playback_path
            duration_ns = self.playback_duration_ns
        worker = self.worker
        if not path or worker is None or not worker.is_alive() or duration_ns <= 0:
            return False
        target_ms = max(0, min(int(position_ms), int(duration_ns // Gst.MSECOND)))
        paused = self.is_paused()
        self.play(path, target_ms, paused=paused)
        return True

    def playback_snapshot(self) -> PlaybackSnapshot:
        with self.playback_clock_lock:
            pipeline = self.playback_clock_pipeline
            base_ns = self.playback_position_base_ns
            duration_ns = self.playback_duration_ns

        position_ns = base_ns
        if pipeline is not None:
            try:
                ok, pipeline_position = pipeline.query_position(Gst.Format.TIME)
                if ok and pipeline_position >= 0:
                    position_ns += int(pipeline_position)
            except Exception:
                pass
        if duration_ns > 0:
            position_ns = min(position_ns, duration_ns)
        return PlaybackSnapshot(
            position_ms=max(0, int(position_ns // Gst.MSECOND)),
            duration_ms=max(0, int(duration_ns // Gst.MSECOND)),
        )

    def pause(self) -> None:
        with self.pause_condition:
            self.paused = True
        self._set_current_pipeline_state(Gst.State.PAUSED)

    def resume(self) -> None:
        with self.pause_condition:
            self.paused = False
            self.pause_condition.notify_all()
        self._set_current_pipeline_state(Gst.State.PLAYING)

    def stop(self) -> None:
        self._stop(reset_clock=True)

    def _stop(self, reset_clock: bool) -> None:
        run = self.active_run
        if run is not None:
            run.stop_event.set()
        self._clear_visual_clock()
        with self.pause_condition:
            self.paused = False
            self.pause_condition.notify_all()
        self._set_current_pipeline_state(Gst.State.NULL)

        worker = self.worker
        if worker is not None and worker is not threading.current_thread():
            worker.join(timeout=1.5)
        if reset_clock:
            with self.playback_clock_lock:
                self.playback_path = ""
                self.playback_position_base_ns = 0
                self.playback_duration_ns = 0
                self.playback_clock_pipeline = None

    def release(self) -> None:
        self.released = True
        self.stop()

    def _run_playback(self, path: str, run: PlaybackRun) -> None:
        completed = False
        try:
            profile = self._profile_for_track(path, run)
            if run.stop_event.is_set():
                return
            self._play_decoded_stream(path, profile, run)
            completed = not run.stop_event.is_set()
        except Exception as error:
            if not run.stop_event.is_set() and not self.released and self.active_run is run:
                self.callbacks.on_error(path, clean_error(error))
        finally:
            if self.active_run is run:
                self._set_current_pipeline_state(Gst.State.NULL)
                self._clear_visual_clock()
                with self.pipeline_lock:
                    self.current_pipelines = []
                self.active_run = None
                self.worker = None

        if completed and not self.released and self.active_run is None:
            self.callbacks.on_track_finished(path)

    def _profile_for_track(self, path: str, run: PlaybackRun) -> Optional[TrackProfile]:
        cached = self.profile_cache.get(path)
        if cached is not None:
            return cached

        with self.settings_lock:
            base_url, token = self.server_base_url, self.server_token
        is_remote_track = remote.is_remote(path) and bool(base_url)
        if is_remote_track:
            remote_data = remote.fetch_profile(path, token)
            remote_profile = TrackProfile.from_dict(remote_data) if remote_data else None
            if remote_profile is not None:
                self.profile_cache.put(path, remote_profile)
                return remote_profile

        _, _, settings, _visualization_settings = self.settings_snapshot()
        if settings.analysis_seconds <= 0:
            return None

        reporter = PercentReporter(
            "Scanning loudness",
            lambda status: self.callbacks.on_status(path, status),
        )
        profile = self._analyze_track(path, settings.analysis_seconds, run, reporter)
        if not run.stop_event.is_set() and profile is not None:
            self.profile_cache.put(path, profile)
            if is_remote_track:
                remote.upload_profile(path, token, profile.rms, profile.peak)
        return profile

    def _analyze_track(
        self,
        path: str,
        seconds: float,
        run: PlaybackRun,
        progress: Optional[ProgressCallback] = None,
    ) -> TrackProfile:
        with self.settings_lock:
            token = self.server_token if remote.is_remote(path) else ""
        pipeline, appsink = self._build_decode_pipeline(path, "fred-scan-sink", token)
        meter = ProfileMeter()
        limit_frames = max(0, int(seconds * SAMPLE_RATE))
        decoded_frames = 0
        bus = pipeline.get_bus()
        eos_seen = False

        self._set_current_pipelines([pipeline])
        pipeline.set_state(Gst.State.PLAYING)
        if progress is not None:
            progress(0.0)
        try:
            while not run.stop_event.is_set() and decoded_frames < limit_frames:
                self._wait_if_paused(run)
                eos_seen = self._poll_bus(bus) or eos_seen
                sample = appsink.emit("try-pull-sample", PULL_TIMEOUT_NS)
                if sample is None:
                    eos_seen = self._poll_bus(bus) or eos_seen
                    if eos_seen:
                        break
                    continue

                data = self._sample_bytes(sample)
                remaining_frames = limit_frames - decoded_frames
                usable_bytes = min(len(data) - (len(data) % FRAME_BYTES), remaining_frames * FRAME_BYTES)
                if usable_bytes <= 0:
                    continue
                meter.accept_f32le_stereo(data[:usable_bytes])
                decoded_frames += usable_bytes // FRAME_BYTES
                if progress is not None and limit_frames > 0:
                    progress(decoded_frames / limit_frames)
        finally:
            pipeline.set_state(Gst.State.NULL)

        if progress is not None:
            progress(1.0)
        return meter.to_profile()

    def _play_decoded_stream(self, path: str, profile: Optional[TrackProfile], run: PlaybackRun) -> None:
        with self.settings_lock:
            token = self.server_token if remote.is_remote(path) else ""
        decode_pipeline, appsink = self._build_decode_pipeline(path, "fred-play-sink", token)
        output_pipeline, appsrc = self._build_output_pipeline()
        decode_bus = decode_pipeline.get_bus()
        output_bus = output_pipeline.get_bus()
        normalizer = VolumeNormalizer(SAMPLE_RATE, profile, self.settings_snapshot()[2])
        _, _, _, visualization_settings = self.settings_snapshot()
        spectrum_profile, waveform_profile = self._visual_profiles_for_track(path, visualization_settings, run)
        if run.stop_event.is_set():
            decode_pipeline.set_state(Gst.State.NULL)
            output_pipeline.set_state(Gst.State.NULL)
            return
        self.visualizer.reset()

        self._set_current_pipelines([decode_pipeline, output_pipeline])
        decode_pipeline.set_state(Gst.State.PAUSED)
        try:
            decode_pipeline.get_state(5 * Gst.SECOND)
        except Exception:
            pass
        duration_ns = self._query_duration_ns(decode_pipeline)
        start_position_ns = run.start_position_ns
        if duration_ns > 0:
            start_position_ns = min(start_position_ns, duration_ns)
        if start_position_ns > 0:
            seeked = decode_pipeline.seek_simple(
                Gst.Format.TIME,
                Gst.SeekFlags.FLUSH | Gst.SeekFlags.ACCURATE,
                start_position_ns,
            )
            if not seeked:
                raise RuntimeError("This track does not support seeking")

        active_spectrum_profile = (
            spectrum_profile
            if spectrum_profile is not None and spectrum_profile.matches(visualization_settings, SAMPLE_RATE)
            else None
        )
        active_waveform_profile = (
            waveform_profile
            if waveform_profile is not None and waveform_profile.matches(visualization_settings, SAMPLE_RATE)
            else None
        )
        self._set_visual_clock(
            output_pipeline,
            active_spectrum_profile,
            active_waveform_profile,
            start_position_ns,
        )
        self._set_playback_clock(output_pipeline, path, start_position_ns, duration_ns)
        output_pipeline.set_state(Gst.State.PLAYING)
        decode_pipeline.set_state(Gst.State.PLAYING)
        self.callbacks.on_track_started(path)

        frames_written = 0
        base_frame = int(start_position_ns * SAMPLE_RATE // Gst.SECOND)
        decode_eos = False
        decode_eos_seen = False
        try:
            while not run.stop_event.is_set() and not decode_eos:
                self._wait_if_paused(run)
                decode_eos_seen = self._poll_bus(decode_bus) or decode_eos_seen
                self._poll_bus(output_bus)

                sample = appsink.emit("try-pull-sample", PULL_TIMEOUT_NS)
                if sample is None:
                    decode_eos_seen = self._poll_bus(decode_bus) or decode_eos_seen
                    decode_eos = decode_eos_seen
                    continue

                data = self._sample_bytes(sample)
                if not data:
                    continue

                output_level, strength, settings, current_visualization_settings = self.settings_snapshot()
                normalizer.update_settings(settings)
                processed = self._process_audio(
                    data,
                    normalizer,
                    output_level,
                    strength,
                    base_frame + frames_written,
                    spectrum_profile,
                    waveform_profile,
                    current_visualization_settings,
                )
                if not processed:
                    continue

                frames = len(processed) // FRAME_BYTES
                buffer = Gst.Buffer.new_allocate(None, len(processed), None)
                buffer.fill(0, processed)
                buffer.pts = frames_written * Gst.SECOND // SAMPLE_RATE
                buffer.duration = frames * Gst.SECOND // SAMPLE_RATE
                frames_written += frames

                result = appsrc.emit("push-buffer", buffer)
                if result != Gst.FlowReturn.OK and not run.stop_event.is_set():
                    raise RuntimeError(f"Audio output failed: {result.value_nick}")

            if not run.stop_event.is_set():
                appsrc.emit("end-of-stream")
                self._wait_for_output_eos(output_bus, run)
        finally:
            decode_pipeline.set_state(Gst.State.NULL)
            output_pipeline.set_state(Gst.State.NULL)
            self._clear_visual_clock(output_pipeline)
            self._clear_playback_pipeline(output_pipeline)

    def _process_audio(
        self,
        data: bytes,
        normalizer: VolumeNormalizer,
        output_level: float,
        strength: float,
        start_frame: int,
        spectrum_profile: Optional[SpectrumProfile],
        waveform_profile: Optional[WaveformProfile],
        visualization_settings: VisualizationSettings,
    ) -> bytes:
        usable = len(data) - (len(data) % FRAME_BYTES)
        if usable <= 0:
            return b""

        out = bytearray(usable)
        unpack = struct.Struct("<ff").iter_unpack
        pack_into = struct.Struct("<ff").pack_into
        offset = 0
        use_cached_spectrum = (
            spectrum_profile is not None
            and spectrum_profile.matches(visualization_settings, SAMPLE_RATE)
        )
        use_cached_waveform = (
            waveform_profile is not None
            and waveform_profile.matches(visualization_settings, SAMPLE_RATE)
        )
        frame_index = start_frame
        for left, right in unpack(data[:usable]):
            gain = normalizer.next_gain(left, right, strength)
            left = normalizer.protect(left * gain * output_level)
            right = normalizer.protect(right * gain * output_level)
            if use_cached_waveform:
                frame = None
            elif use_cached_spectrum and spectrum_profile is not None:
                frame = self.visualizer.accept_cached_waveform(left, right)
                if frame is not None:
                    spectrum = spectrum_profile.spectrum_at_sample(frame_index, SAMPLE_RATE)
                    frame = VisualizationFrame(frame.waveform, spectrum, frame.peak, frame.rms)
            else:
                frame = self.visualizer.accept(left, right)
            if frame is not None:
                self._emit_visualization(frame)
            pack_into(out, offset, left, right)
            offset += FRAME_BYTES
            frame_index += 1
        return bytes(out)

    def _visual_profiles_for_track(
        self,
        path: str,
        settings: VisualizationSettings,
        run: PlaybackRun,
    ) -> tuple[Optional[SpectrumProfile], Optional[WaveformProfile]]:
        if np is None:
            return None, None
        cached_spectrum = self.spectrum_cache.get(path, settings)
        cached_waveform = self.waveform_cache.get(path, settings)
        if cached_spectrum is not None and cached_waveform is not None:
            return cached_spectrum, cached_waveform

        need_spectrum = cached_spectrum is None
        need_waveform = cached_waveform is None
        estimated_bytes = 0
        if need_spectrum:
            estimated_bytes += estimated_spectrum_cache_bytes(240.0, settings)
        if need_waveform:
            estimated_bytes += estimated_waveform_cache_bytes(240.0, settings)
        estimated_mb = estimated_bytes / (1024 * 1024)
        reporter = PercentReporter(
            f"Preparing visual cache ({settings.update_fps:.0f} FPS, ~{estimated_mb:.1f} MB/4 min)",
            lambda status: self.callbacks.on_status(path, status),
        )
        spectrum, waveform = analyze_visual_profiles(
            path,
            settings,
            run.stop_event,
            need_spectrum=need_spectrum,
            need_waveform=need_waveform,
            progress=reporter,
        )
        if spectrum is not None and not run.stop_event.is_set():
            self.spectrum_cache.put(path, settings, spectrum)
            cached_spectrum = spectrum
        if waveform is not None and not run.stop_event.is_set():
            self.waveform_cache.put(path, settings, waveform)
            cached_waveform = waveform
        return cached_spectrum, cached_waveform

    def _emit_visualization(self, frame: VisualizationFrame) -> None:
        try:
            self.callbacks.on_visualization(frame)
        except Exception:
            pass

    def cached_spectrum_active(self) -> bool:
        with self.visual_clock_lock:
            return self.visual_clock_pipeline is not None and self.visual_clock_profile is not None

    def cached_visualization_active(self) -> bool:
        with self.visual_clock_lock:
            return (
                self.visual_clock_pipeline is not None
                and (
                    self.visual_clock_profile is not None
                    or self.visual_clock_waveform_profile is not None
                )
            )

    def current_cached_spectrum(self) -> tuple[float, ...] | None:
        with self.visual_clock_lock:
            pipeline = self.visual_clock_pipeline
            profile = self.visual_clock_profile
        if pipeline is None or profile is None:
            return None
        try:
            ok, position = pipeline.query_position(Gst.Format.TIME)
        except Exception:
            return None
        if not ok or position < 0:
            return None
        with self.visual_clock_lock:
            base_ns = self.visual_clock_base_ns
        sample_index = int((position + base_ns) * profile.sample_rate / Gst.SECOND)
        return profile.spectrum_at_sample(sample_index, profile.sample_rate)

    def current_cached_visualization_frame(
        self,
        fallback: VisualizationFrame,
    ) -> VisualizationFrame | None:
        with self.visual_clock_lock:
            pipeline = self.visual_clock_pipeline
            spectrum_profile = self.visual_clock_profile
            waveform_profile = self.visual_clock_waveform_profile
            base_ns = self.visual_clock_base_ns
        if pipeline is None or (spectrum_profile is None and waveform_profile is None):
            return None
        try:
            ok, position = pipeline.query_position(Gst.Format.TIME)
        except Exception:
            return None
        if not ok or position < 0:
            return None

        sample_rate = (
            waveform_profile.sample_rate
            if waveform_profile is not None
            else spectrum_profile.sample_rate
            if spectrum_profile is not None
            else SAMPLE_RATE
        )
        sample_index = int((position + base_ns) * sample_rate / Gst.SECOND)
        waveform = fallback.waveform
        peak = fallback.peak
        rms = fallback.rms
        if waveform_profile is not None:
            waveform = waveform_profile.waveform_at_sample(sample_index, waveform_profile.sample_rate)
            with self.settings_lock:
                output_level = self.output_level
            waveform = tuple(max(-1.0, min(1.0, value * output_level)) for value in waveform)
            if waveform:
                peak = max(abs(value) for value in waveform)
                rms = (sum(value * value for value in waveform) / len(waveform)) ** 0.5
            else:
                peak = 0.0
                rms = 0.0
        spectrum = fallback.spectrum
        if spectrum_profile is not None:
            spectrum = spectrum_profile.spectrum_at_sample(sample_index, spectrum_profile.sample_rate)
        return VisualizationFrame(waveform=waveform, spectrum=spectrum, peak=peak, rms=rms)

    def _set_visual_clock(
        self,
        pipeline: Optional[Gst.Pipeline],
        profile: Optional[SpectrumProfile],
        waveform_profile: Optional[WaveformProfile],
        base_ns: int = 0,
    ) -> None:
        with self.visual_clock_lock:
            self.visual_clock_pipeline = pipeline if profile is not None or waveform_profile is not None else None
            self.visual_clock_profile = profile
            self.visual_clock_waveform_profile = waveform_profile
            self.visual_clock_base_ns = max(0, int(base_ns))

    def _clear_visual_clock(self, pipeline: Optional[Gst.Pipeline] = None) -> None:
        with self.visual_clock_lock:
            if pipeline is not None and self.visual_clock_pipeline is not pipeline:
                return
            self.visual_clock_pipeline = None
            self.visual_clock_profile = None
            self.visual_clock_waveform_profile = None
            self.visual_clock_base_ns = 0

    def _set_playback_clock(
        self,
        pipeline: Gst.Pipeline,
        path: str,
        base_ns: int,
        duration_ns: int,
    ) -> None:
        with self.playback_clock_lock:
            self.playback_path = path
            self.playback_position_base_ns = max(0, int(base_ns))
            self.playback_duration_ns = max(0, int(duration_ns))
            self.playback_clock_pipeline = pipeline

    def _clear_playback_pipeline(self, pipeline: Gst.Pipeline) -> None:
        with self.playback_clock_lock:
            if self.playback_clock_pipeline is pipeline:
                self.playback_clock_pipeline = None

    def _query_duration_ns(self, pipeline: Gst.Pipeline) -> int:
        try:
            ok, duration = pipeline.query_duration(Gst.Format.TIME)
        except Exception:
            return 0
        if not ok or duration <= 0:
            return 0
        return int(duration)

    def _build_decode_pipeline(self, path: str, sink_name: str, token: str = "") -> tuple[Gst.Pipeline, Gst.Element]:
        pipeline = Gst.Pipeline.new(None)
        source = self._make_element("uridecodebin", "source")
        queue = self._make_element("queue", "decode-queue")
        convert = self._make_element("audioconvert", "decode-convert")
        resample = self._make_element("audioresample", "decode-resample")
        capsfilter = self._make_element("capsfilter", "decode-caps")
        appsink = self._make_element("appsink", sink_name)

        _configure_source_uri(source, path, token)
        source.set_property("caps", Gst.Caps.from_string("audio/x-raw"))
        queue.set_property("max-size-buffers", 32)
        queue.set_property("max-size-bytes", 0)
        queue.set_property("max-size-time", 0)
        capsfilter.set_property("caps", PCM_CAPS)
        appsink.set_property("emit-signals", False)
        appsink.set_property("sync", False)
        appsink.set_property("max-buffers", 16)
        appsink.set_property("drop", False)

        for element in (source, queue, convert, resample, capsfilter, appsink):
            pipeline.add(element)
        if not queue.link(convert) or not convert.link(resample) or not resample.link(capsfilter) or not capsfilter.link(appsink):
            raise RuntimeError("Failed to build decode pipeline")
        source.connect("pad-added", self._on_decode_pad_added, queue)
        return pipeline, appsink

    def _build_output_pipeline(self) -> tuple[Gst.Pipeline, Gst.Element]:
        pipeline = Gst.Pipeline.new(None)
        appsrc = self._make_element("appsrc", "fred-output-source")
        queue = self._make_element("queue", "output-queue")
        convert = self._make_element("audioconvert", "output-convert")
        resample = self._make_element("audioresample", "output-resample")
        sink_factory = os.environ.get("FREDPLAYER_AUDIO_SINK", "autoaudiosink")
        sink = self._make_element(sink_factory, "output-sink")
        try:
            if sink.find_property("sync") is not None:
                sink.set_property("sync", True)
        except Exception:
            pass

        appsrc.set_property("caps", PCM_CAPS)
        appsrc.set_property("format", Gst.Format.TIME)
        appsrc.set_property("block", True)
        appsrc.set_property("is-live", False)
        appsrc.set_property("max-bytes", OUTPUT_BUFFER_BYTES)
        queue.set_property("max-size-buffers", 0)
        queue.set_property("max-size-bytes", OUTPUT_BUFFER_BYTES)
        queue.set_property("max-size-time", OUTPUT_BUFFER_TIME_NS)

        for element in (appsrc, queue, convert, resample, sink):
            pipeline.add(element)
        if not appsrc.link(queue) or not queue.link(convert) or not convert.link(resample) or not resample.link(sink):
            raise RuntimeError("Failed to build audio output pipeline")
        return pipeline, appsrc

    def _on_decode_pad_added(self, _source: Gst.Element, pad: Gst.Pad, queue: Gst.Element) -> None:
        sink_pad = queue.get_static_pad("sink")
        if sink_pad is None or sink_pad.is_linked():
            return
        result = pad.link(sink_pad)
        if result != Gst.PadLinkReturn.OK:
            return

    def _make_element(self, factory: str, name: str) -> Gst.Element:
        element = Gst.ElementFactory.make(factory, name)
        if element is None:
            raise RuntimeError(f"Missing GStreamer element: {factory}")
        return element

    def _sample_bytes(self, sample: Gst.Sample) -> bytes:
        buffer = sample.get_buffer()
        if buffer is None:
            return b""
        ok, info = buffer.map(Gst.MapFlags.READ)
        if not ok:
            return b""
        try:
            return bytes(info.data)
        finally:
            buffer.unmap(info)

    def _wait_if_paused(self, run: PlaybackRun) -> None:
        with self.pause_condition:
            while self.paused and not run.stop_event.is_set():
                self.pause_condition.wait(timeout=0.2)

    def _wait_for_output_eos(self, bus: Gst.Bus, run: PlaybackRun) -> None:
        while not run.stop_event.is_set():
            self._wait_if_paused(run)
            message = bus.timed_pop_filtered(PULL_TIMEOUT_NS, Gst.MessageType.ERROR | Gst.MessageType.EOS)
            if message is None:
                continue
            if message.type == Gst.MessageType.ERROR:
                error, _debug = message.parse_error()
                raise RuntimeError(error.message)
            if message.type == Gst.MessageType.EOS:
                return

    def _poll_bus(self, bus: Gst.Bus) -> bool:
        saw_eos = False
        while True:
            message = bus.pop()
            if message is None:
                return saw_eos
            if message.type == Gst.MessageType.ERROR:
                error, _debug = message.parse_error()
                raise RuntimeError(error.message)
            if message.type == Gst.MessageType.EOS:
                saw_eos = True

    def _set_current_pipelines(self, pipelines: list[Gst.Pipeline]) -> None:
        with self.pipeline_lock:
            self.current_pipelines = pipelines

    def _set_current_pipeline_state(self, state: Gst.State) -> None:
        with self.pipeline_lock:
            pipelines = list(self.current_pipelines)
        for pipeline in pipelines:
            try:
                pipeline.set_state(state)
            except Exception:
                pass
