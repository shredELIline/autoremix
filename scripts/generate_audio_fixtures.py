#!/usr/bin/env python3
"""Generate deterministic, original audio fixtures and demo metadata."""

from __future__ import annotations

import hashlib
import json
import math
import struct
import wave
from dataclasses import dataclass
from pathlib import Path


SAMPLE_RATE = 22_050
CHANNELS = 2
SOURCE_SECONDS = 6.0
BRIDGE_SECONDS = 12.0
TAU = math.tau
ROOT = Path(__file__).resolve().parents[1]
AUDIO_DIR = ROOT / "docs" / "assets" / "audio"
GOLDEN_DIR = ROOT / "tests" / "fixtures" / "golden" / "procedural_bridge"


@dataclass(frozen=True)
class Metrics:
    peak: float
    dc_offset: float
    max_derivative: float
    rms_dbfs: float


def smoothstep(value: float) -> float:
    value = max(0.0, min(1.0, value))
    return value * value * (3.0 - 2.0 * value)


def pulse(phase: float, width: float) -> float:
    if phase >= width:
        return 0.0
    return math.exp(-phase * 8.0 / width)


def source_a(time_s: float) -> tuple[float, float]:
    beat = (time_s * 112.0 / 60.0) % 1.0
    bar = (time_s * 112.0 / 240.0) % 1.0
    kick = 0.26 * pulse(beat, 0.18) * math.sin(TAU * 62.0 * time_s)
    guitar_env = 0.45 + 0.55 * pulse((beat * 2.0) % 1.0, 0.34)
    guitar = guitar_env * (
        0.11 * math.sin(TAU * 220.0 * time_s)
        + 0.045 * math.sin(TAU * 330.0 * time_s + 0.3)
    )
    bass = 0.12 * math.sin(TAU * 55.0 * time_s) * (0.72 + 0.28 * pulse(beat, 0.4))
    pad = 0.045 * math.sin(TAU * 110.0 * time_s + 0.2 * math.sin(TAU * bar))
    return guitar + bass + kick + pad, guitar * 0.96 + bass + kick * 0.93 - pad


def source_b(time_s: float) -> tuple[float, float]:
    beat = (time_s * 118.0 / 60.0) % 1.0
    half_beat = (beat * 2.0) % 1.0
    kick = 0.25 * pulse(beat, 0.16) * math.sin(TAU * 65.4 * time_s)
    hat = 0.035 * pulse(half_beat, 0.08) * math.sin(TAU * 5_600.0 * time_s)
    lead_env = 0.38 + 0.62 * pulse((beat * 4.0) % 1.0, 0.25)
    lead = lead_env * (
        0.10 * math.sin(TAU * 261.63 * time_s + 0.2)
        + 0.035 * math.sin(TAU * 392.0 * time_s)
    )
    bass = 0.11 * math.sin(TAU * 65.4 * time_s) * (0.75 + 0.25 * pulse(beat, 0.4))
    pad = 0.04 * math.sin(TAU * 130.81 * time_s)
    return lead + bass + kick + hat + pad, lead * 0.94 + bass + kick * 0.95 - hat - pad


def procedural_bridge(time_s: float) -> tuple[float, float]:
    progress = time_s / BRIDGE_SECONDS
    tempo = 112.0 + (118.0 - 112.0) * smoothstep(progress)
    beat = (time_s * tempo / 60.0) % 1.0

    # The instrumental anchor never reaches zero. Its pitch glides from A3 to C4.
    anchor_hz = 220.0 * (2.0 ** (3.0 * smoothstep(progress) / 12.0))
    anchor = (0.10 + 0.025 * pulse((beat * 2.0) % 1.0, 0.3)) * math.sin(
        TAU * anchor_hz * time_s
    )

    drums_b = smoothstep((progress - 0.16) / 0.42)
    drums_a = 1.0 - smoothstep((progress - 0.40) / 0.45)
    kick_a = 0.20 * drums_a * pulse(beat, 0.17) * math.sin(TAU * 62.0 * time_s)
    kick_b = 0.22 * drums_b * pulse(beat, 0.15) * math.sin(TAU * 65.4 * time_s)

    bass_handoff = smoothstep((progress - 0.38) / 0.30)
    bass_hz = 55.0 * (2.0 ** (3.0 * bass_handoff / 12.0))
    bass = 0.10 * math.sin(TAU * bass_hz * time_s)

    atmosphere = 0.035 * math.sin(
        TAU * (110.0 + 20.81 * smoothstep(progress)) * time_s
    )
    edge = min(smoothstep(progress / 0.03), smoothstep((1.0 - progress) / 0.03))
    left = (anchor + kick_a + kick_b + bass + atmosphere) * edge
    right = (anchor * 0.96 + kick_a * 0.94 + kick_b * 0.96 + bass - atmosphere) * edge
    return left, right


