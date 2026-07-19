package com.alexey.autoremix;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Preprocessed deterministic continuation chunks for the retained Android provider. */
final class ContinuationReservoir {
    enum ChunkKind { ONE_BAR, TWO_BARS, FOUR_BARS, EIGHT_BARS, PHRASE }

    static final int VARIATION_NONE = 0;
    static final int VARIATION_RHYTHM = 1;
    static final int VARIATION_TIMBRE = 2;

    static final class Fragment {
        final long fragmentId;
        final long sourceFragmentId;
        final long trackId;
        final TrackAnalysis.Fragment source;
        final ChunkKind kind;
        final long cueMs;
        final long durationMs;
        final int barCount;
        final int variationMask;
        final long melodicFingerprint;
        final long harmonicFingerprint;
        final long rhythmicFingerprint;
        final long arrangementFingerprint;
        final float energy;
        final float boundaryScore;

        Fragment(long fragmentId, long sourceFragmentId, long trackId,
                 TrackAnalysis.Fragment source, ChunkKind kind, long cueMs,
                 long durationMs, int barCount, int variationMask,
                 long melodicFingerprint, long harmonicFingerprint,
                 long rhythmicFingerprint, long arrangementFingerprint,
                 float energy, float boundaryScore) {
            this.fragmentId = fragmentId;
            this.sourceFragmentId = sourceFragmentId;
            this.trackId = trackId;
            this.source = source;
            this.kind = kind;
            this.cueMs = cueMs;
            this.durationMs = durationMs;
            this.barCount = barCount;
            this.variationMask = variationMask;
            this.melodicFingerprint = melodicFingerprint;
            this.harmonicFingerprint = harmonicFingerprint;
            this.rhythmicFingerprint = rhythmicFingerprint;
            this.arrangementFingerprint = arrangementFingerprint;
            this.energy = energy;
            this.boundaryScore = boundaryScore;
        }
    }

    static final class Edge {
        final long fromFragmentId;
        final long toFragmentId;
        final float score;

        Edge(long fromFragmentId, long toFragmentId, float score) {
            this.fromFragmentId = fromFragmentId;
            this.toFragmentId = toFragmentId;
            this.score = score;
        }
    }

    static final class CurrentContext {
        final long currentFragmentId;
        final long currentSourceFragmentId;
        final long anchorSourceFragmentId;
        final float energy;

        CurrentContext(long currentFragmentId, long currentSourceFragmentId,
                       long anchorSourceFragmentId, float energy) {
            this.currentFragmentId = currentFragmentId;
            this.currentSourceFragmentId = currentSourceFragmentId;
            this.anchorSourceFragmentId = anchorSourceFragmentId;
            this.energy = energy;
        }
    }

    static final class TargetTrackContext {
        final float energy;

        TargetTrackContext(float energy) {
            this.energy = energy;
        }
    }

    static final class DeviceBudget {
        final int maxCandidates;
        final int maxExpansions;

        DeviceBudget(int maxCandidates, int maxExpansions) {
            this.maxCandidates = Math.max(1, Math.min(32, maxCandidates));
            this.maxExpansions = Math.max(1, Math.min(2_048, maxExpansions));
        }

        static DeviceBudget realtime() {
            return new DeviceBudget(12, 256);
        }
    }

    private final List<Fragment> fragments;
    private final List<Edge> edges;
    private final Map<Long, List<Edge>> outgoing;

    private ContinuationReservoir(List<Fragment> fragments, List<Edge> edges) {
        this.fragments = List.copyOf(fragments);
        this.edges = List.copyOf(edges);
        Map<Long, List<Edge>> graph = new HashMap<>();
        for (Edge edge : edges) {
            graph.computeIfAbsent(edge.fromFragmentId, ignored -> new ArrayList<>()).add(edge);
        }
        this.outgoing = graph;
    }

    static ContinuationReservoir fromTrack(long trackId, long durationMs,
                                           TrackAnalysis analysis) {
        List<Fragment> bank = new ArrayList<>();
        Set<Long> ids = new HashSet<>();
        int sourceIndex = 0;
        for (TrackAnalysis.Fragment source : analysis.fragments) {
            long sourceId = nonZeroHash(trackId, source.cueMs, sourceIndex++);
            addChunk(bank, ids, trackId, durationMs, analysis, source, sourceId,
                    ChunkKind.ONE_BAR, 1, VARIATION_NONE);
            addChunk(bank, ids, trackId, durationMs, analysis, source, sourceId,
                    ChunkKind.TWO_BARS, 2, VARIATION_NONE);
            addChunk(bank, ids, trackId, durationMs, analysis, source, sourceId,
                    ChunkKind.FOUR_BARS, 4, VARIATION_NONE);
            addChunk(bank, ids, trackId, durationMs, analysis, source, sourceId,
                    ChunkKind.FOUR_BARS, 4, VARIATION_RHYTHM);
            addChunk(bank, ids, trackId, durationMs, analysis, source, sourceId,
                    ChunkKind.FOUR_BARS, 4, VARIATION_TIMBRE);
            addChunk(bank, ids, trackId, durationMs, analysis, source, sourceId,
                    ChunkKind.EIGHT_BARS, 8, VARIATION_NONE);
            addChunk(bank, ids, trackId, durationMs, analysis, source, sourceId,
                    ChunkKind.PHRASE, 8, VARIATION_NONE);
        }

        List<Edge> graph = new ArrayList<>();
        for (Fragment from : bank) {
            for (Fragment to : bank) {
                if (from.fragmentId == to.fragmentId
                        || from.sourceFragmentId == to.sourceFragmentId) continue;
                float energyFit = 1f - Math.min(1f, Math.abs(from.energy - to.energy));
                float gridFit = Math.min(from.source.gridConfidence, to.source.gridConfidence);
                float phraseFit = Math.min(from.source.phraseScore, to.source.phraseScore);
                float score = clamp01(.45f * energyFit + .30f * gridFit
                        + .20f * phraseFit + .05f * to.boundaryScore);
                graph.add(new Edge(from.fragmentId, to.fragmentId, score));
            }
        }
        return new ContinuationReservoir(bank, graph);
    }

