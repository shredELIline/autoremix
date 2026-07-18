package com.alexey.autoremix;

public final class BeatPhaseAlignerSmokeTest {
    public static void main(String[] args) {
        int rate = 32_000;
        int frames = rate * 8;
        int beat = rate / 2;
        int offset = rate * 137 / 1000;
        PcmAudio a = pulses(rate, frames, beat, 0);
        PcmAudio b = pulses(rate, frames + beat, beat, offset);
        BeatPhaseAligner.Result result = BeatPhaseAligner.alignForward(a, b, beat);
        int error = Math.abs(result.skippedFrames - offset);
        if (error > 384) {
            throw new AssertionError("phase error " + error + " skipped=" + result.skippedFrames);
        }
        if (result.confidence < .40f) throw new AssertionError("low alignment confidence " + result.confidence);
        System.out.println("Beat phase alignment OK; skipped=" + result.skippedFrames
                + " confidence=" + result.confidence);
    }

    private static PcmAudio pulses(int rate, int frames, int beat, int offset) {
        float[] out = new float[frames * 2];
        for (int frame = 0; frame < frames; frame++) {
            int phase = Math.floorMod(frame - offset, beat);
            float pulse = phase < 240 ? (float) Math.exp(-phase / 55.0) * .72f : 0f;
            float tone = (float) Math.sin(2.0 * Math.PI * 180 * frame / rate) * .04f;
            out[frame * 2] = pulse + tone;
            out[frame * 2 + 1] = pulse * .95f + tone;
        }
        return new PcmAudio(rate, out);
    }
}
