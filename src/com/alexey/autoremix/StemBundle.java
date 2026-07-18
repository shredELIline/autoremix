package com.alexey.autoremix;

/** Four complementary pseudo-stems whose sum reconstructs the source mix. */
final class StemBundle {
    final int sampleRate;
    final float[] lead;
    final float[] drums;
    final float[] bass;
    final float[] backing;
    final float leadRms;
    final float drumsRms;
    final float bassRms;
    final float backingRms;

    StemBundle(int sampleRate, float[] lead, float[] drums, float[] bass, float[] backing) {
        this.sampleRate = sampleRate;
        this.lead = lead;
        this.drums = drums;
        this.bass = bass;
        this.backing = backing;
        leadRms = rms(lead);
        drumsRms = rms(drums);
        bassRms = rms(bass);
        backingRms = rms(backing);
    }

    int frames() {
        return lead.length / 2;
    }

    private static float rms(float[] audio) {
        if (audio == null || audio.length == 0) return 0f;
        double sum = 0.0;
        for (float sample : audio) sum += sample * sample;
        return (float) Math.sqrt(sum / audio.length);
    }
}
