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
    final long activationBoundary;
    final int candidateLevel;
    final boolean validCandidate;
    final int planGeneration;
    final int planEpoch;

    RenderedScene(PcmAudio audio, Track anchorTrack, TrackAnalysis anchorAnalysis,
                  TrackAnalysis.Fragment anchorFragment, long anchorPositionMs,
                  String title, String description, boolean transitionScene) {
        this(audio, anchorTrack, anchorAnalysis, anchorFragment, anchorPositionMs,
                title, description, transitionScene, -1L, -1, false, -1, -1);
    }

    private RenderedScene(PcmAudio audio, Track anchorTrack, TrackAnalysis anchorAnalysis,
                          TrackAnalysis.Fragment anchorFragment, long anchorPositionMs,
                          String title, String description, boolean transitionScene,
                          long activationBoundary, int candidateLevel, boolean validCandidate,
                          int planGeneration, int planEpoch) {
        this.audio = audio;
        this.anchorTrack = anchorTrack;
        this.anchorAnalysis = anchorAnalysis;
        this.anchorFragment = anchorFragment;
        this.anchorPositionMs = Math.max(0L, anchorPositionMs);
        this.title = title;
        this.description = description;
        this.transitionScene = transitionScene;
        this.activationBoundary = activationBoundary;
        this.candidateLevel = candidateLevel;
        this.validCandidate = validCandidate;
        this.planGeneration = planGeneration;
        this.planEpoch = planEpoch;
    }

    RenderedScene asCandidate(long boundary, int level, int generation, int epoch) {
        boolean valid = transitionScene && boundary >= 0L && level >= 0 && audio != null
                && generation >= 0 && epoch >= 0
                && audio.sampleRate == SceneAudioPlayer.outputRate()
                && audio.frames() >= audio.sampleRate * 2;
        if (valid) {
            for (float sample : audio.stereo) {
                if (!Float.isFinite(sample)) {
                    valid = false;
                    break;
                }
            }
        }
        return new RenderedScene(audio, anchorTrack, anchorAnalysis, anchorFragment,
                anchorPositionMs, title, description, transitionScene,
                boundary, level, valid, generation, epoch);
    }

    RenderedScene forPlan(int generation, int epoch) {
        return new RenderedScene(audio, anchorTrack, anchorAnalysis, anchorFragment,
                anchorPositionMs, title, description, transitionScene,
                activationBoundary, candidateLevel, validCandidate, generation, epoch);
    }

    boolean belongsToPlan(int generation, int epoch) {
        return planGeneration == generation && planEpoch == epoch;
    }
}
