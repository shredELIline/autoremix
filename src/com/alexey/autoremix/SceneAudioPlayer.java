package com.alexey.autoremix;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Process;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
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
    private static final int BOUNDARY_FRAMES = OUTPUT_RATE * BOUNDARY_MS / 1_000;
    private static final int OUTPUT_BLOCK_FRAMES = 2_048;
    private static final int LIMITER_LOOKAHEAD_FRAMES = 240;
    private static final int QUEUE_CAPACITY = 6;
    private final LinkedBlockingDeque<RenderedScene> queue = new LinkedBlockingDeque<>(QUEUE_CAPACITY);
    private final Object queueControl = new Object();
    private final Object outputControl = new Object();
    private final Listener listener;
    private final MasterAudioGraph masterGraph =
            new MasterAudioGraph(OUTPUT_BLOCK_FRAMES, LIMITER_LOOKAHEAD_FRAMES);
    private final float[] renderBlock = new float[OUTPUT_BLOCK_FRAMES * 2];
    private final float[] boundaryHead = new float[BOUNDARY_FRAMES * 2];
    private final float[] boundaryBlend = new float[BOUNDARY_FRAMES * 2];
    private final float[] boundaryTail = new float[BOUNDARY_FRAMES * 2];
    private volatile boolean running;
    private volatile boolean paused;
    private volatile long sceneFramesWritten;
    private volatile long sceneFramesTotal = 1L;
    private volatile long sceneClockOriginFrames;
    private volatile long sceneMasterOriginFrames;
    private volatile boolean sceneBoundaryOpen;
    private volatile RenderedScene clockScene;
    private volatile RenderedScene previousClockScene;
    private RenderedScene dequeuedPendingScene;
    private volatile long previousSceneFramesTotal = 1L;
    private volatile long previousSceneClockOriginFrames;
    private volatile long outputClockOffsetFrames;
    private volatile long requestedSeekMs = -1L;
    private volatile long requestedSeekScene;
    private volatile long sceneSerial;
    private volatile int queueUnderruns;
    private volatile int activationUnderrunBaseline;
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
            for (RenderedScene scene : queue) duration += scene.durationMs();
            return duration;
        }
    }

    long bufferedHorizonMs() {
        long currentEnd = sceneClockOriginFrames + sceneFramesTotal;
        long remainingFrames = Math.max(0L, currentEnd - outputClockFrames());
        return Math.round(remainingFrames * 1_000.0 / OUTPUT_RATE) + queuedDurationMs();
    }

    long transitionActivationSample(RenderedScene optionalRunway) {
        long origin;
        List<Long> predecessorFrames = new ArrayList<>();
        synchronized (queueControl) {
            if (dequeuedPendingScene != null) {
                origin = masterGraph.sampleClock();
                predecessorFrames.add((long) dequeuedPendingScene.frames());
            } else if (sceneSerial == 0L || clockScene == null || !sceneBoundaryOpen) {
                origin = masterGraph.sampleClock();
            } else {
                origin = sceneMasterOriginFrames;
                predecessorFrames.add(sceneFramesTotal);
            }
            for (RenderedScene scene : queue) predecessorFrames.add((long) scene.frames());
            if (optionalRunway != null) {
                predecessorFrames.add((long) optionalRunway.frames());
            }
        }
        long[] frames = new long[predecessorFrames.size()];
        for (int i = 0; i < frames.length; i++) frames[i] = predecessorFrames.get(i);
        return Math.max(masterGraph.sampleClock(),
                projectedActivationSample(origin, BOUNDARY_FRAMES, frames));
    }

    static long projectedActivationSample(long origin, long boundaryFrames,
                                          long... predecessorFrames) {
        long activation = Math.max(0L, origin);
        long overlapLimit = Math.max(0L, boundaryFrames);
        for (int i = 0; i < predecessorFrames.length; i++) {
            long current = Math.max(0L, predecessorFrames[i]);
            long next = i + 1 < predecessorFrames.length
                    ? Math.max(0L, predecessorFrames[i + 1]) : overlapLimit;
            long overlap = Math.min(overlapLimit, Math.min(current, next));
            activation += current - overlap;
        }
        return activation;
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

    int activationUnderruns() {
        return Math.max(0, underruns() - activationUnderrunBaseline);
    }

    MasterAudioGraph.ContinuityMetrics continuityMetrics() {
        return masterGraph.snapshotMetrics();
    }

    long masterSampleClock() {
        return masterGraph.sampleClock();
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
        return Math.round(currentFrame() * 1000.0 / OUTPUT_RATE);
    }

    long currentFrame() {
        return audibleSceneFrame(audibleScene());
    }

    RenderedScene audibleScene() {
        if (outputClockFrames() < sceneClockOriginFrames && previousClockScene != null) {
            return previousClockScene;
        }
        return clockScene;
    }

    long audibleSceneFrame() {
        return audibleSceneFrame(audibleScene());
    }

    long audibleSceneFrame(RenderedScene scene) {
        long clock = outputClockFrames();
        if (scene != null && scene == previousClockScene) {
            return Math.min(previousSceneFramesTotal,
                    Math.max(0L, clock - previousSceneClockOriginFrames));
        }
        if (scene != clockScene) return 0L;
        return Math.min(sceneFramesTotal, Math.max(0L, clock - sceneClockOriginFrames));
    }

    long audibleMasterSample() {
        return Math.max(0L, outputClockFrames() - masterGraph.latencyFrames());
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
            int pendingTailSamples = 0;
            RenderedScene previous = null;
            while (running) {
                waitIfPaused();
                RenderedScene scene;
                synchronized (queueControl) {
                    scene = queue.pollFirst();
                    dequeuedPendingScene = scene;
                }
                if (scene == null) {
                    if (pendingTailSamples > 0) {
                        fadeOut(boundaryTail, pendingTailSamples);
                        writeAll(boundaryTail, 0, pendingTailSamples);
                        synchronized (queueControl) {
                            sceneBoundaryOpen = false;
                        }
                        pendingTailSamples = 0;
                        if (previous != null && listener != null) listener.onSceneFinished(previous);
                        previous = null;
                        queueUnderruns++;
                    }
                    Thread.sleep(20L);
                    continue;
                }
                if (listener != null && !listener.canStartScene(scene)) {
                    synchronized (queueControl) {
                        if (dequeuedPendingScene == scene) dequeuedPendingScene = null;
                    }
                    continue;
                }
                if (scene.transitionScene) {
                    activationUnderrunBaseline = underruns();
                    masterGraph.markTransitionActivation(scene.transitionId());
                }
                long serial;
                synchronized (queueControl) {
                    previousClockScene = clockScene;
                    previousSceneClockOriginFrames = sceneClockOriginFrames;
                    previousSceneFramesTotal = sceneFramesTotal;
                    clockScene = scene;
                    serial = ++sceneSerial;
                    sceneFramesWritten = 0L;
                    sceneFramesTotal = Math.max(1L, scene.frames());
                    sceneMasterOriginFrames = masterGraph.sampleClock();
                    sceneClockOriginFrames = sceneMasterOriginFrames + masterGraph.latencyFrames();
                    sceneBoundaryOpen = true;
                    dequeuedPendingScene = null;
                }
                if (listener != null) listener.onSceneStarted(scene);
                int overlapSamples = Math.min(scene.frames() * 2,
                        BOUNDARY_FRAMES * 2);
                overlapSamples -= overlapSamples % 2;
                if (pendingTailSamples > 0) {
                    int count = Math.min(pendingTailSamples, overlapSamples);
                    scene.renderFrames(0L, count / 2, boundaryHead, 0);
                    for (int i = 0; i < count; i += 2) {
                        float p = count <= 2 ? 1f : i / (float) (count - 2);
                        float a = (float) Math.cos(p * Math.PI * .5);
                        float b = (float) Math.sin(p * Math.PI * .5);
                        boundaryBlend[i] = boundaryTail[i] * a + boundaryHead[i] * b;
                        boundaryBlend[i + 1] = boundaryTail[i + 1] * a
                                + boundaryHead[i + 1] * b;
                    }
                    writeAll(boundaryBlend, 0, count);
                    sceneFramesWritten += count / 2L;
                    if (previous != null && listener != null) listener.onSceneFinished(previous);
                } else if (overlapSamples > 0) {
                    scene.renderFrames(0L, overlapSamples / 2, boundaryHead, 0);
                    fadeIn(boundaryHead, overlapSamples);
                    writeAll(boundaryHead, 0, overlapSamples);
                    sceneFramesWritten += overlapSamples / 2L;
                }
                int overlapFrames = overlapSamples / 2;
                int start = overlapFrames;
                int end = Math.max(start, scene.frames() - overlapFrames);
                writeSceneBody(scene, start, end, serial);
                pendingTailSamples = (scene.frames() - end) * 2;
                scene.renderFrames(end, scene.frames() - end, boundaryTail, 0);
                previous = scene;
            }
            if (pendingTailSamples > 0) {
                fadeOut(boundaryTail, pendingTailSamples);
                writeAll(boundaryTail, 0, pendingTailSamples);
                synchronized (queueControl) {
                    sceneBoundaryOpen = false;
                }
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
            int chunk = Math.min(OUTPUT_BLOCK_FRAMES * 2, end - position);
            int wrote = processAndWrite(audio, position, chunk);
            if (wrote < 0) return;
            position += wrote;
        }
    }

    private void writeSceneBody(RenderedScene scene, int start, int end, long serial)
            throws InterruptedException {
        int position = start;
        while (running && position < end) {
            waitIfPaused();
            long seekMs = requestedSeekMs;
            if (seekMs >= 0L) {
                requestedSeekMs = -1L;
                if (requestedSeekScene != serial) continue;
                int target = (int) Math.min(end - 1L,
                        Math.max(start, Math.round(seekMs * OUTPUT_RATE / 1000.0)));
                synchronized (outputControl) {
                    NativeAudioEngine engine = nativeEngine;
                    if (engine != null) {
                        if (!engine.seek(target)) {
                            throw new IllegalStateException("Oboe seek rejected");
                        }
                    } else {
                        track.pause();
                        track.flush();
                    }
                    masterGraph.restartProcessingNodes();
                    scene.resetRenderPosition(target);
                    synchronized (queueControl) {
                        sceneClockOriginFrames = outputClockFrames()
                                + masterGraph.latencyFrames() - target;
                        sceneMasterOriginFrames = masterGraph.sampleClock() - target;
                    }
                    int consumed = writeSeekRamp(scene, target);
                    position = Math.min(end, target + consumed);
                    sceneFramesWritten = position;
                    if (engine != null) {
                        if (!engine.completeSeek()) {
                            throw new IllegalStateException("Oboe seek resume rejected");
                        }
                    } else if (!paused) {
                        track.play();
                    }
                }
            }
            int chunk = Math.min(OUTPUT_BLOCK_FRAMES, end - position);
            int wrote = renderAndWrite(scene, position, chunk);
            if (wrote < 0) return;
            position += wrote;
            sceneFramesWritten = position;
        }
    }

    private int renderAndWrite(RenderedScene scene, int startFrame, int frameCount)
            throws InterruptedException {
        scene.renderFrames(startFrame, frameCount, renderBlock, 0);
        masterGraph.processBlock(renderBlock, 0, frameCount);
        int writtenSamples = 0;
        int sampleCount = frameCount * 2;
        while (running && writtenSamples < sampleCount) {
            waitIfPaused();
            int amount = writeOutput(renderBlock, writtenSamples,
                    sampleCount - writtenSamples);
            if (amount < 0) {
                if (!running) return -1;
                throw new IllegalStateException("Audio output write " + amount);
            }
            writtenSamples += amount;
        }
        return writtenSamples / 2;
    }

    private int processAndWrite(float[] audio, int offset, int length)
            throws InterruptedException {
        int evenLength = length - length % 2;
        if (evenLength <= 0) return 0;
        masterGraph.processBlock(audio, offset / 2, evenLength / 2);
        int written = 0;
        while (running && written < evenLength) {
            waitIfPaused();
            int amount = writeOutput(audio, offset + written, evenLength - written);
            if (amount < 0) {
                if (!running) return -1;
                throw new IllegalStateException("Audio output write " + amount);
            }
            written += amount;
        }
        return written;
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

    private int writeSeekRamp(RenderedScene scene, int targetFrame) throws InterruptedException {
        int rampFrames = Math.min(OUTPUT_RATE * 16 / 1000,
                scene.frames() - targetFrame);
        if (rampFrames <= 1) return 0;
        float[] fadeIn = new float[rampFrames * 2];
        scene.renderFrames(targetFrame, rampFrames, fadeIn, 0);
        fadeIn(fadeIn);
        writeAll(fadeIn, 0, fadeIn.length);
        return rampFrames;
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
        fadeIn(audio, audio.length);
    }

    private static void fadeIn(float[] audio, int length) {
        for (int i = 0; i < length; i += 2) {
            float p = length <= 2 ? 1f : i / (float) (length - 2);
            float g = (float) Math.sin(p * Math.PI * .5);
            audio[i] *= g;
            audio[i + 1] *= g;
        }
    }

    private static void fadeOut(float[] audio) {
        fadeOut(audio, audio.length);
    }

    private static void fadeOut(float[] audio, int length) {
        for (int i = 0; i < length; i += 2) {
            float p = length <= 2 ? 1f : i / (float) (length - 2);
            float g = (float) Math.cos(p * Math.PI * .5);
            audio[i] *= g;
            audio[i + 1] *= g;
        }
    }
}
