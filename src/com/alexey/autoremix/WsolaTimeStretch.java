package com.alexey.autoremix;

/** Lightweight WSOLA tempo matching for the short transition segment. */
final class WsolaTimeStretch {
    private static final int WINDOW = 1024;
    private static final int OVERLAP = 256;
    private static final int SYNTH_HOP = WINDOW - OVERLAP;
    private static final int SEARCH = 128;

    private WsolaTimeStretch() {}

    static PcmAudio stretch(PcmAudio input, float speed) {
        speed = Math.max(.90f, Math.min(1.10f, speed));
        if (Math.abs(speed - 1f) < .002f || input.frames() < WINDOW * 3) return input;
        int inFrames = input.frames();
        int outFrames = Math.max(WINDOW, (int) Math.ceil(inFrames / speed) + WINDOW);
        float[] output = new float[outFrames * 2];
        copyWindow(input.stereo, 0, output, 0, WINDOW);
        int outputPos = SYNTH_HOP;
        double expectedInput = SYNTH_HOP * speed;
        int produced = WINDOW;

        while (outputPos + WINDOW < outFrames) {
            int expected = (int) Math.round(expectedInput);
            if (expected + WINDOW >= inFrames) break;
            int best = findBest(input.stereo, output, expected, outputPos, inFrames);
            overlapWindow(input.stereo, best, output, outputPos);
            produced = Math.max(produced, outputPos + WINDOW);
            outputPos += SYNTH_HOP;
            expectedInput += SYNTH_HOP * speed;
        }
        int desired = Math.min(produced, Math.max(WINDOW, (int) Math.round(inFrames / speed)));
        float[] trimmed = new float[desired * 2];
        System.arraycopy(output, 0, trimmed, 0, trimmed.length);
        return new PcmAudio(input.sampleRate, trimmed);
    }

    private static int findBest(float[] input, float[] output, int expected, int outputPos, int inFrames) {
        int start = Math.max(0, expected - SEARCH);
        int end = Math.min(inFrames - WINDOW - 1, expected + SEARCH);
        int best = Math.max(0, Math.min(inFrames - WINDOW - 1, expected));
        double bestScore = -Double.MAX_VALUE;
        for (int candidate = start; candidate <= end; candidate += 4) {
            double dot = 0.0;
            double a2 = 1e-9;
            double b2 = 1e-9;
            for (int i = 0; i < OVERLAP; i += 2) {
                float outMono = (output[(outputPos + i) * 2] + output[(outputPos + i) * 2 + 1]) * .5f;
                float inMono = (input[(candidate + i) * 2] + input[(candidate + i) * 2 + 1]) * .5f;
                dot += outMono * inMono;
                a2 += outMono * outMono;
                b2 += inMono * inMono;
            }
            double score = dot / Math.sqrt(a2 * b2);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    private static void overlapWindow(float[] input, int inputFrame, float[] output, int outputFrame) {
        for (int i = 0; i < WINDOW; i++) {
            int src = (inputFrame + i) * 2;
            int dst = (outputFrame + i) * 2;
            if (i < OVERLAP) {
                float t = .5f - .5f * (float) Math.cos(Math.PI * i / OVERLAP);
                output[dst] = output[dst] * (1f - t) + input[src] * t;
                output[dst + 1] = output[dst + 1] * (1f - t) + input[src + 1] * t;
            } else {
                output[dst] = input[src];
                output[dst + 1] = input[src + 1];
            }
        }
    }

    private static void copyWindow(float[] input, int inputFrame, float[] output, int outputFrame, int frames) {
        System.arraycopy(input, inputFrame * 2, output, outputFrame * 2, frames * 2);
    }
}
