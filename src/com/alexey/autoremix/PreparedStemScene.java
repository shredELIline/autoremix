package com.alexey.autoremix;

import java.util.Arrays;

/** Preloaded stem nodes rendered incrementally by the single master producer. */
final class PreparedStemScene {
    private static final int LANDING_BLEND_MS = 20;
    private static final int FILTER_ALPHA_STEPS = 1_024;

    final ContinuousSceneTransitionPlan plan;
    private final QuantizedStemBundle sourceA;
    private final QuantizedStemBundle sourceB;
    private final PcmAudio targetProgramme;
    private final int outputSampleRate;
    private final int transitionOutputFrames;
    private final int totalOutputFrames;
    private final float[] lowpassLeft;
    private final float[] lowpassRight;
    private final float[] filterAlpha = new float[FILTER_ALPHA_STEPS];
    private final float[][] reverbDelayLeft;
    private final float[][] reverbDelayRight;
    private final int reverbDelayFrames;
    private int reverbWrite;
    private long nextOutputFrame;

    PreparedStemScene(ContinuousSceneTransitionPlan plan,
                      StemBundle sourceA, StemBundle sourceB,
                      PcmAudio targetProgramme, int outputSampleRate,
                      int transitionProcessFrames, int totalProcessFrames) {
        this(plan, QuantizedStemBundle.from(sourceA), QuantizedStemBundle.from(sourceB),
                targetProgramme, outputSampleRate, transitionProcessFrames,
                totalProcessFrames);
    }

    PreparedStemScene(ContinuousSceneTransitionPlan plan,
                      QuantizedStemBundle sourceA, QuantizedStemBundle sourceB,
                      PcmAudio targetProgramme, int outputSampleRate,
                      int transitionProcessFrames, int totalProcessFrames) {
        if (plan == null || sourceA == null || sourceB == null || targetProgramme == null
                || sourceA.sampleRate != sourceB.sampleRate
                || sourceA.sampleRate != targetProgramme.sampleRate
                || outputSampleRate <= 0 || transitionProcessFrames <= 0
                || totalProcessFrames < transitionProcessFrames
                || sourceA.frames < transitionProcessFrames
                || sourceB.frames < transitionProcessFrames
                || targetProgramme.frames() < totalProcessFrames) {
            throw new IllegalArgumentException("invalid prepared stem scene");
        }
        this.plan = plan;
        this.sourceA = sourceA;
        this.sourceB = sourceB;
        this.targetProgramme = targetProgramme;
        this.outputSampleRate = outputSampleRate;
        transitionOutputFrames = Math.max(1, (int) Math.round(
                (double) transitionProcessFrames * outputSampleRate / sourceA.sampleRate));
        totalOutputFrames = Math.max(transitionOutputFrames, (int) Math.round(
                (double) totalProcessFrames * outputSampleRate / sourceA.sampleRate));
        lowpassLeft = new float[plan.stemTimelines.size()];
        lowpassRight = new float[plan.stemTimelines.size()];
        reverbDelayFrames = Math.max(1, outputSampleRate * 37 / 1_000);
        reverbDelayLeft = new float[plan.stemTimelines.size()][reverbDelayFrames];
        reverbDelayRight = new float[plan.stemTimelines.size()][reverbDelayFrames];
        for (int index = 0; index < filterAlpha.length; index++) {
            float cutoff = 40f + (outputSampleRate * .49f - 40f)
                    * index / (filterAlpha.length - 1f);
            filterAlpha[index] = 1f - (float) Math.exp(
                    -2.0 * Math.PI * cutoff / outputSampleRate);
        }
    }

    int frames() {
        return totalOutputFrames;
    }

    int sampleRate() {
        return outputSampleRate;
    }

    int transitionFrames() {
        return transitionOutputFrames;
    }

    long durationMs() {
        return Math.round(totalOutputFrames * 1_000.0 / outputSampleRate);
    }

