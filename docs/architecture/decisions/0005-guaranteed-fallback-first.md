# ADR 0005: Build the guaranteed fallback first

- Status: Accepted
- Date: 2026-07-19

## Decision

When a target enters the queue, prepare a deterministic end-of-track fallback
before optional expensive candidates. Better candidates may replace it only
after validation. Failure or cancellation keeps the fallback pinned.

Fallback order is cached approved bridge, new approved provider candidate,
deterministic multi-stem bridge, legacy intelligent bridge, phrase-aligned
fade, then emergency click-free normalized fade.

## Consequences

Playback does not wait for a model. A manual Next uses a short escape plan.
Rapid Next changes only the final target epoch and does not build intermediate
bridges.
