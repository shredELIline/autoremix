package com.alexey.autoremix;

/** Allocation-free iterative radix-2 complex FFT. */
final class FastFft {
    private FastFft() {}

    static void transform(float[] real, float[] imag, boolean inverse) {
        int n = real.length;
        if (n != imag.length || Integer.bitCount(n) != 1) {
            throw new IllegalArgumentException("FFT size must be a power of two");
        }
        int j = 0;
        for (int i = 1; i < n; i++) {
            int bit = n >> 1;
            while ((j & bit) != 0) {
                j ^= bit;
                bit >>= 1;
            }
            j ^= bit;
            if (i < j) {
                float tr = real[i]; real[i] = real[j]; real[j] = tr;
                float ti = imag[i]; imag[i] = imag[j]; imag[j] = ti;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double angle = 2.0 * Math.PI / len * (inverse ? 1.0 : -1.0);
            float wLenR = (float) Math.cos(angle);
            float wLenI = (float) Math.sin(angle);
            for (int i = 0; i < n; i += len) {
                float wr = 1f;
                float wi = 0f;
                int half = len >> 1;
                for (int k = 0; k < half; k++) {
                    int even = i + k;
                    int odd = even + half;
                    float vr = real[odd] * wr - imag[odd] * wi;
                    float vi = real[odd] * wi + imag[odd] * wr;
                    float ur = real[even];
                    float ui = imag[even];
                    real[even] = ur + vr;
                    imag[even] = ui + vi;
                    real[odd] = ur - vr;
                    imag[odd] = ui - vi;
                    float nextWr = wr * wLenR - wi * wLenI;
                    wi = wr * wLenI + wi * wLenR;
                    wr = nextWr;
                }
            }
        }
        if (inverse) {
            float inv = 1f / n;
            for (int i = 0; i < n; i++) {
                real[i] *= inv;
                imag[i] *= inv;
            }
        }
    }
}
