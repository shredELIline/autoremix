package com.alexey.autoremix;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Bounded beam planner for 16-32 bars of evolving deterministic continuation. */
final class NonRepeatingContinuationPlanner {
    static final class Plan {
        final List<ContinuationReservoir.Fragment> fragments;
        final int bars;
        final float score;
        final boolean complete;
        final int expandedNodes;
        final RepetitionQualityEvaluator.Evaluation repetition;

        Plan(List<ContinuationReservoir.Fragment> fragments, int bars, float score,
             boolean complete, int expandedNodes,
             RepetitionQualityEvaluator.Evaluation repetition) {
            this.fragments = List.copyOf(fragments);
            this.bars = bars;
            this.score = score;
            this.complete = complete;
            this.expandedNodes = expandedNodes;
            this.repetition = repetition;
        }
    }

    private static final int REUSE_WINDOW = 2;
    private static final int FINGERPRINT_WINDOW = 2;
    private static final int BEAM_WIDTH = 8;
    private final ContinuationReservoir reservoir;

    NonRepeatingContinuationPlanner(ContinuationReservoir reservoir) {
        this.reservoir = reservoir;
    }

    Plan plan(ContinuationReservoir.CurrentContext current,
              ContinuationReservoir.TargetTrackContext target,
              ContinuationReservoir.DeviceBudget budget, int requestedBars,
              Collection<Long> excludedFragmentIds,
              Collection<Long> recentMelodicFingerprints) {
        int targetBars = Math.max(16, Math.min(32, requestedBars));
        List<Beam> beam = List.of(new Beam(new ArrayList<>(), current, 0, 0f,
                current.anchorSourceFragmentId != 0L
                        && current.anchorSourceFragmentId == current.currentSourceFragmentId ? 1 : 0));
        Beam bestPartial = null;
        Beam bestComplete = null;
        int expansions = 0;
        while (!beam.isEmpty() && expansions < budget.maxExpansions) {
            List<Beam> next = new ArrayList<>();
            for (Beam parent : beam) {
                Set<Long> excluded = new HashSet<>(excludedFragmentIds);
                appendRecentSourceIds(parent.fragments, excluded, REUSE_WINDOW);
                List<Long> melodic = new ArrayList<>(recentMelodicFingerprints);
                appendRecentMelodic(parent.fragments, melodic, FINGERPRINT_WINDOW);
                List<ContinuationReservoir.Fragment> candidates = reservoir.getCandidates(
                        parent.context, targetBars - parent.bars, excluded, melodic,
                        target, budget);
                for (ContinuationReservoir.Fragment candidate : candidates) {
                    if (expansions++ >= budget.maxExpansions) break;
                    if (candidate.barCount > 4 || !allowed(parent, candidate)) continue;
                    int usedBars = Math.min(candidate.barCount, targetBars - parent.bars);
                    List<ContinuationReservoir.Fragment> fragments =
                            new ArrayList<>(parent.fragments);
                    fragments.add(candidate);
                    int anchorUses = parent.anchorUses
                            + (candidate.sourceFragmentId == current.anchorSourceFragmentId ? 1 : 0);
                    float edge = edgeScore(parent.context.currentFragmentId,
                            candidate.fragmentId);
                    float score = parent.score + edge + candidate.boundaryScore * .25f
                            + (candidate.variationMask == ContinuationReservoir.VARIATION_NONE
                            ? .08f : 0f);
                    Beam child = new Beam(fragments,
                            new ContinuationReservoir.CurrentContext(candidate.fragmentId,
                                    candidate.sourceFragmentId,
                                    current.anchorSourceFragmentId, candidate.energy),
                            parent.bars + usedBars, score, anchorUses);
                    if (bestPartial == null || betterProgress(child, bestPartial)) {
                        bestPartial = child;
                    }
                    if (child.bars >= targetBars) {
                        RepetitionQualityEvaluator.Evaluation quality =
                                new RepetitionQualityEvaluator().evaluate(child.fragments);
                        if (quality.accepted
                                && (bestComplete == null || child.score > bestComplete.score)) {
                            bestComplete = child;
                        }
                    } else {
                        next.add(child);
                    }
                }
            }
            next.sort(Comparator.comparingInt((Beam state) -> state.bars).reversed()
                    .thenComparing(Comparator.comparingDouble(
                            (Beam state) -> state.score).reversed()));
            if (next.size() > BEAM_WIDTH) next = new ArrayList<>(next.subList(0, BEAM_WIDTH));
            beam = next;
        }

        Beam selected = bestComplete != null ? bestComplete : bestPartial;
        List<ContinuationReservoir.Fragment> fragments = selected == null
                ? List.of() : selected.fragments;
        RepetitionQualityEvaluator.Evaluation repetition =
                new RepetitionQualityEvaluator().evaluate(fragments);
        int bars = selected == null ? 0 : selected.bars;
        float score = selected == null ? Float.NEGATIVE_INFINITY : selected.score
                + repetition.metrics.noveltyPerBar * .25f
                - repetition.metrics.melodicRepeatRate;
        return new Plan(fragments, bars,
                score, bars >= targetBars && repetition.accepted, expansions, repetition);
    }

