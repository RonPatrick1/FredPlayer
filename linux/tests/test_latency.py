from __future__ import annotations

import unittest

import gi
import numpy as np

gi.require_version("Gst", "1.0")
from gi.repository import Gst

from fredplayer import latency


class LatencyDetectionTest(unittest.TestCase):
    def test_chirp_correlation_recovers_known_delay(self) -> None:
        chirp = latency._build_chirp()
        expected_frame = 30_000
        delay_frames = round(0.237 * latency.SAMPLE_RATE)
        actual_frame = expected_frame + delay_frames
        capture = np.zeros(actual_frame + len(chirp) + 5_000, dtype="<f4")
        capture[actual_frame : actual_frame + len(chirp)] = chirp

        matched_frame, confidence = latency._find_chirp(
            capture,
            chirp,
            round(expected_frame * Gst.SECOND / latency.SAMPLE_RATE),
            0,
        )

        self.assertLessEqual(abs(matched_frame - actual_frame), 8)
        self.assertGreater(confidence, 0.99)


if __name__ == "__main__":
    unittest.main()
