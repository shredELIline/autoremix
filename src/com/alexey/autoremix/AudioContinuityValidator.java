package com.alexey.autoremix;

/** Offline diagnostics for one activation boundary in an interleaved PCM timeline. */
public final class AudioContinuityValidator {
    public static final float MAX_SAMPLE_JUMP = .12f;
    public static final float MAX_DERIVATIVE_JUMP = .16f;
    public static final double MAX_LUFS_JUMP = 3.0;
    public static final double MAX_SPECTRAL_FLUX_SPIKE = .35;
    private static final float SILENCE_THRESHOLD = 1.0e-5f;
    private static final double MIN_LOUDNESS = -120.0;
    private static final int DEFAULT_WINDOW_MS = 40;
    private static final int SPECTRAL_BINS = 16;

    private AudioContinuityValidator() {}

    static Metrics analyze(PcmAudio timeline, long activationSample,
                           int activationUnderruns) {
        if (timeline == null) throw new IllegalArgumentException("timeline is required");
        return analyze(timeline.stereo, timeline.sampleRate, 2, activationSample,
                activationUnderruns);
    }

    public static Metrics analyze(float[] interleavedPcm, int sampleRate, int channels,
                                  long activationSample, int activationUnderruns) {
        if (interleavedPcm == null) throw new IllegalArgumentException("PCM is required");
        if (sampleRate <= 0) throw new IllegalArgumentException("sampleRate must be positive");
        if (channels <= 0 || interleavedPcm.length % channels != 0) {
            throw new IllegalArgumentException("PCM must contain complete frames");
        }
        if (activationUnderruns < 0) {
            throw new IllegalArgumentException("activationUnderruns must not be negative");
        }
        int frames = interleavedPcm.length / channels;
        if (activationSample < 2 || activationSample >= frames - 1L) {
            throw new IllegalArgumentException("activationSample must have PCM on both sides");
        }
        int activationFrame = Math.toIntExact(activationSample);
        int windowFrames = Math.max(8, sampleRate * DEFAULT_WINDOW_MS / 1_000);
        windowFrames = Math.min(windowFrames,
                Math.min(activationFrame, frames - activationFrame));
        requireFinite(interleavedPcm, channels, activationFrame - windowFrames,
                activationFrame + windowFrames);

        float sampleJump = 0f;
        float derivativeJump = 0f;
        for (int channel = 0; channel < channels; channel++) {
            float beforePrevious = sample(interleavedPcm, channels, activationFrame - 2, channel);
            float before = sample(interleavedPcm, channels, activationFrame - 1, channel);
            float at = sample(interleavedPcm, channels, activationFrame, channel);
            float after = sample(interleavedPcm, channels, activationFrame + 1, channel);
            float priorDerivative = before - beforePrevious;
            float boundaryDerivative = at - before;
            float nextDerivative = after - at;
            sampleJump = Math.max(sampleJump, Math.abs(boundaryDerivative));
            derivativeJump = Math.max(derivativeJump,
                    Math.max(Math.abs(boundaryDerivative - priorDerivative),
                            Math.abs(nextDerivative - boundaryDerivative)));
        }

        double beforeLufs = loudness(interleavedPcm, channels,
                activationFrame - windowFrames, activationFrame);
        double afterLufs = loudness(interleavedPcm, channels,
                activationFrame, activationFrame + windowFrames);
        double lufsJump = Math.abs(afterLufs - beforeLufs);
        double spectralFlux = spectralFluxProxy(interleavedPcm, channels,
                activationFrame - windowFrames, activationFrame,
                activationFrame, activationFrame + windowFrames);

        return new Metrics(activationSample,
                activationGapMs(interleavedPcm, sampleRate, channels, activationFrame),
                activationUnderruns, sampleJump, derivativeJump, lufsJump, spectralFlux);
    }

    private static float sample(float[] pcm, int channels, int frame, int channel) {
        return pcm[frame * channels + channel];
    }

    private static void requireFinite(float[] pcm, int channels, int startFrame, int endFrame) {
        for (int index = startFrame * channels; index < endFrame * channels; index++) {
            if (!Float.isFinite(pcm[index])) {
                throw new IllegalArgumentException("PCM contains a non-finite sample");
            }
        }
    }

