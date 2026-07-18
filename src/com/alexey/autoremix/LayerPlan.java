package com.alexey.autoremix;

/** Only continuity-preserving scenes are allowed in automatic mode. */
final class LayerPlan {
    enum Type {
        VOCAL_ANCHOR_BACKING_MORPH("Вокал A остаётся · фон A→B", false),
        GROOVE_ANCHOR_LEAD_RELAY("Грув A остаётся · вокал A→B", false),
        DRUM_BRIDGE_TAKEOVER("Общие барабаны · слои переходят к B", false),
        HARMONIC_BED_TAKEOVER("Общий гармонический фон · ведущий слой A→B", false),
        BASS_GROOVE_MORPH("Бас и грув передаются по слоям", false),
        ATMOSPHERE_CHAIN("Атмосфера связывает две песни", false),
        VOCAL_GUEST_RETURN("Вокальная фраза B над бэком A · возврат A", true),
        BACKING_GUEST_RETURN("Вокал A над временным бэком B · возврат A", true),
        MELODY_RELAY_TAKEOVER("Мелодический якорь · постепенный B", false);

        final String label;
        final boolean returnsToA;
        Type(String label, boolean returnsToA) {
            this.label = label;
            this.returnsToA = returnsToA;
        }
    }

    final Type type;
    final int bars;
    final float vibeScore;
    final float tempoRatio;
    final String reason;

    LayerPlan(Type type, int bars, float vibeScore, float tempoRatio, String reason) {
        this.type = type;
        this.bars = bars;
        this.vibeScore = vibeScore;
        this.tempoRatio = tempoRatio;
        this.reason = reason;
    }
}
