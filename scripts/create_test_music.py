#!/usr/bin/env python3
"""Generate test WAV files in /tmp/auxen-test-music/ for manual testing.

Creates a directory tree that mimics a real music library:

    /tmp/auxen-test-music/
        Radiohead/
            In Rainbows/
                01 - Reckoner.wav
                02 - Nude.wav
        Daft Punk/
            Discovery/
                01 - Digital Love.wav

Each file is a minimal 0.5-second silent WAV (44.1 kHz, 16-bit, stereo).
"""

import os
import wave

OUTPUT_DIR = "/tmp/auxen-test-music"

FILES = [
    ("Radiohead", "In Rainbows", "01 - Reckoner.wav"),
    ("Radiohead", "In Rainbows", "02 - Nude.wav"),
    ("Daft Punk", "Discovery", "01 - Digital Love.wav"),
]


def create_wav(path: str, duration_secs: float = 0.5) -> None:
    """Write a silent WAV file at *path*."""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    sample_rate = 44100
    num_channels = 2
    sample_width = 2  # bytes (16-bit)
    num_frames = int(sample_rate * duration_secs)
    with wave.open(path, "w") as wf:
        wf.setnchannels(num_channels)
        wf.setsampwidth(sample_width)
        wf.setframerate(sample_rate)
        wf.writeframes(b"\x00" * num_frames * num_channels * sample_width)


def main() -> None:
    for artist, album, filename in FILES:
        path = os.path.join(OUTPUT_DIR, artist, album, filename)
        create_wav(path)
        print(f"  created {path}")
    print(f"\nDone — {len(FILES)} files in {OUTPUT_DIR}")


if __name__ == "__main__":
    main()
