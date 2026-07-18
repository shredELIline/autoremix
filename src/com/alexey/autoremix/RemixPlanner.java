package com.alexey.autoremix;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Pure musical planning logic. Scenarios are selected by measurable entry rules, never by dice alone. */
final class RemixPlanner {
    enum Mode {
        PHRASE_BLEND("Phrase blend", false),
        STYLE_RESET_HANDOFF("Style-safe handoff", false),
        LONG_HARMONIC_BLEND("Long harmonic blend", false),
        BASS_HANDOFF("Bass handoff", false),
        FILTER_REVEAL("Filter reveal", false),
        BREAKDOWN_LIFT("Breakdown → lift", false),
        DOUBLE_DROP("Controlled double drop", false),

        SLAM_CUT_RETURN("Slam cut: B → возврат A", true),
        ECHO_SLAM_RETURN("Echo slam: хвост A → B → A", true),
        DROP_SWAP_RETURN("Drop swap: B → A", true),
        HOT_CUE_STABS("Hot-cue stabs A ↔ B", true),
        TRANSFORMER_GATE_RETURN("Transformer cuts → возврат A", true),
        FALSE_DROP_RETURN("False drop B → настоящий возврат A", true),
        PERCUSSION_LAYER_RETURN("Percussion layer B под A", true),
        VOCAL_TEASE_RETURN("Vocal tease B → возврат A", true),
        VOCAL_ANCHOR_BACK_SWAP_RETURN("Общий вокал A + меняющийся бэк B", true),
        GROOVE_ANCHOR_VOCAL_TEASE_RETURN("Общий грув A + вокальные фразы B", true),
        LEAD_RELAY_TAKEOVER("Lead relay: вокал A → общий бэк → вокал B", false),
        BACKING_RELAY_TAKEOVER("Backing relay: бэк A → общий вокал → бэк B", false),
        DRUM_BED_MORPH_RETURN("Drum-bed morph: общий текст A, меняется ритм", true),
        ATMOSPHERE_BRIDGE_TAKEOVER("Atmosphere bridge: общий фон соединяет A и B", false),
        MELODY_ANCHOR_BACKING_MORPH_RETURN("Melody anchor: мелодия A + морфинг бэка B", true),

        SNAP_TAKEOVER("Snap cut → B takeover", false),
        BRAKE_DROP_TAKEOVER("Vinyl brake A → drop B", false),
        CUTBACK_TAKEOVER("B cut → A cutback → B", false),
        ECHO_CUTBACK_TAKEOVER("Echo cutback A ↔ B → B", false),
        BACK_TO_BACK_SWAP("Back-to-back cuts → B", false),
        KICK_SWAP_TAKEOVER("Kick swap A ↔ B → B", false),

        CALL_RESPONSE("A ↔ B call & response", false),
        TEASE_RETURN("B tease → A return → B takeover", false),
        PING_PONG_TAKEOVER("A ↔ B ping-pong → B takeover", false),
        DIALOGUE_RETURN("A ↔ B dialogue → return to A", true),
        THREE_ACT_TAKEOVER("Three-act takeover", false),
        DUAL_LAYER_CLIMB("Dual-layer climb", false),
        BACKING_MORPH_TAKEOVER("Lead A + backing B → полный B", false),
        ENERGY_WAVE("Energy wave", false);

        final String label;
        final boolean returnsToOutgoing;
        Mode(String label, boolean returnsToOutgoing) {
            this.label = label;
            this.returnsToOutgoing = returnsToOutgoing;
        }
    }

    static final class Selection {
        final Track track;
        final TrackAnalysis analysis;
        final TrackAnalysis.Fragment fragment;
        final float score;
        final float vibe;
        Selection(Track track, TrackAnalysis analysis, TrackAnalysis.Fragment fragment,
                  float score, float vibe) {
            this.track = track;
            this.analysis = analysis;
            this.fragment = fragment;
            this.score = score;
            this.vibe = vibe;
        }
    }

    static final class TransitionPlan {
        final Mode mode;
        final int bars;
        final float compatibility;
        final float tempoError;
        final String reason;

