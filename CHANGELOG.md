# Changelog

## 2.0.0 — PCM Stem Director

- Replaced two vendor-controlled MediaPlayer decks with one pre-rendered PCM timeline and one
  AudioTrack master output.
- Added MediaCodec segment decoding and linear sample-rate conversion.
- Added local four-role separation: lead, drums, bass, and backing via complementary STFT HPSS plus
  center/side analysis.
- Added WSOLA tempo matching with a strict ±10% safety range.
- Added incoming beat-phase alignment using onset-envelope correlation inside one beat.
- Added nine continuity-only layer narratives; no automatic whole-track cut exists in the new engine.
- Added vocal-priority sidechain space management and sequential lead relay for two vocal tracks.
- Added strict same-vibe filtering and a two-step local vibe graph to avoid future route dead ends.
- Added controlled top-beam randomness so the output remains a random remix without sacrificing a
  clearly better match.
- Added sample-level cosine automation, complementary-stem endpoint reconstruction, DC blocking,
  limiter, soft saturation, and scene-boundary crossfades.
- Fixed an internal normalisation error that could drop level at the exact start of a stem scene.
- Fixed sidechain activation before an incoming lead was audible.
- Removed constant-sample padding that could create DC edges.
- Limited render-ahead to one queued scene to reduce peak memory pressure.
- Added reconstruction, layer endpoint, continuity director, beat phase, WSOLA, and master-bus tests.
