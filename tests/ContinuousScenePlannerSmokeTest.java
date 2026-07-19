package com.alexey.autoremix;

import java.util.HashSet;
import java.util.Set;

public final class ContinuousScenePlannerSmokeTest {
    private static final long BAR = 96_000L;

    public static void main(String[] args) {
        structuredStemPlanUsesIndependentTimelines();
        strictVocalOwnershipHasInstrumentalGap();
        conflictingLeadTimelinesAreRejected();
        separatorFailureIsExplicitLegacyFallback();
        phraseAwarePrecedesBasicFallback();
        basicCrossfadeIsLastFallback();
        unavailableFallbacksReturnNoPlan();
        vocalHeavyFallbackHasNoFullMixOverlap();
        insufficientBufferDefersActivation();
        eligibleCorpusUsesStemPath();
        System.out.println("Continuous scene planner OK");
    }

    private static void structuredStemPlanUsesIndependentTimelines() {
        ContinuousScenePlanner.PlanningResult result = new ContinuousScenePlanner()
                .plan(eligibleRequest(11L).build());
        if (!result.hasPlan() || !result.plan.isStemBased()) {
            throw new AssertionError("eligible request did not select a stem plan");
        }
        if (result.plan.aiUsed()) {
            throw new AssertionError("deterministic plan was mislabeled as AI");
        }
        if (!result.plan.stemSeparatorUsed || result.plan.selectedAnchorSet.isEmpty()) {
            throw new AssertionError("stem/anchor provenance missing");
        }
        Set<ContinuousSceneTransitionPlan.Strategy> families = new HashSet<>();
        for (ContinuousScenePlanner.CandidateDiagnostics candidate : result.candidates) {
            families.add(candidate.strategy);
        }
        if (families.size() < 10) throw new AssertionError("candidate family search is incomplete");
        boolean guitarAnchor = result.plan.selectedAnchorSet.stream().anyMatch(anchor ->
                anchor.semanticRole == ContinuousSceneTransitionPlan.SemanticRole.GUITAR);
        if (!guitarAnchor) throw new AssertionError("similar guitar was not selected as anchor");

        Set<Long> firstChanges = new HashSet<>();
        for (ContinuousSceneTransitionPlan.StemTimeline timeline : result.plan.stemTimelines) {
            if (timeline.source != ContinuousSceneTransitionPlan.Source.A
                    || timeline.semanticRole.isVocal()) continue;
            for (ContinuousSceneTransitionPlan.EnvelopePoint point
                    : timeline.gainEnvelope.points) {
                if (point.value < .999f) {
                    firstChanges.add(point.sample);
                    break;
                }
            }
        }
        if (firstChanges.size() < 3) {
            throw new AssertionError("stem timelines collapse to one full-mix crossfade");
        }
        String json = result.diagnosticJson();
        if (!json.contains("\"qualityCandidates\"")
                || !json.contains("\"stemOwnershipTimeline\"")
                || !json.contains("\"aiUsed\":false")
                || json.contains("audioPayload")) {
            throw new AssertionError("diagnostic JSON is incomplete or contains audio");
        }
    }

    private static void strictVocalOwnershipHasInstrumentalGap() {
        ContinuousSceneTransitionPlan plan = new ContinuousScenePlanner()
                .plan(eligibleRequest(12L).build()).plan;
        String owners = plan.vocalOwnerTimeline(32);
        int lastA = owners.lastIndexOf('A');
        int firstB = owners.indexOf('B');
        if (lastA < 0 || firstB < 0 || firstB <= lastA + 1) {
            throw new AssertionError("no vocal-free owner interval: " + owners);
        }
        for (int i = lastA + 1; i < firstB; i++) {
            if (owners.charAt(i) != '-') {
                throw new AssertionError("unexpected vocal owner: " + owners);
            }
        }
        if (plan.vocalConflictScore != 0f) {
            throw new AssertionError("double lead vocal accepted");
        }
    }

