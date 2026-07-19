package com.alexey.autoremix;

import java.util.Arrays;
import java.util.Locale;

public final class ContinuousSceneEngineSmokeTest {
    private static final int RATE = 48_000;
    private static final long ACTIVATION = RATE * 51L;
    private static final long BAR = 12_000L;
    private static final int TRANSITION_FRAMES = Math.toIntExact(BAR * 16L);
    private static final int TOTAL_FRAMES = TRANSITION_FRAMES + RATE * 2;

    public static void main(String[] args) {
        ContinuousScenePlanner.PlanningResult planning = planning();
        independentStemOperations(planning);
        renderedVocalsKeepOneOwner(planning.plan);
        generatedCandidateRendersANode(planning);
        exactFiftyOneSecondActivationUsesOneGraph(planning.plan);
        System.out.println("Continuous scene engine integration OK");
    }

    private static void independentStemOperations(
            ContinuousScenePlanner.PlanningResult planning) {
        if (!planning.hasPlan() || !planning.plan.isStemBased() || planning.plan.aiUsed()) {
            throw new AssertionError("deterministic stem provenance is wrong");
        }
        StemBundle a = programmeStems(TRANSITION_FRAMES, false, false);
        StemBundle b = programmeStems(TRANSITION_FRAMES, true, false);
        PreparedStemScene scene = new PreparedStemScene(planning.plan, a, b,
                programme(TOTAL_FRAMES, true), RATE, TRANSITION_FRAMES, TOTAL_FRAMES);
        float[] rendered = render(scene);
        if (scene.frames() != TOTAL_FRAMES || rendered.length != TOTAL_FRAMES * 2) {
            throw new AssertionError("unexpected prepared length " + scene.frames()
                    + "/" + rendered.length);
        }
        double differenceEnergy = 0.0;
        double scalarEnergy = 0.0;
        for (int frame = RATE; frame < TRANSITION_FRAMES - RATE; frame += 31) {
            float t = frame / (float) (TRANSITION_FRAMES - 1);
            for (int channel = 0; channel < 2; channel++) {
                float scalar = fullMix(a, frame, channel) * (1f - t)
                        + fullMix(b, frame, channel) * t;
                float difference = rendered[frame * 2 + channel] - scalar;
                differenceEnergy += difference * difference;
                scalarEnergy += scalar * scalar;
            }
        }
        if (differenceEnergy <= scalarEnergy * .015) {
            throw new AssertionError("render collapsed to a scalar crossfade");
        }
        AudioContinuityValidator.Metrics landing = AudioContinuityValidator.analyze(
                rendered, RATE, 2, TRANSITION_FRAMES, 0);
        if (!landing.passesTransitionGate()) {
            throw new AssertionError("in-scene B landing failed continuity gate");
        }
        System.out.println(String.format(Locale.US,
                "landing gap=%.3fms underruns=%d sampleJump=%.8f derivativeJump=%.8f lufsJump=%.6f flux=%.6f",
                landing.activationGapMs, landing.activationUnderruns,
                landing.activationMaxSampleJump, landing.activationMaxDerivativeJump,
                landing.activationLufsJump, landing.activationSpectralFluxSpike));
    }

    private static void renderedVocalsKeepOneOwner(ContinuousSceneTransitionPlan plan) {
        PreparedStemScene scene = new PreparedStemScene(plan,
                vocalOnlyStems(TRANSITION_FRAMES, true),
                vocalOnlyStems(TRANSITION_FRAMES, false),
                vocalProgramme(TOTAL_FRAMES, false), RATE,
                TRANSITION_FRAMES, TOTAL_FRAMES);
        float[] rendered = render(scene);
        boolean heardA = false;
        boolean heardB = false;
        int silentFrames = 0;
        for (int frame = 0; frame < TRANSITION_FRAMES; frame++) {
            float aLead = Math.abs(rendered[frame * 2]);
            float bLead = Math.abs(rendered[frame * 2 + 1]);
            if (aLead > ContinuousSceneTransitionPlan.AUDIBLE_GAIN) {
                heardA = true;
                if (bLead > ContinuousSceneTransitionPlan.SILENCE_GAIN) {
                    throw new AssertionError("B lead audible while A owns vocals");
                }
            }
            if (bLead > ContinuousSceneTransitionPlan.AUDIBLE_GAIN) {
                heardB = true;
                if (aLead > ContinuousSceneTransitionPlan.SILENCE_GAIN) {
                    throw new AssertionError("A lead audible while B owns vocals");
                }
            }
            if (aLead <= ContinuousSceneTransitionPlan.SILENCE_GAIN
                    && bLead <= ContinuousSceneTransitionPlan.SILENCE_GAIN) silentFrames++;
        }
        if (!heardA || !heardB || silentFrames < RATE / 2) {
            throw new AssertionError("vocal handoff lacks an instrumental interval");
        }
    }

