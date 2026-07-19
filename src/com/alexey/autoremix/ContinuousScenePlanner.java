package com.alexey.autoremix;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Deterministic structured planner. It creates automation only; it never starts audio. */
final class ContinuousScenePlanner {
    static final float MIN_SEPARATOR_CONFIDENCE = .45f;
    static final float MAX_ACTIVATION_UNDERRUN_RISK = .05f;
    static final float MAX_SAMPLE_DISCONTINUITY = .20f;
    static final float MIN_QUALITY_SCORE = .55f;
    private static final int TRANSITION_BARS = 16;

    enum VetoReason {
        STEM_SEPARATOR_UNAVAILABLE,
        LOW_SEPARATOR_CONFIDENCE,
        MISSING_REQUIRED_STEMS,
        GENERATED_AUDIO_UNAVAILABLE,
        VOCAL_CHOP_UNSAFE,
        DOUBLE_LEAD_VOCAL,
        BUFFER_NOT_READY,
        ACTIVATION_UNDERRUN_RISK,
        SAMPLE_DISCONTINUITY,
        NO_CONTINUOUS_ANCHOR,
        TARGET_DECODE_FAILED,
        DEVICE_THERMAL_LIMIT,
        QUALITY_SCORE_TOO_LOW
    }

    static final class StemDescriptor {
        final ContinuousSceneTransitionPlan.SemanticRole semanticRole;
        final long sourceAFragmentId;
        final long sourceBFragmentId;
        final long generatedFragmentId;
        final float similarity;
        final float confidence;

        StemDescriptor(ContinuousSceneTransitionPlan.SemanticRole semanticRole,
                       long sourceAFragmentId, long sourceBFragmentId,
                       long generatedFragmentId, float similarity, float confidence) {
            if (semanticRole == null || sourceAFragmentId < -1L || sourceBFragmentId < -1L
                    || generatedFragmentId < -1L || !Float.isFinite(similarity)
                    || !Float.isFinite(confidence)) {
                throw new IllegalArgumentException("invalid stem descriptor");
            }
            if (semanticRole.isVocal() && generatedFragmentId >= 0L) {
                throw new IllegalArgumentException("generated vocal provenance is prohibited");
            }
            this.semanticRole = semanticRole;
            this.sourceAFragmentId = sourceAFragmentId;
            this.sourceBFragmentId = sourceBFragmentId;
            this.generatedFragmentId = generatedFragmentId;
            this.similarity = clamp(similarity);
            this.confidence = clamp(confidence);
        }

        boolean hasA() {
            return sourceAFragmentId >= 0L;
        }

        boolean hasB() {
            return sourceBFragmentId >= 0L;
        }

        boolean hasPair() {
            return hasA() && hasB();
        }

        boolean hasGenerated() {
            return generatedFragmentId >= 0L;
        }
    }

    static final class PlanningRequest {
        final long transitionId;
        final long sourceTrackId;
        final long targetTrackId;
        final long sourceStartSample;
        final long targetLandingSample;
        final long activationSample;
        final long samplesPerBar;
        final long transitionSamples;
        final Map<ContinuousSceneTransitionPlan.SemanticRole, StemDescriptor> stems;
        final boolean targetDecoded;
        final boolean stemSeparatorUsed;
        final float separatorConfidence;
        final boolean bufferReady;
        final long guaranteedRenderedSamples;
        final int activationUnderruns;
        final float activationUnderrunRisk;
        final float sampleDiscontinuity;
        final boolean thermalConstrained;
        final boolean vocalChopSafe;
        final boolean sourceLeadVocalActive;
        final boolean targetLeadVocalActive;
        final boolean legacyVocalSafe;
        final boolean legacyIntelligentAvailable;
        final boolean phraseAwareCrossfadeAvailable;
        final boolean basicCrossfadeAvailable;
        final boolean userForcedSkip;
        final float rhythmCompatibility;
        final float harmonyCompatibility;
        final float timbreCompatibility;
        final float maskingCompatibility;
        final float energyCompatibility;
        final float landingQuality;
        final float generatedArtifactRisk;
        final float tempoRatio;
        final float pitchShiftSemitones;
        final float formantShiftSemitones;

        private PlanningRequest(Builder builder) {
            if (builder.transitionId < 0L || builder.sourceTrackId < 0L
                    || builder.targetTrackId < 0L || builder.sourceStartSample < 0L
                    || builder.targetLandingSample < 0L || builder.activationSample < 0L
                    || builder.samplesPerBar <= 0L || builder.transitionSamples <= 0L
                    || builder.activationUnderruns < 0
                    || !Float.isFinite(builder.separatorConfidence)
                    || !Float.isFinite(builder.activationUnderrunRisk)
                    || !Float.isFinite(builder.sampleDiscontinuity)
                    || !Float.isFinite(builder.tempoRatio) || builder.tempoRatio <= 0f
                    || !Float.isFinite(builder.pitchShiftSemitones)
                    || !Float.isFinite(builder.formantShiftSemitones)
                    || !Float.isFinite(builder.rhythmCompatibility)
                    || !Float.isFinite(builder.harmonyCompatibility)
                    || !Float.isFinite(builder.timbreCompatibility)
                    || !Float.isFinite(builder.maskingCompatibility)
                    || !Float.isFinite(builder.energyCompatibility)
                    || !Float.isFinite(builder.landingQuality)
                    || !Float.isFinite(builder.generatedArtifactRisk)) {
                throw new IllegalArgumentException("invalid planning request");
            }
            transitionId = builder.transitionId;
            sourceTrackId = builder.sourceTrackId;
            targetTrackId = builder.targetTrackId;
            sourceStartSample = builder.sourceStartSample;
            targetLandingSample = builder.targetLandingSample;
            activationSample = builder.activationSample;
            samplesPerBar = builder.samplesPerBar;
            transitionSamples = builder.transitionSamples;
            stems = Collections.unmodifiableMap(new EnumMap<>(builder.stems));
            targetDecoded = builder.targetDecoded;
            stemSeparatorUsed = builder.stemSeparatorUsed;
            separatorConfidence = clamp(builder.separatorConfidence);
            bufferReady = builder.bufferReady;
            guaranteedRenderedSamples = Math.max(0L, builder.guaranteedRenderedSamples);
            activationUnderruns = builder.activationUnderruns;
            activationUnderrunRisk = clamp(builder.activationUnderrunRisk);
            sampleDiscontinuity = clamp(builder.sampleDiscontinuity);
            thermalConstrained = builder.thermalConstrained;
            vocalChopSafe = builder.vocalChopSafe;
            sourceLeadVocalActive = builder.sourceLeadVocalActive;
            targetLeadVocalActive = builder.targetLeadVocalActive;
            legacyVocalSafe = builder.legacyVocalSafe;
            legacyIntelligentAvailable = builder.legacyIntelligentAvailable;
            phraseAwareCrossfadeAvailable = builder.phraseAwareCrossfadeAvailable;
            basicCrossfadeAvailable = builder.basicCrossfadeAvailable;
            userForcedSkip = builder.userForcedSkip;
            rhythmCompatibility = clamp(builder.rhythmCompatibility);
            harmonyCompatibility = clamp(builder.harmonyCompatibility);
            timbreCompatibility = clamp(builder.timbreCompatibility);
            maskingCompatibility = clamp(builder.maskingCompatibility);
            energyCompatibility = clamp(builder.energyCompatibility);
            landingQuality = clamp(builder.landingQuality);
            generatedArtifactRisk = clamp(builder.generatedArtifactRisk);
            tempoRatio = builder.tempoRatio;
            pitchShiftSemitones = builder.pitchShiftSemitones;
            formantShiftSemitones = builder.formantShiftSemitones;
        }

