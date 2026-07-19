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


SAMPLE_RATE = 48_000
CHANNELS = 2
SOURCE_SECONDS = 6.0
TRANSITION_SECONDS = 12.0
SCENE_SECONDS = SOURCE_SECONDS * 2.0 + TRANSITION_SECONDS
TAU = math.tau
ROOT = Path(__file__).resolve().parents[1]
AUDIO_DIR = ROOT / "docs" / "assets" / "audio"
GOLDEN_DIR = ROOT / "tests" / "fixtures" / "golden" / "continuous_stem_scene"


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


def stems_a(time_s: float) -> tuple[float, float, float, float, float]:
    beat = (time_s * 112.0 / 60.0) % 1.0
    bar = (time_s * 112.0 / 240.0) % 1.0
    drums = 0.26 * pulse(beat, 0.18) * math.sin(TAU * 62.0 * time_s)
    guitar_env = 0.45 + 0.55 * pulse((beat * 2.0) % 1.0, 0.34)
    guitar = guitar_env * (
        0.11 * math.sin(TAU * 220.0 * time_s)
        + 0.045 * math.sin(TAU * 330.0 * time_s + 0.3)
    )
    bass = 0.12 * math.sin(TAU * 55.0 * time_s) * (0.72 + 0.28 * pulse(beat, 0.4))
    atmosphere = 0.045 * math.sin(TAU * 110.0 * time_s + 0.2 * math.sin(TAU * bar))
    vocal = 0.042 * math.sin(TAU * 440.0 * time_s + 0.15)
    return vocal, guitar, drums, bass, atmosphere


def stems_b(time_s: float) -> tuple[float, float, float, float, float]:
    beat = (time_s * 118.0 / 60.0) % 1.0
    half_beat = (beat * 2.0) % 1.0
    kick = 0.25 * pulse(beat, 0.16) * math.sin(TAU * 65.4 * time_s)
    hat = 0.035 * pulse(half_beat, 0.08) * math.sin(TAU * 5_600.0 * time_s)
    lead_env = 0.38 + 0.62 * pulse((beat * 4.0) % 1.0, 0.25)
    guitar = lead_env * (
        0.10 * math.sin(TAU * 261.63 * time_s + 0.2)
        + 0.035 * math.sin(TAU * 392.0 * time_s)
    )
    bass = 0.11 * math.sin(TAU * 65.4 * time_s) * (0.75 + 0.25 * pulse(beat, 0.4))
    atmosphere = 0.04 * math.sin(TAU * 130.81 * time_s)
    vocal = 0.04 * math.sin(TAU * 523.25 * time_s + 0.4)
    return vocal, guitar, kick + hat, bass, atmosphere


def stereo_stems(stems: tuple[float, float, float, float, float]) -> tuple[float, float]:
    vocal, guitar, drums, bass, atmosphere = stems
    return (
        vocal + guitar + drums + bass + atmosphere,
        vocal * 0.93 + guitar * 0.96 + drums * 0.94 + bass - atmosphere,
    )


def source_a(time_s: float) -> tuple[float, float]:
    return stereo_stems(stems_a(time_s))


def source_b(time_s: float) -> tuple[float, float]:
    return stereo_stems(stems_b(time_s))


def continuous_stem_scene(time_s: float) -> tuple[float, float]:
    if time_s < SOURCE_SECONDS:
        return source_a(time_s)
    if time_s >= SOURCE_SECONDS + TRANSITION_SECONDS:
        return source_b(time_s)

    progress = (time_s - SOURCE_SECONDS) / TRANSITION_SECONDS
    a = stems_a(time_s)
    b = stems_b(time_s)
    gains_a = (
        1.0 - smoothstep(progress / 0.28),
        1.0 - smoothstep((progress - 0.62) / 0.30),
        1.0 - smoothstep((progress - 0.18) / 0.38),
        1.0 - smoothstep((progress - 0.34) / 0.30),
        1.0 - smoothstep((progress - 0.52) / 0.34),
    )
    gains_b = (
        smoothstep((progress - 0.72) / 0.16),
        smoothstep((progress - 0.50) / 0.35),
        smoothstep((progress - 0.12) / 0.38),
        smoothstep((progress - 0.42) / 0.28),
        smoothstep((progress - 0.06) / 0.28),
    )
    mixed_stems = tuple(
        a[index] * gains_a[index] + b[index] * gains_b[index]
        for index in range(5)
    )

    # Deterministic generated atmosphere. It is instrumental and never a vocal.
    generated_gain = smoothstep((progress - 0.24) / 0.12) * (
        1.0 - smoothstep((progress - 0.68) / 0.14)
    )
    generated = 0.028 * generated_gain * math.sin(TAU * 146.83 * time_s)

    left, right = stereo_stems(mixed_stems)  # type: ignore[arg-type]
    left += generated
    right -= generated
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


