from __future__ import annotations

from dataclasses import dataclass
import json
import subprocess
import time
from typing import Optional

import gi

try:
    import numpy as np
except Exception:  # pragma: no cover - optional at runtime
    np = None

gi.require_version("Gst", "1.0")
gi.require_version("GstAudio", "1.0")
gi.require_version("GstApp", "1.0")
from gi.repository import Gst, GstAudio  # noqa: E402


Gst.init(None)

SAMPLE_RATE = 48_000
PRE_ROLL_SECONDS = 0.40
CHIRP_SECONDS = 0.30
POST_ROLL_SECONDS = 0.35
CAPTURE_AFTER_CHIRP_SECONDS = 1.75
MAX_DELAY_MS = 1_500
MIN_CONFIDENCE = 0.10
PULL_TIMEOUT_NS = 100 * Gst.MSECOND
MONO_CAPS = Gst.Caps.from_string(
    f"audio/x-raw,format=F32LE,channels=1,rate={SAMPLE_RATE},layout=interleaved"
)


@dataclass(frozen=True)
class AudioOutput:
    key: str
    label: str
    bluetooth: bool


@dataclass(frozen=True)
class Microphone:
    key: str
    label: str
    default: bool = False


@dataclass(frozen=True)
class CalibrationResult:
    output: AudioOutput
    delay_ms: int
    confidence: float


def current_output() -> AudioOutput:
    default_name = _pactl_default("sink")
    sinks = _pactl_items("sinks")
    sink = next((item for item in sinks if str(item.get("name", "")) == default_name), None)
    if sink is None:
        return AudioOutput(default_name, default_name or "Default audio output", False)

    name = str(sink.get("name", "")) or default_name
    label = str(sink.get("description", "")).strip() or name or "Default audio output"
    properties = sink.get("properties", {})
    bluetooth = name.startswith("bluez_output.") or (
        isinstance(properties, dict)
        and str(properties.get("device.bus", "")).casefold() == "bluetooth"
    )
    return AudioOutput(name, label, bluetooth)


def microphones() -> list[Microphone]:
    default_name = _pactl_default("source")
    result: list[Microphone] = []
    for source in _pactl_items("sources"):
        name = str(source.get("name", ""))
        if not name or name.endswith(".monitor"):
            continue
        label = str(source.get("description", "")).strip() or name
        result.append(Microphone(name, label, name == default_name))
    result.sort(key=lambda item: (not item.default, item.label.casefold(), item.key))
    return result


def probe_system_latency(output: AudioOutput) -> int:
    """Ask the active GStreamer audio sink for its system-reported latency.

    A live silent source is intentional. GstAudioBaseSink only exposes a useful
    value after the selected output has negotiated a live stream; a non-live
    file-player source commonly reports zero even for Bluetooth.
    """
    pipeline = Gst.Pipeline.new("fred-system-latency-probe")
    source = _make_element("audiotestsrc", "latency-silence")
    convert = _make_element("audioconvert", "latency-convert")
    resample = _make_element("audioresample", "latency-resample")
    sink = _make_output_sink(output, "latency-output")
    source.set_property("is-live", True)
    source.set_property("wave", 4)  # GstAudioTestSrcWave.SILENCE

    for element in (source, convert, resample, sink):
        pipeline.add(element)
    if not source.link(convert) or not convert.link(resample) or not resample.link(sink):
        raise RuntimeError("Could not build the system latency probe")

    try:
        pipeline.set_state(Gst.State.PLAYING)
        pipeline.get_state(3 * Gst.SECOND)
        deadline = time.monotonic() + 1.5
        latency_ns = 0
        while time.monotonic() < deadline and latency_ns <= 0:
            _raise_bus_error(pipeline.get_bus())
            latency_ns = _audio_sink_latency_ns(sink)
            if latency_ns <= 0:
                time.sleep(0.08)
        if latency_ns <= 0:
            query = Gst.Query.new_latency()
            if pipeline.query(query):
                _live, minimum, _maximum = query.parse_latency()
                latency_ns = max(0, int(minimum))
        if latency_ns <= 0:
            raise RuntimeError("The audio system did not report output latency")
        return max(0, min(MAX_DELAY_MS, round(latency_ns / Gst.MSECOND)))
    finally:
        pipeline.set_state(Gst.State.NULL)