        long landingSample() {
            return activationSample + transitionSamples;
        }

        static Builder builder(long transitionId, long sourceTrackId, long targetTrackId,
                               long activationSample, long samplesPerBar) {
            return new Builder(transitionId, sourceTrackId, targetTrackId,
                    activationSample, samplesPerBar);
        }

        static final class Builder {
            private final long transitionId;
            private final long sourceTrackId;
            private final long targetTrackId;
            private final long activationSample;
            private final long samplesPerBar;
            private long transitionSamples;
            private final Map<ContinuousSceneTransitionPlan.SemanticRole, StemDescriptor> stems =
                    new EnumMap<>(ContinuousSceneTransitionPlan.SemanticRole.class);
            private long sourceStartSample;
            private long targetLandingSample;
            private boolean targetDecoded = true;
            private boolean stemSeparatorUsed = true;
            private float separatorConfidence = .9f;
            private boolean bufferReady = true;
            private long guaranteedRenderedSamples;
            private int activationUnderruns;
            private float activationUnderrunRisk;
            private float sampleDiscontinuity;
            private boolean thermalConstrained;
            private boolean vocalChopSafe;
            private boolean sourceLeadVocalActive;
            private boolean targetLeadVocalActive;
            private boolean legacyVocalSafe;
            private boolean legacyIntelligentAvailable = true;
            private boolean phraseAwareCrossfadeAvailable = true;
            private boolean basicCrossfadeAvailable = true;
            private boolean userForcedSkip;
            private float rhythmCompatibility = .85f;
            private float harmonyCompatibility = .82f;
            private float timbreCompatibility = .82f;
            private float maskingCompatibility = .84f;
            private float energyCompatibility = .84f;
            private float landingQuality = .88f;
            private float generatedArtifactRisk = .12f;
            private float tempoRatio = 1f;
            private float pitchShiftSemitones;
            private float formantShiftSemitones;

            private Builder(long transitionId, long sourceTrackId, long targetTrackId,
                            long activationSample, long samplesPerBar) {
                this.transitionId = transitionId;
                this.sourceTrackId = sourceTrackId;
                this.targetTrackId = targetTrackId;
                this.activationSample = activationSample;
                this.samplesPerBar = samplesPerBar;
                transitionSamples = samplesPerBar * TRANSITION_BARS;
                sourceStartSample = activationSample;
                guaranteedRenderedSamples = samplesPerBar * 8L;
            }

            Builder sourceStartSample(long value) {
                sourceStartSample = value;
                return this;
            }

            Builder targetLandingSample(long value) {
                targetLandingSample = value;
                return this;
            }

            Builder transitionSamples(long value) {
                transitionSamples = value;
                return this;
            }

            Builder stem(ContinuousSceneTransitionPlan.SemanticRole role,
                         long sourceAFragmentId, long sourceBFragmentId,
                         float similarity, float confidence) {
                stems.put(role, new StemDescriptor(role, sourceAFragmentId, sourceBFragmentId,
                        -1L, similarity, confidence));
                return this;
            }

            Builder generatedStem(ContinuousSceneTransitionPlan.SemanticRole role,
                                  long sourceAFragmentId, long sourceBFragmentId,
                                  long generatedFragmentId, float similarity, float confidence) {
                stems.put(role, new StemDescriptor(role, sourceAFragmentId, sourceBFragmentId,
                        generatedFragmentId, similarity, confidence));
                return this;
            }

            Builder targetDecoded(boolean value) {
                targetDecoded = value;
                return this;
            }

            Builder separator(boolean used, float confidence) {
                stemSeparatorUsed = used;
                separatorConfidence = confidence;
                return this;
            }

            Builder buffer(boolean ready, long guaranteedSamples, int underruns,
                           float underrunRisk) {
                bufferReady = ready;
                guaranteedRenderedSamples = guaranteedSamples;
                activationUnderruns = underruns;
                activationUnderrunRisk = underrunRisk;
                return this;
            }

            Builder sampleDiscontinuity(float value) {
                sampleDiscontinuity = value;
                return this;
            }

            Builder thermalConstrained(boolean value) {
                thermalConstrained = value;
                return this;
            }

            Builder vocalChopSafe(boolean value) {
                vocalChopSafe = value;
                return this;
            }

            Builder vocalActivity(boolean sourceActive, boolean targetActive) {
                sourceLeadVocalActive = sourceActive;
                targetLeadVocalActive = targetActive;
                return this;
            }

            Builder legacyVocalSafe(boolean value) {
                legacyVocalSafe = value;
                return this;
            }

            Builder fallbacks(boolean legacy, boolean phraseAware, boolean basic) {
                legacyIntelligentAvailable = legacy;
                phraseAwareCrossfadeAvailable = phraseAware;
                basicCrossfadeAvailable = basic;
                return this;
            }

            Builder userForcedSkip(boolean value) {
                userForcedSkip = value;
                return this;
            }

            Builder compatibility(float rhythm, float harmony, float timbre,
                                  float masking, float energy, float landing) {
                rhythmCompatibility = rhythm;
                harmonyCompatibility = harmony;
                timbreCompatibility = timbre;
                maskingCompatibility = masking;
                energyCompatibility = energy;
                landingQuality = landing;
                return this;
            }