def boundary_metrics(samples: list[float], frame: int) -> dict[str, float | int]:
    window = round(SAMPLE_RATE * 0.04)
    before = samples[(frame - window) * CHANNELS : frame * CHANNELS]
    after = samples[frame * CHANNELS : (frame + window) * CHANNELS]
    sample_jump = max(
        abs(samples[frame * CHANNELS + channel] - samples[(frame - 1) * CHANNELS + channel])
        for channel in range(CHANNELS)
    )
    derivative_jump = max(
        abs(
            samples[(frame + 1) * CHANNELS + channel]
            - 2.0 * samples[frame * CHANNELS + channel]
            + samples[(frame - 1) * CHANNELS + channel]
        )
        for channel in range(CHANNELS)
    )

    def level(values: list[float]) -> float:
        rms = math.sqrt(sum(value * value for value in values) / max(1, len(values)))
        return 20.0 * math.log10(max(rms, 1e-6))

    def flux(values: list[float]) -> float:
        derivative = sum(
            abs(values[index] - values[index - CHANNELS])
            for index in range(CHANNELS, len(values))
        ) / max(1, len(values) - CHANNELS)
        amplitude = sum(abs(value) for value in values) / max(1, len(values))
        return derivative / max(amplitude, 1e-6)

    silent = 0
    cursor = frame
    while cursor < len(samples) // CHANNELS and all(
        abs(samples[cursor * CHANNELS + channel]) <= 1e-5 for channel in range(CHANNELS)
    ):
        silent += 1
        cursor += 1
    return {
        "activationSample": frame,
        "activationGapMs": 0.0 if silent < SAMPLE_RATE // 1_000 else silent * 1_000 / SAMPLE_RATE,
        "activationUnderruns": 0,
        "activationMaxSampleJump": round(sample_jump, 8),
        "activationMaxDerivativeJump": round(derivative_jump, 8),
        "activationLufsJump": round(abs(level(after) - level(before)), 6),
        "activationSpectralFluxSpike": round(abs(flux(after) - flux(before)), 6),
    }


def main() -> None:
    audio_a = render(source_a, SOURCE_SECONDS)
    audio_b = render(source_b, SOURCE_SECONDS)
    continuous = render(continuous_stem_scene, SCENE_SECONDS)
    for audio in (audio_a, audio_b, continuous):
        edge_fade(audio)

    outputs = {
        "source-a.wav": audio_a,
        "source-b.wav": audio_b,
        "continuous-stem-scene.wav": continuous,
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
    golden_audio = GOLDEN_DIR / "continuous-stem-scene.wav"
    write_wave(golden_audio, continuous)
    activation_frame = round(SOURCE_SECONDS * SAMPLE_RATE)
    landing_frame = round((SOURCE_SECONDS + TRANSITION_SECONDS) * SAMPLE_RATE)
    activation = boundary_metrics(continuous, activation_frame)
    landing = boundary_metrics(continuous, landing_frame)
    (GOLDEN_DIR / "transition-plan.json").write_text(
        json.dumps(
            {
                "schema": 2,
                "transitionId": 9001,
                "sourceTrackId": 101,
                "targetTrackId": 202,
                "sourceStartSample": 0,
                "targetLandingSample": 0,
                "activationSample": activation_frame,
                "landingSample": landing_frame,
                "sampleRate": SAMPLE_RATE,
                "selectedStrategy": "PRESERVE_GUITAR",
                "origin": "DETERMINISTIC",
                "aiPlannerUsed": False,
                "stemSeparatorUsed": True,
                "fallbackReason": None,
                "qualityScore": 0.93,
                "confidence": 0.95,
                "selectedAnchorSet": [
                    {"semanticRole": "GUITAR", "source": "A", "confidence": 0.95},
                    {"semanticRole": "GUITAR", "source": "B", "confidence": 0.95},
                ],
                "vocalOwnershipTimeline": "AAAAAA------BBBBBB",
                "stemTimelines": [
                    {"semanticRole": "LEAD_VOCAL", "source": "A", "gain": [[0.0, 1.0], [0.28, 0.0]], "ownershipState": "TRACK_A"},
                    {"semanticRole": "LEAD_VOCAL", "source": "B", "gain": [[0.72, 0.0], [0.88, 1.0]], "ownershipState": "TRACK_B"},
                    {"semanticRole": "GUITAR", "source": "A", "gain": [[0.0, 1.0], [0.62, 1.0], [0.92, 0.0]], "ownershipState": "NONE"},
                    {"semanticRole": "GUITAR", "source": "B", "gain": [[0.50, 0.0], [0.85, 1.0]], "ownershipState": "NONE"},
                    {"semanticRole": "DRUMS", "source": "A", "gain": [[0.18, 1.0], [0.56, 0.0]], "ownershipState": "NONE"},
                    {"semanticRole": "DRUMS", "source": "B", "gain": [[0.12, 0.0], [0.50, 1.0]], "ownershipState": "NONE"},
                    {"semanticRole": "BASS", "source": "A", "gain": [[0.34, 1.0], [0.64, 0.0]], "ownershipState": "NONE"},
                    {"semanticRole": "BASS", "source": "B", "gain": [[0.42, 0.0], [0.70, 1.0]], "ownershipState": "NONE"},
                    {"semanticRole": "ATMOSPHERE", "source": "GENERATED", "gain": [[0.24, 0.0], [0.36, 1.0], [0.68, 1.0], [0.82, 0.0]], "ownershipState": "NONE"},
                ],
                "generatedVocal": False,
            },
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    (GOLDEN_DIR / "quality-report.json").write_text(
        json.dumps(
            {
                **manifests["continuous-stem-scene.wav"],
                "activation": activation,
                "landing": landing,
                "thresholds": {
                    "maxSampleJump": 0.12,
                    "maxDerivativeJump": 0.16,
                    "maxLufsJump": 3.0,
                    "maxSpectralFluxSpike": 0.35,
                },
            },
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    (AUDIO_DIR / "manifest.json").write_text(
        json.dumps({"generator": "scripts/generate_audio_fixtures.py", "files": manifests}, indent=2)
        + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
