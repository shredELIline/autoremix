package com.alexey.autoremix;

import android.content.Context;

/** Offline-ahead scene renderer: decode → tempo match → stem separation → layer narrative → master. */
final class SceneRenderer {
    private static final int PROCESS_RATE = 32_000;
    private static final int OUTPUT_RATE = SceneAudioPlayer.outputRate();

    private SceneRenderer() {}

    static RenderedScene renderPrelude(Context context, Track track, TrackAnalysis analysis,
                                       TrackAnalysis.Fragment fragment, long durationMs) throws Exception {
        long available = Math.max(8_000L, track.durationMs - fragment.cueMs - 2_000L);
        long requested = Math.max(20_000L, Math.min(durationMs, available));
        PcmAudio decoded = PcmDecoder.decode(context, track, fragment.cueMs, requested, PROCESS_RATE);
        float[] programme = decoded.stereo.clone();
        applyProgramGain(programme, analysis.loudnessDb);
        MasteringChain.processInPlace(programme, PROCESS_RATE);
        PcmAudio output = PcmDecoder.resample(new PcmAudio(PROCESS_RATE, programme), OUTPUT_RATE);
        MasteringChain.applyEdgeSafety(output.stereo, OUTPUT_RATE, 8);
        return new RenderedScene(output, track, analysis, fragment,
                fragment.cueMs + decoded.durationMs(), track.displayName(),
                "PCM-прелюдия · готовлю stem-сцену в фоне", false);
    }


    static RenderedScene renderContinuation(Context context, Track track, TrackAnalysis analysis,
                                            TrackAnalysis.Fragment fragment, long startMs,
                                            long durationMs, String reason) throws Exception {
        return renderContinuationVariant(context, track, analysis, fragment, startMs,
                durationMs, ContinuationReservoir.VARIATION_NONE, reason);
    }

    static RenderedScene renderContinuationVariant(
            Context context, Track track, TrackAnalysis analysis,
            TrackAnalysis.Fragment fragment, long startMs, long durationMs,
            int variationMask, String reason) throws Exception {
        long available = Math.max(0L, track.durationMs - startMs - 1_000L);
        if (available < 8_000L) throw new IllegalStateException("track ended");
        long requested = Math.min(durationMs, available);
        PcmAudio decoded = PcmDecoder.decode(context, track, startMs, requested, PROCESS_RATE);
        float[] programme = decoded.stereo.clone();
        applyProgramGain(programme, analysis.loudnessDb);
        applyDeterministicVariation(programme, PROCESS_RATE, fragment.beatPeriodMs,
                variationMask);
        MasteringChain.processInPlace(programme, PROCESS_RATE);
        PcmAudio output = PcmDecoder.resample(new PcmAudio(PROCESS_RATE, programme), OUTPUT_RATE);
        MasteringChain.applyEdgeSafety(output.stereo, OUTPUT_RATE, 7);
        return new RenderedScene(output, track, analysis, fragment,
                startMs + decoded.durationMs(), track.displayName(), reason, false);
    }

    static void applyDeterministicVariation(float[] stereo, int sampleRate,
                                            long beatPeriodMs, int variationMask) {
        if (variationMask == ContinuationReservoir.VARIATION_NONE) return;
        int frames = stereo.length / 2;
        int beatFrames = Math.max(1, (int) Math.min(Integer.MAX_VALUE,
                beatPeriodMs * sampleRate / 1_000L));
        float lowLeft = 0f;
        float lowRight = 0f;
        float lowPassAlpha = .12f;
        for (int frame = 0; frame < frames; frame++) {
            int sample = frame * 2;
            float left = stereo[sample];
            float right = stereo[sample + 1];
            if ((variationMask & ContinuationReservoir.VARIATION_RHYTHM) != 0) {
                int beat = frame / beatFrames;
                float phase = (frame % beatFrames) / (float) beatFrames;
                float from = (beat & 1) == 0 ? 1.035f : .965f;
                float to = (beat & 1) == 0 ? .965f : 1.035f;
                float smooth = phase * phase * (3f - 2f * phase);
                float gain = from + (to - from) * smooth;
                left *= gain;
                right *= gain;
            }
            if ((variationMask & ContinuationReservoir.VARIATION_TIMBRE) != 0) {
                lowLeft += (left - lowLeft) * lowPassAlpha;
                lowRight += (right - lowRight) * lowPassAlpha;
                float mid = (left + right) * .5f;
                float side = (left - right) * .41f;
                left = (mid + side) * .82f + lowLeft * .18f;
                right = (mid - side) * .82f + lowRight * .18f;
            }
            stereo[sample] = left;
            stereo[sample + 1] = right;
        }
    }

