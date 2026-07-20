package com.alexey.autoremix;

/**
 * Local, deterministic stem-like decomposition. It combines center/side analysis with a soft
 * harmonic/percussive STFT mask. It is intentionally complementary: lead + drums + bass + backing
 * reconstruct the original signal, which makes long morphs stable and avoids phase discontinuities.
 */
final class SpectralStemSeparator {
    private static final int FFT = 1024;
    private static final int HOP = 256;
    private static final int BINS = FFT / 2 + 1;
    private static final int TIME_RADIUS = 5;
    private static final int FREQ_RADIUS = 6;
    private static final int ANALYSIS_CHUNK_FRAMES = 512;

    private SpectralStemSeparator() {}

    static StemBundle separate(PcmAudio source) {
        int frames = source.frames();
        if (frames < FFT * 2) return fallback(source);
        int analysisFrames = 1 + Math.max(0, (frames - FFT) / HOP);
        float[] window = hann(FFT);
        float[] real = new float[FFT];
        float[] imag = new float[FFT];

        float[] lead = new float[source.stereo.length];
        float[] bass = new float[source.stereo.length];
        float[] drums = new float[source.stereo.length];
        float[] midR = new float[FFT];
        float[] midI = new float[FFT];
        float[] sideR = new float[FFT];
        float[] sideI = new float[FFT];
        float[] leadR = new float[FFT];
        float[] leadI = new float[FFT];
        float[] bassR = new float[FFT];
        float[] bassI = new float[FFT];
        float[] drumLR = new float[FFT];
        float[] drumLI = new float[FFT];
        float[] drumRR = new float[FFT];
        float[] drumRI = new float[FFT];

        // Keep only one analysis core plus the temporal-mask halo. Synthesis frames are
        // emitted once into the global overlap-add reconstruction, so chunk seams are exact.
        for (int coreStart = 0; coreStart < analysisFrames;
             coreStart += ANALYSIS_CHUNK_FRAMES) {
            int coreEnd = Math.min(analysisFrames, coreStart + ANALYSIS_CHUNK_FRAMES);
            int haloStart = Math.max(0, coreStart - TIME_RADIUS);
            int haloEnd = Math.min(analysisFrames, coreEnd + TIME_RADIUS);
            float[][] midMag = new float[haloEnd - haloStart][BINS];
            float[][] sideMag = new float[haloEnd - haloStart][BINS];
            for (int frame = haloStart; frame < haloEnd; frame++) {
                int offset = frame * HOP;
                analyzeMagnitudes(source, window, offset, false,
                        midMag[frame - haloStart], real, imag);
                analyzeMagnitudes(source, window, offset, true,
                        sideMag[frame - haloStart], real, imag);
            }

            for (int frame = coreStart; frame < coreEnd; frame++) {
            int offset = frame * HOP;
            for (int i = 0; i < FFT; i++) {
                float left = source.stereo[(offset + i) * 2];
                float right = source.stereo[(offset + i) * 2 + 1];
                midR[i] = (left + right) * .5f * window[i];
                sideR[i] = (left - right) * .5f * window[i];
                midI[i] = sideI[i] = 0f;
            }
            FastFft.transform(midR, midI, false);
            FastFft.transform(sideR, sideI, false);
            zero(leadR); zero(leadI); zero(bassR); zero(bassI);
            zero(drumLR); zero(drumLI); zero(drumRR); zero(drumRI);

            for (int k = 0; k < BINS; k++) {
                int localFrame = frame - haloStart;
                float magnitude = midMag[localFrame][k]
                        + sideMag[localFrame][k] * .72f + 1e-7f;
                float harmonic = temporalMean(midMag, sideMag, localFrame, k);
                float percussive = frequencyMean(midMag, sideMag, localFrame, k);
                float h2 = harmonic * harmonic;
                float p2 = percussive * percussive;
                float hMask = h2 / (h2 + p2 + 1e-9f);
                float pMask = 1f - hMask;
                float center = midMag[localFrame][k] / magnitude;
                float frequency = k * source.sampleRate / (float) FFT;

                float bassShape = lowShelf(frequency, 65f, 245f);
                float vocalBand = bandShape(frequency, 145f, 330f, 5_800f, 8_600f);
                float leadMask = hMask * vocalBand * pow(center, 1.45f) * (1f - bassShape * .92f);
                float bassMask = bassShape * (.72f * hMask + .28f * pMask) * (.70f + .30f * center);
                float drumHigh = 1f - lowShelf(frequency, 55f, 155f) * .48f;
                float drumMask = pMask * drumHigh * (1f - leadMask * .68f) * (1f - bassMask * .78f);
                float sum = leadMask + bassMask + drumMask;
                if (sum > .96f) {
                    float scale = .96f / sum;
                    leadMask *= scale;
                    bassMask *= scale;
                    drumMask *= scale;
                }
                setSymmetric(leadR, leadI, midR, midI, k, leadMask);
                setSymmetric(bassR, bassI, midR, midI, k, bassMask);
                setStereoSymmetric(drumLR, drumLI, drumRR, drumRI,
                        midR, midI, sideR, sideI, k, drumMask, .82f + .18f * pMask);
            }

            FastFft.transform(leadR, leadI, true);
            FastFft.transform(bassR, bassI, true);
            FastFft.transform(drumLR, drumLI, true);
            FastFft.transform(drumRR, drumRI, true);
            for (int i = 0; i < FFT; i++) {
                int target = offset + i;
                if (target >= frames) break;
                float w = window[i];
                float l = leadR[i] * w;
                float b = bassR[i] * w;
                lead[target * 2] += l;
                lead[target * 2 + 1] += l;
                bass[target * 2] += b;
                bass[target * 2 + 1] += b;
                drums[target * 2] += drumLR[i] * w;
                drums[target * 2 + 1] += drumRR[i] * w;
            }
        }
        }

        float[] backing = new float[source.stereo.length];
        for (int frame = 0; frame < frames; frame++) {
            float norm = overlapNorm(window, frame, analysisFrames);
            float scale = norm > 1e-5f ? 1f / norm : 0f;
            int i = frame * 2;
            lead[i] *= scale; lead[i + 1] *= scale;
            bass[i] *= scale; bass[i + 1] *= scale;
            drums[i] *= scale; drums[i + 1] *= scale;
            backing[i] = source.stereo[i] - lead[i] - bass[i] - drums[i];
            backing[i + 1] = source.stereo[i + 1] - lead[i + 1] - bass[i + 1] - drums[i + 1];
        }
        return new StemBundle(source.sampleRate, lead, drums, bass, backing);
    }

