package com.alexey.autoremix;

/** One real, sample-addressed audio transformation exposed to presentation code. */
public final class AudioTransformationEvent {
    public enum StemType {
        LEAD_VOCAL,
        BACKING_VOCAL,
        VOCAL_TEXTURE,
        GUITAR,
        DRUMS,
        PERCUSSION,
        BASS,
        HARMONY,
        MELODY,
        ATMOSPHERE,
        EFFECTS,
        FULL_MIX,
        UNKNOWN
    }

    public enum TransformationType {
        STEM_FADE,
        STEM_HANDOFF,
        PITCH_SHIFT,
        FORMANT_SHIFT,
        TIME_STRETCH,
        TEMPO_MORPH,
        KEY_CHANGE,
        CHORD_MORPH,
        FILTER_SWEEP,
        EQ_MORPH,
        GRANULAR_LOOP,
        VOCAL_CHOP,
        REVERSE_FRAGMENT,
        DRUM_REARRANGEMENT,
        BASS_HANDOFF,
        GUITAR_MORPH,
        ATMOSPHERE_GENERATION,
        HARMONIC_BRIDGE,
        REVERB_TAIL,
        DELAY_TAIL,
        GENERATED_FILL,
        ANCHOR_PRESERVED,
        ANCHOR_RELEASED,
        LANDING
    }

    public final long eventId;
    public final long transitionId;
    public final StemType stemType;
    public final TransformationType transformationType;
    public final long sourceTrack;
    public final long targetTrack;
    public final long startSample;
    public final long endSample;
    public final float progress;
    public final float intensity;
    public final float fromValue;
    public final float toValue;
    public final String humanReadableLabel;

    public AudioTransformationEvent(
            long eventId,
            long transitionId,
            StemType stemType,
            TransformationType transformationType,
            long sourceTrack,
            long targetTrack,
            long startSample,
            long endSample,
            float progress,
            float intensity,
            float fromValue,
            float toValue,
            String humanReadableLabel) {
        if (startSample < 0L || endSample <= startSample) {
            throw new IllegalArgumentException("invalid transformation range");
        }
        this.eventId = eventId;
        this.transitionId = transitionId;
        this.stemType = stemType == null ? StemType.UNKNOWN : stemType;
        this.transformationType = transformationType == null
                ? TransformationType.STEM_HANDOFF : transformationType;
        this.sourceTrack = sourceTrack;
        this.targetTrack = targetTrack;
        this.startSample = startSample;
        this.endSample = endSample;
        this.progress = clamp01(progress);
        this.intensity = clamp01(intensity);
        this.fromValue = finite(fromValue);
        this.toValue = finite(toValue);
        this.humanReadableLabel = humanReadableLabel == null ? "" : humanReadableLabel;
    }

    AudioTransformationEvent atSample(long sample) {
        float value = (sample - startSample) / (float) Math.max(1L, endSample - startSample);
        return new AudioTransformationEvent(eventId, transitionId, stemType,
                transformationType, sourceTrack, targetTrack, startSample, endSample,
                value, intensity, fromValue, toValue, humanReadableLabel);
    }

    private static float finite(float value) {
        return Float.isFinite(value) ? value : 0f;
    }

    private static float clamp01(float value) {
        return Float.isFinite(value) ? Math.max(0f, Math.min(1f, value)) : 0f;
    }
}
