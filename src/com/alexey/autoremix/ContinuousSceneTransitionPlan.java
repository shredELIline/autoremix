package com.alexey.autoremix;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable control-plane plan for one continuous master-timeline transition. */
final class ContinuousSceneTransitionPlan {
    static final float AUDIBLE_GAIN = .01f;   // -40 dBFS gain proxy; calibrate with real stems.
    static final float SILENCE_GAIN = .001f;  // -60 dBFS gain proxy; calibrate with real stems.

    enum Source {
        A,
        B,
        GENERATED
    }

    enum SemanticRole {
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
        FULL_MIX;

        boolean isVocal() {
            return this == LEAD_VOCAL || this == BACKING_VOCAL
                    || this == VOCAL_TEXTURE;
        }

        boolean isLeadVocal() {
            return this == LEAD_VOCAL;
        }
    }

    enum VocalOwnershipState {
        NONE,
        TRACK_A,
        TRACK_B
    }

    enum Strategy {
        PRESERVE_VOCAL_A(true),
        PRESERVE_GUITAR(true),
        PRESERVE_GROOVE(true),
        PRESERVE_ATMOSPHERE(true),
        BASS_HANDOFF(true),
        DRUM_BRIDGE(true),
        MELODIC_RELAY(true),
        VOCAL_CHOP_BRIDGE(true),
        GENERATED_INSTRUMENTAL_BRIDGE(true),
        DETERMINISTIC_STEM_BRIDGE(true),
        LEGACY_INTELLIGENT(false),
        PHRASE_AWARE_CROSSFADE(false),
        BASIC_CROSSFADE(false);

        final boolean stemBased;

        Strategy(boolean stemBased) {
            this.stemBased = stemBased;
        }
    }

    enum PlanOrigin {
        AI_ASSISTED,
        DETERMINISTIC,
        LEGACY
    }

    enum FallbackReason {
        STEM_SEPARATION_FAILED,
        LOW_SEPARATOR_CONFIDENCE,
        NO_COMPATIBLE_ANCHOR,
        INSUFFICIENT_BUFFER,
        DEVICE_THERMAL_LIMIT,
        TARGET_DECODE_FAILED,
        QUALITY_SCORE_TOO_LOW,
        VOCAL_CONFLICT_UNRESOLVABLE,
        USER_FORCED_SKIP,
        GENERATION_TIMEOUT
    }

    enum Curve {
        LINEAR,
        SMOOTH_STEP,
        HOLD
    }

    static final class EnvelopePoint {
        final long sample;
        final float value;

        EnvelopePoint(long sample, float value) {
            if (sample < 0L || !Float.isFinite(value)) {
                throw new IllegalArgumentException("invalid envelope point");
            }
            this.sample = sample;
            this.value = value;
        }
    }

    static final class Envelope {
        final Curve curve;
        final List<EnvelopePoint> points;
        final boolean constant;
        final float constantValue;

        Envelope(Curve curve, List<EnvelopePoint> points) {
            if (curve == null || points == null || points.isEmpty()) {
                throw new IllegalArgumentException("empty envelope");
            }
            List<EnvelopePoint> sorted = new ArrayList<>(points);
            sorted.sort(Comparator.comparingLong(point -> point.sample));
            List<EnvelopePoint> copy = new ArrayList<>(sorted.size());
            long previous = -1L;
            for (EnvelopePoint point : sorted) {
                if (point.sample == previous) {
                    EnvelopePoint prior = copy.get(copy.size() - 1);
                    if (prior.value != point.value) {
                        throw new IllegalArgumentException("conflicting envelope point");
                    }
                    continue;
                }
                if (point.sample < previous) {
                    throw new IllegalArgumentException("unsorted envelope");
                }
                copy.add(point);
                previous = point.sample;
            }
            this.curve = curve;
            this.points = Collections.unmodifiableList(copy);
            constantValue = copy.get(0).value;
            boolean same = true;
            for (int index = 1; index < copy.size(); index++) {
                if (copy.get(index).value != constantValue) {
                    same = false;
                    break;
                }
            }
            constant = same;
        }

        static Envelope constant(long startSample, long endSample, float value) {
            return new Envelope(Curve.HOLD, List.of(
                    new EnvelopePoint(startSample, value),
                    new EnvelopePoint(endSample, value)));
        }

        static Envelope smooth(EnvelopePoint... points) {
            return new Envelope(Curve.SMOOTH_STEP, List.of(points));
        }