    private static void conflictingLeadTimelinesAreRejected() {
        ContinuousSceneTransitionPlan valid = new ContinuousScenePlanner()
                .plan(eligibleRequest(16L).build()).plan;
        java.util.List<ContinuousSceneTransitionPlan.StemTimeline> timelines =
                new java.util.ArrayList<>(valid.stemTimelines);
        for (int index = 0; index < timelines.size(); index++) {
            ContinuousSceneTransitionPlan.StemTimeline lane = timelines.get(index);
            if (lane.semanticRole != ContinuousSceneTransitionPlan.SemanticRole.LEAD_VOCAL
                    || lane.source != ContinuousSceneTransitionPlan.Source.B) continue;
            timelines.set(index, new ContinuousSceneTransitionPlan.StemTimeline(
                    lane.semanticRole, lane.source, lane.sourceFragmentId,
                    lane.startSample, lane.endSample,
                    ContinuousSceneTransitionPlan.Envelope.constant(
                            lane.startSample, lane.endSample, 1f),
                    lane.pitchEnvelope, lane.formantEnvelope, lane.tempoEnvelope,
                    lane.panEnvelope, lane.widthEnvelope, lane.eqEnvelope,
                    lane.filterEnvelope, lane.reverbEnvelope, lane.transientEnvelope,
                    lane.harmonicMorphEnvelope, lane.timbreMorphEnvelope,
                    lane.ownershipState));
            break;
        }
        boolean rejected = false;
        try {
            new ContinuousSceneTransitionPlan(valid.transitionId, valid.sourceTrackId,
                    valid.targetTrackId, valid.sourceStartSample, valid.targetLandingSample,
                    valid.activationSample, valid.landingSample, valid.selectedAnchorSet,
                    valid.qualityScore, valid.confidence, valid.fallbackReason, timelines,
                    valid.selectedStrategy, valid.origin, valid.stemSeparatorUsed,
                    valid.guaranteedRenderedSamples, valid.activationUnderruns,
                    valid.clickScore);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        if (!rejected) throw new AssertionError("conflicting lead owners were accepted");
    }

    private static void separatorFailureIsExplicitLegacyFallback() {
        ContinuousScenePlanner.PlanningRequest request = eligibleRequest(13L)
                .separator(false, 0f)
                .legacyVocalSafe(true)
                .build();
        ContinuousScenePlanner.PlanningResult result = new ContinuousScenePlanner().plan(request);
        if (!result.hasPlan()
                || result.plan.selectedStrategy
                != ContinuousSceneTransitionPlan.Strategy.LEGACY_INTELLIGENT
                || result.fallbackReason
                != ContinuousSceneTransitionPlan.FallbackReason.STEM_SEPARATION_FAILED
                || result.plan.fallbackReason != result.fallbackReason
                || result.plan.aiUsed()) {
            throw new AssertionError("separator fallback is silent or mislabeled");
        }
    }

    private static void basicCrossfadeIsLastFallback() {
        ContinuousScenePlanner.PlanningRequest request = eligibleRequest(15L)
                .separator(false, 0f)
                .vocalActivity(false, false)
                .fallbacks(false, false, true)
                .build();
        ContinuousScenePlanner.PlanningResult result = new ContinuousScenePlanner().plan(request);
        if (!result.hasPlan()
                || result.plan.selectedStrategy
                != ContinuousSceneTransitionPlan.Strategy.BASIC_CROSSFADE) {
            throw new AssertionError("basic crossfade was not retained as final fallback");
        }
    }

    private static void phraseAwarePrecedesBasicFallback() {
        ContinuousScenePlanner.PlanningResult result = new ContinuousScenePlanner().plan(
                eligibleRequest(17L).separator(false, 0f)
                        .legacyVocalSafe(true).fallbacks(false, true, true).build());
        if (!result.hasPlan() || result.plan.selectedStrategy
                != ContinuousSceneTransitionPlan.Strategy.PHRASE_AWARE_CROSSFADE
                || result.plan.fallbackReason == null) {
            throw new AssertionError("phrase-aware fallback order is broken");
        }
    }

    private static void unavailableFallbacksReturnNoPlan() {
        ContinuousScenePlanner.PlanningResult result = new ContinuousScenePlanner().plan(
                eligibleRequest(18L).separator(false, 0f)
                        .fallbacks(false, false, false).build());
        if (result.hasPlan()
                || result.fallbackReason
                != ContinuousSceneTransitionPlan.FallbackReason.STEM_SEPARATION_FAILED) {
            throw new AssertionError("unavailable fallback was silently selected");
        }
    }

    private static void vocalHeavyFallbackHasNoFullMixOverlap() {
        ContinuousSceneTransitionPlan plan = new ContinuousScenePlanner().plan(
                eligibleRequest(19L).separator(false, 0f)
                        .vocalActivity(true, true).legacyVocalSafe(true)
                        .fallbacks(false, true, false).build()).plan;
        ContinuousSceneTransitionPlan.StemTimeline a = null;
        ContinuousSceneTransitionPlan.StemTimeline b = null;
        for (ContinuousSceneTransitionPlan.StemTimeline timeline : plan.stemTimelines) {
            if (timeline.semanticRole != ContinuousSceneTransitionPlan.SemanticRole.FULL_MIX) {
                continue;
            }
            if (timeline.source == ContinuousSceneTransitionPlan.Source.A) a = timeline;
            if (timeline.source == ContinuousSceneTransitionPlan.Source.B) b = timeline;
        }
        if (a == null || b == null) throw new AssertionError("fallback lanes missing");
        for (long sample = plan.activationSample; sample < plan.landingSample;
             sample += Math.max(1L, BAR / 16L)) {
            if ((a.gainAt(sample) > ContinuousSceneTransitionPlan.SILENCE_GAIN
                    && b.gainAt(sample) > ContinuousSceneTransitionPlan.AUDIBLE_GAIN)
                    || (b.gainAt(sample) > ContinuousSceneTransitionPlan.SILENCE_GAIN
                    && a.gainAt(sample) > ContinuousSceneTransitionPlan.AUDIBLE_GAIN)) {
                throw new AssertionError("vocal-heavy full mixes overlap");
            }
        }
    }

    private static void insufficientBufferDefersActivation() {
        ContinuousScenePlanner.PlanningRequest request = eligibleRequest(14L)
                .buffer(false, BAR, 1, .5f)
                .build();
        ContinuousScenePlanner.PlanningResult result = new ContinuousScenePlanner().plan(request);
        if (result.hasPlan()
                || result.fallbackReason
                != ContinuousSceneTransitionPlan.FallbackReason.INSUFFICIENT_BUFFER) {
            throw new AssertionError("unprepared activation was allowed");
        }
    }

    private static void eligibleCorpusUsesStemPath() {
        int stemPlans = 0;
        TransitionStrategyStatistics statistics = new TransitionStrategyStatistics();
        for (int pair = 0; pair < 1_000; pair++) {
            ContinuousScenePlanner.PlanningResult result = new ContinuousScenePlanner()
                    .plan(eligibleRequest(100L + pair)
                            .separator(true, .58f + pair % 8 * .05f)
                            .vocalActivity(pair % 3 != 0, pair % 4 != 0)
                            .compatibility(.70f + pair % 10 * .025f,
                                    .72f + pair % 7 * .035f,
                                    .74f + pair % 6 * .04f,
                                    .76f, .78f, .82f)
                            .transforms(.94f + pair % 9 * .015f, 0f, 0f)
                            .build());
            if (result.hasPlan()) statistics.record(result.plan);
            if (result.hasPlan() && result.plan.isStemBased()) {
                stemPlans++;
                if (result.plan.aiUsed()) {
                    throw new AssertionError("deterministic corpus mislabeled AI");
                }
            } else if (result.fallbackReason == null) {
                throw new AssertionError("non-stem result has no fallback reason");
            }
        }
        float rate = stemPlans / 1_000f;
        TransitionStrategyStatistics.Snapshot snapshot = statistics.snapshot();
        if (rate < .99f || Math.abs(snapshot.stemPathRate() - rate) > 1e-6f) {
            throw new AssertionError("eligible stem path rate " + rate);
        }
    }

    private static ContinuousScenePlanner.PlanningRequest.Builder eligibleRequest(long id) {
        return ContinuousScenePlanner.PlanningRequest.builder(id, 101L, 202L,
                        2_448_000L, BAR)
                .sourceStartSample(2_448_000L)
                .targetLandingSample(864_000L)
                .stem(ContinuousSceneTransitionPlan.SemanticRole.LEAD_VOCAL,
                        1_001L, 2_001L, .62f, .88f)
                .stem(ContinuousSceneTransitionPlan.SemanticRole.BACKING_VOCAL,
                        1_002L, 2_002L, .70f, .82f)
                .stem(ContinuousSceneTransitionPlan.SemanticRole.GUITAR,
                        1_003L, 2_003L, .98f, .94f)
                .stem(ContinuousSceneTransitionPlan.SemanticRole.DRUMS,
                        1_004L, 2_004L, .84f, .90f)
                .stem(ContinuousSceneTransitionPlan.SemanticRole.PERCUSSION,
                        1_005L, 2_005L, .81f, .86f)
                .stem(ContinuousSceneTransitionPlan.SemanticRole.BASS,
                        1_006L, 2_006L, .86f, .91f)
                .stem(ContinuousSceneTransitionPlan.SemanticRole.HARMONY,
                        1_007L, 2_007L, .88f, .89f)
                .generatedStem(ContinuousSceneTransitionPlan.SemanticRole.ATMOSPHERE,
                        1_008L, 2_008L, 3_008L, .85f, .87f)
                .separator(true, .91f)
                .buffer(true, BAR * 12L, 0, .01f)
                .sampleDiscontinuity(.02f)
                .vocalActivity(true, true)
                .vocalChopSafe(true)
                .compatibility(.91f, .88f, .92f, .90f, .87f, .94f)
                .generatedArtifactRisk(.08f)
                .transforms(1.012f, 0f, 0f);
    }
}