def render(function: object, seconds: float) -> list[float]:
    samples: list[float] = []
    frame_count = round(seconds * SAMPLE_RATE)
    for frame in range(frame_count):
        left, right = function(frame / SAMPLE_RATE)  # type: ignore[operator]
        samples.extend((max(-0.96, min(0.96, left)), max(-0.96, min(0.96, right))))
    return samples


def edge_fade(samples: list[float], milliseconds: float = 12.0) -> None:
    fade_frames = max(1, round(SAMPLE_RATE * milliseconds / 1_000.0))
    frames = len(samples) // CHANNELS
    for frame in range(min(fade_frames, frames // 2)):
        gain = smoothstep(frame / max(1, fade_frames - 1))
        tail_gain = smoothstep((fade_frames - frame - 1) / max(1, fade_frames - 1))
        for channel in range(CHANNELS):
            samples[frame * CHANNELS + channel] *= gain
            samples[(frames - fade_frames + frame) * CHANNELS + channel] *= tail_gain


def write_wave(path: Path, samples: list[float]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    pcm = bytearray()
    for sample in samples:
        pcm.extend(struct.pack("<h", round(max(-1.0, min(1.0, sample)) * 32_767.0)))
    with wave.open(str(path), "wb") as output:
        output.setnchannels(CHANNELS)
        output.setsampwidth(2)
        output.setframerate(SAMPLE_RATE)
        output.writeframes(pcm)


def metrics(samples: list[float]) -> Metrics:
    if not samples:
        return Metrics(0.0, 0.0, 0.0, -120.0)
    peak = max(abs(sample) for sample in samples)
    dc_offset = abs(sum(samples) / len(samples))
    derivative = max(
        abs(samples[index] - samples[index - CHANNELS])
        for index in range(CHANNELS, len(samples))
    )
    rms = math.sqrt(sum(sample * sample for sample in samples) / len(samples))
    return Metrics(peak, dc_offset, derivative, 20.0 * math.log10(max(rms, 1e-6)))


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(64 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def main() -> None:
    audio_a = render(source_a, SOURCE_SECONDS)
    audio_b = render(source_b, SOURCE_SECONDS)
    bridge = render(procedural_bridge, BRIDGE_SECONDS)
    for audio in (audio_a, audio_b, bridge):
        edge_fade(audio)
    combined = [*audio_a, *bridge, *audio_b]

    outputs = {
        "source-a.wav": audio_a,
        "source-b.wav": audio_b,
        "procedural-bridge.wav": bridge,
        "combined-demo.wav": combined,
    }
    manifests: dict[str, dict[str, object]] = {}
    for name, samples in outputs.items():
        output = AUDIO_DIR / name
        write_wave(output, samples)
        result = metrics(samples)
        manifests[name] = {
            "sha256": sha256(output),
            "frames": len(samples) // CHANNELS,
            "sample_rate": SAMPLE_RATE,
            "channels": CHANNELS,
            "peak": round(result.peak, 7),
            "dc_offset": round(result.dc_offset, 9),
            "max_derivative": round(result.max_derivative, 7),
            "rms_dbfs": round(result.rms_dbfs, 4),
        }

    GOLDEN_DIR.mkdir(parents=True, exist_ok=True)
    (GOLDEN_DIR / "transition-plan.json").write_text(
        json.dumps(
            {
                "schema": 1,
                "sample_rate": SAMPLE_RATE,
                "duration_frames": round(BRIDGE_SECONDS * SAMPLE_RATE),
                "anchor": {"role": "guitar", "minimum_gain": 0.1},
                "timelines": [
                    {"role": "guitar", "sources": ["A", "generated", "B"]},
                    {"role": "drums", "sources": ["A", "generated", "B"]},
                    {"role": "bass", "sources": ["A", "generated", "B"]},
                    {"role": "atmosphere", "sources": ["generated"]},
                ],
                "vocal_generation": False,
            },
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    (GOLDEN_DIR / "quality-report.json").write_text(
        json.dumps(manifests["procedural-bridge.wav"], indent=2) + "\n",
        encoding="utf-8",
    )
    (AUDIO_DIR / "manifest.json").write_text(
        json.dumps({"generator": "scripts/generate_audio_fixtures.py", "files": manifests}, indent=2)
        + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
