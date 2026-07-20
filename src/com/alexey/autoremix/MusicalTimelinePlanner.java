package com.alexey.autoremix;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Chooses original-song entry, transition and exit positions on a musical grid. */
final class MusicalTimelinePlanner {
    private static final int[] RUNWAY_BARS = {16, 24, 32, 40, 48, 56};
    private static final int[] TRANSITION_BARS = {6, 8, 10, 12, 16};
    private static final int MIN_TRANSITION_BARS = 6;
    private static final int MAX_TRANSITION_BARS = 16;

    static final class TrackPlan {
        final long trackId;
        final long durationMs;
        final long entryPositionMs;
        final long transitionStartPositionMs;
        final long trackExitPositionMs;
        final long estimatedTransitionLengthMs;
        final float confidence;

        TrackPlan(long trackId, long durationMs, long entryPositionMs,
                  long transitionStartPositionMs, long trackExitPositionMs,
                  long estimatedTransitionLengthMs, float confidence) {
            this.trackId = trackId;
            this.durationMs = Math.max(1L, durationMs);
            this.entryPositionMs = clamp(entryPositionMs, this.durationMs);
            this.transitionStartPositionMs = clamp(transitionStartPositionMs, this.durationMs);
            this.trackExitPositionMs = clamp(trackExitPositionMs, this.durationMs);
            this.estimatedTransitionLengthMs = Math.max(1L, estimatedTransitionLengthMs);
            this.confidence = clamp01(confidence);
        }

        TrackPlaybackTimeline timeline(long currentPositionMs,
                                       TrackPlaybackTimeline.MarkerStatus markerStatus,
                                       Long landingPositionFromPreviousMs) {
            return new TrackPlaybackTimeline(trackId, durationMs, entryPositionMs,
                    currentPositionMs, transitionStartPositionMs, trackExitPositionMs,
                    landingPositionFromPreviousMs, confidence,
                    TrackPlaybackTimeline.MarkerSource.AI_AUTOMATIC,
                    markerStatus, false);
        }

        TrackPlan withTransition(long transitionStartMs, long exitPositionMs,
                                 long transitionLengthMs, float planConfidence) {
            return new TrackPlan(trackId, durationMs, entryPositionMs,
                    transitionStartMs, exitPositionMs, transitionLengthMs,
                    Math.max(confidence, planConfidence));
        }

        TrackPlan postponedTo(long transitionStartMs, long barMs) {
            long start = Math.max(entryPositionMs,
                    Math.min(durationMs, transitionStartMs));
            long exit = Math.min(durationMs, start + estimatedTransitionLengthMs);
            return new TrackPlan(trackId, durationMs, entryPositionMs, start, exit,
                    estimatedTransitionLengthMs,
                    Math.max(.25f, confidence - Math.min(.2f,
                            barMs / (float) Math.max(1L, durationMs))));
        }
    }

    private MusicalTimelinePlanner() {}

    static TrackPlan planTrack(
            Track track, TrackAnalysis analysis, TrackAnalysis.Fragment entryFragment,
            long entryPositionMs, int sequence) {
        return planTrack(track, analysis, entryFragment, entryPositionMs,
                entryPositionMs, sequence);
    }

    static TrackPlan planTrackAfterRenderedRunway(
            Track track, TrackAnalysis analysis, TrackAnalysis.Fragment entryFragment,
            long entryPositionMs, long renderedThroughPositionMs, int sequence) {
        return planTrack(track, analysis, entryFragment, entryPositionMs,
                renderedThroughPositionMs, sequence);
    }

