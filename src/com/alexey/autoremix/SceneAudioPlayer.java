package com.alexey.autoremix;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Process;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Single master AudioTrack. All decks and layers are already mixed in PCM before reaching it. */
final class SceneAudioPlayer {
    interface Listener {
        void onSceneStarted(RenderedScene scene);
        void onSceneFinished(RenderedScene scene);
        void onPlaybackError(String error);
    }

    private static final int OUTPUT_RATE = 48_000;
    private static final int BOUNDARY_MS = 14;
    private final LinkedBlockingQueue<RenderedScene> queue = new LinkedBlockingQueue<>(3);
    private final Listener listener;
    private volatile boolean running;
    private volatile boolean paused;
    private Thread thread;
    private AudioTrack track;

    SceneAudioPlayer(Listener listener) {
        this.listener = listener;
    }

    static int outputRate() {
        return OUTPUT_RATE;
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
        try {
            return queue.offer(scene, 300, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    int queuedScenes() {
        return queue.size();
    }

    void clearQueued() {
        queue.clear();
    }

    void pause() {
        paused = true;
        try { if (track != null) track.pause(); } catch (Throwable ignored) {}
    }

    void resume() {
        paused = false;
        try { if (track != null) track.play(); } catch (Throwable ignored) {}
        synchronized (this) { notifyAll(); }
    }

    void stop() {
        running = false;
        paused = false;
        queue.clear();
        synchronized (this) { notifyAll(); }
        if (thread != null) thread.interrupt();
        try { if (track != null) track.pause(); } catch (Throwable ignored) {}
        try { if (track != null) track.flush(); } catch (Throwable ignored) {}
        try { if (track != null) track.release(); } catch (Throwable ignored) {}
        track = null;
    }

    private void runLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        try {
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
            track.play();
            float[] pendingTail = null;
            RenderedScene previous = null;
            while (running) {
                waitIfPaused();
                RenderedScene scene = queue.poll(250, TimeUnit.MILLISECONDS);
                if (scene == null) {
                    if (pendingTail != null) {
                        fadeOut(pendingTail);
                        writeAll(pendingTail, 0, pendingTail.length);
                        pendingTail = null;
                        if (previous != null && listener != null) listener.onSceneFinished(previous);
                        previous = null;
                    }
                    continue;
                }
                if (listener != null) listener.onSceneStarted(scene);
                float[] audio = scene.audio.stereo;
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
                    if (previous != null && listener != null) listener.onSceneFinished(previous);
                } else if (overlapSamples > 0) {
                    float[] head = new float[overlapSamples];
                    System.arraycopy(audio, 0, head, 0, overlapSamples);
                    fadeIn(head);
                    writeAll(head, 0, head.length);
                }
                int start = overlapSamples;
                int end = Math.max(start, audio.length - overlapSamples);
                writeAll(audio, start, end - start);
                pendingTail = new float[audio.length - end];
                System.arraycopy(audio, end, pendingTail, 0, pendingTail.length);
                previous = scene;
            }
            if (pendingTail != null) {
                fadeOut(pendingTail);
                writeAll(pendingTail, 0, pendingTail.length);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Throwable error) {
            if (listener != null) listener.onPlaybackError(error.getClass().getSimpleName()
                    + ": " + String.valueOf(error.getMessage()));
        } finally {
            try { if (track != null) track.stop(); } catch (Throwable ignored) {}
            try { if (track != null) track.release(); } catch (Throwable ignored) {}
            track = null;
            running = false;
        }
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
            int wrote = track.write(audio, position, chunk, AudioTrack.WRITE_BLOCKING);
            if (wrote < 0) throw new IllegalStateException("AudioTrack write " + wrote);
            position += wrote;
        }
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
