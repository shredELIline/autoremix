package com.alexey.autoremix;

/** Maps technical scene frames to original source/target song positions. */
public final class SceneTimelineMapping {
    public final long sourceTrackId;
    public final long targetTrackId;
    public final long sourceTrackDurationMs;
    public final long targetTrackDurationMs;
    public final long sourceStartPositionMs;
    public final long targetStartPositionMs;
    public final long targetLandingPositionMs;
    public final long sourceExitPositionMs;
    public final long transitionStartSample;
    public final long fullLandingSample;
    public final int sampleRate;
    public final float targetTempoRatio;
    public final boolean transition;

    private SceneTimelineMapping(
            long sourceTrackId,
            long targetTrackId,
            long sourceTrackDurationMs,
            long targetTrackDurationMs,
            long sourceStartPositionMs,
            long targetStartPositionMs,
            long targetLandingPositionMs,
            long sourceExitPositionMs,
            long transitionStartSample,
            long fullLandingSample,
            int sampleRate,
            float targetTempoRatio,
            boolean transition) {
        this.sourceTrackId = sourceTrackId;
        this.targetTrackId = targetTrackId;
        this.sourceTrackDurationMs = Math.max(1L, sourceTrackDurationMs);
        this.targetTrackDurationMs = Math.max(1L, targetTrackDurationMs);
        this.sourceStartPositionMs = clamp(sourceStartPositionMs, this.sourceTrackDurationMs);
        this.targetStartPositionMs = clamp(targetStartPositionMs, this.targetTrackDurationMs);
        this.targetLandingPositionMs = clamp(targetLandingPositionMs,
                this.targetTrackDurationMs);
        this.sourceExitPositionMs = clamp(sourceExitPositionMs, this.sourceTrackDurationMs);
        this.transitionStartSample = Math.max(0L, transitionStartSample);
        this.fullLandingSample = Math.max(this.transitionStartSample + 1L, fullLandingSample);
        this.sampleRate = Math.max(1, sampleRate);
        this.targetTempoRatio = Float.isFinite(targetTempoRatio)
                ? Math.max(.25f, Math.min(4f, targetTempoRatio)) : 1f;
        this.transition = transition;
    }

    public static SceneTimelineMapping normal(
            long trackId, long trackDurationMs, long startPositionMs, int sampleRate,
            long sceneFrames) {
        long end = startPositionMs + framesToMs(sceneFrames, sampleRate);
        return new SceneTimelineMapping(trackId, trackId, trackDurationMs, trackDurationMs,
                startPositionMs, startPositionMs, end, end,
                0L, Math.max(1L, sceneFrames), sampleRate, 1f, false);
    }

    public static SceneTimelineMapping transition(
            long sourceTrackId,
            long targetTrackId,
            long sourceTrackDurationMs,
            long targetTrackDurationMs,
            long sourceStartPositionMs,
            long targetStartPositionMs,
            long targetLandingPositionMs,
            long sourceExitPositionMs,
            long transitionStartSample,
            long fullLandingSample,
            int sampleRate,
            float targetTempoRatio) {
        return new SceneTimelineMapping(sourceTrackId, targetTrackId,
                sourceTrackDurationMs, targetTrackDurationMs, sourceStartPositionMs,
                targetStartPositionMs, targetLandingPositionMs, sourceExitPositionMs,
                transitionStartSample, fullLandingSample, sampleRate, targetTempoRatio, true);
    }

    public long normalPositionMs(long sceneFrame) {
        return clamp(sourceStartPositionMs + framesToMs(sceneFrame, sampleRate),
                sourceTrackDurationMs);
    }

    public long sourcePositionMs(long masterSample) {
        long elapsed = Math.max(0L, masterSample - transitionStartSample);
        return Math.min(sourceExitPositionMs,
                clamp(sourceStartPositionMs + framesToMs(elapsed, sampleRate),
                        sourceTrackDurationMs));
    }

    public long targetPositionMs(long masterSample) {
        long elapsed = Math.max(0L, masterSample - transitionStartSample);
        long consumed = Math.round(framesToMs(elapsed, sampleRate) * targetTempoRatio);
        return clamp(targetStartPositionMs + consumed, targetTrackDurationMs);
    }

    public boolean fullLandingReached(long masterSample) {
        return transition && masterSample >= fullLandingSample;
    }

    SceneTimelineMapping withMasterRange(long startSample, long landingSample) {
        return new SceneTimelineMapping(sourceTrackId, targetTrackId,
                sourceTrackDurationMs, targetTrackDurationMs, sourceStartPositionMs,
                targetStartPositionMs, targetLandingPositionMs, sourceExitPositionMs,
                startSample, landingSample, sampleRate, targetTempoRatio, transition);
    }

    private static long framesToMs(long frames, int sampleRate) {
        return Math.round(Math.max(0L, frames) * 1_000.0 / Math.max(1, sampleRate));
    }

    private static long clamp(long value, long durationMs) {
        return Math.max(0L, Math.min(durationMs, value));
    }
}
