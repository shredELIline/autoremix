# AutoRemix WOW — PCM Stem Director 2.0

Android 10+. The application scans ordinary local audio files exposed through Android MediaStore.
It does not copy, export, or upload source music.

## Product invariant

Automatic mode never performs a whole-track cut from A to B. Every generated scene keeps at least
one perceptual musical anchor continuous while other roles change gradually:

- lead/vocal A over a morphing backing B;
- groove/drums A under a gradually introduced lead B;
- bass or drum bridge between compatible arrangements;
- shared harmonic/atmospheric bed during a staged takeover;
- temporary guest lead/backing that returns to A.

When no genuinely compatible continuation exists, A continues. The engine does not select the
least-bad song and does not expose an emergency abrupt fallback.

## 2.0 audio architecture

1. MediaCodec decodes local segments ahead of playback to stereo float PCM.
2. WSOLA matches tempo within a conservative ±10% range.
3. A beat-phase aligner searches one beat of the incoming signal using onset-envelope correlation.
4. A complementary STFT HPSS + mid/side separator creates lead, drums, bass, and backing layers.
5. The continuity director chooses one of nine role narratives using fragment role, vibe, key, BPM,
   phrase score, and beat-grid confidence.
6. Sample-level cosine automation and vocal-priority sidechain ducking render a single PCM scene.
7. A DC blocker, limiter, soft saturation, and edge-safe scene crossfades feed one AudioTrack.

There are no MediaPlayer decks in the 2.0 runtime.

## Same-vibe routing

Candidate tracks are compared using their measured sound rather than filename genre labels:
brightness, transient density, dynamics, bass weight, percussion density, vocal balance, energy,
key, tempo, phrase readiness, and beat-grid stability. A local vibe graph also favours candidates
that retain several high-quality continuation routes, avoiding a good one-off transition that leads
to a stylistic dead end. Randomness is allowed only inside a near-equal top beam.

## Source-separation honesty

The bundled separator is a strong deterministic mobile algorithm, not a bundled Demucs neural
network. It preserves phase and reconstructs the original exactly when all four layers are summed,
which is important for smooth long morphs. It cannot produce a studio-clean acapella from every
mastered song. A future neural provider can replace the separator while keeping the same PCM scene
and continuity APIs.

## Installation

Install the signed debug APK on Android 10 or newer, grant access to music, and press
“ЗАПУСТИТЬ БЕСКОНЕЧНЫЙ РЕМИКС”. The first candidate analysis and scene render may take noticeable
CPU time because all processing is local and no cloud service is used.
