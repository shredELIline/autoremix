package com.alexey.autoremix;

/** Immutable projection of one original song timeline for UI and MediaSession. */
public final class TrackPlaybackTimeline {
    public enum MarkerSource {
        AI_AUTOMATIC,
        USER_LOCKED
    }

    public enum MarkerStatus {
        CALCULATING,
        TENTATIVE,
        CONFIRMED,
        ACTIVE,
        PASSED,
        CANCELLED,
        NONE
    }

    public static final TrackPlaybackTimeline EMPTY = new TrackPlaybackTimeline(
            -1L, 1L, 0L, 0L, null, null, null, 0f,
            MarkerSource.AI_AUTOMATIC, MarkerStatus.NONE, false);

    public final long trackId;
    public final long durationMs;
    public final long entryPositionMs;
    public final long currentPositionMs;
    public final Long nextTransitionStartMs;
    public final Long plannedExitPositionMs;
    public final Long landingPositionFromPreviousMs;
    public final float timelineConfidence;
    public final MarkerSource markerSource;
    public final MarkerStatus markerStatus;
    public final boolean isUserEditable;

    public TrackPlaybackTimeline(
            long trackId,
            long durationMs,
            long entryPositionMs,
            long currentPositionMs,
            Long nextTransitionStartMs,
            Long plannedExitPositionMs,
            Long landingPositionFromPreviousMs,
            float timelineConfidence,
            MarkerSource markerSource,
            MarkerStatus markerStatus,
            boolean isUserEditable) {
        this.trackId = trackId;
        this.durationMs = Math.max(1L, durationMs);
        this.entryPositionMs = clamp(entryPositionMs, this.durationMs);
        this.currentPositionMs = clamp(currentPositionMs, this.durationMs);
        this.nextTransitionStartMs = nullableClamp(nextTransitionStartMs, this.durationMs);
        this.plannedExitPositionMs = nullableClamp(plannedExitPositionMs, this.durationMs);
        this.landingPositionFromPreviousMs = nullableClamp(
                landingPositionFromPreviousMs, this.durationMs);
        this.timelineConfidence = clamp01(timelineConfidence);
        this.markerSource = markerSource == null ? MarkerSource.AI_AUTOMATIC : markerSource;
        this.markerStatus = markerStatus == null ? MarkerStatus.NONE : markerStatus;
        this.isUserEditable = isUserEditable;
    }

    public TrackPlaybackTimeline withPosition(long positionMs) {
        return new TrackPlaybackTimeline(trackId, durationMs, entryPositionMs, positionMs,
                nextTransitionStartMs, plannedExitPositionMs, landingPositionFromPreviousMs,
                timelineConfidence, markerSource, markerStatus, isUserEditable);
    }

    public TrackPlaybackTimeline withMarker(
            Long transitionStartMs, Long exitPositionMs, MarkerStatus status) {
        return new TrackPlaybackTimeline(trackId, durationMs, entryPositionMs,
                currentPositionMs, transitionStartMs, exitPositionMs,
                landingPositionFromPreviousMs, timelineConfidence, markerSource,
                status, isUserEditable);
    }

    private static Long nullableClamp(Long value, long durationMs) {
        return value == null ? null : clamp(value, durationMs);
    }

    private static long clamp(long value, long durationMs) {
        return Math.max(0L, Math.min(durationMs, value));
    }

    private static float clamp01(float value) {
        return Float.isFinite(value) ? Math.max(0f, Math.min(1f, value)) : 0f;
    }
}
