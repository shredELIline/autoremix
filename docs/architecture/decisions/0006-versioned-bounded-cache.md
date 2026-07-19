# ADR 0006: Versioned bounded cache

- Status: Accepted
- Date: 2026-07-19

## Decision

Use content-addressed keys containing both content hashes, exit/entry samples,
anchor, tier, model versions, and engine version. Store metadata atomically,
enforce a user-visible byte budget, and evict unpinned least-recently-used data.

Do not choose a permanent stem-audio encoding until device measurements compare
chunked int16, lossless compression, and on-demand regeneration.

## Consequences

Active and near-future queue artifacts are pinned. Schema/model changes remove
only incompatible entries. Corruption behaves as a cache miss.
