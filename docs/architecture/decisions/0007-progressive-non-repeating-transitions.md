# ADR 0007: Progressive non-repeating transitions

- Status: Accepted
- Date: 2026-07-19

## Decision

Separate target selection, bridge preparation, arming, and audible activation.
Track A continues through its natural runway until a technically valid Level 0
candidate is ready and a future musical boundary is reached.

When runway is short, use a versioned continuation reservoir and bounded graph
search to render distinct deterministic blocks. Never extend playback with an
unbounded self-edge or one repeated anchor. Keep 2 bars committed, at least 8
bars playable, a 16–32 bar rendered target, and 32–64 bars of planning context.

Optional enhanced or neural providers may replace only uncommitted future
audio at a safe boundary. Their failure or cancellation cannot reduce the
guaranteed deterministic horizon.

## Consequences

Playback no longer starts transition audio merely because a target exists.
Planning needs fragment identity, repetition history, plan epochs, candidate
quality, and boundary metadata. More PCM is prepared ahead, but expensive work
is bounded and stopped at the low watermark.

The release remains deterministic until named-device measurements and licensing
justify a neural provider. No latency or quality gain is claimed without those
measurements.