    static RenderedScene renderTransition(Context context,
                                          Track aTrack, TrackAnalysis aAnalysis,
                                          TrackAnalysis.Fragment aFragment, long aPositionMs,
                                          Track bTrack, TrackAnalysis bAnalysis,
                                          TrackAnalysis.Fragment bFragment, LayerPlan plan,
                                          long soloMs) throws Exception {
        long transitionMs = barsToMs(aAnalysis.bpm, plan.bars);
        transitionMs = Math.max(18_000L, Math.min(48_000L, transitionMs));
        long remainingA = Math.max(0L, aTrack.durationMs - aPositionMs - 1_500L);
        soloMs = Math.max(12_000L, Math.min(soloMs, Math.max(12_000L, remainingA - transitionMs)));
        long requiredA = Math.min(remainingA, soloMs + transitionMs);
        if (requiredA < 22_000L) throw new IllegalStateException("outgoing track has no safe transition tail");
        soloMs = Math.max(4_000L, requiredA - transitionMs);

        PcmAudio aProgramme = PcmDecoder.decode(context, aTrack, aPositionMs,
                soloMs + transitionMs + 500L, PROCESS_RATE);
        applyProgramGain(aProgramme.stereo, aAnalysis.loudnessDb);
        int soloFrames = Math.min(aProgramme.frames(), msToFrames(soloMs));
        int transitionFrames = Math.min(msToFrames(transitionMs), aProgramme.frames() - soloFrames);
        if (transitionFrames < PROCESS_RATE * 12) throw new IllegalStateException("short decoded transition");
        long renderedTransitionMs = Math.round(transitionFrames * 1000.0 / PROCESS_RATE);
        PcmAudio aTransition = aProgramme.sliceFrames(soloFrames, transitionFrames);

        long bInputMs = Math.round(transitionMs * plan.tempoRatio) + 2_000L;
        long bStart = Math.max(0L, Math.min(bTrack.durationMs - 8_000L, bFragment.cueMs));
        PcmAudio bRaw = PcmDecoder.decode(context, bTrack, bStart, bInputMs, PROCESS_RATE);
        applyProgramGain(bRaw.stereo, bAnalysis.loudnessDb);
        PcmAudio bMatched = WsolaTimeStretch.stretch(bRaw, plan.tempoRatio);
        int oneBeatFrames = Math.max(PROCESS_RATE / 4,
                Math.round(PROCESS_RATE * 60f / Math.max(65f, aAnalysis.bpm)));
        BeatPhaseAligner.Result phase = BeatPhaseAligner.alignForward(
                aTransition, bMatched, oneBeatFrames);
        PcmAudio bTransition = fitLength(phase.audio, transitionFrames);

        StemBundle aStems = SpectralStemSeparator.separate(aTransition);
        StemBundle bStems = SpectralStemSeparator.separate(bTransition);
        PcmAudio mixed = LayerTransitionMixer.mix(aStems, bStems, plan);

        float[] scene = new float[(soloFrames + transitionFrames) * 2];
        System.arraycopy(aProgramme.stereo, 0, scene, 0, soloFrames * 2);
        System.arraycopy(mixed.stereo, 0, scene, soloFrames * 2, transitionFrames * 2);
        MasteringChain.processInPlace(scene, PROCESS_RATE);
        PcmAudio output = PcmDecoder.resample(new PcmAudio(PROCESS_RATE, scene), OUTPUT_RATE);
        MasteringChain.applyEdgeSafety(output.stereo, OUTPUT_RATE, 7);

        Track anchorTrack;
        TrackAnalysis anchorAnalysis;
        TrackAnalysis.Fragment anchorFragment;
        long anchorPosition;
        if (plan.type.returnsToA) {
            anchorTrack = aTrack;
            anchorAnalysis = aAnalysis;
            anchorFragment = aFragment;
            anchorPosition = Math.min(aTrack.durationMs - 1_000L,
                    aPositionMs + Math.round(soloFrames * 1000.0 / PROCESS_RATE) + renderedTransitionMs);
        } else {
            anchorTrack = bTrack;
            anchorAnalysis = bAnalysis;
            anchorFragment = bFragment;
            long phaseSourceMs = Math.round(phase.skippedFrames * 1000.0
                    / PROCESS_RATE * plan.tempoRatio);
            long consumedSourceMs = Math.round(renderedTransitionMs * plan.tempoRatio) + phaseSourceMs;
            anchorPosition = Math.min(bTrack.durationMs - 2_000L, bStart + consumedSourceMs);
        }
        String title = aTrack.displayName() + "  ×  " + bTrack.displayName();
        String description = plan.type.label + " · " + plan.bars + " тактов · " + plan.reason
                + " · HPSS stems · beat phase " + Math.round(phase.confidence * 100f)
                + "% · single PCM master";
        return new RenderedScene(output, anchorTrack, anchorAnalysis, anchorFragment,
                anchorPosition, title, description, true);
    }