            Builder generatedArtifactRisk(float value) {
                generatedArtifactRisk = value;
                return this;
            }

            Builder transforms(float tempoRatio, float pitchSemitones,
                               float formantSemitones) {
                this.tempoRatio = tempoRatio;
                pitchShiftSemitones = pitchSemitones;
                formantShiftSemitones = formantSemitones;
                return this;
            }

            PlanningRequest build() {
                return new PlanningRequest(this);
            }
        }
    }

    static final class CandidateScore {
        final float continuity;
        final float anchorPersistence;
        final float vocalExclusivity;
        final float rhythm;
        final float harmony;
        final float timbre;
        final float masking;
        final float energy;
        final float mlArtifacts;
        final float clickProbability;
        final float bufferReadiness;
        final float landingQuality;
        final float total;

        CandidateScore(float continuity, float anchorPersistence, float vocalExclusivity,
                       float rhythm, float harmony, float timbre, float masking,
                       float energy, float mlArtifacts, float clickProbability,
                       float bufferReadiness, float landingQuality) {
            this.continuity = clamp(continuity);
            this.anchorPersistence = clamp(anchorPersistence);
            this.vocalExclusivity = clamp(vocalExclusivity);
            this.rhythm = clamp(rhythm);
            this.harmony = clamp(harmony);
            this.timbre = clamp(timbre);
            this.masking = clamp(masking);
            this.energy = clamp(energy);
            this.mlArtifacts = clamp(mlArtifacts);
            this.clickProbability = clamp(clickProbability);
            this.bufferReadiness = clamp(bufferReadiness);
            this.landingQuality = clamp(landingQuality);
            total = clamp(this.continuity * .16f + this.anchorPersistence * .13f
                    + this.vocalExclusivity * .12f + this.rhythm * .10f
                    + this.harmony * .09f + this.timbre * .08f
                    + this.masking * .07f + this.energy * .07f
                    + (1f - this.mlArtifacts) * .05f
                    + (1f - this.clickProbability) * .05f
                    + this.bufferReadiness * .04f + this.landingQuality * .04f);
        }
    }

    static final class CandidateDiagnostics {
        final ContinuousSceneTransitionPlan.Strategy strategy;
        final CandidateScore score;
        final List<VetoReason> vetoes;
        final boolean selected;
        final ContinuousSceneTransitionPlan plan;

        CandidateDiagnostics(Candidate candidate, boolean selected) {
            strategy = candidate.strategy;
            score = candidate.score;
            vetoes = Collections.unmodifiableList(new ArrayList<>(candidate.vetoes));
            this.selected = selected;
            plan = candidate.plan;
        }

        boolean accepted() {
            return vetoes.isEmpty();
        }
    }

    static final class PlanningResult {
        final ContinuousSceneTransitionPlan plan;
        final ContinuousSceneTransitionPlan.FallbackReason fallbackReason;
        final List<CandidateDiagnostics> candidates;
        private final PlanningRequest request;

        PlanningResult(PlanningRequest request, ContinuousSceneTransitionPlan plan,
                       ContinuousSceneTransitionPlan.FallbackReason fallbackReason,
                       List<CandidateDiagnostics> candidates) {
            this.request = request;
            this.plan = plan;
            this.fallbackReason = fallbackReason;
            this.candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
        }

        boolean hasPlan() {
            return plan != null;
        }

        String stemTimelineText() {
            return plan == null ? "NO_PLAN" : plan.stemTimelineText(18);
        }

        String diagnosticJson() {
            StringBuilder json = new StringBuilder(1024);
            json.append('{');
            number(json, "transitionId", request.transitionId).append(',');
            number(json, "sourceA", request.sourceTrackId).append(',');
            number(json, "targetB", request.targetTrackId).append(',');
            text(json, "selectedStrategy", plan == null ? "NONE"
                    : plan.selectedStrategy.name()).append(',');
            bool(json, "aiUsed", plan != null && plan.aiUsed()).append(',');
            bool(json, "stemSeparatorUsed", plan != null && plan.stemSeparatorUsed).append(',');
            text(json, "anchors", plan == null ? "" : anchorSummary(plan)).append(',');
            decimal(json, "anchorConfidence", plan == null ? 0f : plan.confidence).append(',');
            text(json, "vocalOwnerTimeline", plan == null ? ""
                    : plan.vocalOwnerTimeline(18)).append(',');
            text(json, "stemOwnershipTimeline", plan == null ? ""
                    : plan.stemTimelineText(18)).append(',');
            json.append("\"qualityCandidates\":[");
            for (int i = 0; i < candidates.size(); i++) {
                if (i > 0) json.append(',');
                CandidateDiagnostics candidate = candidates.get(i);
                json.append('{');
                text(json, "strategy", candidate.strategy.name()).append(',');
                decimal(json, "score", candidate.score.total).append(',');
                bool(json, "selected", candidate.selected).append(',');
                json.append("\"vetoes\":[");
                for (int veto = 0; veto < candidate.vetoes.size(); veto++) {
                    if (veto > 0) json.append(',');
                    quoted(json, candidate.vetoes.get(veto).name());
                }
                json.append("]}");
            }
            json.append("],");
            decimal(json, "winnerScore", plan == null ? 0f : plan.qualityScore).append(',');
            json.append("\"rejectedCandidates\":[");
            boolean first = true;
            for (CandidateDiagnostics candidate : candidates) {
                if (candidate.selected) continue;
                if (!first) json.append(',');
                json.append('{');
                text(json, "strategy", candidate.strategy.name()).append(',');
                json.append("\"reasons\":[");
                if (candidate.vetoes.isEmpty()) {
                    quoted(json, "LOWER_SCORE");
                } else {
                    for (int veto = 0; veto < candidate.vetoes.size(); veto++) {
                        if (veto > 0) json.append(',');
                        quoted(json, candidate.vetoes.get(veto).name());
                    }
                }
                json.append("]}");
                first = false;
            }
            json.append("],");
            text(json, "fallbackReason", fallbackReason == null ? ""
                    : fallbackReason.name()).append(',');
            number(json, "activationSample", request.activationSample).append(',');
            number(json, "guaranteedBuffer", request.guaranteedRenderedSamples).append(',');
            number(json, "underruns", request.activationUnderruns).append(',');
            decimal(json, "clickScore", plan == null ? 0f : plan.clickScore).append(',');
            decimal(json, "vocalConflictScore", plan == null ? 0f
                    : plan.vocalConflictScore);
            return json.append('}').toString();
        }

