package com.alexey.autoremix;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public final class ContinuationPlannerSmokeTest {
    public static void main(String[] args) {
        chunkBankContainsAllRequiredSizes();
        plannerProducesNovelSixteenBars();
        fallbackBankUsesOnlySpacedVariations();
        variationBoundariesStayContinuous();
        debugReportContainsNoMediaIdentity();
        System.out.println("Continuation planner OK");
    }

    private static void chunkBankContainsAllRequiredSizes() {
        TrackAnalysis analysis = analysis(5);
        ContinuationReservoir reservoir = ContinuationReservoir.fromTrack(7L, 180_000L, analysis);
        HashSet<ContinuationReservoir.ChunkKind> kinds = new HashSet<>();
        for (ContinuationReservoir.Fragment fragment : reservoir.fragments()) kinds.add(fragment.kind);
        if (!kinds.containsAll(List.of(ContinuationReservoir.ChunkKind.ONE_BAR,
                ContinuationReservoir.ChunkKind.TWO_BARS,
                ContinuationReservoir.ChunkKind.FOUR_BARS,
                ContinuationReservoir.ChunkKind.EIGHT_BARS,
                ContinuationReservoir.ChunkKind.PHRASE))) {
            throw new AssertionError("missing chunk-bank size");
        }
        for (ContinuationReservoir.Edge edge : reservoir.edges()) {
            if (edge.fromFragmentId == edge.toFragmentId) {
                throw new AssertionError("self-edge accepted");
            }
        }
    }

    private static void plannerProducesNovelSixteenBars() {
        TrackAnalysis analysis = analysis(5);
        ContinuationReservoir reservoir = ContinuationReservoir.fromTrack(9L, 180_000L, analysis);
        long anchor = reservoir.sourceIdFor(analysis.fragments.get(0));
        NonRepeatingContinuationPlanner.Plan plan = new NonRepeatingContinuationPlanner(reservoir)
                .plan(new ContinuationReservoir.CurrentContext(0L, anchor, anchor, .5f),
                        new ContinuationReservoir.TargetTrackContext(.65f),
                        ContinuationReservoir.DeviceBudget.realtime(), 16,
                        List.of(), List.of());
        if (!plan.complete || plan.bars < 16 || !plan.repetition.accepted) {
            throw new AssertionError("16-bar plan incomplete");
        }
        for (int index = 0; index < plan.fragments.size(); index++) {
            ContinuationReservoir.Fragment current = plan.fragments.get(index);
            for (int prior = Math.max(0, index - 2); prior < index; prior++) {
                if (current.sourceFragmentId == plan.fragments.get(prior).sourceFragmentId) {
                    throw new AssertionError("fragment reused inside window");
                }
            }
        }
    }

    private static void fallbackBankUsesOnlySpacedVariations() {
        TrackAnalysis analysis = analysis(3);
        ContinuationReservoir reservoir = ContinuationReservoir.fromTrack(11L, 140_000L, analysis);
        long anchor = reservoir.sourceIdFor(analysis.fragments.get(0));
        NonRepeatingContinuationPlanner.Plan plan = new NonRepeatingContinuationPlanner(reservoir)
                .plan(new ContinuationReservoir.CurrentContext(0L, anchor, anchor, .5f),
                        new ContinuationReservoir.TargetTrackContext(.55f),
                        new ContinuationReservoir.DeviceBudget(18, 512), 16,
                        List.of(), List.of());
        if (!plan.complete) throw new AssertionError("fallback plan incomplete");
        HashSet<Long> sources = new HashSet<>();
        int anchorUses = 1;
        for (ContinuationReservoir.Fragment fragment : plan.fragments) {
            boolean reused = !sources.add(fragment.sourceFragmentId);
            if (reused && fragment.variationMask == ContinuationReservoir.VARIATION_NONE) {
                throw new AssertionError("unvaried fragment reused");
            }
            if (fragment.sourceFragmentId == anchor) anchorUses++;
        }
        if (anchorUses > 2) throw new AssertionError("anchor used more than twice");
    }

    private static void variationBoundariesStayContinuous() {
        int rate = 8_000;
        float[] audio = new float[rate * 4];
        float[] original = new float[audio.length];
        for (int frame = 0; frame < audio.length / 2; frame++) {
            float value = (float) Math.sin(frame * Math.PI * 2.0 / 80.0) * .2f;
            audio[frame * 2] = value;
            audio[frame * 2 + 1] = value * .9f;
        }
        System.arraycopy(audio, 0, original, 0, audio.length);
        SceneRenderer.applyDeterministicVariation(audio, rate, 500L,
                ContinuationReservoir.VARIATION_RHYTHM
                        | ContinuationReservoir.VARIATION_TIMBRE);
        float maximumJump = 0f;
        boolean changed = false;
        for (int index = 2; index < audio.length; index++) {
            maximumJump = Math.max(maximumJump, Math.abs(audio[index] - audio[index - 2]));
            changed |= Math.abs(audio[index] - original[index]) > 1e-5f;
        }
        if (!changed || maximumJump > .08f) {
            throw new AssertionError("variation click " + maximumJump);
        }
    }

    private static void debugReportContainsNoMediaIdentity() {
        RemixEngineService.currentTrack = "private-track-name";
        String report = RemixEngineService.anonymizedDebugReport();
        if (report.contains("private-track-name") || report.contains("audio=")) {
            throw new AssertionError("debug report leaked media identity");
        }
        if (!report.contains("guaranteed_rendered_horizon_ms=")
                || !report.contains("repetition_score=")) {
            throw new AssertionError("debug report missing diagnostics");
        }
    }

    private static TrackAnalysis analysis(int count) {
        List<TrackAnalysis.Fragment> fragments = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            fragments.add(new TrackAnalysis.Fragment(index * 24_000L,
                    .35f + index * .10f, 120f, .8f,
                    .42f + index * .05f, .35f + index * .08f,
                    .55f, .8f, .75f, .25f + index * .04f,
                    .42f, .55f, -16f, 500L));
        }
        return new TrackAnalysis(120f, .55f, 9, true, .8f,
                fragments, false, -16f);
    }
}
