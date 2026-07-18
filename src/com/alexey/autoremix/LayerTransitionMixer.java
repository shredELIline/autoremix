package com.alexey.autoremix;

/** Sample-accurate stem automation. No scene can expose a whole second track in one control step. */
final class LayerTransitionMixer {
    private LayerTransitionMixer() {}

    static PcmAudio mix(StemBundle a, StemBundle b, LayerPlan plan) {
        int frames = Math.min(a.frames(), b.frames());
        float[] out = new float[frames * 2];
        float[] aLeadEnv = envelope(a.lead, frames, a.sampleRate);
        float[] bLeadEnv = envelope(b.lead, frames, b.sampleRate);
        for (int frame = 0; frame < frames; frame++) {
            float p = frames <= 1 ? 1f : frame / (float) (frames - 1);
            Gains g = gains(plan.type, p);

            // Vocal-priority sidechain: a preserved lead automatically opens space in the incoming
            // backing. This is the difference between two songs fighting and one shared arrangement.
            float duckB = 1f - .38f * aLeadEnv[frame] * g.aLead
                    * Math.max(g.bBacking, g.bDrums);
            float duckA = 1f - .32f * bLeadEnv[frame] * g.bLead
                    * Math.max(g.aBacking, g.aDrums);
            g.bBacking *= duckB;
            g.bDrums *= .86f + .14f * duckB;
            g.aBacking *= duckA;
            g.aDrums *= .88f + .12f * duckA;

            // Stems within one deck are complementary, not four unrelated full-scale signals.
            // Normalize by deck presence so p=0 reconstructs A exactly and p=1 reconstructs B
            // exactly; only simultaneous deck overlap receives power compensation.
            float aPresence = g.aPresence();
            float bPresence = g.bPresence();
            float deckPower = aPresence * aPresence + bPresence * bPresence;
            float headroom = deckPower > 1f ? 1f / (float) Math.sqrt(deckPower) : 1f;
            int i = frame * 2;
            for (int channel = 0; channel < 2; channel++) {
                int s = i + channel;
                float value =
                        a.lead[s] * g.aLead + a.drums[s] * g.aDrums
                                + a.bass[s] * g.aBass + a.backing[s] * g.aBacking
                                + b.lead[s] * g.bLead + b.drums[s] * g.bDrums
                                + b.bass[s] * g.bBass + b.backing[s] * g.bBacking;
                out[s] = value * headroom;
            }
        }
        return new PcmAudio(a.sampleRate, out);
    }

