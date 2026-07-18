package com.alexey.autoremix;

/** One fully rendered, click-safe PCM programme segment and the anchor state at its end. */
final class RenderedScene {
    final PcmAudio audio;
    final Track anchorTrack;
    final TrackAnalysis anchorAnalysis;
    final TrackAnalysis.Fragment anchorFragment;
    final long anchorPositionMs;
    final String title;
    final String description;
    final boolean transitionScene;

    RenderedScene(PcmAudio audio, Track anchorTrack, TrackAnalysis anchorAnalysis,
                  TrackAnalysis.Fragment anchorFragment, long anchorPositionMs,
                  String title, String description, boolean transitionScene) {
        this.audio = audio;
        this.anchorTrack = anchorTrack;
        this.anchorAnalysis = anchorAnalysis;
        this.anchorFragment = anchorFragment;
        this.anchorPositionMs = Math.max(0L, anchorPositionMs);
        this.title = title;
        this.description = description;
        this.transitionScene = transitionScene;
    }
}
