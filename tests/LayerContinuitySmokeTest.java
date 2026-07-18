package com.alexey.autoremix;

public final class LayerContinuitySmokeTest {
    public static void main(String[] args) {
        int rate = 8_000;
        int frames = rate * 8;
        StemBundle a = bundle(rate, frames, .14f, .10f, .08f, .12f);
        StemBundle b = bundle(rate, frames, .12f, .11f, .09f, .10f);
        float aSum = .14f + .10f + .08f + .12f;
        float bSum = .12f + .11f + .09f + .10f;
        int checked = 0;
        for (LayerPlan.Type type : LayerPlan.Type.values()) {
            LayerPlan plan = new LayerPlan(type, 24, .9f, 1f, "test");
            PcmAudio mixed = LayerTransitionMixer.mix(a, b, plan);
            float maxStep = 0f;
            for (int i = 2; i < mixed.stereo.length; i += 2) {
                if (!Float.isFinite(mixed.stereo[i]) || !Float.isFinite(mixed.stereo[i + 1])) {
                    throw new AssertionError(type + " produced non-finite samples");
                }
                maxStep = Math.max(maxStep, Math.abs(mixed.stereo[i] - mixed.stereo[i - 2]));
                maxStep = Math.max(maxStep, Math.abs(mixed.stereo[i + 1] - mixed.stereo[i - 1]));
            }
            if (maxStep > .012f) throw new AssertionError(type + " gain discontinuity " + maxStep);
            assertClose(type + " must begin as A", mixed.stereo[0], aSum, .002f);
            float last = mixed.stereo[mixed.stereo.length - 2];
            float expected = type.returnsToA ? aSum : bSum;
            assertClose(type + " must finish on its declared anchor", last, expected, .002f);
            checked++;
        }
        System.out.println("Layer continuity/endpoints OK for " + checked + " narratives");
    }

    private static void assertClose(String label, float actual, float expected, float tolerance) {
        if (Math.abs(actual - expected) > tolerance) {
            throw new AssertionError(label + ": " + actual + " expected " + expected);
        }
    }

    private static StemBundle bundle(int rate, int frames,
                                     float leadValue, float drumValue, float bassValue, float backingValue) {
        float[] lead = constant(frames, leadValue);
        float[] drums = constant(frames, drumValue);
        float[] bass = constant(frames, bassValue);
        float[] backing = constant(frames, backingValue);
        return new StemBundle(rate, lead, drums, bass, backing);
    }

    private static float[] constant(int frames, float value) {
        float[] out = new float[frames * 2];
        for (int i = 0; i < out.length; i++) out[i] = value;
        return out;
    }
}
