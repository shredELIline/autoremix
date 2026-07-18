package com.alexey.autoremix;

/** DC blocker, look-free peak limiter and soft saturation for the single PCM master bus. */
final class MasteringChain {
    private MasteringChain() {}

    static void processInPlace(float[] stereo, int sampleRate) {
        float dcL = 0f, dcR = 0f, prevL = 0f, prevR = 0f;
        float gain = 1f;
        float attack = 1f - (float) Math.exp(-1f / Math.max(1f, sampleRate * .0015f));
        float release = 1f - (float) Math.exp(-1f / Math.max(1f, sampleRate * .120f));
        for (int i = 0; i + 1 < stereo.length; i += 2) {
            float inL = stereo[i];
            float inR = stereo[i + 1];
            float outL = inL - prevL + .995f * dcL;
            float outR = inR - prevR + .995f * dcR;
            prevL = inL; prevR = inR; dcL = outL; dcR = outR;
            float peak = Math.max(Math.abs(outL), Math.abs(outR));
            float target = peak > .91f ? .91f / Math.max(.0001f, peak) : 1f;
            gain += (target - gain) * (target < gain ? attack : release);
            stereo[i] = soft(outL * gain);
            stereo[i + 1] = soft(outR * gain);
        }
    }

    static void applyEdgeSafety(float[] stereo, int sampleRate, int milliseconds) {
        int frames = stereo.length / 2;
        int ramp = Math.min(frames / 2, Math.max(1, sampleRate * milliseconds / 1000));
        for (int frame = 0; frame < ramp; frame++) {
            float t = .5f - .5f * (float) Math.cos(Math.PI * frame / ramp);
            stereo[frame * 2] *= t;
            stereo[frame * 2 + 1] *= t;
            int tail = frames - 1 - frame;
            stereo[tail * 2] *= t;
            stereo[tail * 2 + 1] *= t;
        }
    }

    private static float soft(float value) {
        // Smooth cubic saturation below hard digital full scale.
        float x = Math.max(-1.5f, Math.min(1.5f, value));
        float shaped = (float) Math.tanh(x * 1.08f) / .793f * .94f;
        // Keep a true digital ceiling after the smooth saturation. The limiter envelope handles
        // ordinary peaks; this final guard only catches impossible inter-sample/overdrive cases.
        return Math.max(-.985f, Math.min(.985f, shaped));
    }
}
