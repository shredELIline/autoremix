package com.alexey.autoremix;

import java.util.Locale;

/** Chooses a musical layer narrative from role complementarity, never from a random transition list. */
final class ContinuityDirector {
    private ContinuityDirector() {}

    static LayerPlan plan(TrackAnalysis a, TrackAnalysis.Fragment af,
                          TrackAnalysis b, TrackAnalysis.Fragment bf,
                          int sequence, boolean allowReturns) {
        float vibe = advancedVibeSimilarity(a, af, b, bf);
        float ratio = RemixPlanner.bestTempoRatio(a.bpm, b.bpm);
        float tempoError = Math.abs(1f - ratio);
        if (vibe < .82f || tempoError > .065f) return null;

        LayerPlan.Type type;
        String reason;
        if (af.isVocalHeavy() && !bf.isVocalHeavy() && bf.percussiveness > .46f) {
            type = allowReturns && sequence % 4 == 1
                    ? LayerPlan.Type.BACKING_GUEST_RETURN
                    : LayerPlan.Type.VOCAL_ANCHOR_BACKING_MORPH;
            reason = "ведущий вокал A можно сохранить, B подходит как новый бэк";
        } else if (!af.isVocalHeavy() && bf.isVocalHeavy()) {
            type = allowReturns && sequence % 5 == 2
                    ? LayerPlan.Type.VOCAL_GUEST_RETURN
                    : LayerPlan.Type.GROOVE_ANCHOR_LEAD_RELAY;
            reason = "ритм A свободен для плавного входа ведущей фразы B";
        } else if (af.percussiveness > .60f && bf.percussiveness > .58f) {
            type = LayerPlan.Type.DRUM_BRIDGE_TAKEOVER;
            reason = "похожие ударные позволяют сохранить непрерывную пульсацию";
        } else if (af.bassPresence > .58f && bf.bassPresence > .55f) {
            type = LayerPlan.Type.BASS_GROOVE_MORPH;
            reason = "низ и грув совместимы для последовательной передачи";
        } else if (af.isBreakdown() || bf.isBreakdown()) {
            type = LayerPlan.Type.ATMOSPHERE_CHAIN;
            reason = "разреженная фраза подходит для общего атмосферного моста";
        } else if (Math.abs(af.brightness - bf.brightness) < .15f) {
            type = LayerPlan.Type.HARMONIC_BED_TAKEOVER;
            reason = "тембр гармонического фона совпадает";
        } else {
            type = LayerPlan.Type.MELODY_RELAY_TAKEOVER;
            reason = "мелодический слой совместим при строгом vibe-фильтре";
        }
        int bars = (af.gridConfidence > .62f && bf.gridConfidence > .62f) ? 24 : 32;
        if (type.returnsToA) bars = 16;
        return new LayerPlan(type, bars, vibe, ratio,
                String.format(Locale.US, "%s · vibe %d%%", reason, Math.round(vibe * 100f)));
    }

    static float advancedVibeSimilarity(TrackAnalysis a, TrackAnalysis.Fragment af,
                                        TrackAnalysis b, TrackAnalysis.Fragment bf) {
        // Compare audible character directly instead of cosine-normalising each song in isolation.
        // Role complementarity (lead over backing, or backing under lead) is allowed, while
        // tempo, timbre, low-end weight and rhythmic density must still belong to one vibe.
        float trackVibe = RemixPlanner.vibeSimilarity(a, b);
        float fragmentDistance =
                Math.abs(af.brightness - bf.brightness) * .21f +
                Math.abs(af.transientDensity - bf.transientDensity) * .18f +
                Math.abs(af.dynamicRange - bf.dynamicRange) * .10f +
                Math.abs(af.bassPresence - bf.bassPresence) * .16f +
                Math.abs(af.percussiveness - bf.percussiveness) * .17f +
                Math.abs(af.energy - bf.energy) * .18f;
        float fragmentVibe = clamp(1f - fragmentDistance, 0f, 1f);

        float roleCompatibility;
        boolean complementaryLead = af.isVocalHeavy() != bf.isVocalHeavy();
        if (complementaryLead && (af.percussiveness > .38f || bf.percussiveness > .38f)) {
            roleCompatibility = .96f;
        } else if (af.isVocalHeavy() && bf.isVocalHeavy()) {
            // Two leads can still work, but only through a serial relay, never simultaneously.
            roleCompatibility = .72f;
        } else {
            roleCompatibility = clamp(1f - Math.abs(af.vocalPresence - bf.vocalPresence) * .38f, 0f, 1f);
        }

        float key = RemixPlanner.keyCompatibility(a, b);
        float tempoRatio = RemixPlanner.bestTempoRatio(a.bpm, b.bpm);
        float bpm = 1f - Math.min(1f, Math.abs(1f - tempoRatio) / .075f);
        float grid = clamp((af.gridConfidence + bf.gridConfidence) * .5f, 0f, 1f);

        return clamp(trackVibe * .35f + fragmentVibe * .25f + roleCompatibility * .14f
                + key * .10f + bpm * .12f + grid * .04f, 0f, 1f);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
