package com.alexey.autoremix;

/** Control-thread strategy counters. Never call from the realtime callback. */
final class TransitionStrategyStatistics {
    private long total;
    private long aiLayered;
    private long deterministicStem;
    private long legacy;
    private long basicCrossfade;

    synchronized void record(ContinuousSceneTransitionPlan plan) {
        if (plan == null) return;
        total++;
        if (plan.isStemBased()) {
            if (plan.aiUsed()) aiLayered++;
            else deterministicStem++;
        } else if (plan.selectedStrategy
                == ContinuousSceneTransitionPlan.Strategy.BASIC_CROSSFADE) {
            basicCrossfade++;
        } else {
            legacy++;
        }
    }

    synchronized void reset() {
        total = 0L;
        aiLayered = 0L;
        deterministicStem = 0L;
        legacy = 0L;
        basicCrossfade = 0L;
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(total, rate(aiLayered), rate(deterministicStem),
                rate(legacy), rate(basicCrossfade));
    }

    private float rate(long count) {
        return total == 0L ? 0f : count / (float) total;
    }

    static final class Snapshot {
        final long total;
        final float aiLayeredTransitionRate;
        final float deterministicStemTransitionRate;
        final float legacyTransitionRate;
        final float basicCrossfadeRate;

        Snapshot(long total, float aiLayeredTransitionRate,
                 float deterministicStemTransitionRate,
                 float legacyTransitionRate, float basicCrossfadeRate) {
            this.total = total;
            this.aiLayeredTransitionRate = aiLayeredTransitionRate;
            this.deterministicStemTransitionRate = deterministicStemTransitionRate;
            this.legacyTransitionRate = legacyTransitionRate;
            this.basicCrossfadeRate = basicCrossfadeRate;
        }

        float stemPathRate() {
            return aiLayeredTransitionRate + deterministicStemTransitionRate;
        }
    }
}
