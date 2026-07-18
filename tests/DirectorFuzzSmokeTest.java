package com.alexey.autoremix;

import java.util.Arrays;
import java.util.Random;

public final class DirectorFuzzSmokeTest {
    public static void main(String[] args) {
        Random random = new Random(20260718L);
        int accepted = 0;
        for (int i = 0; i < 100_000; i++) {
            float aBpm = 70f + random.nextFloat() * 110f;
            float bBpm = 70f + random.nextFloat() * 110f;
            TrackAnalysis.Fragment af = fragment(random, aBpm);
            TrackAnalysis.Fragment bf = fragment(random, bBpm);
            TrackAnalysis a = new TrackAnalysis(aBpm, af.energy, random.nextInt(12),
                    random.nextBoolean(), .3f + random.nextFloat() * .7f,
                    Arrays.asList(af), false, af.loudnessDb);
            TrackAnalysis b = new TrackAnalysis(bBpm, bf.energy, random.nextInt(12),
                    random.nextBoolean(), .3f + random.nextFloat() * .7f,
                    Arrays.asList(bf), false, bf.loudnessDb);
            LayerPlan plan = ContinuityDirector.plan(a, af, b, bf, i, true);
            if (plan == null) continue;
            accepted++;
            if (!Float.isFinite(plan.vibeScore) || !Float.isFinite(plan.tempoRatio)) {
                throw new AssertionError("non-finite plan");
            }
            if (plan.vibeScore < .82f) throw new AssertionError("vibe invariant broken");
            if (Math.abs(1f - plan.tempoRatio) > .0651f) {
                throw new AssertionError("tempo invariant broken " + plan.tempoRatio);
            }
            String name = plan.type.name();
            if (name.contains("CUT") || name.contains("SLAM") || name.contains("SNAP")) {
                throw new AssertionError("abrupt narrative leaked: " + name);
            }
        }
        if (accepted < 100) throw new AssertionError("director became unusably strict: " + accepted);
        System.out.println("Director fuzz OK; accepted=" + accepted + "/100000 continuity-only plans");
    }

    private static TrackAnalysis.Fragment fragment(Random random, float bpm) {
        float vocal = random.nextFloat();
        float percussion = random.nextFloat();
        return new TrackAnalysis.Fragment(10_000L, random.nextFloat(), bpm,
                .35f + random.nextFloat() * .65f,
                random.nextFloat(), random.nextFloat(), random.nextFloat(),
                .35f + random.nextFloat() * .65f,
                .35f + random.nextFloat() * .65f,
                vocal, random.nextFloat(), percussion,
                -24f + random.nextFloat() * 16f,
                Math.round(60_000f / bpm));
    }
}