    List<Fragment> fragments() {
        return fragments;
    }

    List<Edge> edges() {
        return edges;
    }

    long sourceIdFor(TrackAnalysis.Fragment source) {
        Fragment best = null;
        long distance = Long.MAX_VALUE;
        for (Fragment fragment : fragments) {
            long candidateDistance = Math.abs(fragment.source.cueMs - source.cueMs);
            if (candidateDistance < distance) {
                best = fragment;
                distance = candidateDistance;
            }
        }
        return best == null ? 0L : best.sourceFragmentId;
    }

    List<Fragment> getCandidates(CurrentContext currentContext, int desiredBars,
                                 Collection<Long> excludedFragmentIds,
                                 Collection<Long> recentMelodicFingerprints,
                                 TargetTrackContext targetTrackContext,
                                 DeviceBudget deviceBudget) {
        Set<Long> excluded = new HashSet<>(excludedFragmentIds);
        Set<Long> melodic = new HashSet<>(recentMelodicFingerprints);
        Map<Long, Float> graphScores = new HashMap<>();
        for (Edge edge : outgoing.getOrDefault(currentContext.currentFragmentId, List.of())) {
            graphScores.put(edge.toFragmentId, edge.score);
        }

        List<Fragment> result = new ArrayList<>();
        for (Fragment fragment : fragments) {
            if (fragment.fragmentId == currentContext.currentFragmentId
                    || fragment.sourceFragmentId == currentContext.currentSourceFragmentId
                    || excluded.contains(fragment.sourceFragmentId)
                    || (fragment.melodicFingerprint != 0L
                    && melodic.contains(fragment.melodicFingerprint))) continue;
            result.add(fragment);
        }
        result.sort(Comparator.comparingDouble((Fragment fragment) -> {
            float graph = graphScores.getOrDefault(fragment.fragmentId, .5f);
            float energy = 1f - Math.min(1f,
                    Math.abs(fragment.energy - targetTrackContext.energy));
            float bars = 1f - Math.min(1f,
                    Math.abs(fragment.barCount - Math.min(4, desiredBars)) / 4f);
            float variationPenalty = fragment.variationMask == VARIATION_NONE ? 0f : .08f;
            return graph * .45f + energy * .25f + bars * .20f
                    + fragment.boundaryScore * .10f - variationPenalty;
        }).reversed().thenComparingLong(fragment -> fragment.fragmentId));
        if (result.size() > deviceBudget.maxCandidates) {
            return new ArrayList<>(result.subList(0, deviceBudget.maxCandidates));
        }
        return result;
    }

    private static void addChunk(List<Fragment> bank, Set<Long> ids, long trackId,
                                 long trackDurationMs, TrackAnalysis analysis,
                                 TrackAnalysis.Fragment source, long sourceId,
                                 ChunkKind kind, int bars, int variationMask) {
        long beatMs = Math.max(240L, source.beatPeriodMs);
        long requestedMs = beatMs * 4L * bars;
        long maximumCue = Math.max(0L, trackDurationMs - requestedMs - 1_000L);
        long cueMs = Math.min(source.cueMs, maximumCue);
        long durationMs = Math.min(requestedMs,
                Math.max(0L, trackDurationMs - cueMs - 1_000L));
        if (durationMs < 2_000L) return;
        long fragmentId = nonZeroHash(sourceId, kind.ordinal(), variationMask);
        if (!ids.add(fragmentId)) return;
        long melodic = nonZeroHash(sourceId,
                Math.round(source.brightness * 100f),
                Math.round(source.vocalPresence * 100f));
        long harmonic = nonZeroHash(trackId, analysis.key,
                Math.round(source.energy * 100f));
        long rhythmic = nonZeroHash(source.beatPeriodMs,
                Math.round(source.transientDensity * 100f),
                Math.round(source.percussiveness * 100f));
        long arrangement = nonZeroHash(sourceId, variationMask, kind.ordinal());
        float boundary = clamp01(source.gridConfidence * .55f
                + source.phraseScore * .45f);
        bank.add(new Fragment(fragmentId, sourceId, trackId, source, kind, cueMs,
                durationMs, bars, variationMask, melodic, harmonic, rhythmic,
                arrangement, source.energy, boundary));
    }

    private static long nonZeroHash(long a, long b, long c) {
        long value = 0xcbf29ce484222325L;
        value = (value ^ a) * 0x100000001b3L;
        value = (value ^ b) * 0x100000001b3L;
        value = (value ^ c) * 0x100000001b3L;
        return value == 0L ? 1L : value;
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
