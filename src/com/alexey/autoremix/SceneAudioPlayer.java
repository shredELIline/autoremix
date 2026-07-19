package com.alexey.autoremix;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Process;
import android.util.Log;

import java.util.concurrent.LinkedBlockingDeque;

/** Single master output. Oboe is preferred; AudioTrack remains the deterministic Tier-C fallback. */
final class SceneAudioPlayer {
    private static final String TAG = "AutoRemixOutput";
    interface Listener {
        boolean canStartScene(RenderedScene scene);
        void onSceneStarted(RenderedScene scene);
        void onSceneFinished(RenderedScene scene);
        void onPlaybackError(String error);
    }

    private static final int OUTPUT_RATE = 48_000;
    private static final int BOUNDARY_MS = 14;
    private static final int QUEUE_CAPACITY = 6;
    private final LinkedBlockingDeque<RenderedScene> queue = new LinkedBlockingDeque<>(QUEUE_CAPACITY);
    private final Object queueControl = new Object();
    private final Object outputControl = new Object();
    private final Listener listener;
    private volatile boolean running;
    private volatile boolean paused;
    private volatile long sceneFramesWritten;
    private volatile long sceneFramesTotal = 1L;
    private volatile long sceneClockOriginFrames;
    private volatile long outputClockOffsetFrames;
    private volatile long requestedSeekMs = -1L;
    private volatile long requestedSeekScene;
    private volatile long sceneSerial;
    private volatile int queueUnderruns;
    private Thread thread;
    private AudioTrack track;
    private volatile NativeAudioEngine nativeEngine;

    SceneAudioPlayer(Listener listener) {
        this.listener = listener;
    }

    static int outputRate() {
        return OUTPUT_RATE;
    }

    boolean nativeOutputActive() {
        return nativeEngine != null;
    }

    void start() {
        if (running) return;
        running = true;
        paused = false;
        thread = new Thread(this::runLoop, "AutoRemix-PCM-Master");
        thread.start();
    }

    boolean offer(RenderedScene scene) {
        if (!running || scene == null) return false;
        synchronized (queueControl) {
            return queue.offerLast(scene);
        }
    }

    boolean offerAll(RenderedScene... scenes) {
        if (!running || scenes == null || scenes.length == 0) return false;
        synchronized (queueControl) {
            if (!running || queue.remainingCapacity() < scenes.length) return false;
            for (RenderedScene scene : scenes) {
                if (scene == null) return false;
            }
            for (RenderedScene scene : scenes) queue.offerLast(scene);
            return true;
        }
    }

    int queuedScenes() {
        synchronized (queueControl) {
            return queue.size();
        }
    }

    long queuedDurationMs() {
        synchronized (queueControl) {
            long duration = 0L;
            for (RenderedScene scene : queue) duration += scene.audio.durationMs();
            return duration;
        }
    }

    long bufferedHorizonMs() {
        return Math.max(0L, currentDurationMs() - currentPositionMs()) + queuedDurationMs();
    }

    int underruns() {
        int nativeUnderruns = 0;
        synchronized (outputControl) {
            NativeAudioEngine engine = nativeEngine;
            if (engine != null) nativeUnderruns = engine.underruns();
            else if (track != null) nativeUnderruns = track.getUnderrunCount();
        }
        return queueUnderruns + nativeUnderruns;
    }

    boolean replaceQueued(RenderedScene expected, RenderedScene replacement) {
        if (!running || expected == null || replacement == null) return false;
        synchronized (queueControl) {
            if (queue.peekLast() != expected) return false;
            queue.pollLast();
            if (queue.offerLast(replacement)) return true;
            queue.offerLast(expected);
            return false;
        }
    }

    void clearQueued() {
        synchronized (queueControl) {
            queue.clear();
        }
    }

    void seekTo(long positionMs) {
        requestedSeekScene = sceneSerial;
        requestedSeekMs = Math.max(0L, positionMs);
    }

    long currentPositionMs() {
        long frames = Math.max(0L, outputClockFrames() - sceneClockOriginFrames);
        return Math.round(Math.min(sceneFramesTotal, frames) * 1000.0 / OUTPUT_RATE);
    }

    long currentDurationMs() {
        return Math.round(sceneFramesTotal * 1000.0 / OUTPUT_RATE);
    }

    void pause() {
        try {
            synchronized (outputControl) {
                paused = true;
                if (nativeEngine != null && !nativeEngine.pause()) {
                    throw new IllegalStateException("Oboe pause rejected");
                }
                if (track != null) track.pause();
            }
        } catch (RuntimeException error) {
            reportControlError("pause_failed", error);
        }
    }

    void resume() {
        try {
            synchronized (outputControl) {
                paused = false;
                if (nativeEngine != null && !nativeEngine.start()) {
                    throw new IllegalStateException("Oboe resume rejected");
                }
                if (track != null) track.play();
            }
        } catch (RuntimeException error) {
            reportControlError("resume_failed", error);
        }
        synchronized (this) { notifyAll(); }
    }