        TransitionPlan(Mode mode, int bars, float compatibility, float tempoError, String reason) {
            this.mode = mode;
            this.bars = bars;
            this.compatibility = compatibility;
            this.tempoError = tempoError;
            this.reason = reason;
        }
    }

    private RemixPlanner() {}

    static float score(TrackAnalysis current, TrackAnalysis.Fragment currentFragment,
                       TrackAnalysis candidate, TrackAnalysis.Fragment candidateFragment,
                       int chaos) {
        float ratio = bestTempoRatio(current.bpm, candidate.bpm);
        float tempo = 1f - Math.min(1f, Math.abs(1f - ratio) / .14f);
        float key = keyCompatibility(current, candidate);
        float vibe = vibeSimilarity(current, candidate);
        float desiredEnergy = clamp(currentFragment == null ? current.energy : currentFragment.energy,
                .10f, .98f);
        float energyDistance = Math.abs(candidateFragment.energy - desiredEnergy);
        float energy = 1f - Math.min(1f, energyDistance / .62f);
        float phrase = candidateFragment.mixReadiness();
        float grid = Math.min(currentFragment == null ? .35f : currentFragment.gridConfidence,
                candidateFragment.gridConfidence);
        float rhythmic = Math.min(currentFragment == null ? .45f : currentFragment.confidence,
                candidateFragment.confidence);
        float contrastBonus = 0f;
        if (currentFragment != null && currentFragment.isBreakdown() && candidateFragment.isDropLike()) {
            contrastBonus = .14f;
        }

        float vocalFit = 0f;
        if (currentFragment != null) {
            if (currentFragment.isVocalHeavy() && candidateFragment.isVocalHeavy()) vocalFit -= .30f;
            else if (currentFragment.isVocalHeavy() && candidateFragment.isPercussive()) vocalFit += .15f;
            else if (currentFragment.isPercussive() && candidateFragment.isVocalHeavy()) vocalFit += .09f;
        }
        float loudnessFit = 1f - Math.min(1f,
                Math.abs((currentFragment == null ? current.loudnessDb : currentFragment.loudnessDb)
                        - candidateFragment.loudnessDb) / 13f);
        float adventurous = chaos / 100f;

        // Same-vibe selection is a hard product invariant. A very different timbre/rhythm profile
        // may still be a good song, but it is not a good mashup candidate.
        float stylePenalty = vibe < .64f ? -4.20f : vibe < .72f ? -2.10f : vibe < .78f ? -.72f : 0f;
        return vibe * .38f + tempo * .22f + key * .17f
                + energy * (.10f - adventurous * .015f)
                + phrase * .10f + grid * .09f + rhythmic * .04f
                + loudnessFit * .04f + contrastBonus + vocalFit + stylePenalty;
    }

    /**
     * Perceptual "same vibe" score based on track-level timbre/rhythm fingerprints. It deliberately
     * keeps vocal amount low-weight, because a vocal lead and a drum-heavy backing can still belong
     * to the same style and make a better layered remix than two competing lead vocals.
     */
    static float vibeSimilarity(TrackAnalysis a, TrackAnalysis b) {
        if (a == null || b == null) return 0f;
        float brightness = 1f - Math.abs(a.styleBrightness - b.styleBrightness);
        float transients = 1f - Math.abs(a.styleTransientDensity - b.styleTransientDensity);
        float dynamics = 1f - Math.abs(a.styleDynamicRange - b.styleDynamicRange);
        float bass = 1f - Math.abs(a.styleBassPresence - b.styleBassPresence);
        float percussion = 1f - Math.abs(a.stylePercussiveness - b.stylePercussiveness);
        float vocals = 1f - Math.abs(a.styleVocalPresence - b.styleVocalPresence);
        float energy = 1f - Math.abs(a.energy - b.energy);
        float tempoRatio = bestTempoRatio(a.bpm, b.bpm);
        float tempo = 1f - Math.min(1f, Math.abs(1f - tempoRatio) / .14f);
        float confidence = Math.min(a.styleConfidence, b.styleConfidence);
        float score = brightness * .18f + transients * .17f + dynamics * .11f
                + bass * .13f + percussion * .17f + vocals * .05f
                + energy * .11f + tempo * .08f;
        // Low-confidence analysis is allowed, but it cannot claim a perfect match.
        float confidenceCeiling = .72f + confidence * .28f;
        return clamp(Math.min(score, confidenceCeiling), 0f, 1f);
    }

