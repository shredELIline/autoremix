package com.alexey.autoremix;

public final class MasteringSmokeTest {
    public static void main(String[] args) {
        int rate = 48_000;
        int frames = rate * 4;
        float[] audio = new float[frames * 2];
        for (int frame = 0; frame < frames; frame++) {
            double t = frame / (double) rate;
            float signal = (float) (1.8 * Math.sin(2 * Math.PI * 73 * t)
                    + .55 * Math.sin(2 * Math.PI * 3000 * t) + .18);
            audio[frame * 2] = signal;
            audio[frame * 2 + 1] = signal * .93f;
        }
        MasteringChain.processInPlace(audio, rate);
        MasteringChain.applyEdgeSafety(audio, rate, 8);
        float peak = 0f;
        for (float sample : audio) {
            if (!Float.isFinite(sample)) throw new AssertionError("non-finite master");
            peak = Math.max(peak, Math.abs(sample));
        }
        if (peak > 1.001f) throw new AssertionError("master exceeds full scale: " + peak);
        if (Math.abs(audio[0]) > 1e-4f || Math.abs(audio[audio.length - 2]) > .02f) {
            throw new AssertionError("unsafe scene edge");
        }
        System.out.println("Mastering/edge safety OK; peak=" + peak);
    }
}