    private static TrackPlan planTrack(
            Track track, TrackAnalysis analysis, TrackAnalysis.Fragment entryFragment,
            long entryPositionMs, long renderedThroughPositionMs, int sequence) {
        if (track == null || analysis == null || entryFragment == null) {
            throw new IllegalArgumentException("track timeline inputs");
        }
        long durationMs = Math.max(1L, track.durationMs);
        long entry = clamp(entryPositionMs, durationMs);
        long availableFrom = Math.max(entry, clamp(renderedThroughPositionMs, durationMs));
        long barMs = barMs(analysis, entryFragment);
        long signature = mix(track.id, sequence,
                Math.round(analysis.energy * 1_000f), Math.round(analysis.bpm * 10f));
        int runwayBars = RUNWAY_BARS[Math.floorMod(signature, RUNWAY_BARS.length)];
        int transitionBars = TRANSITION_BARS[Math.floorMod(signature >>> 7,
                TRANSITION_BARS.length)];
        long transitionLength = transitionBars * barMs;
        long safetyTail = Math.max(barMs * 2L, transitionLength / 4L);
        long latestStart = Math.max(availableFrom, durationMs - transitionLength - safetyTail);
        long earliestStart = Math.min(latestStart, availableFrom + barMs * 8L);
        long desired = clamp(availableFrom + runwayBars * barMs, latestStart);

        List<TrackAnalysis.Fragment> candidates = new ArrayList<>(analysis.fragments);
        candidates.sort(Comparator.comparingLong(fragment -> fragment.cueMs));
        long selected = desired;
        float bestScore = -Float.MAX_VALUE;
        float selectedConfidence = entryFragment.gridConfidence;
        for (TrackAnalysis.Fragment fragment : candidates) {
            long snapped = snapToBar(fragment.cueMs, entry, barMs,
                    fragment.gridConfidence >= .40f);
            if (snapped < earliestStart || snapped > latestStart) continue;
            float distance = Math.abs(snapped - desired)
                    / (float) Math.max(barMs * Math.max(8, runwayBars), 1L);
            float score = fragment.phraseScore * .42f + fragment.gridConfidence * .31f
                    + fragment.mixReadiness() * .19f - distance * .28f
                    - (fragment.isVocalHeavy() ? .08f : 0f);
            if (score > bestScore) {
                bestScore = score;
                selected = snapped;
                selectedConfidence = fragment.gridConfidence * .55f
                        + fragment.phraseScore * .45f;
            }
        }
        selected = snapToBar(clamp(selected, latestStart), entry, barMs,
                entryFragment.gridConfidence >= .40f);
        selected = Math.max(earliestStart, Math.min(latestStart, selected));
        long exit = Math.min(durationMs, selected + transitionLength);
        return new TrackPlan(track.id, durationMs, entry, selected, exit,
                transitionLength, selectedConfidence);
    }

    static long transitionLengthMs(
            TrackAnalysis source, TrackAnalysis.Fragment sourceFragment,
            TrackAnalysis target, TrackAnalysis.Fragment targetFragment,
            LayerPlan hint, long sourceRemainingMs, long targetRemainingMs) {
        return transitionLengthMs(source, sourceFragment, target, targetFragment, hint,
                sourceRemainingMs, targetRemainingMs, 0L);
    }

    static long transitionLengthMs(
            TrackAnalysis source, TrackAnalysis.Fragment sourceFragment,
            TrackAnalysis target, TrackAnalysis.Fragment targetFragment,
            LayerPlan hint, long sourceRemainingMs, long targetRemainingMs,
            long plannedTransitionMs) {
        if (source == null || sourceFragment == null || target == null
                || targetFragment == null || hint == null) {
            throw new IllegalArgumentException("transition timeline inputs");
        }
        long sourceBar = barMs(source, sourceFragment);
        int hintedBars = Math.max(MIN_TRANSITION_BARS, hint.bars);
        long requested = plannedTransitionMs > 0L
                ? plannedTransitionMs : sourceBar * hintedBars;
        long minimum = sourceBar * MIN_TRANSITION_BARS;
        long maximum = sourceBar * MAX_TRANSITION_BARS;
        long musical = Math.max(minimum, Math.min(maximum,
                Math.round(requested / (double) sourceBar) * sourceBar));
        long available = Math.max(0L, Math.min(sourceRemainingMs,
                Math.round(targetRemainingMs / Math.max(.25f, hint.tempoRatio))));
        if (available < minimum) return minimum;
        long bounded = Math.min(musical, available);
        long bars = Math.max(MIN_TRANSITION_BARS,
                bounded / Math.max(1L, sourceBar));
        return bars * sourceBar;
    }

    static long barMs(TrackAnalysis analysis, TrackAnalysis.Fragment fragment) {
        long beat = fragment == null ? 0L : fragment.beatPeriodMs;
        if (beat <= 0L) {
            float bpm = analysis == null || !Float.isFinite(analysis.bpm)
                    ? 120f : analysis.bpm;
            beat = Math.round(60_000f / Math.max(65f, bpm));
        }
        return Math.max(1L, beat * 4L);
    }

    private static long snapToBar(long positionMs, long originMs, long barMs,
                                  boolean stableGrid) {
        if (!stableGrid || barMs <= 0L) return positionMs;
        long delta = Math.max(0L, positionMs - originMs);
        return originMs + Math.round(delta / (double) barMs) * barMs;
    }

    private static int mix(long trackId, int sequence, long energy, long bpm) {
        long value = trackId * 0x9E3779B97F4A7C15L;
        value ^= (long) sequence * 0xBF58476D1CE4E5B9L;
        value ^= energy * 0x94D049BB133111EBL;
        value ^= bpm + (value >>> 31);
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        return (int) (value ^ value >>> 32);
    }

    private static long clamp(long value, long maximum) {
        return Math.max(0L, Math.min(maximum, value));
    }

    private static float clamp01(float value) {
        return Float.isFinite(value) ? Math.max(0f, Math.min(1f, value)) : 0f;
    }
}
