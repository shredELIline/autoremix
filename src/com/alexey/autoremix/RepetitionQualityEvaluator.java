package com.alexey.autoremix;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Rejects obvious fragment, melody, rhythm and full-arrangement repetition. */
final class RepetitionQualityEvaluator {
    static final class Metrics {
        final float exactFragmentRepeatRate;
        final float melodicRepeatRate;
        final float harmonicRepeatRate;
        final float rhythmicRepeatRate;
        final float fullArrangementRepeatRate;
        final int longestIdenticalRunBars;
        final float noveltyPerBar;

        Metrics(float exactFragmentRepeatRate, float melodicRepeatRate,
                float harmonicRepeatRate, float rhythmicRepeatRate,
                float fullArrangementRepeatRate, int longestIdenticalRunBars,
                float noveltyPerBar) {
            this.exactFragmentRepeatRate = exactFragmentRepeatRate;
            this.melodicRepeatRate = melodicRepeatRate;
            this.harmonicRepeatRate = harmonicRepeatRate;
            this.rhythmicRepeatRate = rhythmicRepeatRate;
            this.fullArrangementRepeatRate = fullArrangementRepeatRate;
            this.longestIdenticalRunBars = longestIdenticalRunBars;
            this.noveltyPerBar = noveltyPerBar;
        }
    }

    static final class Evaluation {
        final Metrics metrics;
        final boolean accepted;

        Evaluation(Metrics metrics, boolean accepted) {
            this.metrics = metrics;
            this.accepted = accepted;
        }
    }

    Evaluation evaluate(List<ContinuationReservoir.Fragment> fragments) {
        if (fragments.isEmpty()) {
            return new Evaluation(new Metrics(0f, 0f, 0f, 0f, 0f, 0, 1f), true);
        }
        Set<Long> exact = new HashSet<>();
        Set<Long> melodic = new HashSet<>();
        Set<Long> harmonic = new HashSet<>();
        Set<Long> rhythmic = new HashSet<>();
        Set<Long> arrangements = new HashSet<>();
        int exactRepeats = 0;
        int melodicRepeats = 0;
        int harmonicRepeats = 0;
        int rhythmicRepeats = 0;
        int arrangementRepeats = 0;
        int totalBars = 0;
        int novelBars = 0;
        int longestRun = 0;
        int currentRun = 0;
        long previousArrangement = 0L;
        for (ContinuationReservoir.Fragment fragment : fragments) {
            exactRepeats += exact.add(fragment.fragmentId) ? 0 : 1;
            melodicRepeats += melodic.add(fragment.melodicFingerprint) ? 0 : 1;
            harmonicRepeats += harmonic.add(fragment.harmonicFingerprint) ? 0 : 1;
            rhythmicRepeats += rhythmic.add(fragment.rhythmicFingerprint) ? 0 : 1;
            boolean novelArrangement = arrangements.add(fragment.arrangementFingerprint);
            arrangementRepeats += novelArrangement ? 0 : 1;
            totalBars += fragment.barCount;
            if (novelArrangement) novelBars += fragment.barCount;
            currentRun = previousArrangement == fragment.arrangementFingerprint
                    ? currentRun + fragment.barCount : fragment.barCount;
            longestRun = Math.max(longestRun, currentRun);
            previousArrangement = fragment.arrangementFingerprint;
        }
        float count = fragments.size();
        Metrics metrics = new Metrics(exactRepeats / count, melodicRepeats / count,
                harmonicRepeats / count, rhythmicRepeats / count,
                arrangementRepeats / count, longestRun,
                totalBars == 0 ? 1f : novelBars / (float) totalBars);
        boolean accepted = metrics.exactFragmentRepeatRate == 0f
                && metrics.melodicRepeatRate <= .25f
                && metrics.fullArrangementRepeatRate <= .20f
                && metrics.longestIdenticalRunBars <= 4
                && metrics.noveltyPerBar >= .35f;
        return new Evaluation(metrics, accepted);
    }
}
