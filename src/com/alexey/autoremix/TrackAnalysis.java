package com.alexey.autoremix;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** Compact music-information-retrieval result used by the autonomous mix director. */
final class TrackAnalysis {
    static final String[] KEY_NAMES = {"C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B"};

    static final class Fragment {
        final long cueMs;
        final float energy;
        final float bpm;
        final float confidence;
        final float transientDensity;
        final float brightness;
        final float dynamicRange;
        final float phraseScore;
        final float gridConfidence;
        final float vocalPresence;
        final float bassPresence;
        final float percussiveness;
        final float loudnessDb;
        final long beatPeriodMs;

        Fragment(long cueMs, float energy, float bpm, float confidence) {
            this(cueMs, energy, bpm, confidence, .45f, .45f, .45f, .45f,
                    .25f, .42f, .45f, .45f, -16f,
                    Math.round(60_000f / Math.max(1f, bpm)));
        }

        Fragment(long cueMs, float energy, float bpm, float confidence,
                 float transientDensity, float brightness, float dynamicRange, float phraseScore) {
            this(cueMs, energy, bpm, confidence, transientDensity, brightness, dynamicRange, phraseScore,
                    confidence * .72f, .42f, .45f, transientDensity, -16f,
                    Math.round(60_000f / Math.max(1f, bpm)));
        }

        Fragment(long cueMs, float energy, float bpm, float confidence,
                 float transientDensity, float brightness, float dynamicRange, float phraseScore,
                 float gridConfidence, float vocalPresence, float bassPresence,
                 float percussiveness, float loudnessDb, long beatPeriodMs) {
            this.cueMs = Math.max(0L, cueMs);
            this.energy = clamp01(energy);
            this.bpm = bpm;
            this.confidence = clamp01(confidence);
            this.transientDensity = clamp01(transientDensity);
            this.brightness = clamp01(brightness);
            this.dynamicRange = clamp01(dynamicRange);
            this.phraseScore = clamp01(phraseScore);
            this.gridConfidence = clamp01(gridConfidence);
            this.vocalPresence = clamp01(vocalPresence);
            this.bassPresence = clamp01(bassPresence);
            this.percussiveness = clamp01(percussiveness);
            this.loudnessDb = Math.max(-60f, Math.min(0f, loudnessDb));
            this.beatPeriodMs = Math.max(240L, Math.min(1_000L,
                    beatPeriodMs <= 0 ? Math.round(60_000f / Math.max(1f, bpm)) : beatPeriodMs));
        }

        boolean isBreakdown() {
            return energy < .48f && transientDensity < .52f;
        }

        boolean isDropLike() {
            return energy > .62f && transientDensity > .48f && percussiveness > .46f;
        }

        boolean isVocalHeavy() {
            return vocalPresence >= .58f;
        }

        boolean isPercussive() {
            return percussiveness >= .62f && vocalPresence <= .48f;
        }

        boolean isInstrumentalFriendly() {
            return vocalPresence <= .43f && gridConfidence >= .38f;
        }

        boolean hasStableGrid() {
            return gridConfidence >= .40f && confidence >= .32f;
        }

        float mixReadiness() {
            float collisionSafety = isVocalHeavy() ? .84f : 1f;
            return clamp01((confidence * .22f + gridConfidence * .26f + phraseScore * .28f
                    + dynamicRange * .10f + transientDensity * .08f + percussiveness * .06f)
                    * collisionSafety);
        }

        String roleLabel() {
            if (isDropLike()) return "drop";
            if (isBreakdown()) return "breakdown";
            if (isVocalHeavy()) return "vocal";
            if (isPercussive()) return "drums";
            return "phrase";
        }
    }

    final float bpm;
    final float energy;
    final int key;
    final boolean minor;
    final float keyConfidence;
    final List<Fragment> fragments;
    final boolean fallback;
    final float loudnessDb;

