# ADR 0003: Deterministic Tier C first

- Status: Accepted
- Date: 2026-07-19

## Decision

Ship a complete deterministic bridge before selecting neural weights. Keep all
model capabilities behind replaceable providers. Do not bundle or download a
model until its code, weights, and dataset licenses plus checksums and measured
device costs are recorded.

## Why

The repository has no legally reviewed weights or device benchmarks. Calling
the spectral separator AI would be false. Deterministic non-repeating graph
continuation, WSOLA, phase-aligned overlap-add, granular instrumental texture,
and staged stem handoff provide a valid offline fallback on every supported
device.

## Consequences

Tier A and learned parts of Tier B remain unavailable by default. The UI shows
the selected quality capability without promising neural generation.
