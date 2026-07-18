package com.alexey.autoremix;

public final class WsolaSmokeTest {
    public static void main(String[] args) {
        int rate = 32_000;
        int frames = rate * 6;
        float[] stereo = new float[frames * 2];
        for (int frame = 0; frame < frames; frame++) {
            double t = frame / (double) rate;
            int phase = frame % (rate / 2);
            float kick = phase < 160 ? (float) Math.exp(-phase / 42.0) * .42f : 0f;
            float tone = (float) (Math.sin(2 * Math.PI * 220 * t) * .14
                    + Math.sin(2 * Math.PI * 440 * t) * .06);
            stereo[frame * 2] = tone + kick;
            stereo[frame * 2 + 1] = tone + kick * .94f;
        }
        PcmAudio source = new PcmAudio(rate, stereo);
        for (float speed : new float[]{.92f, .96f, 1.04f, 1.08f}) {
            PcmAudio stretched = WsolaTimeStretch.stretch(source, speed);
            double expected = source.frames() / (double) speed;
            double durationError = Math.abs(stretched.frames() - expected) / expected;
            if (durationError > .035) throw new AssertionError("duration error " + speed + " " + durationError);
            float maxStep = 0f;
            for (int i = 2; i < stretched.stereo.length; i++) {
                float value = stretched.stereo[i];
                if (!Float.isFinite(value)) throw new AssertionError("non-finite at " + speed);
                maxStep = Math.max(maxStep, Math.abs(value - stretched.stereo[i - 2]));
            }
            if (maxStep > .9f) throw new AssertionError("tempo splice click " + speed + " step=" + maxStep);
        }
        System.out.println("WSOLA tempo matching OK");
    }
}
