# ADR 0004: Quality gates before ranking

- Status: Accepted
- Date: 2026-07-19

## Decision

Run hard technical gates before a weighted musical score. Reject non-finite
samples, clipping, excessive boundary DC/derivatives, missing anchor coverage,
generated vocals, and invalid plan timing. Rank survivors across deterministic
and future neural origins with the same evaluator.

## Why

A high learned or musical score cannot make a click, NaN, missing anchor, or
unsafe vocal provenance acceptable.

## Consequences

Every rejection carries diagnostics. Threshold changes require fixtures and an
ADR update. Model confidence never bypasses technical gates.