def calibrate_with_microphone(microphone_key: str) -> CalibrationResult:
    if np is None:
        raise RuntimeError("Microphone calibration requires python3-numpy")
    if not microphone_key:
        raise RuntimeError("Choose a microphone first")

    output = current_output()
    if not output.key:
        raise RuntimeError("No audio output is available")

    pipeline = Gst.Pipeline.new("fred-speaker-latency-calibration")
    source = _make_element("pulsesrc", "calibration-microphone")
    source.set_property("device", microphone_key)
    input_convert = _make_element("audioconvert", "calibration-input-convert")
    input_resample = _make_element("audioresample", "calibration-input-resample")
    input_caps = _make_element("capsfilter", "calibration-input-caps")
    input_caps.set_property("caps", MONO_CAPS)
    appsink = _make_element("appsink", "calibration-capture")
    appsink.set_property("emit-signals", False)
    appsink.set_property("sync", False)
    appsink.set_property("max-buffers", 256)
    appsink.set_property("drop", False)

    appsrc = _make_element("appsrc", "calibration-sound")
    appsrc.set_property("caps", MONO_CAPS)
    appsrc.set_property("format", Gst.Format.TIME)
    appsrc.set_property("is-live", True)
    appsrc.set_property("block", True)
    output_queue = _make_element("queue", "calibration-output-queue")
    output_convert = _make_element("audioconvert", "calibration-output-convert")
    output_resample = _make_element("audioresample", "calibration-output-resample")
    output_sink = _make_output_sink(output, "calibration-output")

    elements = (
        source,
        input_convert,
        input_resample,
        input_caps,
        appsink,
        appsrc,
        output_queue,
        output_convert,
        output_resample,
        output_sink,
    )
    for element in elements:
        pipeline.add(element)
    if (
        not source.link(input_convert)
        or not input_convert.link(input_resample)
        or not input_resample.link(input_caps)
        or not input_caps.link(appsink)
        or not appsrc.link(output_queue)
        or not output_queue.link(output_convert)
        or not output_convert.link(output_resample)
        or not output_resample.link(output_sink)
    ):
        raise RuntimeError("Could not build the speaker calibration pipeline")

    captured: list[tuple[int, "np.ndarray"]] = []
    chirp = _build_chirp()
    output_samples = np.concatenate(
        (
            np.zeros(round(PRE_ROLL_SECONDS * SAMPLE_RATE), dtype="<f4"),
            chirp,
            np.zeros(round(POST_ROLL_SECONDS * SAMPLE_RATE), dtype="<f4"),
        )
    )

    try:
        pipeline.set_state(Gst.State.PLAYING)
        first_sample = _pull_sample(appsink, pipeline, 3.0)
        if first_sample is None:
            raise RuntimeError("The selected microphone did not provide audio")
        _append_capture(captured, first_sample)

        clock = pipeline.get_clock()
        if clock is None:
            raise RuntimeError("The audio system did not provide a shared clock")
        running_time = max(0, int(clock.get_time() - pipeline.get_base_time()))
        scheduled_start_ns = running_time + 120 * Gst.MSECOND
        expected_chirp_ns = scheduled_start_ns + round(PRE_ROLL_SECONDS * Gst.SECOND)

        buffer = Gst.Buffer.new_allocate(None, output_samples.nbytes, None)
        buffer.fill(0, output_samples.tobytes())
        buffer.pts = scheduled_start_ns
        buffer.dts = scheduled_start_ns
        buffer.duration = round(len(output_samples) * Gst.SECOND / SAMPLE_RATE)
        result = appsrc.emit("push-buffer", buffer)
        if result != Gst.FlowReturn.OK:
            raise RuntimeError("Could not play the calibration sound")

        stop_running_time = expected_chirp_ns + round(CAPTURE_AFTER_CHIRP_SECONDS * Gst.SECOND)
        wall_deadline = time.monotonic() + 5.0
        while time.monotonic() < wall_deadline:
            _raise_bus_error(pipeline.get_bus())
            sample = appsink.emit("try-pull-sample", PULL_TIMEOUT_NS)
            if sample is not None:
                _append_capture(captured, sample)
            now_running = max(0, int(clock.get_time() - pipeline.get_base_time()))
            if now_running >= stop_running_time:
                break
        appsrc.emit("end-of-stream")

        if current_output().key != output.key:
            raise RuntimeError("The audio output changed during calibration")
        capture, capture_start_ns = _capture_timeline(captured)
        match_frame, confidence = _find_chirp(capture, chirp, expected_chirp_ns, capture_start_ns)
        delay_ms = round(
            ((capture_start_ns + match_frame * Gst.SECOND / SAMPLE_RATE) - expected_chirp_ns)
            / Gst.MSECOND
        )
        if confidence < MIN_CONFIDENCE:
            raise RuntimeError(
                "The calibration sound was not clear enough; move the microphone closer or raise the speaker volume"
            )
        if delay_ms < 0 or delay_ms > MAX_DELAY_MS:
            raise RuntimeError("The measured speaker delay was outside the supported range")
        return CalibrationResult(output, int(delay_ms), float(confidence))
    finally:
        pipeline.set_state(Gst.State.NULL)


