package com.alexey.autoremix;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public final class MusicalTimelinePlannerTest {
    @Test public void automaticProfilesProduceVariableMusicalSchedules() {
        Set<Long> transitionStarts = new HashSet<>();
        Set<Long> transitionLengths = new HashSet<>();

        for (int profile = 0; profile < 128; profile++) {
            float bpm = 82f + profile % 73;
            long beatMs = Math.round(60_000f / bpm);
            long durationMs = 165_000L + profile % 17 * 13_000L;
            Track track = new Track(10_000L + profile, null, "track", "artist",
                    durationMs, "audio/test");
            TrackAnalysis analysis = analysis(bpm, beatMs, durationMs);
            TrackAnalysis.Fragment entry = analysis.fragments.get(0);

            MusicalTimelinePlanner.TrackPlan plan = MusicalTimelinePlanner.planTrack(
                    track, analysis, entry, entry.cueMs, profile);

            assertEquals(track.id, plan.trackId);
            assertEquals(durationMs, plan.durationMs);
            assertTrue(plan.entryPositionMs <= plan.transitionStartPositionMs);
            assertTrue(plan.transitionStartPositionMs < plan.trackExitPositionMs);
            assertTrue(plan.trackExitPositionMs <= durationMs);
            assertTrue(plan.estimatedTransitionLengthMs > 0L);
            transitionStarts.add(plan.transitionStartPositionMs);
            transitionLengths.add(plan.estimatedTransitionLengthMs);
        }

        assertTrue(transitionStarts.size() > 24);
        assertTrue(transitionLengths.size() > 12);
    }

    @Test public void transitionLengthFollowsTempoBarsAndAvailableRunway() {
        Set<Long> lengths = new HashSet<>();
        for (float bpm : new float[]{84f, 101f, 120f, 147f}) {
            long beatMs = Math.round(60_000f / bpm);
            TrackAnalysis source = analysis(bpm, beatMs, 300_000L);
            TrackAnalysis target = analysis(bpm * 1.03f,
                    Math.round(60_000f / (bpm * 1.03f)), 300_000L);
            for (int bars : new int[]{8, 12, 16, 24}) {
                long length = MusicalTimelinePlanner.transitionLengthMs(
                        source, source.fragments.get(0), target, target.fragments.get(0),
                        new LayerPlan(LayerPlan.Type.ATMOSPHERE_CHAIN, bars, .9f, 1.03f,
                                "test"), 180_000L, 180_000L);
                assertEquals(0L, length % MusicalTimelinePlanner.barMs(
                        source, source.fragments.get(0)));
                assertTrue(length >= MusicalTimelinePlanner.barMs(
                        source, source.fragments.get(0)) * 4L);
                lengths.add(length);
            }
        }
        assertTrue(lengths.size() > 8);
    }

    @Test public void publicModelsKeepSongTimeAndPauseTransitionState() {
        SceneTimelineMapping mapping = SceneTimelineMapping.transition(
                1L, 2L, 240_000L, 300_000L,
                72_000L, 18_000L, 42_000L, 104_000L,
                480_000L, 1_440_000L, 48_000, 1.2f);

        assertEquals(240_000L, mapping.sourceTrackDurationMs);
        assertEquals(300_000L, mapping.targetTrackDurationMs);
        assertEquals(18_000L, mapping.targetPositionMs(mapping.transitionStartSample));
        assertTrue(mapping.targetPositionMs(mapping.transitionStartSample + 48_000L) > 0L);
        assertFalse(mapping.fullLandingReached(mapping.fullLandingSample - 1L));
        assertTrue(mapping.fullLandingReached(mapping.fullLandingSample));

        TransitionUiState active = new TransitionUiState(
                7L, 1L, 2L, PlaybackPhase.TRANSITION_ACTIVE, .4f, List.of(),
                81_000L, 25_000L, 42_000L, .9f, "GUITAR", "active",
                864_000L, mapping.transitionStartSample, mapping.fullLandingSample);
        PlaybackUiSnapshot paused = new PlaybackUiSnapshot(
                PlaybackPhase.PAUSED, null, active);

        assertEquals(PlaybackPhase.PAUSED, paused.phase);
        assertEquals(PlaybackPhase.TRANSITION_ACTIVE, paused.transitionUiState.phase);
        assertSame(TrackPlaybackTimeline.EMPTY, paused.trackTimeline);
        assertNotNull(paused.transitionUiState.activeStemOperations);
    }

    @Test public void landingRequiresTargetOwnershipAtTheMappedSampleBoundary() {
        long barSamples = 96_000L;
        long activation = 2_448_000L;
        ContinuousScenePlanner.PlanningResult result = new ContinuousScenePlanner().plan(
                ContinuousScenePlanner.PlanningRequest.builder(
                                77L, 1L, 2L, activation, barSamples)
                        .transitionSamples(barSamples * 16L)
                        .sourceStartSample(activation)
                        .targetLandingSample(864_000L)
                        .stem(ContinuousSceneTransitionPlan.SemanticRole.LEAD_VOCAL,
                                11L, 21L, .8f, .9f)
                        .stem(ContinuousSceneTransitionPlan.SemanticRole.GUITAR,
                                12L, 22L, .96f, .94f)
                        .stem(ContinuousSceneTransitionPlan.SemanticRole.DRUMS,
                                13L, 23L, .87f, .91f)
                        .stem(ContinuousSceneTransitionPlan.SemanticRole.BASS,
                                14L, 24L, .86f, .90f)
                        .separator(true, .92f)
                        .buffer(true, barSamples * 16L, 0, 0f)
                        .vocalActivity(true, true)
                        .vocalChopSafe(true)
                        .compatibility(.91f, .89f, .90f, .88f, .86f, .92f)
                        .build());

        assertTrue(result.hasPlan());
        ContinuousSceneTransitionPlan plan = result.plan;
        assertFalse(plan.fullTrackLandingAt(plan.activationSample));
        assertTrue(plan.targetCoreOwnershipSample() >= plan.activationSample);
        assertTrue(plan.targetCoreOwnershipSample() < plan.landingSample);
        assertTrue(plan.fullTrackLandingAt(plan.landingSample - 1L));
        assertFalse(plan.fullTrackLandingAt(plan.landingSample));
    }

    @Test public void activationProjectionAccountsForEverySceneOverlap() {
        long boundary = 672L;
        assertEquals(10_000L, SceneAudioPlayer.projectedActivationSample(
                10_000L, boundary));
        assertEquals(57_328L, SceneAudioPlayer.projectedActivationSample(
                10_000L, boundary, 48_000L));
        assertEquals(104_656L, SceneAudioPlayer.projectedActivationSample(
                10_000L, boundary, 48_000L, 48_000L));
        assertEquals(105_984L, SceneAudioPlayer.projectedActivationSample(
                10_000L, boundary, 48_000L, 48_000L, 2_000L));
        assertEquals(10_128L, SceneAudioPlayer.projectedActivationSample(
                10_000L, boundary, 800L));
    }

    @Test public void chunkedSeparatorAndPackedStemsPreserveProgramme() {
        int rate = 8_000;
        int frames = 140_000;
        float[] programme = new float[frames * 2];
        for (int frame = 0; frame < frames; frame++) {
            float left = (float) (Math.sin(frame * .019) * .24
                    + Math.sin(frame * .071) * .08);
            float right = (float) (Math.sin(frame * .021) * .22
                    + Math.cos(frame * .067) * .07);
            programme[frame * 2] = left;
            programme[frame * 2 + 1] = right;
        }

        StemBundle separated = SpectralStemSeparator.separate(
                new PcmAudio(rate, programme));
        QuantizedStemBundle packed = QuantizedStemBundle.from(separated);
        float maximumError = 0f;
        for (int frame = 0; frame < frames; frame += 257) {
            for (int channel = 0; channel < 2; channel++) {
                float reconstructed = packed.fullMixSample(frame, channel);
                maximumError = Math.max(maximumError,
                        Math.abs(programme[frame * 2 + channel] - reconstructed));
            }
        }
        assertTrue("packed reconstruction error " + maximumError,
                maximumError < 2e-4f);
    }

    private static TrackAnalysis analysis(float bpm, long beatMs, long durationMs) {
        long barMs = beatMs * 4L;
        List<TrackAnalysis.Fragment> fragments = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            long cue = Math.min(durationMs - barMs * 8L,
                    8_000L + index * barMs * 8L);
            if (cue < 0L) cue = 0L;
            fragments.add(new TrackAnalysis.Fragment(cue, .52f + index % 3 * .08f,
                    bpm, .9f, .62f, .55f, .58f, .72f + index % 2 * .15f,
                    .91f, .30f, .58f, .67f, -14f, beatMs));
        }
        return new TrackAnalysis(bpm, .62f, 4, false, .84f, fragments, false, -14f);
    }
}