        float valueAt(long sample) {
            if (constant) return constantValue;
            if (sample <= points.get(0).sample) return points.get(0).value;
            EnvelopePoint last = points.get(points.size() - 1);
            if (sample >= last.sample) return last.value;
            for (int i = 1; i < points.size(); i++) {
                EnvelopePoint right = points.get(i);
                if (sample > right.sample) continue;
                EnvelopePoint left = points.get(i - 1);
                if (curve == Curve.HOLD) return left.value;
                float t = (sample - left.sample) / (float) (right.sample - left.sample);
                if (curve == Curve.SMOOTH_STEP) t = t * t * (3f - 2f * t);
                return left.value + (right.value - left.value) * t;
            }
            return last.value;
        }

        Set<Long> criticalSamples(float... thresholds) {
            Set<Long> samples = new LinkedHashSet<>();
            for (EnvelopePoint point : points) samples.add(point.sample);
            for (int i = 1; i < points.size(); i++) {
                EnvelopePoint left = points.get(i - 1);
                EnvelopePoint right = points.get(i);
                samples.add(left.sample + (right.sample - left.sample) / 2L);
                if (curve == Curve.HOLD || left.value == right.value) continue;
                for (float threshold : thresholds) {
                    if ((threshold < Math.min(left.value, right.value))
                            || (threshold > Math.max(left.value, right.value))) continue;
                    long low = left.sample;
                    long high = right.sample;
                    boolean rising = right.value > left.value;
                    while (low + 1L < high) {
                        long middle = low + (high - low) / 2L;
                        boolean below = valueAt(middle) < threshold;
                        if (below == rising) low = middle;
                        else high = middle;
                    }
                    samples.add(low);
                    samples.add(high);
                }
            }
            return samples;
        }
    }

    static final class AnchorSelection {
        final SemanticRole semanticRole;
        final Source source;
        final long startSample;
        final long endSample;
        final float confidence;

        AnchorSelection(SemanticRole semanticRole, Source source, long startSample,
                        long endSample, float confidence) {
            if (semanticRole == null || source == null || startSample < 0L
                    || endSample <= startSample || !Float.isFinite(confidence)) {
                throw new IllegalArgumentException("invalid anchor");
            }
            this.semanticRole = semanticRole;
            this.source = source;
            this.startSample = startSample;
            this.endSample = endSample;
            this.confidence = clamp(confidence);
        }
    }

    static final class StemTimeline {
        final SemanticRole semanticRole;
        final Source source;
        final long sourceFragmentId;
        final long startSample;
        final long endSample;
        final Envelope gainEnvelope;
        final Envelope pitchEnvelope;
        final Envelope formantEnvelope;
        final Envelope tempoEnvelope;
        final Envelope panEnvelope;
        final Envelope widthEnvelope;
        final Envelope eqEnvelope;
        final Envelope filterEnvelope;
        final Envelope reverbEnvelope;
        final Envelope transientEnvelope;
        final Envelope harmonicMorphEnvelope;
        final Envelope timbreMorphEnvelope;
        final VocalOwnershipState ownershipState;

        StemTimeline(SemanticRole semanticRole, Source source, long sourceFragmentId,
                     long startSample, long endSample, Envelope gainEnvelope,
                     Envelope pitchEnvelope, Envelope formantEnvelope,
                     Envelope tempoEnvelope, Envelope panEnvelope,
                     Envelope widthEnvelope, Envelope eqEnvelope,
                     Envelope filterEnvelope, Envelope reverbEnvelope,
                     Envelope transientEnvelope, Envelope harmonicMorphEnvelope,
                     Envelope timbreMorphEnvelope, VocalOwnershipState ownershipState) {
            if (semanticRole == null || source == null || sourceFragmentId < 0L
                    || startSample < 0L || endSample <= startSample
                    || gainEnvelope == null || pitchEnvelope == null
                    || formantEnvelope == null || tempoEnvelope == null
                    || panEnvelope == null || widthEnvelope == null
                    || eqEnvelope == null || filterEnvelope == null
                    || reverbEnvelope == null || transientEnvelope == null
                    || harmonicMorphEnvelope == null || timbreMorphEnvelope == null
                    || ownershipState == null) {
                throw new IllegalArgumentException("invalid stem timeline");
            }
            if (source == Source.GENERATED && semanticRole.isVocal()) {
                throw new IllegalArgumentException("generated vocal provenance is prohibited");
            }
            if (semanticRole.isLeadVocal()) {
                VocalOwnershipState expected = source == Source.A
                        ? VocalOwnershipState.TRACK_A
                        : source == Source.B ? VocalOwnershipState.TRACK_B
                        : VocalOwnershipState.NONE;
                if (ownershipState != expected) {
                    throw new IllegalArgumentException("lead vocal owner does not match source");
                }
            }
            this.semanticRole = semanticRole;
            this.source = source;
            this.sourceFragmentId = sourceFragmentId;
            this.startSample = startSample;
            this.endSample = endSample;
            this.gainEnvelope = gainEnvelope;
            this.pitchEnvelope = pitchEnvelope;
            this.formantEnvelope = formantEnvelope;
            this.tempoEnvelope = tempoEnvelope;
            this.panEnvelope = panEnvelope;
            this.widthEnvelope = widthEnvelope;
            this.eqEnvelope = eqEnvelope;
            this.filterEnvelope = filterEnvelope;
            this.reverbEnvelope = reverbEnvelope;
            this.transientEnvelope = transientEnvelope;
            this.harmonicMorphEnvelope = harmonicMorphEnvelope;
            this.timbreMorphEnvelope = timbreMorphEnvelope;
            this.ownershipState = ownershipState;
        }

