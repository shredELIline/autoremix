package com.alexey.autoremix;

import android.util.Log;

/** Thin owner for the Oboe output engine. All methods run off the real-time callback thread. */
public final class NativeAudioEngine implements AutoCloseable {
    private static final String TAG = "AutoRemixNative";
    private static final boolean AVAILABLE;

    static {
        boolean ready = false;
        try {
            System.loadLibrary("autoremix_jni");
            ready = nativeSelfTest();
        } catch (LinkageError | RuntimeException error) {
            Log.w(TAG, "event=native_probe_failed reason="
                    + error.getClass().getSimpleName(), error);
        }
        AVAILABLE = ready;
    }

    private long handle;

    private NativeAudioEngine(long handle) {
        this.handle = handle;
    }

    public static boolean isAvailable() {
        return AVAILABLE;
    }

    static NativeAudioEngine open(int sampleRate, int channels, int bufferFrames) {
        if (!AVAILABLE) return null;
        long handle = nativeCreate(sampleRate, channels, bufferFrames);
        return handle == 0L ? null : new NativeAudioEngine(handle);
    }

    synchronized boolean start() {
        return handle != 0L && nativeStart(handle);
    }

    synchronized boolean pause() {
        return handle != 0L && nativePause(handle);
    }

    synchronized boolean seek(long targetFrame) {
        return handle != 0L && nativeSeek(handle, Math.max(0L, targetFrame));
    }

    synchronized boolean completeSeek() {
        return handle != 0L && nativeCompleteSeek(handle);
    }

    synchronized long requestNext(long trackId) {
        return handle == 0L ? 0L : nativeRequestNext(handle, trackId);
    }

    synchronized int write(float[] pcm, int offset, int sampleCount) {
        if (handle == 0L || pcm == null || offset < 0 || sampleCount < 0
                || offset > pcm.length - sampleCount) return -1;
        return nativeEnqueue(handle, pcm, offset, sampleCount);
    }

    synchronized long playedFrames() {
        return handle == 0L ? 0L : nativePlayedFrames(handle);
    }

    synchronized int queuedFrames() {
        return handle == 0L ? 0 : nativeQueuedFrames(handle);
    }

    synchronized long generation() {
        return handle == 0L ? 0L : nativeGeneration(handle);
    }

    synchronized int underruns() {
        return handle == 0L ? 0 : nativeUnderruns(handle);
    }

    @Override public synchronized void close() {
        long owned = handle;
        handle = 0L;
        if (owned != 0L) nativeDestroy(owned);
    }

    private static native boolean nativeSelfTest();
    private static native long nativeCreate(int sampleRate, int channels, int bufferFrames);
    private static native void nativeDestroy(long handle);
    private static native boolean nativeStart(long handle);
    private static native boolean nativePause(long handle);
    private static native boolean nativeSeek(long handle, long targetFrame);
    private static native boolean nativeCompleteSeek(long handle);
    private static native long nativeRequestNext(long handle, long trackId);
    private static native int nativeEnqueue(long handle, float[] pcm, int offset, int sampleCount);
    private static native long nativePlayedFrames(long handle);
    private static native int nativeQueuedFrames(long handle);
    private static native long nativeGeneration(long handle);
    private static native int nativeUnderruns(long handle);
}