    void stop() {
        running = false;
        paused = false;
        clearQueued();
        synchronized (this) { notifyAll(); }
        if (thread != null) thread.interrupt();
        closeOutputs("stop");
    }

    void requestNext(long trackId) {
        NativeAudioEngine engine = nativeEngine;
        if (engine != null) engine.requestNext(trackId);
    }

    private void runLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        try {
            NativeAudioEngine opened = NativeAudioEngine.open(OUTPUT_RATE, 2, OUTPUT_RATE / 2);
            if (!running) {
                if (opened != null) opened.close();
                return;
            }
            if (opened != null && opened.start()) {
                nativeEngine = opened;
                if (paused) opened.pause();
            } else {
                if (opened != null) opened.close();
                openAudioTrackFallback();
            }
            float[] pendingTail = null;
            RenderedScene previous = null;
            while (running) {
                waitIfPaused();
                RenderedScene scene;
                synchronized (queueControl) {
                    scene = queue.pollFirst();
                }
                if (scene == null) {
                    if (pendingTail != null) {
                        fadeOut(pendingTail);
                        writeAll(pendingTail, 0, pendingTail.length);
                        pendingTail = null;
                        if (previous != null && listener != null) listener.onSceneFinished(previous);
                        previous = null;
                        queueUnderruns++;
                    }
                    Thread.sleep(20L);
                    continue;
                }
                if (listener != null && !listener.canStartScene(scene)) continue;
                if (listener != null) listener.onSceneStarted(scene);
                long serial = ++sceneSerial;
                float[] audio = scene.audio.stereo;
                sceneFramesWritten = 0L;
                sceneFramesTotal = Math.max(1L, audio.length / 2L);
                NativeAudioEngine engine = nativeEngine;
                sceneClockOriginFrames = outputClockFrames()
                        + (engine == null ? 0L : engine.queuedFrames());
                int overlapSamples = Math.min(audio.length / 4,
                        OUTPUT_RATE * BOUNDARY_MS / 1000 * 2);
                overlapSamples -= overlapSamples % 2;
                if (pendingTail != null) {
                    int count = Math.min(pendingTail.length, overlapSamples);
                    float[] blend = new float[count];
                    for (int i = 0; i < count; i += 2) {
                        float p = count <= 2 ? 1f : i / (float) (count - 2);
                        float a = (float) Math.cos(p * Math.PI * .5);
                        float b = (float) Math.sin(p * Math.PI * .5);
                        blend[i] = pendingTail[i] * a + audio[i] * b;
                        blend[i + 1] = pendingTail[i + 1] * a + audio[i + 1] * b;
                    }
                    writeAll(blend, 0, blend.length);
                    sceneFramesWritten += blend.length / 2L;
                    if (previous != null && listener != null) listener.onSceneFinished(previous);
                } else if (overlapSamples > 0) {
                    float[] head = new float[overlapSamples];
                    System.arraycopy(audio, 0, head, 0, overlapSamples);
                    fadeIn(head);
                    writeAll(head, 0, head.length);
                    sceneFramesWritten += head.length / 2L;
                }
                int start = overlapSamples;
                int end = Math.max(start, audio.length - overlapSamples);
                writeSceneBody(audio, start, end, serial);
                pendingTail = new float[audio.length - end];
                System.arraycopy(audio, end, pendingTail, 0, pendingTail.length);
                previous = scene;
            }
            if (pendingTail != null) {
                fadeOut(pendingTail);
                writeAll(pendingTail, 0, pendingTail.length);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException error) {
            if (running && listener != null) listener.onPlaybackError(error.getClass().getSimpleName()
                    + ": " + String.valueOf(error.getMessage()));
        } finally {
            closeOutputs("final");
            running = false;
        }
    }

    private void openAudioTrackFallback() {
        int min = AudioTrack.getMinBufferSize(OUTPUT_RATE,
                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT);
        int bufferBytes = Math.max(min * 3, OUTPUT_RATE * 2 * 4 / 4);
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(OUTPUT_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferBytes)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build();
        if (running && !paused) track.play();
    }

    private void waitIfPaused() throws InterruptedException {
        while (running && paused) {
            synchronized (this) { wait(120L); }
        }
    }

    private void writeAll(float[] audio, int offset, int length) throws InterruptedException {
        int position = offset;
        int end = offset + length;
        while (running && position < end) {
            waitIfPaused();
            int chunk = Math.min(4096, end - position);
            int wrote = writeOutput(audio, position, chunk);
            if (wrote < 0) {
                if (!running) return;
                throw new IllegalStateException("Audio output write " + wrote);
            }
            position += wrote;
        }
    }