    private static void analyzeMagnitudes(PcmAudio source, float[] window, int offset,
                                          boolean side, float[] magnitudes,
                                          float[] real, float[] imag) {
        for (int i = 0; i < FFT; i++) {
            float left = source.stereo[(offset + i) * 2];
            float right = source.stereo[(offset + i) * 2 + 1];
            real[i] = (side ? left - right : left + right) * .5f * window[i];
            imag[i] = 0f;
        }
        FastFft.transform(real, imag, false);
        for (int k = 0; k < BINS; k++) magnitudes[k] = hypot(real[k], imag[k]);
    }

    private static float overlapNorm(float[] window, int target, int analysisFrames) {
        int first = Math.max(0, (target - FFT + HOP) / HOP);
        int last = Math.min(analysisFrames - 1, target / HOP);
        float norm = 0f;
        for (int frame = first; frame <= last; frame++) {
            float w = window[target - frame * HOP];
            norm += w * w;
        }
        return norm;
    }

    private static StemBundle fallback(PcmAudio source) {
        float[] lead = new float[source.stereo.length];
        float[] bass = new float[source.stereo.length];
        float[] drums = new float[source.stereo.length];
        float[] backing = source.stereo.clone();
        return new StemBundle(source.sampleRate, lead, drums, bass, backing);
    }

    private static float temporalMean(float[][] mid, float[][] side, int frame, int bin) {
        int start = Math.max(0, frame - TIME_RADIUS);
        int end = Math.min(mid.length - 1, frame + TIME_RADIUS);
        float sum = 0f;
        for (int i = start; i <= end; i++) sum += mid[i][bin] + side[i][bin] * .72f;
        return sum / (end - start + 1);
    }

    private static float frequencyMean(float[][] mid, float[][] side, int frame, int bin) {
        int start = Math.max(0, bin - FREQ_RADIUS);
        int end = Math.min(BINS - 1, bin + FREQ_RADIUS);
        float sum = 0f;
        for (int k = start; k <= end; k++) sum += mid[frame][k] + side[frame][k] * .72f;
        return sum / (end - start + 1);
    }

    private static void setSymmetric(float[] outR, float[] outI, float[] inR, float[] inI,
                                     int k, float mask) {
        outR[k] = inR[k] * mask;
        outI[k] = inI[k] * mask;
        if (k > 0 && k < FFT / 2) {
            int mirror = FFT - k;
            outR[mirror] = inR[mirror] * mask;
            outI[mirror] = inI[mirror] * mask;
        }
    }

    private static void setStereoSymmetric(float[] leftR, float[] leftI, float[] rightR, float[] rightI,
                                           float[] midR, float[] midI, float[] sideR, float[] sideI,
                                           int k, float mask, float sideAmount) {
        applyStereoBin(leftR, leftI, rightR, rightI, midR, midI, sideR, sideI,
                k, mask, sideAmount);
        if (k > 0 && k < FFT / 2) {
            applyStereoBin(leftR, leftI, rightR, rightI, midR, midI, sideR, sideI,
                    FFT - k, mask, sideAmount);
        }
    }

    private static void applyStereoBin(float[] leftR, float[] leftI, float[] rightR, float[] rightI,
                                       float[] midR, float[] midI, float[] sideR, float[] sideI,
                                       int k, float mask, float sideAmount) {
        float mr = midR[k] * mask;
        float mi = midI[k] * mask;
        float sr = sideR[k] * mask * sideAmount;
        float si = sideI[k] * mask * sideAmount;
        leftR[k] = mr + sr;
        leftI[k] = mi + si;
        rightR[k] = mr - sr;
        rightI[k] = mi - si;
    }

    private static float[] hann(int size) {
        float[] window = new float[size];
        for (int i = 0; i < size; i++) {
            window[i] = (float) (.5 - .5 * Math.cos(2.0 * Math.PI * i / size));
        }
        return window;
    }

    private static float lowShelf(float frequency, float fullBelow, float zeroAbove) {
        if (frequency <= fullBelow) return 1f;
        if (frequency >= zeroAbove) return 0f;
        float t = (frequency - fullBelow) / (zeroAbove - fullBelow);
        return .5f + .5f * (float) Math.cos(Math.PI * t);
    }

    private static float bandShape(float frequency, float low0, float low1, float high1, float high0) {
        if (frequency <= low0 || frequency >= high0) return 0f;
        if (frequency >= low1 && frequency <= high1) return 1f;
        if (frequency < low1) return smooth((frequency - low0) / (low1 - low0));
        return smooth((high0 - frequency) / (high0 - high1));
    }

    private static float smooth(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * (3f - 2f * t);
    }

    private static float pow(float value, float power) {
        return (float) Math.pow(Math.max(0f, value), power);
    }

    private static float hypot(float real, float imag) {
        return (float) Math.sqrt(real * real + imag * imag);
    }

    private static void zero(float[] values) {
        java.util.Arrays.fill(values, 0f);
    }
}
