package com.alexey.autoremix;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable sample-clock projection of an active or prepared transition. */
public final class TransitionUiState {
    public static final TransitionUiState EMPTY = new TransitionUiState(
            -1L, -1L, -1L, PlaybackPhase.IDLE, 0f, List.of(),
            0L, 0L, 0L, 0f, "", "", 0L, 0L, 1L);

    public final long transitionId;
    public final long sourceTrackId;
    public final long targetTrackId;
    public final PlaybackPhase phase;
    public final float progress;
    public final List<AudioTransformationEvent> activeStemOperations;
    public final long sourcePositionMs;
    public final long targetPositionMs;
    public final long estimatedLandingPositionMs;
    public final float landingConfidence;
    public final String selectedAnchor;
    public final String humanReadableStatus;
    public final long audioSamplePosition;
    public final long transitionStartSample;
    public final long fullLandingSample;

    public TransitionUiState(
            long transitionId,
            long sourceTrackId,
            long targetTrackId,
            PlaybackPhase phase,
            float progress,
            List<AudioTransformationEvent> activeStemOperations,
            long sourcePositionMs,
            long targetPositionMs,
            long estimatedLandingPositionMs,
            float landingConfidence,
            String selectedAnchor,
            String humanReadableStatus,
            long audioSamplePosition,
            long transitionStartSample,
            long fullLandingSample) {
        this.transitionId = transitionId;
        this.sourceTrackId = sourceTrackId;
        this.targetTrackId = targetTrackId;
        this.phase = phase == null ? PlaybackPhase.IDLE : phase;
        this.progress = clamp01(progress);
        this.activeStemOperations = Collections.unmodifiableList(new ArrayList<>(
                activeStemOperations == null ? List.of() : activeStemOperations));
        this.sourcePositionMs = Math.max(0L, sourcePositionMs);
        this.targetPositionMs = Math.max(0L, targetPositionMs);
        this.estimatedLandingPositionMs = Math.max(0L, estimatedLandingPositionMs);
        this.landingConfidence = clamp01(landingConfidence);
        this.selectedAnchor = selectedAnchor == null ? "" : selectedAnchor;
        this.humanReadableStatus = humanReadableStatus == null ? "" : humanReadableStatus;
        this.audioSamplePosition = Math.max(0L, audioSamplePosition);
        this.transitionStartSample = Math.max(0L, transitionStartSample);
        this.fullLandingSample = Math.max(this.transitionStartSample + 1L, fullLandingSample);
    }

    private static float clamp01(float value) {
        return Float.isFinite(value) ? Math.max(0f, Math.min(1f, value)) : 0f;
    }
}