def _build_chirp() -> "np.ndarray":
    frames = round(CHIRP_SECONDS * SAMPLE_RATE)
    times = np.arange(frames, dtype=np.float64) / SAMPLE_RATE
    start_hz = 700.0
    end_hz = 6_500.0
    sweep = (end_hz - start_hz) / CHIRP_SECONDS
    phase = 2.0 * np.pi * (start_hz * times + 0.5 * sweep * times * times)
    envelope = np.hanning(frames)
    return (np.sin(phase) * envelope * 0.22).astype("<f4")


def _find_chirp(
    capture: "np.ndarray",
    chirp: "np.ndarray",
    expected_chirp_ns: int,
    capture_start_ns: int,
) -> tuple[int, float]:
    downsample = 8
    capture_ds = np.asarray(capture[::downsample], dtype=np.float64)
    reference = np.asarray(chirp[::downsample], dtype=np.float64)
    expected_frame = round((expected_chirp_ns - capture_start_ns) * SAMPLE_RATE / Gst.SECOND)
    earliest_frame = max(0, expected_frame - round(0.20 * SAMPLE_RATE))
    latest_frame = min(
        max(0, len(capture) - len(chirp)),
        expected_frame + round(1.50 * SAMPLE_RATE),
    )
    first = earliest_frame // downsample
    last = latest_frame // downsample
    search = capture_ds[first : last + len(reference)]
    if len(search) < len(reference):
        raise RuntimeError("Not enough microphone audio was captured")

    correlation = np.correlate(search, reference, mode="valid")
    reference_energy = float(np.dot(reference, reference))
    squared = search * search
    cumulative = np.concatenate(([0.0], np.cumsum(squared)))
    window_energy = cumulative[len(reference) :] - cumulative[: -len(reference)]
    denominator = np.sqrt(np.maximum(1e-20, reference_energy * window_energy))
    scores = np.abs(correlation) / denominator
    best = int(np.argmax(scores))
    return (first + best) * downsample, float(scores[best])


