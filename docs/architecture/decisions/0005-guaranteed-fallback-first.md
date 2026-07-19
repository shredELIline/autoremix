# ADR 0005: Build the guaranteed fallback first

- Status: Superseded by continuous-stem-primary runtime
- Date: 2026-07-19

## Decision

When a target enters the queue, prepare a deterministic end-of-track fallback
before optional expensive candidates. Better candidates may replace it only
after validation. Failure or cancellation keeps the fallback pinned.

Current order is AI-authored layered stems when a provider exists, deterministic
layered stems, legacy intelligent transition, phrase-aware crossfade, then basic
crossfade. Every non-stem result records a fallback reason. No cached bridge
asset is started by the realtime path.

## Consequences

Playback does not wait for a model. A manual Next uses a short escape plan.
Rapid Next changes only the final target epoch and does not build intermediate
bridges.
