package com.alexey.autoremix;

import java.util.Arrays;

public final class ContinuityDirectorSmokeTest {
    public static void main(String[] args) {
        TrackAnalysis.Fragment vocal = new TrackAnalysis.Fragment(10_000, .58f, 122f, .8f,
                .48f, .52f, .55f, .82f, .80f, .76f, .52f, .46f, -15f, 492L);
        TrackAnalysis.Fragment back = new TrackAnalysis.Fragment(12_000, .60f, 123f, .8f,
                .62f, .50f, .52f, .84f, .82f, .31f, .58f, .70f, -15f, 488L);
        TrackAnalysis a = new TrackAnalysis(122f, .58f, 0, false, .8f,
                Arrays.asList(vocal), false, -15f);
        TrackAnalysis b = new TrackAnalysis(123f, .60f, 7, false, .75f,
                Arrays.asList(back), false, -15f);
        System.out.println("similarity=" + ContinuityDirector.advancedVibeSimilarity(a, vocal, b, back));
        for (int i = 0; i < 1000; i++) {
            LayerPlan plan = ContinuityDirector.plan(a, vocal, b, back, i, true);
            if (plan == null) throw new AssertionError("compatible pair rejected");
            if (plan.type.name().contains("CUT") || plan.type.name().contains("SLAM")) {
                throw new AssertionError("abrupt narrative leaked into automatic mode");
            }
        }
        TrackAnalysis.Fragment alien = new TrackAnalysis.Fragment(12_000, .95f, 175f, .7f,
                .95f, .95f, .12f, .4f, .7f, .9f, .95f, .95f, -7f, 343L);
        TrackAnalysis c = new TrackAnalysis(175f, .95f, 2, true, .8f,
                Arrays.asList(alien), false, -7f);
        if (ContinuityDirector.plan(a, vocal, c, alien, 0, true) != null) {
            throw new AssertionError("incompatible vibe was accepted");
        }
        System.out.println("Continuity director OK");
    }
}