        private static String anchorSummary(ContinuousSceneTransitionPlan plan) {
            StringBuilder result = new StringBuilder();
            for (ContinuousSceneTransitionPlan.AnchorSelection anchor : plan.selectedAnchorSet) {
                if (result.length() > 0) result.append(',');
                result.append(anchor.semanticRole.name()).append(':').append(anchor.source.name());
            }
            return result.toString();
        }

        private static StringBuilder number(StringBuilder out, String key, long value) {
            quoted(out, key).append(':').append(value);
            return out;
        }

        private static StringBuilder decimal(StringBuilder out, String key, float value) {
            quoted(out, key).append(':').append(String.format(Locale.US, "%.6f", value));
            return out;
        }

        private static StringBuilder bool(StringBuilder out, String key, boolean value) {
            quoted(out, key).append(':').append(value);
            return out;
        }

        private static StringBuilder text(StringBuilder out, String key, String value) {
            quoted(out, key).append(':');
            quoted(out, value);
            return out;
        }

        private static StringBuilder quoted(StringBuilder out, String value) {
            out.append('"');
            for (int i = 0; i < value.length(); i++) {
                char character = value.charAt(i);
                switch (character) {
                    case '"': out.append("\\\""); break;
                    case '\\': out.append("\\\\"); break;
                    case '\n': out.append("\\n"); break;
                    case '\r': out.append("\\r"); break;
                    case '\t': out.append("\\t"); break;
                    default:
                        if (character < 0x20) {
                            out.append(String.format(Locale.US, "\\u%04x", (int) character));
                        } else {
                            out.append(character);
                        }
                }
            }
            return out.append('"');
        }
    }

    PlanningResult plan(PlanningRequest request) {
        if (request == null) throw new IllegalArgumentException("request");
        List<Candidate> candidates = new ArrayList<>();
        for (ContinuousSceneTransitionPlan.Strategy strategy
                : ContinuousSceneTransitionPlan.Strategy.values()) {
            if (!strategy.stemBased) continue;
            candidates.add(buildStemCandidate(request, strategy));
        }

        Candidate winner = candidates.stream()
                .filter(candidate -> candidate.vetoes.isEmpty())
                .max(Comparator.comparingDouble((Candidate candidate) -> candidate.score.total)
                        .thenComparingInt(candidate -> -candidate.strategy.ordinal()))
                .orElse(null);
        ContinuousSceneTransitionPlan.FallbackReason fallbackReason = null;
        if (winner == null) {
            fallbackReason = fallbackReason(request, candidates);
            for (ContinuousSceneTransitionPlan.Strategy strategy : new ContinuousSceneTransitionPlan.Strategy[]{
                    ContinuousSceneTransitionPlan.Strategy.LEGACY_INTELLIGENT,
                    ContinuousSceneTransitionPlan.Strategy.PHRASE_AWARE_CROSSFADE,
                    ContinuousSceneTransitionPlan.Strategy.BASIC_CROSSFADE}) {
                Candidate fallback = buildFallbackCandidate(request, strategy, fallbackReason);
                candidates.add(fallback);
                if (fallback.vetoes.isEmpty()) {
                    winner = fallback;
                    break;
                }
            }
        }

        List<CandidateDiagnostics> diagnostics = new ArrayList<>();
        for (Candidate candidate : candidates) {
            diagnostics.add(new CandidateDiagnostics(candidate, candidate == winner));
        }
        return new PlanningResult(request, winner == null ? null : winner.plan,
                fallbackReason, diagnostics);
    }

    private Candidate buildStemCandidate(PlanningRequest request,
                                         ContinuousSceneTransitionPlan.Strategy strategy) {
        List<VetoReason> vetoes = new ArrayList<>();
        if (!request.stemSeparatorUsed) vetoes.add(VetoReason.STEM_SEPARATOR_UNAVAILABLE);
        if (request.separatorConfidence < MIN_SEPARATOR_CONFIDENCE) {
            vetoes.add(VetoReason.LOW_SEPARATOR_CONFIDENCE);
        }
        if (!request.targetDecoded) vetoes.add(VetoReason.TARGET_DECODE_FAILED);
        addReadinessVetoes(request, vetoes);

        ContinuousSceneTransitionPlan.SemanticRole preferredRole = preferredRole(strategy);
        if (!requirementsMet(request, strategy)) vetoes.add(VetoReason.MISSING_REQUIRED_STEMS);
        if (strategy == ContinuousSceneTransitionPlan.Strategy.GENERATED_INSTRUMENTAL_BRIDGE) {
            if (!hasGeneratedInstrument(request)) vetoes.add(VetoReason.GENERATED_AUDIO_UNAVAILABLE);
            if (request.thermalConstrained) vetoes.add(VetoReason.DEVICE_THERMAL_LIMIT);
        }
        if (strategy == ContinuousSceneTransitionPlan.Strategy.VOCAL_CHOP_BRIDGE
                && !request.vocalChopSafe) {
            vetoes.add(VetoReason.VOCAL_CHOP_UNSAFE);
        }

        StemDescriptor anchor = selectAnchor(request, preferredRole);
        if (anchor == null) vetoes.add(VetoReason.NO_CONTINUOUS_ANCHOR);
        long landingSample = request.landingSample();
        List<ContinuousSceneTransitionPlan.StemTimeline> timelines =
                buildStemTimelines(request, strategy, anchor, landingSample);
        List<ContinuousSceneTransitionPlan.AnchorSelection> anchors =
                buildAnchorSet(request, strategy, anchor, landingSample);
        float vocalConflict = ContinuousSceneTransitionPlan.vocalConflictScore(
                timelines, request.activationSample, landingSample);
        if (vocalConflict > 0f) vetoes.add(VetoReason.DOUBLE_LEAD_VOCAL);

        CandidateScore score = score(request, strategy, anchor, vocalConflict);
        if (score.total < MIN_QUALITY_SCORE) vetoes.add(VetoReason.QUALITY_SCORE_TOO_LOW);
        ContinuousSceneTransitionPlan plan = null;
        if (anchors.size() > 0 && vocalConflict == 0f) {
            try {
                plan = createPlan(request, strategy, null, anchors, timelines, score,
                        ContinuousSceneTransitionPlan.PlanOrigin.DETERMINISTIC, true);
            } catch (IllegalArgumentException invalid) {
                if (!vetoes.contains(VetoReason.DOUBLE_LEAD_VOCAL)) {
                    vetoes.add(VetoReason.NO_CONTINUOUS_ANCHOR);
                }
            }
        }
        return new Candidate(strategy, score, vetoes, plan);
    }

