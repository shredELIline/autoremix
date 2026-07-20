package com.alexey.autoremix;

/** User-visible playback lifecycle. */
public enum PlaybackPhase {
    IDLE,
    TRACK_PLAYBACK,
    TRANSITION_PREPARING,
    TRANSITION_ARMED,
    TRANSITION_ACTIVE,
    TRACK_LANDING,
    PAUSED,
    SEEKING,
    ERROR
}
