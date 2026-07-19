# ADR 0002: Explicit stem plan and vocal provenance

- Status: Accepted
- Date: 2026-07-19

## Decision

Represent a transition as independent immutable stem timelines. Make anchor,
source provenance, entry/exit sample, and automation explicit. Reject a plan if
the anchor has an audible gap or a vocal timeline uses generated provenance.

## Why

A narrative enum cannot prove continuity, schedule arbitrary points, or express
separate stem replacement. Vocal provenance is a product and safety invariant,
not a UI preference.

## Consequences

Legacy gain narratives become candidate templates. They must compile to and
validate as explicit plans before playback.