        float gainAt(long sample) {
            return sample < startSample || sample >= endSample ? 0f
                    : Math.max(0f, gainEnvelope.valueAt(sample));
        }
    }

    final long transitionId;
    final long sourceTrackId;
    final long targetTrackId;
    final long sourceStartSample;
    final long targetLandingSample;
    final long activationSample;
    final long landingSample;
    final List<AnchorSelection> selectedAnchorSet;
    final float qualityScore;
    final float confidence;
    final FallbackReason fallbackReason;
    final List<StemTimeline> stemTimelines;
    final Strategy selectedStrategy;
    final PlanOrigin origin;
    final boolean stemSeparatorUsed;
    final long guaranteedRenderedSamples;
    final int activationUnderruns;
    final float clickScore;
    final float vocalConflictScore;

    ContinuousSceneTransitionPlan(
            long transitionId,
            long sourceTrackId,
            long targetTrackId,
            long sourceStartSample,
            long targetLandingSample,
            long activationSample,
            long landingSample,
            List<AnchorSelection> selectedAnchorSet,
            float qualityScore,
            float confidence,
            FallbackReason fallbackReason,
            List<StemTimeline> stemTimelines,
            Strategy selectedStrategy,
            PlanOrigin origin,
            boolean stemSeparatorUsed,
            long guaranteedRenderedSamples,
            int activationUnderruns,
            float clickScore) {
        if (transitionId < 0L || sourceTrackId < 0L || targetTrackId < 0L
                || sourceStartSample < 0L || targetLandingSample < 0L
                || activationSample < 0L || landingSample <= activationSample
                || selectedAnchorSet == null || stemTimelines == null
                || selectedStrategy == null || origin == null
                || !Float.isFinite(qualityScore) || !Float.isFinite(confidence)
                || guaranteedRenderedSamples < 0L || activationUnderruns < 0
                || !Float.isFinite(clickScore)) {
            throw new IllegalArgumentException("invalid continuous scene plan");
        }
        List<AnchorSelection> anchors = new ArrayList<>(selectedAnchorSet);
        List<StemTimeline> timelines = new ArrayList<>(stemTimelines);
        for (StemTimeline timeline : timelines) {
            if (timeline.startSample < activationSample || timeline.endSample > landingSample) {
                throw new IllegalArgumentException("stem timeline exceeds plan range");
            }
        }
        if (selectedStrategy.stemBased) validateAnchorCoverage(anchors, activationSample, landingSample);
        float conflict = vocalConflictScore(timelines, activationSample, landingSample);
        if (conflict > 0f) throw new IllegalArgumentException("double lead vocal");
        this.transitionId = transitionId;
        this.sourceTrackId = sourceTrackId;
        this.targetTrackId = targetTrackId;
        this.sourceStartSample = sourceStartSample;
        this.targetLandingSample = targetLandingSample;
        this.activationSample = activationSample;
        this.landingSample = landingSample;
        this.selectedAnchorSet = Collections.unmodifiableList(anchors);
        this.qualityScore = clamp(qualityScore);
        this.confidence = clamp(confidence);
        this.fallbackReason = fallbackReason;
        this.stemTimelines = Collections.unmodifiableList(timelines);
        this.selectedStrategy = selectedStrategy;
        this.origin = origin;
        this.stemSeparatorUsed = stemSeparatorUsed;
        this.guaranteedRenderedSamples = guaranteedRenderedSamples;
        this.activationUnderruns = activationUnderruns;
        this.clickScore = clamp(clickScore);
        this.vocalConflictScore = conflict;
    }

    boolean aiUsed() {
        return origin == PlanOrigin.AI_ASSISTED;
    }

