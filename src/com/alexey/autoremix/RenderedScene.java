package com.alexey.autoremix;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One prepared programme segment and the anchor state at its end. */
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
    final ContinuousSceneTransitionPlan continuousPlan;
    final PreparedStemScene preparedStemScene;
    final SceneTimelineMapping timelineMapping;
    final List<AudioTransformationEvent> transformationEvents;

    RenderedScene(PcmAudio audio, Track anchorTrack, TrackAnalysis anchorAnalysis,
                  TrackAnalysis.Fragment anchorFragment, long anchorPositionMs,
                  String title, String description, boolean transitionScene) {
        this(audio, anchorTrack, anchorAnalysis, anchorFragment, anchorPositionMs,
                title, description, transitionScene, -1L, -1, false, -1, -1,
                null, null, null, List.of());
    }

    private RenderedScene(PcmAudio audio, Track anchorTrack, TrackAnalysis anchorAnalysis,
                          TrackAnalysis.Fragment anchorFragment, long anchorPositionMs,
                          String title, String description, boolean transitionScene,
                          long activationBoundary, int candidateLevel, boolean validCandidate,
                           int planGeneration, int planEpoch,
                           ContinuousSceneTransitionPlan continuousPlan,
                           PreparedStemScene preparedStemScene,
                           SceneTimelineMapping timelineMapping,
                           List<AudioTransformationEvent> transformationEvents) {
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
        this.continuousPlan = continuousPlan;
        this.preparedStemScene = preparedStemScene;
        this.timelineMapping = timelineMapping == null
                ? defaultMapping(audio, anchorTrack, anchorPositionMs, preparedStemScene)
                : timelineMapping;
        this.transformationEvents = Collections.unmodifiableList(new ArrayList<>(
                transformationEvents == null ? List.of() : transformationEvents));
    }

    RenderedScene asCandidate(long boundary, int level, int generation, int epoch) {
        boolean valid = transitionScene && boundary >= 0L && level >= 0 && audio != null
                && generation >= 0 && epoch >= 0
                && sampleRate() == SceneAudioPlayer.outputRate()
                && frames() >= sampleRate() * 2;
        if (valid && preparedStemScene == null) {
            for (float sample : audio.stereo) {
                if (!Float.isFinite(sample)) {
                    valid = false;
                    break;
                }
            }
        }
        return new RenderedScene(audio, anchorTrack, anchorAnalysis, anchorFragment,
                anchorPositionMs, title, description, transitionScene,
                boundary, level, valid, generation, epoch, continuousPlan,
                preparedStemScene, timelineMapping, transformationEvents);
    }

    RenderedScene forPlan(int generation, int epoch) {
        return new RenderedScene(audio, anchorTrack, anchorAnalysis, anchorFragment,
                anchorPositionMs, title, description, transitionScene,
                activationBoundary, candidateLevel, validCandidate, generation, epoch,
                continuousPlan, preparedStemScene, timelineMapping, transformationEvents);
    }

    RenderedScene withContinuousPlan(ContinuousSceneTransitionPlan plan) {
        if (!transitionScene || plan == null) {
            throw new IllegalArgumentException("continuous plan requires a transition scene");
        }
        return new RenderedScene(audio, anchorTrack, anchorAnalysis, anchorFragment,
                anchorPositionMs, title, description, true, activationBoundary,
                candidateLevel, validCandidate, planGeneration, planEpoch, plan,
                preparedStemScene, timelineMapping, transformationEvents);
    }

    RenderedScene withPreparedStemScene(PreparedStemScene prepared,
                                        ContinuousSceneTransitionPlan plan) {
        if (!transitionScene || prepared == null || plan == null) {
            throw new IllegalArgumentException("prepared stem scene requires a transition plan");
        }
        return new RenderedScene(new PcmAudio(prepared.sampleRate(), new float[0]),
                anchorTrack, anchorAnalysis, anchorFragment, anchorPositionMs,
                title, description, true, activationBoundary, candidateLevel,
                validCandidate, planGeneration, planEpoch, plan, prepared,
                timelineMapping, transformationEvents);
    }

    RenderedScene withTimelineMapping(SceneTimelineMapping mapping) {
        if (mapping == null) throw new IllegalArgumentException("mapping");
        return new RenderedScene(audio, anchorTrack, anchorAnalysis, anchorFragment,
                anchorPositionMs, title, description, transitionScene, activationBoundary,
                candidateLevel, validCandidate, planGeneration, planEpoch, continuousPlan,
                preparedStemScene, mapping, transformationEvents);
    }

    RenderedScene withTransformationEvents(List<AudioTransformationEvent> events) {
        return new RenderedScene(audio, anchorTrack, anchorAnalysis, anchorFragment,
                anchorPositionMs, title, description, transitionScene, activationBoundary,
                candidateLevel, validCandidate, planGeneration, planEpoch, continuousPlan,
                preparedStemScene, timelineMapping, events);
    }

    int sampleRate() {
        return preparedStemScene == null ? audio.sampleRate : preparedStemScene.sampleRate();
    }

    int frames() {
        return preparedStemScene == null ? audio.frames() : preparedStemScene.frames();
    }

    long durationMs() {
        return preparedStemScene == null ? audio.durationMs() : preparedStemScene.durationMs();
    }

    void renderFrames(long startFrame, int frameCount,
                      float[] destination, int destinationOffsetSamples) {
        if (startFrame < 0L || frameCount < 0 || startFrame > frames() - (long) frameCount
                || destination == null || destinationOffsetSamples < 0
                || destinationOffsetSamples > destination.length - frameCount * 2) {
            throw new IllegalArgumentException("invalid scene render range");
        }
        if (preparedStemScene != null) {
            preparedStemScene.render(destination, destinationOffsetSamples,
                    startFrame, frameCount);
        } else {
            System.arraycopy(audio.stereo, Math.toIntExact(startFrame * 2L), destination,
                    destinationOffsetSamples, frameCount * 2);
        }
    }

    void resetRenderPosition(long frame) {
        if (preparedStemScene != null) preparedStemScene.resetForSeek(frame);
    }

    long transitionId() {
        return continuousPlan == null ? Math.max(0L, activationBoundary)
                : continuousPlan.transitionId;
    }

    boolean belongsToPlan(int generation, int epoch) {
        return planGeneration == generation && planEpoch == epoch;
    }

    private static SceneTimelineMapping defaultMapping(
            PcmAudio audio, Track anchorTrack, long anchorPositionMs,
            PreparedStemScene preparedStemScene) {
        int sampleRate = preparedStemScene == null ? audio.sampleRate
                : preparedStemScene.sampleRate();
        int frames = preparedStemScene == null ? audio.frames() : preparedStemScene.frames();
        long durationMs = Math.round(frames * 1_000.0 / Math.max(1, sampleRate));
        long startMs = Math.max(0L, anchorPositionMs - durationMs);
        return SceneTimelineMapping.normal(anchorTrack.id, anchorTrack.durationMs,
                startMs, sampleRate, frames);
    }
}