    static TransitionPlan planTransition(TrackAnalysis current, TrackAnalysis.Fragment currentFragment,
                                         TrackAnalysis next, TrackAnalysis.Fragment nextFragment,
                                         int chaos, boolean allowDialogue, boolean forceSwitch,
                                         boolean harmonic, int scenarioIndex) {
        return planTransition(current, currentFragment, next, nextFragment, chaos, allowDialogue,
                forceSwitch, harmonic, scenarioIndex, null);
    }

    /**
     * Automatic mode is intentionally layer-only. It never hands the room from full track A to full
     * track B with a cut. At least one audible anchor (lead, groove, drums, ambience or melodic bed)
     * remains continuous for the entire scene. Legacy cut modes stay in the enum for future manual
     * performance controls, but this planner never returns them.
     */
    static TransitionPlan planTransition(TrackAnalysis current, TrackAnalysis.Fragment currentFragment,
                                         TrackAnalysis next, TrackAnalysis.Fragment nextFragment,
                                         int chaos, boolean allowDialogue, boolean forceSwitch,
                                         boolean harmonic, int scenarioIndex, Mode lastMode) {
        float ratio = bestTempoRatio(current.bpm, next.bpm);
        float tempoError = Math.abs(1f - ratio);
        float key = harmonic ? keyCompatibility(current, next) : .76f;
        float vibe = vibeSimilarity(current, next);
        float rhythm = Math.min(currentFragment.confidence, nextFragment.confidence);
        float grid = Math.min(currentFragment.gridConfidence, nextFragment.gridConfidence);
        float phrase = Math.min(currentFragment.phraseScore, nextFragment.phraseScore);
        float readiness = Math.min(currentFragment.mixReadiness(), nextFragment.mixReadiness());
        float compatibility = clamp((1f - Math.min(1f, tempoError / .085f)) * .24f
                + key * .20f + vibe * .32f + grid * .12f + rhythm * .05f
                + phrase * .07f, 0f, 1f);
        boolean aVocal = currentFragment.isVocalHeavy();
        boolean bVocal = nextFragment.isVocalHeavy();
        boolean aPerc = currentFragment.isPercussive();
        boolean bPerc = nextFragment.isPercussive();
        boolean aInstrumental = currentFragment.isInstrumentalFriendly();
        boolean bInstrumental = nextFragment.isInstrumentalFriendly();
        float energyDelta = nextFragment.energy - currentFragment.energy;
        float brightnessDelta = nextFragment.brightness - currentFragment.brightness;
        int variant = Math.floorMod(scenarioIndex, 7);

        // prepareNext normally rejects these pairs. This is a final safety net: even here the only
        // fallback is a long spectral bridge, never a full-track cut or instant switch.
        if (vibe < .72f || tempoError > .075f || key < .58f) {
            return new TransitionPlan(Mode.ATMOSPHERE_BRIDGE_TAKEOVER, 32,
                    compatibility, tempoError,
                    "защитный длинный мост: прямое смешивание запрещено из-за слабого совпадения вайба");
        }

        // A lead remains intelligible while B contributes only backing/drums/low-end. This is the
        // preferred return scene for a vocal current phrase.
        if (!forceSwitch && aVocal && (bPerc || bInstrumental)) {
            TransitionPlan[] options = {
                    new TransitionPlan(Mode.VOCAL_ANCHOR_BACK_SWAP_RETURN, 32,
                            compatibility, tempoError,
                            "текст A остаётся ведущим; у B плавно раскрываются только бэк, ритм и низ"),
                    new TransitionPlan(Mode.DRUM_BED_MORPH_RETURN, 24,
                            compatibility, tempoError,
                            "вокальная линия A непрерывна, меняется только ударная подложка"),
                    new TransitionPlan(Mode.MELODY_ANCHOR_BACKING_MORPH_RETURN, 32,
                            compatibility, tempoError,
                            "мелодический центр A остаётся якорем, фон B приходит и уходит слоями")
            };
            return pickDistinct(options, variant, lastMode);
        }

        // A groove remains continuous while B contributes a lead phrase with removed low end.
        if (!forceSwitch && (aInstrumental || aPerc) && bVocal) {
            TransitionPlan[] options = {
                    new TransitionPlan(Mode.GROOVE_ANCHOR_VOCAL_TEASE_RETURN, 32,
                            compatibility, tempoError,
                            "непрерывный грув A держит сцену; B входит только как ведущая фраза"),
                    new TransitionPlan(Mode.VOCAL_TEASE_RETURN, 24,
                            compatibility, tempoError,
                            "две длинные вокальные фразы B подмешиваются поверх общего бэка A"),
                    new TransitionPlan(Mode.BACKING_RELAY_TAKEOVER, 32,
                            compatibility, tempoError,
                            "бэк A остаётся общим, затем вокал B мягко приводит к новой подложке")
            };
            return pickDistinct(options, variant, lastMode);
        }

        // If both have vocals, never overlap two full lead bands. Use a staged relay: first B is only
        // backing, then A lead leaves, and only afterwards the B lead opens.
        if (aVocal && bVocal) {
            return distinct(lastMode,
                    new TransitionPlan(Mode.LEAD_RELAY_TAKEOVER, 32,
                            compatibility, tempoError,
                            "два вокала разведены по времени: бэк B входит под A, затем лид передаётся B"),
                    new TransitionPlan(Mode.BACKING_MORPH_TAKEOVER, 32,
                            compatibility, tempoError,
                            "сначала меняется фон, а новая ведущая линия появляется только после ухода A"));
        }

        // Instrumental pairs are allowed to morph for longer, but still preserve a shared groove or
        // ambience instead of crossfading two complete songs.
        if (aInstrumental && bInstrumental) {
            if (Math.abs(energyDelta) <= .14f && readiness >= .38f) {
                return distinct(lastMode,
                        new TransitionPlan(Mode.DUAL_LAYER_CLIMB, 32,
                                compatibility, tempoError,
                                "общий ритмический слой удерживается, инструменты и низ меняются по очереди"),
                        new TransitionPlan(Mode.BASS_HANDOFF, 32,
                                compatibility, tempoError,
                                "один непрерывный верхний слой соединяет постепенную передачу баса"));
            }
            return new TransitionPlan(Mode.ATMOSPHERE_BRIDGE_TAKEOVER, 32,
                    compatibility, tempoError,
                    "общая атмосфера и хвосты соединяют различающиеся по энергии инструменталы");
        }

        if (forceSwitch) {
            return new TransitionPlan(Mode.BACKING_MORPH_TAKEOVER, 24,
                    compatibility, tempoError,
                    "ручной skip тоже выполняется через поэтапную замену бэка, а не включение целого B");
        }

        if (brightnessDelta > .16f || brightnessDelta < -.16f) {
            return new TransitionPlan(Mode.ATMOSPHERE_BRIDGE_TAKEOVER, 32,
                    compatibility, tempoError,
                    "тембры соединяются общей атмосферой, после чего слои B раскрываются последовательно");
        }
        return new TransitionPlan(Mode.BACKING_MORPH_TAKEOVER, 32,
                compatibility, tempoError,
                "универсальный плавный layer-morph: сначала фон B, затем бас и только в конце ведущий слой");
    }

