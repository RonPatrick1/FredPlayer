from __future__ import annotations

import unittest

from fredplayer.store import SpeakerLatency, WindowState


class StoredLatencyTest(unittest.TestCase):
    def test_window_width_can_be_restored_below_old_minimum(self) -> None:
        self.assertEqual(WindowState.from_dict({"width": 640}).width, 640)

    def test_speaker_delay_is_clamped(self) -> None:
        calibration = SpeakerLatency.from_json(
            "bluez_output.example",
            {"label": "Example speaker", "delay_ms": 9_999},
        )
        self.assertIsNotNone(calibration)
        self.assertEqual(calibration.delay_ms, 1_500)


if __name__ == "__main__":
    unittest.main()
