package com.alexey.autoremix;

/** Aligns the incoming programme to the outgoing transient grid after tempo matching. */
final class BeatPhaseAligner {
    private static final int HOP = 128;
    private static final int WINDOW = 384;

    static final class Result {
        final PcmAudio audio;
        final int skippedFrames;
        final float confidence;

        Result(PcmAudio audio, int skippedFrames, float confidence) {
            this.audio = audio;
            this.skippedFrames = skippedFrames;
            this.confidence = confidence;
        }
    }

    private BeatPhaseAligner() {}

    static Result alignForward(PcmAudio anchor, PcmAudio incoming, int maximumShiftFrames) {
        if (anchor == null || incoming == null || anchor.frames() < WINDOW * 4
                || incoming.frames() < WINDOW * 4 || anchor.sampleRate != incoming.sampleRate) {
            return new Result(incoming, 0, 0f);
        }
        float[] a = onsetEnvelope(anchor);
        float[] b = onsetEnvelope(incoming);
        int maxShiftHops = Math.min(Math.max(0, maximumShiftFrames / HOP),
                Math.max(0, b.length - 8));
        int compare = Math.min(a.length, Math.min(b.length - maxShiftHops,
                Math.max(24, anchor.sampleRate * 10 / HOP)));
        if (compare < 12) return new Result(incoming, 0, 0f);

        int bestShift = 0;
        float best = -Float.MAX_VALUE;
        float second = -Float.MAX_VALUE;
        for (int shift = 0; shift <= maxShiftHops; shift++) {
            float score = normalizedCorrelation(a, b, shift, compare);
            if (score > best) {
                second = best;
                best = score;
                bestShift = shift;
            } else if (score > second) {
                second = score;
            }
        }
        int skippedFrames = bestShift * HOP;
        PcmAudio aligned = incoming.sliceFrames(skippedFrames,
                Math.max(0, incoming.frames() - skippedFrames));
        float margin = best <= -1f ? 0f : clamp((best - Math.max(-1f, second)) * 3.5f, 0f, 1f);
        float absolute = clamp((best + 1f) * .5f, 0f, 1f);
        return new Result(aligned, skippedFrames, absolute * .72f + margin * .28f);
    }

    private static float[] onsetEnvelope(PcmAudio audio) {
        int count = Math.max(1, 1 + (audio.frames() - WINDOW) / HOP);
        float[] envelope = new float[count];
        float previousEnergy = 0f;
        float previousMono = 0f;
        for (int frame = 0; frame < count; frame++) {
            int start = frame * HOP;
            double flux = 0.0;
            double energy = 0.0;
            for (int i = 0; i < WINDOW && start + i < audio.frames(); i += 2) {
                int index = (start + i) * 2;
                float mono = (audio.stereo[index] + audio.stereo[index + 1]) * .5f;
                float high = mono - previousMono * .985f;
                previousMono = mono;
                flux += Math.abs(high);
                energy += mono * mono;
            }
            float localEnergy = (float) Math.sqrt(energy / Math.max(1, WINDOW / 2));
            float positiveFlux = (float) flux / Math.max(1, WINDOW / 2);
            float rise = Math.max(0f, localEnergy - previousEnergy);
            previousEnergy += (localEnergy - previousEnergy) * .35f;
            envelope[frame] = positiveFlux * .72f + rise * .28f;
        }
        normalize(envelope);
        return envelope;
    }

    private static float normalizedCorrelation(float[] a, float[] b, int shift, int count) {
        double dot = 0.0, a2 = 1e-9, b2 = 1e-9;
        for (int i = 0; i < count; i++) {
            float av = a[i];
            float bv = b[i + shift];
            dot += av * bv;
            a2 += av * av;
            b2 += bv * bv;
        }
        return (float) (dot / Math.sqrt(a2 * b2));
    }

    private static void normalize(float[] values) {
        double mean = 0.0;
        for (float value : values) mean += value;
        mean /= Math.max(1, values.length);
        double variance = 1e-9;
        for (float value : values) {
            double d = value - mean;
            variance += d * d;
        }
        float scale = (float) (1.0 / Math.sqrt(variance / Math.max(1, values.length)));
        for (int i = 0; i < values.length; i++) values[i] = (float) ((values[i] - mean) * scale);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