    private static TransitionPlan distinct(Mode lastMode, TransitionPlan first, TransitionPlan second) {
        return lastMode == first.mode ? second : first;
    }

    private static TransitionPlan pickDistinct(TransitionPlan[] plans, int seed, Mode lastMode) {
        if (plans.length == 0) throw new IllegalArgumentException("plans");
        int start = Math.floorMod(seed, plans.length);
        for (int i = 0; i < plans.length; i++) {
            TransitionPlan candidate = plans[(start + i) % plans.length];
            if (candidate.mode != lastMode) return candidate;
        }
        return plans[start];
    }

    static float bestTempoRatio(float targetBpm, float sourceBpm) {
        if (targetBpm <= 0 || sourceBpm <= 0) return 1f;
        float[] variants = {sourceBpm * .5f, sourceBpm, sourceBpm * 2f};
        float bestRatio = targetBpm / sourceBpm;
        float bestDistance = Math.abs(1f - bestRatio);
        for (float variant : variants) {
            float ratio = targetBpm / variant;
            float distance = Math.abs(1f - ratio);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestRatio = ratio;
            }
        }
        return clamp(bestRatio, .86f, 1.14f);
    }

    static int bestPitchShift(TrackAnalysis current, TrackAnalysis next) {
        if (current.key < 0 || next.key < 0) return 0;
        int bestShift = 0;
        float bestScore = -1f;
        for (int shift = -2; shift <= 2; shift++) {
            TrackAnalysis shifted = new TrackAnalysis(next.bpm, next.energy, mod12(next.key + shift), next.minor,
                    next.keyConfidence, next.fragments, next.fallback, next.loudnessDb);
            float score = keyCompatibility(current, shifted) - Math.abs(shift) * .07f;
            if (score > bestScore) {
                bestScore = score;
                bestShift = shift;
            }
        }
        return bestShift;
    }

    static float keyCompatibility(TrackAnalysis a, TrackAnalysis b) {
        if (a.key < 0 || b.key < 0) return .45f;
        int diff = mod12(b.key - a.key);
        if (diff == 0 && a.minor == b.minor) return 1f;
        if (a.minor != b.minor) {
            if (a.minor && diff == 3) return .93f;
            if (!a.minor && diff == 9) return .93f;
        }
        if ((diff == 7 || diff == 5) && a.minor == b.minor) return .84f;
        if ((diff == 2 || diff == 10) && a.minor == b.minor) return .62f;
        if (diff == 0) return .70f;
        return .22f;
    }

    static int segmentBars(int scenarioIndex, int chaos, float energy, int patience) {
        int p = Math.max(0, Math.min(100, patience));
        int[] calm;
        int[] active;
        if (p >= 75) {
            calm = new int[]{56, 64, 48, 56, 64};
            active = new int[]{40, 48, 40, 36, 48};
        } else if (p >= 45) {
            calm = new int[]{44, 52, 40, 48, 44};
            active = new int[]{32, 40, 32, 36, 40};
        } else {
            calm = new int[]{32, 40, 32, 36, 40};
            active = new int[]{24, 32, 24, 28, 32};
        }
        int[] values = chaos >= 78 || energy >= .76f ? active : calm;
        return values[Math.floorMod(scenarioIndex, values.length)];
    }

    static int postDialogueBars(int patience) {
        if (patience >= 75) return 40;
        if (patience >= 45) return 32;
        return 24;
    }

    static long minimumSoloMs(int patience, float energy) {
        long base = 36_000L + Math.round(Math.max(0, Math.min(100, patience)) * 540f);
        if (energy >= .72f) base += 9_000L;
        if (energy <= .38f) base -= 4_000L;
        return Math.max(34_000L, Math.min(108_000L, base));
    }

    static long barsToMs(float bpm, int bars) {
        float safe = bpm <= 0 ? 120f : bpm;
        return Math.max(1000L, Math.round(bars * 4f * 60_000f / safe));
    }

    static <T> List<T> shuffledSample(List<T> source, int max, Random random) {
        List<T> copy = new ArrayList<>(source);
        Collections.shuffle(copy, random);
        if (copy.size() > max) return new ArrayList<>(copy.subList(0, max));
        return copy;
    }

    static int mod12(int value) {
        int r = value % 12;
        return r < 0 ? r + 12 : r;
    }

    static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