    // Track-level "vibe" fingerprint. These are robust averages over several windows and are
    // intentionally independent from genre labels: the planner compares how the music actually
    // sounds (density, timbre, low-end, vocal/beat balance), not filename metadata.
    final float styleBrightness;
    final float styleTransientDensity;
    final float styleDynamicRange;
    final float styleVocalPresence;
    final float styleBassPresence;
    final float stylePercussiveness;
    final float styleConfidence;

    TrackAnalysis(float bpm, float energy, int key, boolean minor, float keyConfidence,
                  List<Fragment> fragments, boolean fallback) {
        this(bpm, energy, key, minor, keyConfidence, fragments, fallback,
                averageLoudness(fragments, -16f));
    }

    TrackAnalysis(float bpm, float energy, int key, boolean minor, float keyConfidence,
                  List<Fragment> fragments, boolean fallback, float loudnessDb) {
        this.bpm = bpm;
        this.energy = energy;
        this.key = key;
        this.minor = minor;
        this.keyConfidence = keyConfidence;
        this.fragments = fragments == null ? new ArrayList<>() : fragments;
        this.fallback = fallback;
        this.loudnessDb = Math.max(-60f, Math.min(0f, loudnessDb));

        float brightness = 0f;
        float transients = 0f;
        float dynamics = 0f;
        float vocals = 0f;
        float bass = 0f;
        float percussion = 0f;
        float confidence = 0f;
        float weightSum = 0f;
        for (Fragment fragment : this.fragments) {
            float weight = .35f + fragment.mixReadiness() * .65f;
            weightSum += weight;
            brightness += fragment.brightness * weight;
            transients += fragment.transientDensity * weight;
            dynamics += fragment.dynamicRange * weight;
            vocals += fragment.vocalPresence * weight;
            bass += fragment.bassPresence * weight;
            percussion += fragment.percussiveness * weight;
            confidence += (fragment.confidence * .35f + fragment.gridConfidence * .35f
                    + fragment.phraseScore * .30f) * weight;
        }
        if (weightSum <= 0f) {
            styleBrightness = .45f;
            styleTransientDensity = .45f;
            styleDynamicRange = .45f;
            styleVocalPresence = .45f;
            styleBassPresence = .45f;
            stylePercussiveness = .45f;
            styleConfidence = 0f;
        } else {
            styleBrightness = clamp01(brightness / weightSum);
            styleTransientDensity = clamp01(transients / weightSum);
            styleDynamicRange = clamp01(dynamics / weightSum);
            styleVocalPresence = clamp01(vocals / weightSum);
            styleBassPresence = clamp01(bass / weightSum);
            stylePercussiveness = clamp01(percussion / weightSum);
            styleConfidence = clamp01(confidence / weightSum);
        }
    }

    static TrackAnalysis fallback(Track track) {
        List<Fragment> parts = new ArrayList<>();
        long usable = Math.max(0, track.durationMs - 72_000);
        parts.add(new Fragment(Math.min(usable, Math.max(0, track.durationMs / 5)), .45f, 120f, 0f,
                .35f, .40f, .42f, .30f, .12f, .43f, .42f, .36f, -18f, 500L));
        parts.add(new Fragment(Math.min(usable, Math.max(0, track.durationMs * 2 / 5)), .58f, 120f, 0f,
                .48f, .48f, .48f, .34f, .12f, .46f, .49f, .48f, -16f, 500L));
        parts.add(new Fragment(Math.min(usable, Math.max(0, track.durationMs * 3 / 5)), .65f, 120f, 0f,
                .55f, .52f, .50f, .36f, .12f, .44f, .55f, .57f, -14f, 500L));
        return new TrackAnalysis(120f, .55f, -1, false, 0f, parts, true, -16f);
    }

    Fragment chooseFragment(Random random, float targetEnergy, int chaos, long durationMs) {
        return chooseCompatibleFragment(null, targetEnergy, chaos, durationMs, false);
    }