    void render(float[] destination, int destinationOffsetSamples,
                long startOutputFrame, int frameCount) {
        if (destination == null || destinationOffsetSamples < 0 || frameCount < 0
                || destinationOffsetSamples > destination.length - frameCount * 2
                || startOutputFrame < 0L
                || startOutputFrame > totalOutputFrames - (long) frameCount) {
            throw new IllegalArgumentException("invalid stem render range");
        }
        if (startOutputFrame != nextOutputFrame) resetForSeek(startOutputFrame);
        for (int frame = 0; frame < frameCount; frame++) {
            long outputFrame = startOutputFrame + frame;
            int destinationSample = destinationOffsetSamples + frame * 2;
            if (outputFrame >= transitionOutputFrames) {
                targetSample(outputFrame, destination, destinationSample);
            } else {
                transitionSample(outputFrame, destination, destinationSample);
            }
            nextOutputFrame++;
        }
    }

    void resetForSeek(long outputFrame) {
        if (outputFrame < 0L || outputFrame > totalOutputFrames) {
            throw new IllegalArgumentException("outputFrame");
        }
        Arrays.fill(lowpassLeft, 0f);
        Arrays.fill(lowpassRight, 0f);
        for (int index = 0; index < reverbDelayLeft.length; index++) {
            Arrays.fill(reverbDelayLeft[index], 0f);
            Arrays.fill(reverbDelayRight[index], 0f);
        }
        reverbWrite = 0;
        nextOutputFrame = outputFrame;
    }

