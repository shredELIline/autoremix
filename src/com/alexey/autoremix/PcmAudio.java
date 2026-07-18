package com.alexey.autoremix;

/** Immutable interleaved stereo float PCM buffer. */
final class PcmAudio {
    final int sampleRate;
    final float[] stereo;

    PcmAudio(int sampleRate, float[] stereo) {
        this.sampleRate = Math.max(8_000, sampleRate);
        this.stereo = stereo == null ? new float[0] : stereo;
    }

    int frames() {
        return stereo.length / 2;
    }

    long durationMs() {
        return Math.round(frames() * 1000.0 / sampleRate);
    }

    PcmAudio sliceFrames(int startFrame, int frameCount) {
        int start = Math.max(0, Math.min(frames(), startFrame));
        int count = Math.max(0, Math.min(frames() - start, frameCount));
        float[] out = new float[count * 2];
        System.arraycopy(stereo, start * 2, out, 0, out.length);
        return new PcmAudio(sampleRate, out);
    }

    PcmAudio sliceMs(long startMs, long durationMs) {
        int start = (int) Math.max(0, Math.min(Integer.MAX_VALUE,
                Math.round(startMs * sampleRate / 1000.0)));
        int count = (int) Math.max(0, Math.min(Integer.MAX_VALUE,
                Math.round(durationMs * sampleRate / 1000.0)));
        return sliceFrames(start, count);
    }

    static PcmAudio silence(int sampleRate, long durationMs) {
        int frames = (int) Math.max(0, Math.min(Integer.MAX_VALUE / 2,
                Math.round(durationMs * sampleRate / 1000.0)));
        return new PcmAudio(sampleRate, new float[frames * 2]);
    }
}