    private Candidate buildFallbackCandidate(
            PlanningRequest request,
            ContinuousSceneTransitionPlan.Strategy strategy,
            ContinuousSceneTransitionPlan.FallbackReason fallbackReason) {
        List<VetoReason> vetoes = new ArrayList<>();
        addReadinessVetoes(request, vetoes);
        if (!request.targetDecoded) vetoes.add(VetoReason.TARGET_DECODE_FAILED);
        boolean available = strategy == ContinuousSceneTransitionPlan.Strategy.LEGACY_INTELLIGENT
                ? request.legacyIntelligentAvailable
                : strategy == ContinuousSceneTransitionPlan.Strategy.PHRASE_AWARE_CROSSFADE
                ? request.phraseAwareCrossfadeAvailable : request.basicCrossfadeAvailable;
        if (!available) vetoes.add(VetoReason.MISSING_REQUIRED_STEMS);
        if (request.sourceLeadVocalActive && request.targetLeadVocalActive
                && !request.legacyVocalSafe) {
            vetoes.add(VetoReason.DOUBLE_LEAD_VOCAL);
        }
        CandidateScore score = fallbackScore(request, strategy);
        ContinuousSceneTransitionPlan plan = null;
        if (vetoes.isEmpty()) {
            List<ContinuousSceneTransitionPlan.StemTimeline> timelines =
                    buildFallbackTimelines(request, strategy);
            plan = createPlan(request, strategy, fallbackReason, List.of(), timelines, score,
                    ContinuousSceneTransitionPlan.PlanOrigin.LEGACY, false);
        }
        return new Candidate(strategy, score, vetoes, plan);
    }

    private static void addReadinessVetoes(PlanningRequest request, List<VetoReason> vetoes) {
        long guaranteedMinimum = request.samplesPerBar * 8L;
        if (!request.bufferReady || request.guaranteedRenderedSamples < guaranteedMinimum
                || request.activationUnderruns > 0) {
            vetoes.add(VetoReason.BUFFER_NOT_READY);
        }
        if (request.activationUnderrunRisk > MAX_ACTIVATION_UNDERRUN_RISK) {
            vetoes.add(VetoReason.ACTIVATION_UNDERRUN_RISK);
        }
        if (request.sampleDiscontinuity > MAX_SAMPLE_DISCONTINUITY) {
            vetoes.add(VetoReason.SAMPLE_DISCONTINUITY);
        }
    }

    private static boolean requirementsMet(PlanningRequest request,
                                           ContinuousSceneTransitionPlan.Strategy strategy) {
        switch (strategy) {
            case PRESERVE_VOCAL_A:
                return hasA(request, ContinuousSceneTransitionPlan.SemanticRole.LEAD_VOCAL)
                        && pairedNonVocalCount(request) >= 1;
            case PRESERVE_GUITAR:
                return hasPair(request, ContinuousSceneTransitionPlan.SemanticRole.GUITAR);
            case PRESERVE_GROOVE:
                return hasPair(request, ContinuousSceneTransitionPlan.SemanticRole.DRUMS)
                        && hasPair(request, ContinuousSceneTransitionPlan.SemanticRole.BASS);
            case PRESERVE_ATMOSPHERE:
                return hasPair(request, ContinuousSceneTransitionPlan.SemanticRole.ATMOSPHERE);
            case BASS_HANDOFF:
                return hasPair(request, ContinuousSceneTransitionPlan.SemanticRole.BASS);
            case DRUM_BRIDGE:
                return hasPair(request, ContinuousSceneTransitionPlan.SemanticRole.DRUMS);
            case MELODIC_RELAY:
                return hasPair(request, ContinuousSceneTransitionPlan.SemanticRole.MELODY)
                        || hasPair(request, ContinuousSceneTransitionPlan.SemanticRole.GUITAR)
                        || hasPair(request, ContinuousSceneTransitionPlan.SemanticRole.HARMONY);
            case VOCAL_CHOP_BRIDGE:
                return hasA(request, ContinuousSceneTransitionPlan.SemanticRole.LEAD_VOCAL)
                        && pairedNonVocalCount(request) >= 1;
            case GENERATED_INSTRUMENTAL_BRIDGE:
                return hasGeneratedInstrument(request) && pairedNonVocalCount(request) >= 1;
            case DETERMINISTIC_STEM_BRIDGE:
                return pairedNonVocalCount(request) >= 2;
            default:
                return false;
        }
    }

    private static ContinuousSceneTransitionPlan.SemanticRole preferredRole(
            ContinuousSceneTransitionPlan.Strategy strategy) {
        switch (strategy) {
            case PRESERVE_GUITAR:
            case MELODIC_RELAY:
                return ContinuousSceneTransitionPlan.SemanticRole.GUITAR;
            case PRESERVE_GROOVE:
            case DRUM_BRIDGE:
                return ContinuousSceneTransitionPlan.SemanticRole.DRUMS;
            case PRESERVE_ATMOSPHERE:
            case GENERATED_INSTRUMENTAL_BRIDGE:
                return ContinuousSceneTransitionPlan.SemanticRole.ATMOSPHERE;
            case BASS_HANDOFF:
                return ContinuousSceneTransitionPlan.SemanticRole.BASS;
            case PRESERVE_VOCAL_A:
                return ContinuousSceneTransitionPlan.SemanticRole.HARMONY;
            default:
                return null;
        }
    }

    private static StemDescriptor selectAnchor(
            PlanningRequest request,
            ContinuousSceneTransitionPlan.SemanticRole preferredRole) {
        StemDescriptor best = null;
        float bestScore = -1f;
        for (StemDescriptor descriptor : request.stems.values()) {
            if (!descriptor.hasPair() || descriptor.semanticRole.isVocal()
                    || descriptor.semanticRole == ContinuousSceneTransitionPlan.SemanticRole.FULL_MIX) {
                continue;
            }
            float preference = descriptor.semanticRole == preferredRole ? .08f : 0f;
            float role = descriptor.semanticRole == ContinuousSceneTransitionPlan.SemanticRole.GUITAR
                    || descriptor.semanticRole == ContinuousSceneTransitionPlan.SemanticRole.HARMONY
                    || descriptor.semanticRole == ContinuousSceneTransitionPlan.SemanticRole.DRUMS
                    ? .04f : 0f;
            float score = descriptor.similarity * .62f + descriptor.confidence * .30f
                    + preference + role;
            if (score > bestScore) {
                bestScore = score;
                best = descriptor;
            }
        }
        return best;
    }