    Fragment chooseCompatibleFragment(Fragment current, float targetEnergy, int chaos,
                                      long durationMs, boolean preferContrast) {
        if (fragments.isEmpty()) return new Fragment(0, energy, bpm, 0);
        Fragment best = fragments.get(0);
        float bestScore = -Float.MAX_VALUE;
        for (Fragment fragment : fragments) {
            float energyCloseness = 1f - Math.min(1f, Math.abs(fragment.energy - targetEnergy) / .55f);
            float confidenceScore = fragment.confidence * .13f + fragment.gridConfidence * .20f
                    + fragment.phraseScore * .28f;
            float rhythmicClarity = fragment.transientDensity * .08f + fragment.dynamicRange * .08f
                    + fragment.percussiveness * .08f;
            float roleScore = 0f;
            if (current != null) {
                float energyDifference = Math.abs(fragment.energy - current.energy);
                if (preferContrast) roleScore = Math.min(1f, energyDifference / .38f) * .20f;
                else roleScore = (1f - Math.min(1f, energyDifference / .45f)) * .14f;
                if (current.isBreakdown() && fragment.isDropLike()) roleScore += .18f;
                if (current.isDropLike() && fragment.isBreakdown()) roleScore += .10f;

                // Avoid the classic amateur mashup mistake: two lead vocals fighting each other.
                if (current.isVocalHeavy() && fragment.isVocalHeavy()) roleScore -= .62f;
                else if (current.isVocalHeavy() && fragment.isPercussive()) roleScore += .31f;
                else if (current.isVocalHeavy() && fragment.isInstrumentalFriendly()) roleScore += .23f;
                else if ((current.isPercussive() || current.isInstrumentalFriendly())
                        && fragment.isVocalHeavy()) roleScore += .24f;
                else if (current.isInstrumentalFriendly() && fragment.isInstrumentalFriendly())
                    roleScore += .10f;
            }

            long remaining = Math.max(0L, durationMs - fragment.cueMs);
            float tailScore = clamp01((remaining - 55_000L) / 90_000f);
            float latePenalty = remaining < 78_000L ? -.38f : 0f;
            float safety = fallback ? -.22f : .09f;
            float score = energyCloseness * .38f + confidenceScore + rhythmicClarity + roleScore
                    + tailScore * .18f + latePenalty + safety;
            if (score > bestScore) {
                bestScore = score;
                best = fragment;
            }
        }
        long maxCue = Math.max(0, durationMs - 78_000L);
        if (best.cueMs > maxCue) {
            return new Fragment(maxCue, best.energy, best.bpm, best.confidence,
                    best.transientDensity, best.brightness, best.dynamicRange, best.phraseScore,
                    best.gridConfidence, best.vocalPresence, best.bassPresence,
                    best.percussiveness, best.loudnessDb, best.beatPeriodMs);
        }
        return best;
    }

    String vibeSummary() {
        return String.format(Locale.US, "vibe: bright %d · drums %d · vocal %d · bass %d",
                Math.round(styleBrightness * 100), Math.round(stylePercussiveness * 100),
                Math.round(styleVocalPresence * 100), Math.round(styleBassPresence * 100));
    }

    String keyName() {
        if (key < 0 || key >= 12) return "—";
        return KEY_NAMES[key] + (minor ? "m" : "");
    }

    String summary() {
        String grid = fragments.isEmpty() ? "" : " · grid "
                + Math.round(fragments.get(0).gridConfidence * 100) + "%";
        return String.format(Locale.US, "%.0f BPM · %s · энергия %d%%%s", bpm, keyName(),
                Math.round(energy * 100), grid);
    }

    private static float averageLoudness(List<Fragment> fragments, float fallback) {
        if (fragments == null || fragments.isEmpty()) return fallback;
        float sum = 0f;
        int count = 0;
        for (Fragment fragment : fragments) {
            if (Float.isFinite(fragment.loudnessDb)) {
                sum += fragment.loudnessDb;
                count++;
            }
        }
        return count == 0 ? fallback : sum / count;
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
