package com.alexey.autoremix;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class AudioContinuityValidatorSmokeTest {
    private static final int SAMPLE_RATE = 8_000;
    private static final int CHANNELS = 2;

    @Test public void variableActivationBoundariesStayOnOneContinuousTimeline() {
        for (int boundarySeconds : new int[]{7, 19, 37, 51}) {
            int durationFrames = SAMPLE_RATE * (boundarySeconds + 2);
            long activationSample = SAMPLE_RATE * (long) boundarySeconds;
            float[] timeline = new float[durationFrames * CHANNELS];
            for (int frame = 0; frame < durationFrames; frame++) {
                double time = frame / (double) SAMPLE_RATE;
                double distance = (frame - activationSample) / (double) SAMPLE_RATE;
                double morph = Math.max(0.0, Math.min(1.0, (distance + 1.0) / 2.0));
                float value = (float) (Math.sin(2.0 * Math.PI * 220.0 * time)
                        * (0.18 + 0.04 * morph));
                timeline[frame * CHANNELS] = value;
                timeline[frame * CHANNELS + 1] = value * 0.94f;
            }

            AudioContinuityValidator.Metrics metrics = AudioContinuityValidator.analyze(
                    timeline, SAMPLE_RATE, CHANNELS, activationSample, 0);

            assertEquals(boundarySeconds * 1_000.0,
                    metrics.activationSample * 1_000.0 / SAMPLE_RATE, 0.0);
            assertEquals(0.0, metrics.activationGapMs, 0.0);
            assertEquals(0, metrics.activationUnderruns);
            assertTrue(metrics.activationMaxSampleJump < 0.04f);
            assertTrue(metrics.activationMaxDerivativeJump < 0.01f);
            assertTrue(metrics.activationLufsJump < 0.1);
            assertTrue(metrics.activationSpectralFluxSpike < 0.02);
            assertEquals(durationFrames * CHANNELS, timeline.length);
        }
    }

    @Test public void discontinuityMetricsDetectARejectedBoundary() {
        int activationFrame = SAMPLE_RATE;
        float[] timeline = new float[SAMPLE_RATE * 2 * CHANNELS];
        for (int frame = 0; frame < timeline.length / CHANNELS; frame++) {
            float value = frame < activationFrame
                    ? (float) Math.sin(2.0 * Math.PI * 125.0 * frame / SAMPLE_RATE) * 0.08f
                    : 0.55f + (float) Math.sin(2.0 * Math.PI * 1_750.0 * frame / SAMPLE_RATE) * 0.30f;
            timeline[frame * CHANNELS] = value;
            timeline[frame * CHANNELS + 1] = value;
        }

        AudioContinuityValidator.Metrics metrics = AudioContinuityValidator.analyze(
                timeline, SAMPLE_RATE, CHANNELS, activationFrame, 0);

        assertTrue(metrics.activationMaxSampleJump > 0.4f);
        assertTrue(metrics.activationMaxDerivativeJump > 0.2f);
        assertTrue(metrics.activationLufsJump > 10.0);
        assertTrue(metrics.activationSpectralFluxSpike > 0.1);
    }

    @Test public void silenceHoleAndReportedUnderrunsAreNotHidden() {
        int activationFrame = SAMPLE_RATE;
        float[] timeline = new float[SAMPLE_RATE * 2 * CHANNELS];
        for (int frame = 0; frame < timeline.length / CHANNELS; frame++) {
            float value = (float) Math.sin(2.0 * Math.PI * 220.0 * frame / SAMPLE_RATE) * 0.2f;
            timeline[frame * CHANNELS] = value;
            timeline[frame * CHANNELS + 1] = value;
        }
        int gapFrames = SAMPLE_RATE / 100;
        for (int frame = activationFrame - gapFrames / 2;
             frame < activationFrame + gapFrames / 2; frame++) {
            timeline[frame * CHANNELS] = 0f;
            timeline[frame * CHANNELS + 1] = 0f;
        }

        AudioContinuityValidator.Metrics metrics = AudioContinuityValidator.analyze(
                timeline, SAMPLE_RATE, CHANNELS, activationFrame, 2);

        assertEquals(10.0, metrics.activationGapMs, 0.001);
        assertEquals(2, metrics.activationUnderruns);
    }
}