    private static List<ContinuousSceneTransitionPlan.AnchorSelection> buildAnchorSet(
            PlanningRequest request, ContinuousSceneTransitionPlan.Strategy strategy,
            StemDescriptor anchor, long landingSample) {
        if (anchor == null) return List.of();
        Window handoff = handoffWindow(request, anchor.semanticRole,
                strategy, true);
        long aEnd = at(request, handoff.aFadeEnd);
        long bStart = at(request, handoff.bFadeStart);
        return List.of(
                new ContinuousSceneTransitionPlan.AnchorSelection(anchor.semanticRole,
                        ContinuousSceneTransitionPlan.Source.A, request.activationSample,
                        aEnd, anchor.confidence),
                new ContinuousSceneTransitionPlan.AnchorSelection(anchor.semanticRole,
                        ContinuousSceneTransitionPlan.Source.B, bStart, landingSample,
                        anchor.confidence));
    }

    private static List<ContinuousSceneTransitionPlan.StemTimeline> buildStemTimelines(
            PlanningRequest request, ContinuousSceneTransitionPlan.Strategy strategy,
            StemDescriptor anchor, long landingSample) {
        List<ContinuousSceneTransitionPlan.StemTimeline> timelines = new ArrayList<>();
        for (StemDescriptor descriptor : request.stems.values()) {
            boolean isAnchor = descriptor == anchor;
            Window window = handoffWindow(request, descriptor.semanticRole, strategy, isAnchor);
            if (descriptor.hasA()) {
                timelines.add(lane(request, descriptor.semanticRole,
                        ContinuousSceneTransitionPlan.Source.A, descriptor.sourceAFragmentId,
                        fadeOut(request, window.aFadeStart, window.aFadeEnd), isAnchor));
            }
            if (descriptor.hasB()) {
                timelines.add(lane(request, descriptor.semanticRole,
                        ContinuousSceneTransitionPlan.Source.B, descriptor.sourceBFragmentId,
                        fadeIn(request, window.bFadeStart, window.bFadeEnd), isAnchor));
            }
            if (strategy == ContinuousSceneTransitionPlan.Strategy.GENERATED_INSTRUMENTAL_BRIDGE
                    && descriptor.hasGenerated()) {
                timelines.add(lane(request, descriptor.semanticRole,
                        ContinuousSceneTransitionPlan.Source.GENERATED,
                        descriptor.generatedFragmentId,
                        window(request, .28f, .72f, .12f), false));
            }
        }
        if (strategy == ContinuousSceneTransitionPlan.Strategy.VOCAL_CHOP_BRIDGE) {
            StemDescriptor vocal = request.stems.get(
                    ContinuousSceneTransitionPlan.SemanticRole.LEAD_VOCAL);
            if (vocal != null && vocal.hasA()) {
                timelines.add(lane(request,
                        ContinuousSceneTransitionPlan.SemanticRole.VOCAL_TEXTURE,
                        ContinuousSceneTransitionPlan.Source.A, vocal.sourceAFragmentId,
                        window(request, .36f, .60f, .08f), false));
            }
        }
        return timelines;
    }

    private static Window handoffWindow(
            PlanningRequest request,
            ContinuousSceneTransitionPlan.SemanticRole role,
            ContinuousSceneTransitionPlan.Strategy strategy,
            boolean anchor) {
        if (role == ContinuousSceneTransitionPlan.SemanticRole.LEAD_VOCAL) {
            if (strategy == ContinuousSceneTransitionPlan.Strategy.PRESERVE_VOCAL_A) {
                return new Window(.52f, .62f, .78f, .90f);
            }
            return new Window(.20f, .30f, .70f, .82f);
        }
        if (role == ContinuousSceneTransitionPlan.SemanticRole.BACKING_VOCAL) {
            return new Window(.26f, .36f, .62f, .74f);
        }
        if (anchor) {
            if (role == ContinuousSceneTransitionPlan.SemanticRole.GUITAR
                    && strategy == ContinuousSceneTransitionPlan.Strategy.PRESERVE_GUITAR) {
                return new Window(.66f, .88f, .66f, .88f);
            }
            return new Window(.38f, .62f, .38f, .62f);
        }
        switch (role) {
            case ATMOSPHERE: return new Window(.48f, .78f, .04f, .32f);
            case PERCUSSION: return new Window(.18f, .44f, .08f, .30f);
            case DRUMS: return new Window(.22f, .48f, .18f, .44f);
            case BASS: return new Window(.36f, .64f, .42f, .68f);
            case GUITAR:
                if (strategy == ContinuousSceneTransitionPlan.Strategy.PRESERVE_GUITAR) {
                    return new Window(.66f, .88f, .64f, .86f);
                }
                return new Window(.44f, .72f, .38f, .66f);
            case HARMONY: return new Window(.42f, .76f, .26f, .60f);
            case MELODY: return new Window(.48f, .78f, .42f, .72f);
            case EFFECTS: return new Window(.28f, .66f, .12f, .52f);
            default: return new Window(.38f, .66f, .34f, .62f);
        }
    }

