package com.alexey.autoremix;

public final class MasterAudioGraphSmokeTest {
    public static void main(String[] args) {
        continuousBlocksKeepOneTimelineAndPersistentNodes();
        underrunIsClockedAndMeasured();
        System.out.println("Persistent master audio graph OK");
    }

    private static void continuousBlocksKeepOneTimelineAndPersistentNodes() {
        int blockFrames = 128;
        MasterAudioGraph graph = new MasterAudioGraph(blockFrames, 24);
        float[] block = new float[blockFrames * MasterAudioGraph.CHANNELS];
        long inputFrame = 0L;
        long activationFrame = -1L;
        float peak = 0f;

        for (int blockIndex = 0; blockIndex < 300; blockIndex++) {
            if (blockIndex == 120) {
                activationFrame = graph.markTransitionActivation(77L);
            }
            for (int frame = 0; frame < blockFrames; frame++, inputFrame++) {
                double phase = 2.0 * Math.PI * 220.0 * inputFrame / MasterAudioGraph.SAMPLE_RATE;
                float source = (float) (1.4 * Math.sin(phase));
                block[frame * 2] = source;
                block[frame * 2 + 1] = source * .93f;
            }
            graph.processBlock(block, 0, blockFrames);
            for (float sample : block) {
                if (!Float.isFinite(sample)) throw new AssertionError("non-finite output");
                peak = Math.max(peak, Math.abs(sample));
            }
        }

        MasterAudioGraph.ContinuityMetrics metrics = graph.snapshotMetrics();
        long expectedFrames = 300L * blockFrames;
        if (graph.sampleClock() != expectedFrames || metrics.masterFrame != expectedFrames) {
            throw new AssertionError("master clock split or drifted");
        }
        if (activationFrame != 120L * blockFrames
                || metrics.lastActivationFrame != activationFrame
                || metrics.lastTransitionId != 77L
                || graph.transitionFrameFromNewest(0) != activationFrame) {
            throw new AssertionError("activation marker left the master timeline");
        }
        if (metrics.transitionActivations != 1L || graph.retainedTransitionMarkers() != 1) {
            throw new AssertionError("activation marker missing");
        }
        if (metrics.activationGapFrames != 0L || metrics.underrunEvents != 0L) {
            throw new AssertionError("continuous activation reported a gap or underrun");
        }
        if (metrics.graphRecreations != 0L || metrics.nodeRecreations != 0L) {
            throw new AssertionError("transition recreated the graph or processors");
        }
        if (peak > .986f) throw new AssertionError("limiter ceiling exceeded: " + peak);
        if (!Float.isFinite(metrics.lufsProxyDb) || !Float.isFinite(metrics.spectralProxy)
                || metrics.maximumSampleDiscontinuity <= 0f
                || metrics.maximumDerivativeDiscontinuity <= 0f) {
            throw new AssertionError("continuity meters inactive");
        }
        if (metrics.activationMaximumSampleDiscontinuity > .06f
                || metrics.activationMaximumDerivativeDiscontinuity > .01f
                || metrics.activationLufsJump > .2f
                || metrics.activationSpectralFluxSpike > .02f) {
            throw new AssertionError("activation-local continuity regression");
        }
    }

    private static void underrunIsClockedAndMeasured() {
        int blockFrames = 64;
        MasterAudioGraph graph = new MasterAudioGraph(blockFrames, 0);
        float[] block = new float[blockFrames * MasterAudioGraph.CHANNELS];
        graph.processBlock(block, 0, blockFrames, false);
        MasterAudioGraph.ContinuityMetrics metrics = graph.snapshotMetrics();
        if (metrics.masterFrame != blockFrames || metrics.underrunEvents != 1L
                || metrics.underrunFrames != blockFrames) {
            throw new AssertionError("underrun did not preserve and meter the master clock");
        }
    }
}