    private void writeSceneBody(float[] audio, int start, int end, long serial)
            throws InterruptedException {
        int position = start;
        while (running && position < end) {
            waitIfPaused();
            long seekMs = requestedSeekMs;
            if (seekMs >= 0L) {
                requestedSeekMs = -1L;
                if (requestedSeekScene != serial) continue;
                int target = (int) Math.min(end - 2L,
                        Math.max(start, Math.round(seekMs * OUTPUT_RATE / 1000.0) * 2L));
                synchronized (outputControl) {
                    NativeAudioEngine engine = nativeEngine;
                    if (engine != null) {
                        if (!engine.seek(target / 2L)) {
                            throw new IllegalStateException("Oboe seek rejected");
                        }
                    } else {
                        track.pause();
                        track.flush();
                    }
                    sceneClockOriginFrames = outputClockFrames() - target / 2L;
                    int consumed = writeSeekRamp(audio, target);
                    position = Math.min(end, target + consumed);
                    sceneFramesWritten = position / 2L;
                    if (engine != null) {
                        if (!engine.completeSeek()) {
                            throw new IllegalStateException("Oboe seek resume rejected");
                        }
                    } else if (!paused) {
                        track.play();
                    }
                }
            }
            int chunk = Math.min(4096, end - position);
            int wrote = writeOutput(audio, position, chunk);
            if (wrote < 0) {
                if (!running) return;
                throw new IllegalStateException("Audio output write " + wrote);
            }
            if (wrote == 0) continue;
            position += wrote;
            sceneFramesWritten = position / 2L;
        }
    }

    private int writeOutput(float[] audio, int offset, int length) throws InterruptedException {
        int written;
        synchronized (outputControl) {
            if (!running) return -1;
            NativeAudioEngine engine = nativeEngine;
            if (engine != null) {
                written = engine.write(audio, offset, length);
                if (written < 0 && running) {
                    long failoverClock = engine.playedFrames();
                    nativeEngine = null;
                    try {
                        engine.close();
                    } catch (RuntimeException error) {
                        logCleanupError("failover_native_close", error);
                    }
                    outputClockOffsetFrames += failoverClock;
                    Log.w(TAG, "event=native_output_failover code=" + written
                            + " clockFrames=" + failoverClock);
                    openAudioTrackFallback();
                    written = track.write(audio, offset, length, AudioTrack.WRITE_BLOCKING);
                }
            } else {
                AudioTrack audioTrack = track;
                written = audioTrack == null ? -1
                        : audioTrack.write(audio, offset, length, AudioTrack.WRITE_BLOCKING);
            }
        }
        if (written == 0) Thread.sleep(2L);
        return written;
    }

    private long outputClockFrames() {
        synchronized (outputControl) {
            NativeAudioEngine engine = nativeEngine;
            if (engine != null) return outputClockOffsetFrames + engine.playedFrames();
            AudioTrack audioTrack = track;
            return outputClockOffsetFrames + (audioTrack == null ? 0L
                    : Integer.toUnsignedLong(audioTrack.getPlaybackHeadPosition()));
        }
    }

    private int writeSeekRamp(float[] audio, int target) throws InterruptedException {
        int rampSamples = Math.min(OUTPUT_RATE * 16 / 1000 * 2, audio.length - target);
        rampSamples -= rampSamples % 2;
        if (rampSamples <= 2) return 0;
        float[] fadeIn = new float[rampSamples];
        System.arraycopy(audio, target, fadeIn, 0, rampSamples);
        fadeIn(fadeIn);
        writeAll(fadeIn, 0, fadeIn.length);
        return rampSamples;
    }

    private void reportControlError(String event, RuntimeException error) {
        Log.w(TAG, "event=" + event + " reason=" + error.getClass().getSimpleName(), error);
        if (listener != null) listener.onPlaybackError(event + ": " + error.getMessage());
    }

    private void closeOutputs(String phase) {
        synchronized (outputControl) {
            NativeAudioEngine engine = nativeEngine;
            nativeEngine = null;
            try {
                if (engine != null) engine.close();
            } catch (RuntimeException error) {
                logCleanupError(phase + "_native_close", error);
            }
            AudioTrack audioTrack = track;
            track = null;
            try {
                if (audioTrack != null) audioTrack.release();
            } catch (RuntimeException error) {
                logCleanupError(phase + "_audiotrack_release", error);
            }
        }
    }

    private static void logCleanupError(String event, RuntimeException error) {
        Log.w(TAG, "event=" + event + " reason=" + error.getClass().getSimpleName(), error);
    }

    private static void fadeIn(float[] audio) {
        for (int i = 0; i < audio.length; i += 2) {
            float p = audio.length <= 2 ? 1f : i / (float) (audio.length - 2);
            float g = (float) Math.sin(p * Math.PI * .5);
            audio[i] *= g;
            audio[i + 1] *= g;
        }
    }

    private static void fadeOut(float[] audio) {
        for (int i = 0; i < audio.length; i += 2) {
            float p = audio.length <= 2 ? 1f : i / (float) (audio.length - 2);
            float g = (float) Math.cos(p * Math.PI * .5);
            audio[i] *= g;
            audio[i + 1] *= g;
        }
    }
}