def _capture_timeline(captured: list[tuple[int, "np.ndarray"]]) -> tuple["np.ndarray", int]:
    valid = [(pts, samples) for pts, samples in captured if pts >= 0 and len(samples)]
    if not valid:
        raise RuntimeError("No usable microphone audio was captured")
    start_ns = min(pts for pts, _samples in valid)
    end_frame = max(
        round((pts - start_ns) * SAMPLE_RATE / Gst.SECOND) + len(samples)
        for pts, samples in valid
    )
    timeline = np.zeros(max(1, end_frame), dtype="<f4")
    for pts, samples in valid:
        start = max(0, round((pts - start_ns) * SAMPLE_RATE / Gst.SECOND))
        end = min(len(timeline), start + len(samples))
        if end > start:
            timeline[start:end] = samples[: end - start]
    return timeline, start_ns


def _append_capture(captured: list[tuple[int, "np.ndarray"]], sample: Gst.Sample) -> None:
    buffer = sample.get_buffer()
    if buffer is None:
        return
    ok, info = buffer.map(Gst.MapFlags.READ)
    if not ok:
        return
    try:
        samples = np.frombuffer(info.data, dtype="<f4").copy()
    finally:
        buffer.unmap(info)
    pts = int(buffer.pts) if buffer.pts != Gst.CLOCK_TIME_NONE else -1
    if pts < 0 and captured:
        previous_pts, previous = captured[-1]
        pts = previous_pts + round(len(previous) * Gst.SECOND / SAMPLE_RATE)
    elif pts < 0:
        pts = 0
    captured.append((pts, samples))


def _pull_sample(appsink: Gst.Element, pipeline: Gst.Pipeline, timeout_seconds: float) -> Optional[Gst.Sample]:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        _raise_bus_error(pipeline.get_bus())
        sample = appsink.emit("try-pull-sample", PULL_TIMEOUT_NS)
        if sample is not None:
            return sample
    return None


def _make_output_sink(output: AudioOutput, name: str) -> Gst.Element:
    sink = Gst.ElementFactory.make("pulsesink", name)
    if sink is not None:
        if output.key:
            sink.set_property("device", output.key)
        return sink
    sink = Gst.ElementFactory.make("autoaudiosink", name)
    if sink is None:
        raise RuntimeError("No GStreamer audio output is available")
    return sink


def _audio_sink_latency_ns(sink: Gst.Element) -> int:
    candidates = [sink]
    if hasattr(sink, "get_children_count"):
        try:
            candidates.extend(sink.get_child_by_index(index) for index in range(sink.get_children_count()))
        except Exception:
            pass
    for candidate in candidates:
        try:
            latency = int(GstAudio.AudioBaseSink.get_latency(candidate))
        except Exception:
            continue
        if latency > 0:
            return latency
    return 0


def _make_element(factory: str, name: str) -> Gst.Element:
    element = Gst.ElementFactory.make(factory, name)
    if element is None:
        raise RuntimeError(f"Missing GStreamer element: {factory}")
    return element


def _raise_bus_error(bus: Gst.Bus) -> None:
    while True:
        message = bus.pop_filtered(Gst.MessageType.ERROR)
        if message is None:
            return
        error, _debug = message.parse_error()
        raise RuntimeError(error.message)


def _pactl_default(kind: str) -> str:
    command = "get-default-sink" if kind == "sink" else "get-default-source"
    try:
        result = subprocess.run(
            ["pactl", command],
            check=True,
            capture_output=True,
            text=True,
            timeout=2.0,
        )
        return result.stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return ""


def _pactl_items(kind: str) -> list[dict]:
    try:
        result = subprocess.run(
            ["pactl", "-f", "json", "list", kind],
            check=True,
            capture_output=True,
            text=True,
            timeout=2.0,
        )
        value = json.loads(result.stdout)
        return [item for item in value if isinstance(item, dict)] if isinstance(value, list) else []
    except (OSError, subprocess.SubprocessError, json.JSONDecodeError):
        return []