    private static void generatedCandidateRendersANode(
            ContinuousScenePlanner.PlanningResult planning) {
        ContinuousSceneTransitionPlan generated = null;
        for (ContinuousScenePlanner.CandidateDiagnostics candidate : planning.candidates) {
            if (candidate.strategy
                    == ContinuousSceneTransitionPlan.Strategy.GENERATED_INSTRUMENTAL_BRIDGE
                    && candidate.accepted()) {
                generated = candidate.plan;
                break;
            }
        }
        if (generated == null || generated.stemTimelines.stream().noneMatch(timeline ->
                timeline.source == ContinuousSceneTransitionPlan.Source.GENERATED
                        && !timeline.semanticRole.isVocal())) {
            throw new AssertionError("generated instrumental candidate is not structured");
        }
        float[] silence = new float[TRANSITION_FRAMES * 2];
        StemBundle emptyA = new StemBundle(RATE, silence.clone(), silence.clone(),
                silence.clone(), silence.clone());
        StemBundle emptyB = new StemBundle(RATE, silence.clone(), silence.clone(),
                silence.clone(), silence.clone());
        PreparedStemScene scene = new PreparedStemScene(generated, emptyA, emptyB,
                PcmAudio.silence(RATE, TOTAL_FRAMES * 1_000L / RATE), RATE,
                TRANSITION_FRAMES, TOTAL_FRAMES);
        float[] rendered = render(scene);
        double energy = 0.0;
        for (int index = TRANSITION_FRAMES * 70 / 100;
             index < TRANSITION_FRAMES * 130 / 100; index++) {
            energy += rendered[index] * rendered[index];
        }
        if (energy <= 1e-3) throw new AssertionError("generated node was not rendered");
    }

    private static void exactFiftyOneSecondActivationUsesOneGraph(
            ContinuousSceneTransitionPlan plan) {
        MasterAudioGraph graph = new MasterAudioGraph(512, 0);
        float[] block = new float[512 * 2];
        long frame = 0L;
        while (frame < ACTIVATION) {
            int count = (int) Math.min(512L, ACTIVATION - frame);
            fillProgramme(block, count, frame, false);
            graph.processBlock(block, 0, count);
            frame += count;
        }
        if (graph.markTransitionActivation(plan.transitionId) != ACTIVATION) {
            throw new AssertionError("activation is not exactly 51.000s");
        }
        PreparedStemScene scene = new PreparedStemScene(plan,
                programmeStems(TRANSITION_FRAMES, false, false),
                programmeStems(TRANSITION_FRAMES, true, false),
                programme(TOTAL_FRAMES, true), RATE,
                TRANSITION_FRAMES, TOTAL_FRAMES);
        int position = 0;
        while (position < scene.frames()) {
            int count = Math.min(512, scene.frames() - position);
            scene.render(block, 0, position, count);
            graph.processBlock(block, 0, count);
            position += count;
        }
        MasterAudioGraph.ContinuityMetrics metrics = graph.snapshotMetrics();
        if (metrics.lastActivationFrame != ACTIVATION
                || metrics.transitionActivations != 1L
                || metrics.currentActivationGapFrames != 0L
                || metrics.underrunEvents != 0L
                || metrics.graphRecreations != 0L
                || metrics.nodeRecreations != 0L) {
            throw new AssertionError("51-second activation broke the persistent graph");
        }
        if (metrics.activationMaximumSampleDiscontinuity
                > AudioContinuityValidator.MAX_SAMPLE_JUMP
                || metrics.activationMaximumDerivativeDiscontinuity
                > AudioContinuityValidator.MAX_DERIVATIVE_JUMP) {
            throw new AssertionError("click threshold exceeded at 51 seconds");
        }
        System.out.println(String.format(Locale.US,
                "51s activation frame=%d gapFrames=%d underruns=%d graphRecreates=%d sampleJump=%.8f derivativeJump=%.8f",
                metrics.lastActivationFrame, metrics.currentActivationGapFrames,
                metrics.underrunEvents, metrics.graphRecreations,
                metrics.activationMaximumSampleDiscontinuity,
                metrics.activationMaximumDerivativeDiscontinuity));
    }

