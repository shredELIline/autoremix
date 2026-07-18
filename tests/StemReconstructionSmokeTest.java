package com.alexey.autoremix;

public final class StemReconstructionSmokeTest {
    public static void main(String[] args) {
        int rate = 32_000;
        int frames = rate * 3;
        float[] mix = new float[frames * 2];
        for (int i = 0; i < frames; i++) {
            double t = i / (double) rate;
            float vocal = (float) (Math.sin(2 * Math.PI * 440 * t) * .23
                    + Math.sin(2 * Math.PI * 660 * t) * .08);
            float bass = (float) Math.sin(2 * Math.PI * 86 * t) * .20f;
            float padL = (float) Math.sin(2 * Math.PI * 225 * t) * .08f;
            float padR = (float) Math.sin(2 * Math.PI * 233 * t) * .08f;
            float kick = (i % (rate / 2)) < 120
                    ? (float) Math.exp(-(i % (rate / 2)) / 35.0) * .45f : 0f;
            mix[i * 2] = vocal + bass + padL + kick;
            mix[i * 2 + 1] = vocal + bass + padR + kick * .92f;
        }
        StemBundle stems = SpectralStemSeparator.separate(new PcmAudio(rate, mix));
        double error = 0.0;
        double source = 1e-12;
        for (int i = 0; i < mix.length; i++) {
            float reconstructed = stems.lead[i] + stems.drums[i] + stems.bass[i] + stems.backing[i];
            if (!Float.isFinite(reconstructed)) throw new AssertionError("non-finite stem sample");
            double d = reconstructed - mix[i];
            error += d * d;
            source += mix[i] * mix[i];
        }
        double relative = Math.sqrt(error / source);
        if (relative > 1e-5) throw new AssertionError("reconstruction error " + relative);
        if (stems.leadRms <= 0f || stems.backingRms <= 0f) {
            throw new AssertionError("separator returned empty principal layers");
        }
        System.out.println("Stem reconstruction OK; relative error=" + relative
                + " lead=" + stems.leadRms + " drums=" + stems.drumsRms
                + " bass=" + stems.bassRms + " backing=" + stems.backingRms);
    }
}