    private static Gains gains(LayerPlan.Type type, float p) {
        Gains g = new Gains();
        switch (type) {
            case VOCAL_ANCHOR_BACKING_MORPH:
                g.aLead = out(p, .72f, 1f);
                g.aDrums = out(p, .12f, .58f);
                g.aBass = out(p, .28f, .68f);
                g.aBacking = out(p, .08f, .64f);
                g.bDrums = in(p, .05f, .48f);
                g.bBacking = in(p, .12f, .60f);
                g.bBass = in(p, .38f, .72f);
                g.bLead = in(p, .76f, 1f);
                break;
            case GROOVE_ANCHOR_LEAD_RELAY:
                g.aLead = out(p, .18f, .56f);
                g.aDrums = out(p, .66f, 1f);
                g.aBass = out(p, .58f, .92f);
                g.aBacking = out(p, .45f, .82f);
                g.bLead = in(p, .16f, .55f);
                g.bBacking = in(p, .48f, .82f);
                g.bBass = in(p, .62f, .94f);
                g.bDrums = in(p, .70f, 1f);
                break;
            case DRUM_BRIDGE_TAKEOVER:
                g.aLead = out(p, .58f, .90f);
                g.aDrums = out(p, .48f, .82f);
                g.aBass = out(p, .35f, .72f);
                g.aBacking = out(p, .24f, .74f);
                g.bDrums = in(p, .05f, .36f);
                g.bBacking = in(p, .28f, .66f);
                g.bBass = in(p, .43f, .76f);
                g.bLead = in(p, .66f, .96f);
                break;
            case HARMONIC_BED_TAKEOVER:
                g.aLead = out(p, .50f, .84f);
                g.aBacking = out(p, .42f, .88f);
                g.aBass = out(p, .40f, .78f);
                g.aDrums = out(p, .54f, .92f);
                g.bBacking = in(p, .04f, .54f);
                g.bLead = in(p, .48f, .84f);
                g.bBass = in(p, .52f, .84f);
                g.bDrums = in(p, .60f, .96f);
                break;
            case BASS_GROOVE_MORPH:
                g.aLead = out(p, .55f, .90f);
                g.aDrums = out(p, .36f, .72f);
                g.aBass = out(p, .22f, .62f);
                g.aBacking = out(p, .50f, .88f);
                g.bBass = in(p, .20f, .62f);
                g.bDrums = in(p, .34f, .74f);
                g.bBacking = in(p, .48f, .86f);
                g.bLead = in(p, .67f, .96f);
                break;
            case ATMOSPHERE_CHAIN:
                g.aLead = out(p, .50f, .82f);
                g.aDrums = out(p, .30f, .68f);
                g.aBass = out(p, .32f, .70f);
                g.aBacking = out(p, .58f, .94f);
                g.bBacking = in(p, .04f, .62f);
                g.bLead = in(p, .52f, .86f);
                g.bBass = in(p, .58f, .90f);
                g.bDrums = in(p, .64f, .98f);
                break;
            case VOCAL_GUEST_RETURN: {
                float guest = window(p, .18f, .70f, .15f);
                g.aLead = 1f - guest * .52f;
                g.aDrums = 1f;
                g.aBass = 1f;
                g.aBacking = 1f - guest * .14f;
                g.bLead = guest * .88f;
                g.bBacking = guest * .16f;
                g.bDrums = guest * .12f;
                g.bBass = 0f;
                break;
            }
            case BACKING_GUEST_RETURN: {
                float guest = window(p, .10f, .78f, .18f);
                g.aLead = 1f;
                g.aDrums = 1f - guest * .72f;
                g.aBass = 1f - guest * .78f;
                g.aBacking = 1f - guest * .68f;
                g.bLead = 0f;
                g.bDrums = guest * .72f;
                g.bBass = guest * .72f;
                g.bBacking = guest * .74f;
                break;
            }
            case MELODY_RELAY_TAKEOVER:
            default:
                g.aLead = out(p, .44f, .82f);
                g.aBacking = out(p, .24f, .76f);
                g.aBass = out(p, .38f, .76f);
                g.aDrums = out(p, .52f, .92f);
                g.bBacking = in(p, .06f, .50f);
                g.bLead = in(p, .42f, .80f);
                g.bBass = in(p, .50f, .84f);
                g.bDrums = in(p, .62f, .98f);
                break;
        }
        return g;
    }

    private static float[] envelope(float[] stereo, int frames, int sampleRate) {
        float[] out = new float[frames];
        float env = 0f;
        float attack = 1f - (float) Math.exp(-1f / Math.max(1f, sampleRate * .008f));
        float release = 1f - (float) Math.exp(-1f / Math.max(1f, sampleRate * .180f));
        for (int frame = 0; frame < frames; frame++) {
            int i = frame * 2;
            float value = Math.max(Math.abs(stereo[i]), Math.abs(stereo[i + 1]));
            env += (value - env) * (value > env ? attack : release);
            out[frame] = Math.min(1f, env * 4.2f);
        }
        return out;
    }

    private static float in(float p, float start, float end) {
        if (p <= start) return 0f;
        if (p >= end) return 1f;
        float t = (p - start) / (end - start);
        return .5f - .5f * (float) Math.cos(Math.PI * t);
    }

    private static float out(float p, float start, float end) {
        return 1f - in(p, start, end);
    }

    private static float window(float p, float start, float end, float ramp) {
        return in(p, start, Math.min(end, start + ramp))
                * out(p, Math.max(start, end - ramp), end);
    }

    private static final class Gains {
        float aLead, aDrums, aBass, aBacking;
        float bLead, bDrums, bBass, bBacking;

        float aPresence() {
            return (aLead + aDrums + aBass + aBacking) * .25f;
        }

        float bPresence() {
            return (bLead + bDrums + bBass + bBacking) * .25f;
        }
    }
}