    /** Guaranteed last-resort bridge. It is bounded, equal-power, normalized and never cuts PCM. */
    static RenderedScene renderEmergencyCrossfade(Context context,
                                                   Track aTrack, TrackAnalysis aAnalysis,
                                                   TrackAnalysis.Fragment aFragment, long aPositionMs,
                                                   Track bTrack, TrackAnalysis bAnalysis,
                                                   TrackAnalysis.Fragment bFragment,
                                                   long durationMs) throws Exception {
        long bridgeMs = Math.max(6_000L, Math.min(12_000L, durationMs));
        long aStart = Math.max(0L, Math.min(aPositionMs,
                Math.max(0L, aTrack.durationMs - bridgeMs - 1_000L)));
        long bStart = Math.max(0L, Math.min(bFragment.cueMs,
                Math.max(0L, bTrack.durationMs - bridgeMs - 1_000L)));
        PcmAudio a = PcmDecoder.decode(context, aTrack, aStart, bridgeMs, PROCESS_RATE);
        PcmAudio b = PcmDecoder.decode(context, bTrack, bStart, bridgeMs, PROCESS_RATE);
        applyProgramGain(a.stereo, aAnalysis.loudnessDb);
        applyProgramGain(b.stereo, bAnalysis.loudnessDb);
        int frames = Math.min(a.frames(), b.frames());
        if (frames < PROCESS_RATE * 2) throw new IllegalStateException("no emergency bridge audio");
        float[] mixed = new float[frames * 2];
        for (int frame = 0; frame < frames; frame++) {
            float p = frame / (float) Math.max(1, frames - 1);
            float gainA = (float) Math.cos(p * Math.PI * .5);
            float gainB = (float) Math.sin(p * Math.PI * .5);
            int sample = frame * 2;
            mixed[sample] = a.stereo[sample] * gainA + b.stereo[sample] * gainB;
            mixed[sample + 1] = a.stereo[sample + 1] * gainA + b.stereo[sample + 1] * gainB;
        }
        MasteringChain.processInPlace(mixed, PROCESS_RATE);
        PcmAudio output = PcmDecoder.resample(new PcmAudio(PROCESS_RATE, mixed), OUTPUT_RATE);
        MasteringChain.applyEdgeSafety(output.stereo, OUTPUT_RATE, 8);
        long consumedB = Math.round(frames * 1000.0 / PROCESS_RATE);
        return new RenderedScene(output, bTrack, bAnalysis, bFragment,
                Math.min(bTrack.durationMs - 1_000L, bStart + consumedB),
                aTrack.displayName() + "  ×  " + bTrack.displayName(),
                "Guaranteed click-free fallback · equal-power PCM bridge", true);
    }

    private static PcmAudio fitLength(PcmAudio input, int targetFrames) {
        if (input.frames() == targetFrames) return input;
        float[] out = new float[targetFrames * 2];
        int copy = Math.min(out.length, input.stereo.length);
        System.arraycopy(input.stereo, 0, out, 0, copy);
        if (input.frames() > 0 && copy < out.length) {
            // Never extend a decoded clip with a constant last sample: that creates DC and an
            // audible edge when the layer closes. Fade the available tail and leave silence.
            int copiedFrames = copy / 2;
            int ramp = Math.min(copiedFrames, Math.max(1, input.sampleRate / 50)); // 20 ms
            for (int frame = 0; frame < ramp; frame++) {
                float p = frame / (float) Math.max(1, ramp - 1);
                float gain = .5f + .5f * (float) Math.cos(Math.PI * p);
                int index = (copiedFrames - ramp + frame) * 2;
                out[index] *= gain;
                out[index + 1] *= gain;
            }
        }
        return new PcmAudio(input.sampleRate, out);
    }

    private static void applyProgramGain(float[] audio, float loudnessDb) {
        float correctionDb = clamp(-15.5f - loudnessDb, -5f, 2.5f);
        float gain = (float) Math.pow(10.0, correctionDb / 20.0);
        for (int i = 0; i < audio.length; i++) audio[i] *= gain;
    }

    private static int msToFrames(long milliseconds) {
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE,
                Math.round(milliseconds * PROCESS_RATE / 1000.0)));
    }

    private static long barsToMs(float bpm, int bars) {
        return Math.round(Math.max(1, bars) * 4.0 * 60_000.0 / Math.max(65f, bpm));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