    private void transitionSample(long outputFrame, float[] destination,
                                  int destinationSample) {
        long planSample = plan.activationSample + outputFrame;
        double processFrame = outputFrame * sourceA.sampleRate / (double) outputSampleRate;
        float left = 0f;
        float right = 0f;
        float gainA = 0f;
        float gainB = 0f;
        float gainGenerated = 0f;
        for (int index = 0; index < plan.stemTimelines.size(); index++) {
            ContinuousSceneTransitionPlan.StemTimeline timeline = plan.stemTimelines.get(index);
            float gain = timeline.gainAt(planSample);
            if (gain <= 0f) continue;
            float tempo = Math.max(.5f, Math.min(2f,
                    timeline.tempoEnvelope.valueAt(planSample)));
            float pitch = timeline.pitchEnvelope.valueAt(planSample);
            float pitchRatio = Math.abs(pitch) < 1e-6f ? 1f
                    : (float) Math.pow(2.0, pitch / 12.0);
            double laneFrame = processFrame * tempo * pitchRatio;
            float sourceLeft;
            float sourceRight;
            if (timeline.source == ContinuousSceneTransitionPlan.Source.GENERATED) {
                if (timeline.semanticRole.isVocal()) continue;
                double frequency = timeline.semanticRole
                        == ContinuousSceneTransitionPlan.SemanticRole.BASS ? 55.0 : 220.0;
                float texture = (float) Math.sin(2.0 * Math.PI * frequency
                        * laneFrame / sourceA.sampleRate) * .035f;
                sourceLeft = sourceRight = texture;
            } else {
                QuantizedStemBundle stems = timeline.source
                        == ContinuousSceneTransitionPlan.Source.A ? sourceA : sourceB;
                if (timeline.semanticRole
                        == ContinuousSceneTransitionPlan.SemanticRole.FULL_MIX) {
                    sourceLeft = stems.fullMixSample(laneFrame, 0);
                    sourceRight = stems.fullMixSample(laneFrame, 1);
                } else {
                    sourceLeft = stems.sample(timeline.semanticRole, laneFrame, 0);
                    sourceRight = stems.sample(timeline.semanticRole, laneFrame, 1);
                }
            }
            float width = Math.max(0f, timeline.widthEnvelope.valueAt(planSample));
            float mid = (sourceLeft + sourceRight) * .5f;
            float side = (sourceLeft - sourceRight) * .5f * width;
            sourceLeft = mid + side;
            sourceRight = mid - side;
            float formant = timeline.formantEnvelope.valueAt(planSample);
            float formantRatio = Math.abs(formant) < 1e-6f ? 1f
                    : (float) Math.pow(2.0, formant / 12.0);
            float cutoff = Math.max(40f, Math.min(outputSampleRate * .49f,
                    timeline.filterEnvelope.valueAt(planSample) * formantRatio));
            int cutoffIndex = Math.max(0, Math.min(FILTER_ALPHA_STEPS - 1,
                    Math.round((cutoff - 40f) / (outputSampleRate * .49f - 40f)
                            * (FILTER_ALPHA_STEPS - 1))));
            float alpha = filterAlpha[cutoffIndex];
            lowpassLeft[index] += (sourceLeft - lowpassLeft[index]) * alpha;
            lowpassRight[index] += (sourceRight - lowpassRight[index]) * alpha;
            float pan = Math.max(-1f, Math.min(1f,
                    timeline.panEnvelope.valueAt(planSample)));
            float leftPan = Math.abs(pan) < 1e-6f ? 1f
                    : (float) Math.sqrt((1f - pan) * .5f) * 1.4142135f;
            float rightPan = Math.abs(pan) < 1e-6f ? 1f
                    : (float) Math.sqrt((1f + pan) * .5f) * 1.4142135f;
            float eq = timeline.eqEnvelope.valueAt(planSample);
            float eqGain = Math.abs(eq) < 1e-6f ? 1f
                    : (float) Math.pow(10.0, eq / 20.0);
            float transientGain = Math.max(0f,
                    timeline.transientEnvelope.valueAt(planSample));
            float morph = Math.max(0f, Math.min(1f,
                    (timeline.harmonicMorphEnvelope.valueAt(planSample)
                            + timeline.timbreMorphEnvelope.valueAt(planSample)) * .5f));
            float processedGain = gain * eqGain * transientGain * (1f - .04f * morph);
            float reverbWet = Math.max(0f, Math.min(.35f,
                    timeline.reverbEnvelope.valueAt(planSample)));
            float delayedLeft = reverbDelayLeft[index][reverbWrite];
            float delayedRight = reverbDelayRight[index][reverbWrite];
            reverbDelayLeft[index][reverbWrite] = lowpassLeft[index] + delayedLeft * .28f;
            reverbDelayRight[index][reverbWrite] = lowpassRight[index] + delayedRight * .28f;
            float processedLeft = lowpassLeft[index] * (1f - reverbWet)
                    + delayedLeft * reverbWet;
            float processedRight = lowpassRight[index] * (1f - reverbWet)
                    + delayedRight * reverbWet;
            left += processedLeft * processedGain * leftPan;
            right += processedRight * processedGain * rightPan;
            if (timeline.source == ContinuousSceneTransitionPlan.Source.A) gainA += gain;
            else if (timeline.source == ContinuousSceneTransitionPlan.Source.B) gainB += gain;
            else gainGenerated += gain;
        }
        reverbWrite = (reverbWrite + 1) % reverbDelayFrames;
        float deckPower = gainA * gainA + gainB * gainB
                + gainGenerated * gainGenerated;
        float headroom = deckPower > 16f ? 4f / (float) Math.sqrt(deckPower) : 1f;
        int landingBlendFrames = Math.max(1,
                outputSampleRate * LANDING_BLEND_MS / 1_000);
        long blendStart = Math.max(0L, transitionOutputFrames - landingBlendFrames);
        if (outputFrame >= blendStart) {
            float t = (outputFrame - blendStart)
                    / (float) Math.max(1L, transitionOutputFrames - 1L - blendStart);
            t = Math.max(0f, Math.min(1f, t));
            float smooth = t * t * (3f - 2f * t);
            float targetLeft = targetSample(outputFrame, 0);
            float targetRight = targetSample(outputFrame, 1);
            left = left * headroom + (targetLeft - left * headroom) * smooth;
            right = right * headroom + (targetRight - right * headroom) * smooth;
        } else {
            left *= headroom;
            right *= headroom;
        }
        destination[destinationSample] = left;
        destination[destinationSample + 1] = right;
    }

    private void targetSample(long outputFrame, float[] destination, int destinationSample) {
        destination[destinationSample] = targetSample(outputFrame, 0);
        destination[destinationSample + 1] = targetSample(outputFrame, 1);
    }

    private float targetSample(long outputFrame, int channel) {
        double processFrame = outputFrame * targetProgramme.sampleRate
                / (double) outputSampleRate;
        return interpolated(targetProgramme.stereo, processFrame, channel);
    }

    private static float interpolated(float[] stereo, double frame, int channel) {
        int frames = stereo.length / 2;
        if (frames == 0) return 0f;
        int leftFrame = Math.max(0, Math.min(frames - 1, (int) frame));
        int rightFrame = Math.min(frames - 1, leftFrame + 1);
        float fraction = (float) Math.max(0.0, Math.min(1.0, frame - leftFrame));
        float left = stereo[leftFrame * 2 + channel];
        return left + (stereo[rightFrame * 2 + channel] - left) * fraction;
    }
}