    private static ContinuousSceneTransitionPlan.StemTimeline lane(
            PlanningRequest request,
            ContinuousSceneTransitionPlan.SemanticRole role,
            ContinuousSceneTransitionPlan.Source source,
            long fragmentId,
            ContinuousSceneTransitionPlan.Envelope gain,
            boolean anchor) {
        long start = request.activationSample;
        long end = request.landingSample();
        float pitch = source == ContinuousSceneTransitionPlan.Source.B
                ? request.pitchShiftSemitones : 0f;
        float formant = source == ContinuousSceneTransitionPlan.Source.B
                ? request.formantShiftSemitones : 0f;
        float tempo = source == ContinuousSceneTransitionPlan.Source.B
                ? request.tempoRatio : 1f;
        float width = source == ContinuousSceneTransitionPlan.Source.GENERATED ? 1.2f : 1f;
        ContinuousSceneTransitionPlan.Envelope harmonic = anchor
                ? ContinuousSceneTransitionPlan.Envelope.smooth(
                point(start, source == ContinuousSceneTransitionPlan.Source.A ? 0f : 1f),
                point(end, 1f))
                : constant(start, end, 0f);
        ContinuousSceneTransitionPlan.Envelope timbre = anchor
                ? ContinuousSceneTransitionPlan.Envelope.smooth(point(start, 0f), point(end, 1f))
                : constant(start, end, 0f);
        ContinuousSceneTransitionPlan.VocalOwnershipState owner =
                role == ContinuousSceneTransitionPlan.SemanticRole.VOCAL_TEXTURE
                        ? ContinuousSceneTransitionPlan.VocalOwnershipState.NONE
                        : source == ContinuousSceneTransitionPlan.Source.A && role.isVocal()
                        ? ContinuousSceneTransitionPlan.VocalOwnershipState.TRACK_A
                        : source == ContinuousSceneTransitionPlan.Source.B && role.isVocal()
                        ? ContinuousSceneTransitionPlan.VocalOwnershipState.TRACK_B
                        : ContinuousSceneTransitionPlan.VocalOwnershipState.NONE;
        return new ContinuousSceneTransitionPlan.StemTimeline(role, source, fragmentId,
                start, end, gain,
                constant(start, end, pitch),
                constant(start, end, formant),
                constant(start, end, tempo),
                constant(start, end, 0f),
                constant(start, end, width),
                constant(start, end, 0f),
                source == ContinuousSceneTransitionPlan.Source.B
                        ? ContinuousSceneTransitionPlan.Envelope.smooth(
                        point(start, 8_000f), point(at(request, .35f), 20_000f),
                        point(end, 20_000f))
                        : constant(start, end, 20_000f),
                source == ContinuousSceneTransitionPlan.Source.A
                        ? ContinuousSceneTransitionPlan.Envelope.smooth(
                        point(start, 0f), point(end, .12f))
                        : ContinuousSceneTransitionPlan.Envelope.smooth(
                        point(start, .10f), point(end, 0f)),
                constant(start, end, role == ContinuousSceneTransitionPlan.SemanticRole.DRUMS
                        || role == ContinuousSceneTransitionPlan.SemanticRole.PERCUSSION ? 1f : .92f),
                harmonic, timbre, owner);
    }

    private static List<ContinuousSceneTransitionPlan.StemTimeline> buildFallbackTimelines(
            PlanningRequest request, ContinuousSceneTransitionPlan.Strategy strategy) {
        float aStart;
        float aEnd;
        float bStart;
        float bEnd;
        if (strategy == ContinuousSceneTransitionPlan.Strategy.LEGACY_INTELLIGENT) {
            aStart = .38f; aEnd = .56f; bStart = .44f; bEnd = .62f;
        } else if (strategy == ContinuousSceneTransitionPlan.Strategy.PHRASE_AWARE_CROSSFADE) {
            aStart = .34f; aEnd = .58f; bStart = .42f; bEnd = .66f;
        } else {
            aStart = 0f; aEnd = 1f; bStart = 0f; bEnd = 1f;
        }
        if (request.sourceLeadVocalActive && request.targetLeadVocalActive) {
            aStart = 0f;
            aEnd = .48f;
            bStart = .52f;
            bEnd = 1f;
        }
        return List.of(
                lane(request, ContinuousSceneTransitionPlan.SemanticRole.FULL_MIX,
                        ContinuousSceneTransitionPlan.Source.A, request.sourceTrackId,
                        fadeOut(request, aStart, aEnd), false),
                lane(request, ContinuousSceneTransitionPlan.SemanticRole.FULL_MIX,
                        ContinuousSceneTransitionPlan.Source.B, request.targetTrackId,
                        fadeIn(request, bStart, bEnd), false));
    }

    private static ContinuousSceneTransitionPlan createPlan(
            PlanningRequest request,
            ContinuousSceneTransitionPlan.Strategy strategy,
            ContinuousSceneTransitionPlan.FallbackReason fallbackReason,
            List<ContinuousSceneTransitionPlan.AnchorSelection> anchors,
            List<ContinuousSceneTransitionPlan.StemTimeline> timelines,
            CandidateScore score,
            ContinuousSceneTransitionPlan.PlanOrigin origin,
            boolean stemSeparatorUsed) {
        float confidence = anchors.isEmpty() ? 0f : (float) anchors.stream()
                .mapToDouble(anchor -> anchor.confidence).average().orElse(0.0);
        return new ContinuousSceneTransitionPlan(
                request.transitionId,
                request.sourceTrackId,
                request.targetTrackId,
                request.sourceStartSample,
                request.targetLandingSample,
                request.activationSample,
                request.landingSample(),
                anchors,
                score.total,
                confidence,
                fallbackReason,
                timelines,
                strategy,
                origin,
                stemSeparatorUsed,
                request.guaranteedRenderedSamples,
                request.activationUnderruns,
                1f - request.sampleDiscontinuity);
    }

    private static CandidateScore score(
            PlanningRequest request,
            ContinuousSceneTransitionPlan.Strategy strategy,
            StemDescriptor anchor,
            float vocalConflict) {
        float continuity = anchor == null ? 0f : anchor.similarity;
        if (anchor != null && anchor.semanticRole == preferredRole(strategy)) {
            continuity = clamp(continuity + .05f);
        }
        float anchorPersistence = anchor == null ? 0f : clamp(.72f + anchor.confidence * .28f);
        float generatedRisk = strategy
                == ContinuousSceneTransitionPlan.Strategy.GENERATED_INSTRUMENTAL_BRIDGE
                ? request.generatedArtifactRisk : 0f;
        float readiness = request.samplesPerBar <= 0L ? 0f : clamp(
                request.guaranteedRenderedSamples / (float) (request.samplesPerBar * 8L));
        return new CandidateScore(continuity, anchorPersistence,
                vocalConflict == 0f ? 1f : 0f,
                request.rhythmCompatibility, request.harmonyCompatibility,
                request.timbreCompatibility, request.maskingCompatibility,
                request.energyCompatibility, generatedRisk,
                request.sampleDiscontinuity, readiness, request.landingQuality);
    }