    private static double activationGapMs(float[] pcm, int sampleRate, int channels,
                                          int activationFrame) {
        int start = activationFrame;
        while (start > 0 && silentFrame(pcm, channels, start - 1)) start--;
        int end = activationFrame;
        int frames = pcm.length / channels;
        while (end < frames && silentFrame(pcm, channels, end)) end++;
        int silentFrames = end - start;
        int minimumGapFrames = Math.max(2, (int) Math.ceil(sampleRate / 1_000.0));
        if (silentFrames < minimumGapFrames || start == 0 || end == frames) return 0.0;
        return silentFrames * 1_000.0 / sampleRate;
    }

    private static boolean silentFrame(float[] pcm, int channels, int frame) {
        for (int channel = 0; channel < channels; channel++) {
            if (Math.abs(sample(pcm, channels, frame, channel)) > SILENCE_THRESHOLD) return false;
        }
        return true;
    }

    private static double loudness(float[] pcm, int channels, int startFrame, int endFrame) {
        double sumSquares = 0.0;
        int sampleCount = (endFrame - startFrame) * channels;
        for (int index = startFrame * channels; index < endFrame * channels; index++) {
            double value = pcm[index];
            sumSquares += value * value;
        }
        if (sampleCount == 0 || sumSquares == 0.0) return MIN_LOUDNESS;
        return Math.max(MIN_LOUDNESS,
                -0.691 + 10.0 * Math.log10(sumSquares / sampleCount));
    }

    private static double spectralFluxProxy(float[] pcm, int channels,
                                            int beforeStart, int beforeEnd,
                                            int afterStart, int afterEnd) {
        double[] before = normalizedSpectrum(pcm, channels, beforeStart, beforeEnd);
        double[] after = normalizedSpectrum(pcm, channels, afterStart, afterEnd);
        double flux = 0.0;
        for (int bin = 0; bin < before.length; bin++) {
            double increase = Math.max(0.0, after[bin] - before[bin]);
            flux += increase * increase;
        }
        return Math.sqrt(flux);
    }

    private static double[] normalizedSpectrum(float[] pcm, int channels,
                                               int startFrame, int endFrame) {
        int frameCount = endFrame - startFrame;
        double[] magnitudes = new double[SPECTRAL_BINS];
        double magnitudeSum = 0.0;
        for (int bin = 1; bin <= SPECTRAL_BINS; bin++) {
            double real = 0.0;
            double imaginary = 0.0;
            for (int offset = 0; offset < frameCount; offset++) {
                double mono = 0.0;
                for (int channel = 0; channel < channels; channel++) {
                    mono += sample(pcm, channels, startFrame + offset, channel);
                }
                mono /= channels;
                double window = frameCount == 1 ? 1.0
                        : 0.5 - 0.5 * Math.cos(2.0 * Math.PI * offset / (frameCount - 1));
                double phase = 2.0 * Math.PI * bin * offset / frameCount;
                real += mono * window * Math.cos(phase);
                imaginary -= mono * window * Math.sin(phase);
            }
            double magnitude = Math.hypot(real, imaginary);
            magnitudes[bin - 1] = magnitude;
            magnitudeSum += magnitude;
        }
        if (magnitudeSum > 0.0) {
            for (int index = 0; index < magnitudes.length; index++) {
                magnitudes[index] /= magnitudeSum;
            }
        }
        return magnitudes;
    }

    public static final class Metrics {
        public final long activationSample;
        public final double activationGapMs;
        public final int activationUnderruns;
        public final float activationMaxSampleJump;
        public final float activationMaxDerivativeJump;
        public final double activationLufsJump;
        public final double activationSpectralFluxSpike;

        private Metrics(long activationSample, double activationGapMs,
                        int activationUnderruns, float activationMaxSampleJump,
                        float activationMaxDerivativeJump, double activationLufsJump,
                        double activationSpectralFluxSpike) {
            this.activationSample = activationSample;
            this.activationGapMs = activationGapMs;
            this.activationUnderruns = activationUnderruns;
            this.activationMaxSampleJump = activationMaxSampleJump;
            this.activationMaxDerivativeJump = activationMaxDerivativeJump;
            this.activationLufsJump = activationLufsJump;
            this.activationSpectralFluxSpike = activationSpectralFluxSpike;
        }

        public boolean passesTransitionGate() {
            return activationGapMs == 0.0 && activationUnderruns == 0
                    && activationMaxSampleJump <= MAX_SAMPLE_JUMP
                    && activationMaxDerivativeJump <= MAX_DERIVATIVE_JUMP
                    && activationLufsJump <= MAX_LUFS_JUMP
                    && activationSpectralFluxSpike <= MAX_SPECTRAL_FLUX_SPIKE;
        }
    }
}
