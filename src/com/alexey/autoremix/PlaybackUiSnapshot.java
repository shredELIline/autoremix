package com.alexey.autoremix;

/** Atomically published UI state. */
public final class PlaybackUiSnapshot {
    public static final PlaybackUiSnapshot IDLE = new PlaybackUiSnapshot(
            PlaybackPhase.IDLE, TrackPlaybackTimeline.EMPTY, TransitionUiState.EMPTY);

    public final PlaybackPhase phase;
    public final TrackPlaybackTimeline trackTimeline;
    public final TransitionUiState transitionUiState;

    public PlaybackUiSnapshot(
            PlaybackPhase phase,
            TrackPlaybackTimeline trackTimeline,
            TransitionUiState transitionUiState) {
        this.phase = phase == null ? PlaybackPhase.IDLE : phase;
        this.trackTimeline = trackTimeline == null
                ? TrackPlaybackTimeline.EMPTY : trackTimeline;
        this.transitionUiState = transitionUiState == null
                ? TransitionUiState.EMPTY : transitionUiState;
    }
}