    private static CandidateScore fallbackScore(
            PlanningRequest request, ContinuousSceneTransitionPlan.Strategy strategy) {
        float quality = strategy == ContinuousSceneTransitionPlan.Strategy.LEGACY_INTELLIGENT
                ? .56f : strategy == ContinuousSceneTransitionPlan.Strategy.PHRASE_AWARE_CROSSFADE
                ? .46f : .25f;
        float readiness = clamp(request.guaranteedRenderedSamples
                / (float) Math.max(1L, request.samplesPerBar * 8L));
        return new CandidateScore(quality, quality,
                request.sourceLeadVocalActive && request.targetLeadVocalActive ? 0f : 1f,
                request.rhythmCompatibility, request.harmonyCompatibility,
                quality, request.maskingCompatibility, request.energyCompatibility,
                0f, request.sampleDiscontinuity, readiness, request.landingQuality);
    }

    private static ContinuousSceneTransitionPlan.FallbackReason fallbackReason(
            PlanningRequest request, List<Candidate> candidates) {
        if (request.userForcedSkip) {
            return ContinuousSceneTransitionPlan.FallbackReason.USER_FORCED_SKIP;
        }
        if (!request.targetDecoded) {
            return ContinuousSceneTransitionPlan.FallbackReason.TARGET_DECODE_FAILED;
        }
        if (!request.bufferReady || request.activationUnderruns > 0
                || request.guaranteedRenderedSamples < request.samplesPerBar * 8L
                || request.activationUnderrunRisk > MAX_ACTIVATION_UNDERRUN_RISK) {
            return ContinuousSceneTransitionPlan.FallbackReason.INSUFFICIENT_BUFFER;
        }
        if (!request.stemSeparatorUsed) {
            return ContinuousSceneTransitionPlan.FallbackReason.STEM_SEPARATION_FAILED;
        }
        if (request.separatorConfidence < MIN_SEPARATOR_CONFIDENCE) {
            return ContinuousSceneTransitionPlan.FallbackReason.LOW_SEPARATOR_CONFIDENCE;
        }
        if (request.thermalConstrained && candidates.stream().allMatch(candidate ->
                candidate.vetoes.contains(VetoReason.DEVICE_THERMAL_LIMIT)
                        || candidate.vetoes.contains(VetoReason.MISSING_REQUIRED_STEMS))) {
            return ContinuousSceneTransitionPlan.FallbackReason.DEVICE_THERMAL_LIMIT;
        }
        if (candidates.stream().anyMatch(candidate ->
                candidate.vetoes.contains(VetoReason.DOUBLE_LEAD_VOCAL))) {
            return ContinuousSceneTransitionPlan.FallbackReason.VOCAL_CONFLICT_UNRESOLVABLE;
        }
        if (candidates.stream().allMatch(candidate ->
                candidate.vetoes.contains(VetoReason.NO_CONTINUOUS_ANCHOR)
                        || candidate.vetoes.contains(VetoReason.MISSING_REQUIRED_STEMS))) {
            return ContinuousSceneTransitionPlan.FallbackReason.NO_COMPATIBLE_ANCHOR;
        }
        return ContinuousSceneTransitionPlan.FallbackReason.QUALITY_SCORE_TOO_LOW;
    }

    private static boolean hasA(PlanningRequest request,
                                ContinuousSceneTransitionPlan.SemanticRole role) {
        StemDescriptor descriptor = request.stems.get(role);
        return descriptor != null && descriptor.hasA();
    }

    private static boolean hasPair(PlanningRequest request,
                                   ContinuousSceneTransitionPlan.SemanticRole role) {
        StemDescriptor descriptor = request.stems.get(role);
        return descriptor != null && descriptor.hasPair();
    }

    private static int pairedNonVocalCount(PlanningRequest request) {
        int count = 0;
        for (StemDescriptor descriptor : request.stems.values()) {
            if (!descriptor.semanticRole.isVocal() && descriptor.hasPair()) count++;
        }
        return count;
    }

    private static boolean hasGeneratedInstrument(PlanningRequest request) {
        for (StemDescriptor descriptor : request.stems.values()) {
            if (!descriptor.semanticRole.isVocal() && descriptor.hasGenerated()) return true;
        }
        return false;
    }

    private static ContinuousSceneTransitionPlan.Envelope fadeOut(
            PlanningRequest request, float start, float end) {
        return ContinuousSceneTransitionPlan.Envelope.smooth(
                point(request.activationSample, 1f), point(at(request, start), 1f),
                point(at(request, end), 0f), point(request.landingSample(), 0f));
    }

    private static ContinuousSceneTransitionPlan.Envelope fadeIn(
            PlanningRequest request, float start, float end) {
        return ContinuousSceneTransitionPlan.Envelope.smooth(
                point(request.activationSample, 0f), point(at(request, start), 0f),
                point(at(request, end), 1f), point(request.landingSample(), 1f));
    }

    private static ContinuousSceneTransitionPlan.Envelope window(
            PlanningRequest request, float start, float end, float ramp) {
        return ContinuousSceneTransitionPlan.Envelope.smooth(
                point(request.activationSample, 0f), point(at(request, start), 0f),
                point(at(request, Math.min(end, start + ramp)), 1f),
                point(at(request, Math.max(start, end - ramp)), 1f),
                point(at(request, end), 0f), point(request.landingSample(), 0f));
    }

    private static ContinuousSceneTransitionPlan.Envelope constant(
            long start, long end, float value) {
        return ContinuousSceneTransitionPlan.Envelope.constant(start, end, value);
    }

    private static ContinuousSceneTransitionPlan.EnvelopePoint point(long sample, float value) {
        return new ContinuousSceneTransitionPlan.EnvelopePoint(sample, value);
    }

    private static long at(PlanningRequest request, float fraction) {
        long duration = request.landingSample() - request.activationSample;
        return request.activationSample + Math.round(duration * clamp(fraction));
    }

    private static final class Window {
        final float aFadeStart;
        final float aFadeEnd;
        final float bFadeStart;
        final float bFadeEnd;

        Window(float aFadeStart, float aFadeEnd, float bFadeStart, float bFadeEnd) {
            this.aFadeStart = aFadeStart;
            this.aFadeEnd = aFadeEnd;
            this.bFadeStart = bFadeStart;
            this.bFadeEnd = bFadeEnd;
        }
    }

    private static final class Candidate {
        final ContinuousSceneTransitionPlan.Strategy strategy;
        final CandidateScore score;
        final List<VetoReason> vetoes;
        final ContinuousSceneTransitionPlan plan;

        Candidate(ContinuousSceneTransitionPlan.Strategy strategy, CandidateScore score,
                  List<VetoReason> vetoes, ContinuousSceneTransitionPlan plan) {
            this.strategy = strategy;
            this.score = score;
            this.vetoes = vetoes;
            this.plan = plan;
        }
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