    private boolean allowed(Beam parent, ContinuationReservoir.Fragment candidate) {
        int start = Math.max(0, parent.fragments.size() - REUSE_WINDOW);
        boolean reused = false;
        for (int index = 0; index < parent.fragments.size(); index++) {
            ContinuationReservoir.Fragment previous = parent.fragments.get(index);
            if (previous.fragmentId == candidate.fragmentId) return false;
            if (previous.sourceFragmentId == candidate.sourceFragmentId) {
                reused = true;
                if (index >= start) return false;
            }
        }
        if (reused && candidate.variationMask == ContinuationReservoir.VARIATION_NONE) {
            return false;
        }
        if (candidate.sourceFragmentId == parent.context.anchorSourceFragmentId
                && parent.anchorUses >= 2) return false;
        if (!parent.fragments.isEmpty()) {
            ContinuationReservoir.Fragment previous =
                    parent.fragments.get(parent.fragments.size() - 1);
            if (previous.melodicFingerprint == candidate.melodicFingerprint
                    && previous.harmonicFingerprint == candidate.harmonicFingerprint
                    && previous.rhythmicFingerprint == candidate.rhythmicFingerprint) return false;
            if (previous.arrangementFingerprint == candidate.arrangementFingerprint
                    && previous.barCount + candidate.barCount > 4) return false;
        }
        return true;
    }

    private float edgeScore(long from, long to) {
        if (from == 0L) return .5f;
        for (ContinuationReservoir.Edge edge : reservoir.edges()) {
            if (edge.fromFragmentId == from && edge.toFragmentId == to) return edge.score;
        }
        return .35f;
    }

    private static boolean betterProgress(Beam left, Beam right) {
        return left.bars > right.bars || left.bars == right.bars && left.score > right.score;
    }

    private static void appendRecentSourceIds(
            List<ContinuationReservoir.Fragment> fragments, Set<Long> output, int window) {
        int start = Math.max(0, fragments.size() - window);
        for (int index = start; index < fragments.size(); index++) {
            output.add(fragments.get(index).sourceFragmentId);
        }
    }

    private static void appendRecentMelodic(
            List<ContinuationReservoir.Fragment> fragments, List<Long> output, int window) {
        int start = Math.max(0, fragments.size() - window);
        for (int index = start; index < fragments.size(); index++) {
            output.add(fragments.get(index).melodicFingerprint);
        }
    }

    private static final class Beam {
        final List<ContinuationReservoir.Fragment> fragments;
        final ContinuationReservoir.CurrentContext context;
        final int bars;
        final float score;
        final int anchorUses;

        Beam(List<ContinuationReservoir.Fragment> fragments,
             ContinuationReservoir.CurrentContext context,
             int bars, float score, int anchorUses) {
            this.fragments = fragments;
            this.context = context;
            this.bars = bars;
            this.score = score;
            this.anchorUses = anchorUses;
        }
    }
}