    boolean isStemBased() {
        return selectedStrategy.stemBased;
    }

    String vocalOwnerTimeline(int cells) {
        int width = Math.max(4, cells);
        StringBuilder result = new StringBuilder(width);
        for (int cell = 0; cell < width; cell++) {
            long sample = cellSample(cell, width);
            float a = maximumOwnedVocalGain(stemTimelines, Source.A, sample);
            float b = maximumOwnedVocalGain(stemTimelines, Source.B, sample);
            result.append(a > AUDIBLE_GAIN ? 'A' : b > AUDIBLE_GAIN ? 'B' : '-');
        }
        return result.toString();
    }

    String stemTimelineText(int cells) {
        int width = Math.max(4, cells);
        Map<SemanticRole, List<StemTimeline>> byRole = new EnumMap<>(SemanticRole.class);
        for (StemTimeline timeline : stemTimelines) {
            byRole.computeIfAbsent(timeline.semanticRole, ignored -> new ArrayList<>())
                    .add(timeline);
        }
        StringBuilder result = new StringBuilder();
        for (Map.Entry<SemanticRole, List<StemTimeline>> entry : byRole.entrySet()) {
            if (result.length() > 0) result.append('\n');
            String label = entry.getKey().name();
            result.append(label);
            for (int pad = label.length(); pad < 16; pad++) result.append(' ');
            for (int cell = 0; cell < width; cell++) {
                long sample = cellSample(cell, width);
                Source owner = null;
                float best = AUDIBLE_GAIN;
                for (StemTimeline timeline : entry.getValue()) {
                    float gain = timeline.gainAt(sample);
                    if (gain > best) {
                        best = gain;
                        owner = timeline.source;
                    }
                }
                result.append(owner == Source.A ? 'A'
                        : owner == Source.B ? 'B'
                        : owner == Source.GENERATED ? 'G' : '-');
            }
        }
        return result.toString();
    }

    private long cellSample(int cell, int cells) {
        long duration = landingSample - activationSample;
        return activationSample + Math.min(duration - 1L,
                Math.max(0L, (2L * cell + 1L) * duration / (2L * cells)));
    }

    static float vocalConflictScore(List<StemTimeline> timelines,
                                    long activationSample, long landingSample) {
        Set<Long> samples = new LinkedHashSet<>();
        samples.add(activationSample);
        samples.add(landingSample - 1L);
        for (StemTimeline timeline : timelines) {
            if (!isOwnedVocal(timeline.semanticRole)) continue;
            samples.addAll(timeline.gainEnvelope.criticalSamples(AUDIBLE_GAIN, SILENCE_GAIN));
        }
        Set<Long> expanded = new LinkedHashSet<>();
        for (long sample : samples) {
            for (long candidate : new long[]{sample - 1L, sample, sample + 1L}) {
                if (candidate >= activationSample && candidate < landingSample) expanded.add(candidate);
            }
        }
        float maximum = 0f;
        for (long sample : expanded) {
            float a = maximumOwnedVocalGain(timelines, Source.A, sample);
            float b = maximumOwnedVocalGain(timelines, Source.B, sample);
            if (a > AUDIBLE_GAIN && b > SILENCE_GAIN) maximum = Math.max(maximum, b);
            if (b > AUDIBLE_GAIN && a > SILENCE_GAIN) maximum = Math.max(maximum, a);
        }
        return maximum;
    }

    private static float maximumOwnedVocalGain(
            List<StemTimeline> timelines, Source source, long sample) {
        float maximum = 0f;
        for (StemTimeline timeline : timelines) {
            if (isOwnedVocal(timeline.semanticRole) && timeline.source == source) {
                maximum = Math.max(maximum, timeline.gainAt(sample));
            }
        }
        return maximum;
    }

    private static boolean isOwnedVocal(SemanticRole role) {
        return role == SemanticRole.LEAD_VOCAL || role == SemanticRole.BACKING_VOCAL;
    }

    private static void validateAnchorCoverage(List<AnchorSelection> anchors,
                                               long activationSample, long landingSample) {
        if (anchors.isEmpty()) throw new IllegalArgumentException("stem plan has no anchor");
        anchors.sort(Comparator.comparingLong(anchor -> anchor.startSample));
        long coveredUntil = activationSample;
        for (AnchorSelection anchor : anchors) {
            if (anchor.startSample > coveredUntil || anchor.endSample <= coveredUntil) continue;
            coveredUntil = Math.max(coveredUntil, anchor.endSample);
            if (coveredUntil >= landingSample) return;
        }
        throw new IllegalArgumentException("anchor set does not cover transition");
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