    private static ContinuousScenePlanner.PlanningResult planning() {
        ContinuousScenePlanner.PlanningRequest request =
                ContinuousScenePlanner.PlanningRequest.builder(
                                9_001L, 101L, 202L, ACTIVATION, BAR)
                        .sourceStartSample(ACTIVATION)
                        .targetLandingSample(RATE * 18L)
                        .stem(ContinuousSceneTransitionPlan.SemanticRole.LEAD_VOCAL,
                                1L, 2L, .78f, .92f)
                        .stem(ContinuousSceneTransitionPlan.SemanticRole.GUITAR,
                                3L, 4L, .98f, .95f)
                        .stem(ContinuousSceneTransitionPlan.SemanticRole.DRUMS,
                                5L, 6L, .86f, .91f)
                        .stem(ContinuousSceneTransitionPlan.SemanticRole.BASS,
                                7L, 8L, .84f, .90f)
                        .generatedStem(ContinuousSceneTransitionPlan.SemanticRole.ATMOSPHERE,
                                9L, 10L, 11L, .88f, .91f)
                        .separator(true, .93f)
                        .buffer(true, BAR * 16L, 0, 0f)
                        .vocalActivity(true, true)
                        .vocalChopSafe(true)
                        .compatibility(.91f, .89f, .90f, .88f, .86f, .92f)
                        .generatedArtifactRisk(.06f)
                        .build();
        ContinuousScenePlanner.PlanningResult result =
                new ContinuousScenePlanner().plan(request);
        if (!result.hasPlan()) throw new AssertionError("eligible plan rejected");
        return result;
    }

    private static float[] render(PreparedStemScene scene) {
        float[] output = new float[scene.frames() * 2];
        int frame = 0;
        while (frame < scene.frames()) {
            int count = Math.min(512, scene.frames() - frame);
            scene.render(output, frame * 2, frame, count);
            frame += count;
        }
        return output;
    }

    private static StemBundle programmeStems(int frames, boolean target, boolean vocalOnly) {
        float[] lead = new float[frames * 2];
        float[] drums = new float[frames * 2];
        float[] bass = new float[frames * 2];
        float[] backing = new float[frames * 2];
        long origin = target ? 0L : ACTIVATION;
        for (int frame = 0; frame < frames; frame++) {
            double time = (origin + frame) / (double) RATE;
            float vocal = tone(time, target ? 247.0 : 220.0, .055f);
            lead[frame * 2] = vocal;
            lead[frame * 2 + 1] = vocal * .95f;
            if (vocalOnly) continue;
            stereo(drums, frame, tone(time, target ? 132.0 : 120.0, .035f));
            stereo(bass, frame, tone(time, target ? 65.0 : 60.0, .045f));
            stereo(backing, frame, tone(time, target ? 370.0 : 330.0, .04f));
        }
        return new StemBundle(RATE, lead, drums, bass, backing);
    }

    private static StemBundle vocalOnlyStems(int frames, boolean sourceA) {
        StemBundle programme = programmeStems(frames, !sourceA, true);
        float[] lead = programme.lead.clone();
        for (int frame = 0; frame < frames; frame++) {
            if (sourceA) lead[frame * 2 + 1] = 0f;
            else lead[frame * 2] = 0f;
        }
        float[] zero = new float[frames * 2];
        return new StemBundle(RATE, lead, zero.clone(), zero.clone(), zero.clone());
    }

    private static PcmAudio programme(int frames, boolean target) {
        StemBundle stems = programmeStems(frames, target, false);
        float[] mix = new float[frames * 2];
        for (int frame = 0; frame < frames; frame++) {
            mix[frame * 2] = fullMix(stems, frame, 0);
            mix[frame * 2 + 1] = fullMix(stems, frame, 1);
        }
        return new PcmAudio(RATE, mix);
    }

    private static PcmAudio vocalProgramme(int frames, boolean sourceA) {
        return new PcmAudio(RATE, vocalOnlyStems(frames, sourceA).lead);
    }

    private static float fullMix(StemBundle stems, int frame, int channel) {
        int sample = frame * 2 + channel;
        return stems.lead[sample] + stems.drums[sample]
                + stems.bass[sample] + stems.backing[sample];
    }

    private static void fillProgramme(float[] destination, int frames,
                                      long startFrame, boolean target) {
        Arrays.fill(destination, 0f);
        for (int frame = 0; frame < frames; frame++) {
            double time = (startFrame + frame) / (double) RATE;
            float value = tone(time, target ? 247.0 : 220.0, .055f)
                    + tone(time, target ? 132.0 : 120.0, .035f)
                    + tone(time, target ? 65.0 : 60.0, .045f)
                    + tone(time, target ? 370.0 : 330.0, .04f);
            destination[frame * 2] = value;
            destination[frame * 2 + 1] = value * .95f;
        }
    }

    private static float tone(double time, double frequency, float amplitude) {
        return (float) Math.sin(2.0 * Math.PI * frequency * time) * amplitude;
    }

    private static void stereo(float[] destination, int frame, float value) {
        destination[frame * 2] = value;
        destination[frame * 2 + 1] = value * .95f;
    }
}
