package com.alexey.autoremix;

/** Compact prepared-scene stems with per-lane scaling and 16-bit sample storage. */
final class QuantizedStemBundle {
    final int sampleRate;
    final int frames;
    final float leadRms;
    final float drumsRms;
    final float bassRms;
    final float backingRms;

    private final short[] lead;
    private final short[] drums;
    private final short[] bass;
    private final short[] backing;
    private final float leadScale;
    private final float drumsScale;
    private final float bassScale;
    private final float backingScale;

    private QuantizedStemBundle(StemBundle source) {
        if (source == null) throw new IllegalArgumentException("source");
        sampleRate = source.sampleRate;
        frames = source.frames();
        leadRms = source.leadRms;
        drumsRms = source.drumsRms;
        bassRms = source.bassRms;
        backingRms = source.backingRms;
        PackedLane packedLead = pack(source.lead);
        PackedLane packedDrums = pack(source.drums);
        PackedLane packedBass = pack(source.bass);
        PackedLane packedBacking = pack(source.backing);
        lead = packedLead.samples;
        drums = packedDrums.samples;
        bass = packedBass.samples;
        backing = packedBacking.samples;
        leadScale = packedLead.scale;
        drumsScale = packedDrums.scale;
        bassScale = packedBass.scale;
        backingScale = packedBacking.scale;
    }

    static QuantizedStemBundle from(StemBundle source) {
        return new QuantizedStemBundle(source);
    }

    float sample(ContinuousSceneTransitionPlan.SemanticRole role,
                 double frame, int channel) {
        switch (role) {
            case LEAD_VOCAL:
            case VOCAL_TEXTURE:
                return interpolated(lead, leadScale, frame, channel);
            case DRUMS:
            case PERCUSSION:
                return interpolated(drums, drumsScale, frame, channel);
            case BASS:
                return interpolated(bass, bassScale, frame, channel);
            default:
                return interpolated(backing, backingScale, frame, channel);
        }
    }

    float fullMixSample(double frame, int channel) {
        return interpolated(lead, leadScale, frame, channel)
                + interpolated(drums, drumsScale, frame, channel)
                + interpolated(bass, bassScale, frame, channel)
                + interpolated(backing, backingScale, frame, channel);
    }

    private static PackedLane pack(float[] source) {
        float peak = 0f;
        for (float sample : source) {
            if (Float.isFinite(sample)) peak = Math.max(peak, Math.abs(sample));
        }
        float scale = Math.max(1e-9f, peak / Short.MAX_VALUE);
        short[] packed = new short[source.length];
        for (int i = 0; i < source.length; i++) {
            int value = Float.isFinite(source[i]) ? Math.round(source[i] / scale) : 0;
            packed[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
        }
        return new PackedLane(packed, scale);
    }

    private static float interpolated(short[] samples, float scale,
                                      double frame, int channel) {
        int availableFrames = samples.length / 2;
        if (availableFrames == 0) return 0f;
        int leftFrame = Math.max(0, Math.min(availableFrames - 1, (int) frame));
        int rightFrame = Math.min(availableFrames - 1, leftFrame + 1);
        float fraction = (float) Math.max(0.0, Math.min(1.0, frame - leftFrame));
        float left = samples[leftFrame * 2 + channel] * scale;
        float right = samples[rightFrame * 2 + channel] * scale;
        return left + (right - left) * fraction;
    }

    private static final class PackedLane {
        final short[] samples;
        final float scale;

        PackedLane(short[] samples, float scale) {
            this.samples = samples;
            this.scale = scale;
        }
    }
}
