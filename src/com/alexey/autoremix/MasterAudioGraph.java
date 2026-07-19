package com.alexey.autoremix;

/**
 * Persistent 48 kHz stereo master graph. Own and call it from one audio thread.
 * Construction and metrics snapshots may allocate; block processing does not.
 */
final class MasterAudioGraph {
    static final int SAMPLE_RATE = 48_000;
    static final int CHANNELS = 2;
    static final int PROCESSOR_NODE_COUNT = 4;

    private static final float DC_COEFFICIENT = .995f;
    private static final float LIMITER_CEILING = .985f;
    private static final float SOFT_KNEE = .90f;
    private static final float SILENCE_THRESHOLD = 1e-5f;
    private static final int MINIMUM_GAP_FRAMES = 24;
    private static final int MARKER_CAPACITY = 32;
    private static final int ACTIVATION_METER_FRAMES = 2_048;

    private final int maximumBlockFrames;
    private final int lookaheadFrames;
    private final float limiterRelease;
    private final float[] delayLeft;
    private final float[] delayRight;
    private final float[] delayPeak;
    private final long[] markerIds = new long[MARKER_CAPACITY];
    private final long[] markerFrames = new long[MARKER_CAPACITY];
    private final float[] recentSignalEnergy = new float[ACTIVATION_METER_FRAMES];
    private final float[] recentDerivativeEnergy = new float[ACTIVATION_METER_FRAMES];

    private int delayWrite;
    private int delayCount;
    private float previousInputLeft;
    private float previousInputRight;
    private float dcOutputLeft;
    private float dcOutputRight;
    private float limiterGain = 1f;

    private long masterFrame;
    private long processedBlocks;
    private long underrunEvents;
    private long underrunFrames;
    private long graphRecreations;
    private long nodeRecreations;

    private long markerCount;
    private int markerWrite;
    private long lastMarkerId = -1L;
    private long lastMarkerFrame = -1L;
    private long pendingActivationFrame = -1L;
    private boolean activationMeterStarted;
    private boolean activationGapOpen;
    private long currentActivationGapFrames;
    private long activationGapFrames;
    private long maximumActivationGapFrames;
    private long markerActivationGapFrames;
    private long markerMaximumActivationGapFrames;

    private boolean havePreviousOutput;
    private boolean havePreviousDerivative;
    private float previousOutputLeft;
    private float previousOutputRight;
    private float previousDerivativeLeft;
    private float previousDerivativeRight;
    private float maximumSampleDiscontinuity;
    private float maximumDerivativeDiscontinuity;
    private double signalEnergy;
    private double derivativeEnergy;
    private long meteredFrames;
    private int recentMeterWrite;
    private int recentMeterCount;
    private double recentSignalEnergySum;
    private double recentDerivativeEnergySum;
    private long activationPreFrames;
    private double activationPreSignalEnergy;
    private double activationPreDerivativeEnergy;
    private long activationPostFrames;
    private double activationPostSignalEnergy;
    private double activationPostDerivativeEnergy;
    private float activationMaximumSampleDiscontinuity;
    private float activationMaximumDerivativeDiscontinuity;

    MasterAudioGraph(int maximumBlockFrames, int lookaheadFrames) {
        if (maximumBlockFrames <= 0) throw new IllegalArgumentException("maximumBlockFrames");
        if (lookaheadFrames < 0 || lookaheadFrames > maximumBlockFrames) {
            throw new IllegalArgumentException("lookaheadFrames");
        }
        this.maximumBlockFrames = maximumBlockFrames;
        this.lookaheadFrames = lookaheadFrames;
        this.limiterRelease = 1f - (float) Math.exp(-1f / (SAMPLE_RATE * .120f));
        int delayCapacity = Math.max(1, lookaheadFrames);
        delayLeft = new float[delayCapacity];
        delayRight = new float[delayCapacity];
        delayPeak = new float[delayCapacity];
    }

    int maximumBlockFrames() {
        return maximumBlockFrames;
    }

    int latencyFrames() {
        return lookaheadFrames;
    }

    long sampleClock() {
        return masterFrame;
    }

    /** Mark logical activation now; continuity metering compensates limiter latency. */
    long markTransitionActivation(long transitionId) {
        long activationFrame = masterFrame;
        markerIds[markerWrite] = transitionId;
        markerFrames[markerWrite] = activationFrame;
        markerWrite = (markerWrite + 1) % MARKER_CAPACITY;
        markerCount++;
        lastMarkerId = transitionId;
        lastMarkerFrame = activationFrame;
        pendingActivationFrame = activationFrame + lookaheadFrames;
        activationMeterStarted = false;
        return activationFrame;
    }

    private void beginActivationMeter() {
        activationGapOpen = true;
        currentActivationGapFrames = 0L;
        activationPreFrames = recentMeterCount;
        activationPreSignalEnergy = recentSignalEnergySum;
        activationPreDerivativeEnergy = recentDerivativeEnergySum;
        activationPostFrames = 0L;
        activationPostSignalEnergy = 0.0;
        activationPostDerivativeEnergy = 0.0;
        activationMaximumSampleDiscontinuity = 0f;
        activationMaximumDerivativeDiscontinuity = 0f;
        markerActivationGapFrames = 0L;
        markerMaximumActivationGapFrames = 0L;
        activationMeterStarted = true;
        pendingActivationFrame = -1L;
    }

    int retainedTransitionMarkers() {
        return (int) Math.min(markerCount, MARKER_CAPACITY);
    }

    long transitionIdFromNewest(int offset) {
        return markerIds[markerIndexFromNewest(offset)];
    }

    long transitionFrameFromNewest(int offset) {
        return markerFrames[markerIndexFromNewest(offset)];
    }

    /**
     * Process interleaved stereo in place. Set inputAvailable=false to render clocked silence and
     * record one output underrun event.
     */
    void processBlock(float[] interleaved, int offsetFrames, int frameCount,
                      boolean inputAvailable) {
        validateBlock(interleaved, offsetFrames, frameCount);
        if (!inputAvailable && frameCount > 0) {
            underrunEvents++;
            underrunFrames += frameCount;
        }
        int sample = offsetFrames * CHANNELS;
        for (int frame = 0; frame < frameCount; frame++, sample += CHANNELS) {
            float inputLeft = inputAvailable ? finiteOrZero(interleaved[sample]) : 0f;
            float inputRight = inputAvailable ? finiteOrZero(interleaved[sample + 1]) : 0f;

            float filteredLeft = inputLeft - previousInputLeft + DC_COEFFICIENT * dcOutputLeft;
            float filteredRight = inputRight - previousInputRight + DC_COEFFICIENT * dcOutputRight;
            previousInputLeft = inputLeft;
            previousInputRight = inputRight;
            dcOutputLeft = filteredLeft;
            dcOutputRight = filteredRight;

            float outputLeft;
            float outputRight;
            float lookaheadPeak;
            if (lookaheadFrames == 0) {
                outputLeft = filteredLeft;
                outputRight = filteredRight;
                lookaheadPeak = Math.max(Math.abs(filteredLeft), Math.abs(filteredRight));
            } else if (delayCount < lookaheadFrames) {
                delayLeft[delayWrite] = filteredLeft;
                delayRight[delayWrite] = filteredRight;
                delayPeak[delayWrite] = Math.max(Math.abs(filteredLeft), Math.abs(filteredRight));
                delayWrite = (delayWrite + 1) % lookaheadFrames;
                delayCount++;
                outputLeft = 0f;
                outputRight = 0f;
                lookaheadPeak = 0f;
            } else {
                outputLeft = delayLeft[delayWrite];
                outputRight = delayRight[delayWrite];
                lookaheadPeak = Math.max(Math.abs(filteredLeft), Math.abs(filteredRight));
                for (int i = 0; i < lookaheadFrames; i++) {
                    lookaheadPeak = Math.max(lookaheadPeak, delayPeak[i]);
                }
                delayLeft[delayWrite] = filteredLeft;
                delayRight[delayWrite] = filteredRight;
                delayPeak[delayWrite] = Math.max(Math.abs(filteredLeft), Math.abs(filteredRight));
                delayWrite = (delayWrite + 1) % lookaheadFrames;
            }

            float targetGain = lookaheadPeak > LIMITER_CEILING
                    ? LIMITER_CEILING / lookaheadPeak : 1f;
            if (targetGain < limiterGain) limiterGain = targetGain;
            else limiterGain += (targetGain - limiterGain) * limiterRelease;
            outputLeft = softLimit(outputLeft * limiterGain);
            outputRight = softLimit(outputRight * limiterGain);
            interleaved[sample] = outputLeft;
            interleaved[sample + 1] = outputRight;
            meter(outputLeft, outputRight);
            masterFrame++;
        }
        processedBlocks++;
    }

    void processBlock(float[] interleaved, int offsetFrames, int frameCount) {
        processBlock(interleaved, offsetFrames, frameCount, true);
    }

    /** Record an output restart. Transitions must never call this. */
    void restartProcessingNodes() {
        graphRecreations++;
        nodeRecreations += PROCESSOR_NODE_COUNT;
        delayWrite = 0;
        delayCount = 0;
        previousInputLeft = 0f;
        previousInputRight = 0f;
        dcOutputLeft = 0f;
        dcOutputRight = 0f;
        limiterGain = 1f;
        for (int i = 0; i < delayLeft.length; i++) {
            delayLeft[i] = 0f;
            delayRight[i] = 0f;
            delayPeak[i] = 0f;
        }
    }

    ContinuityMetrics snapshotMetrics() {
        long openGap = activationGapOpen && currentActivationGapFrames >= MINIMUM_GAP_FRAMES
                ? currentActivationGapFrames : 0L;
        long maxGap = Math.max(maximumActivationGapFrames, openGap);
        double meanSquare = meteredFrames == 0L ? 0.0 : signalEnergy / (meteredFrames * 2.0);
        float lufsProxy = meanSquare <= 1e-12
                ? -120f : (float) (-.691 + 10.0 * Math.log10(meanSquare));
        float spectralProxy = signalEnergy <= 1e-12
                ? 0f : (float) Math.sqrt(derivativeEnergy / signalEnergy);
        float activationLufsJump = Math.abs(levelDb(activationPreSignalEnergy,
                activationPreFrames) - levelDb(activationPostSignalEnergy,
                activationPostFrames));
        float activationSpectralFluxSpike = Math.abs(spectralProxy(
                activationPreDerivativeEnergy, activationPreSignalEnergy)
                - spectralProxy(activationPostDerivativeEnergy,
                activationPostSignalEnergy));
        return new ContinuityMetrics(masterFrame, processedBlocks, underrunEvents, underrunFrames,
                markerCount, lastMarkerId, lastMarkerFrame, activationGapFrames + openGap, maxGap,
                markerActivationGapFrames + openGap,
                Math.max(markerMaximumActivationGapFrames, openGap),
                maximumSampleDiscontinuity, maximumDerivativeDiscontinuity, lufsProxy,
                spectralProxy, activationMaximumSampleDiscontinuity,
                activationMaximumDerivativeDiscontinuity, activationLufsJump,
                activationSpectralFluxSpike, graphRecreations, nodeRecreations);
    }

    private int markerIndexFromNewest(int offset) {
        int retained = retainedTransitionMarkers();
        if (offset < 0 || offset >= retained) throw new IndexOutOfBoundsException("offset");
        int index = markerWrite - 1 - offset;
        if (index < 0) index += MARKER_CAPACITY;
        return index;
    }

    private void validateBlock(float[] interleaved, int offsetFrames, int frameCount) {
        if (interleaved == null || offsetFrames < 0 || frameCount < 0
                || frameCount > maximumBlockFrames
                || offsetFrames > interleaved.length / CHANNELS - frameCount) {
            throw new IllegalArgumentException("invalid stereo block");
        }
    }

    private void meter(float left, float right) {
        if (pendingActivationFrame >= 0L && masterFrame >= pendingActivationFrame) {
            beginActivationMeter();
        }
        if (activationGapOpen) {
            if (Math.max(Math.abs(left), Math.abs(right)) <= SILENCE_THRESHOLD) {
                currentActivationGapFrames++;
            } else {
                if (currentActivationGapFrames >= MINIMUM_GAP_FRAMES) {
                    activationGapFrames += currentActivationGapFrames;
                    maximumActivationGapFrames = Math.max(maximumActivationGapFrames,
                            currentActivationGapFrames);
                    markerActivationGapFrames += currentActivationGapFrames;
                    markerMaximumActivationGapFrames = Math.max(
                            markerMaximumActivationGapFrames, currentActivationGapFrames);
                }
                activationGapOpen = false;
            }
        }
        float frameSignalEnergy = left * left + right * right;
        float frameDerivativeEnergy = 0f;
        signalEnergy += frameSignalEnergy;
        meteredFrames++;
        if (havePreviousOutput) {
            float derivativeLeft = left - previousOutputLeft;
            float derivativeRight = right - previousOutputRight;
            frameDerivativeEnergy = derivativeLeft * derivativeLeft
                    + derivativeRight * derivativeRight;
            maximumSampleDiscontinuity = Math.max(maximumSampleDiscontinuity,
                    Math.max(Math.abs(derivativeLeft), Math.abs(derivativeRight)));
            derivativeEnergy += frameDerivativeEnergy;
            if (activationMeterStarted
                    && activationPostFrames < ACTIVATION_METER_FRAMES) {
                activationMaximumSampleDiscontinuity = Math.max(
                        activationMaximumSampleDiscontinuity,
                        Math.max(Math.abs(derivativeLeft), Math.abs(derivativeRight)));
            }
            if (havePreviousDerivative) {
                maximumDerivativeDiscontinuity = Math.max(maximumDerivativeDiscontinuity,
                        Math.max(Math.abs(derivativeLeft - previousDerivativeLeft),
                                Math.abs(derivativeRight - previousDerivativeRight)));
                if (activationMeterStarted
                        && activationPostFrames < ACTIVATION_METER_FRAMES) {
                    activationMaximumDerivativeDiscontinuity = Math.max(
                            activationMaximumDerivativeDiscontinuity,
                            Math.max(Math.abs(derivativeLeft - previousDerivativeLeft),
                                    Math.abs(derivativeRight - previousDerivativeRight)));
                }
            }
            previousDerivativeLeft = derivativeLeft;
            previousDerivativeRight = derivativeRight;
            havePreviousDerivative = true;
        }
        previousOutputLeft = left;
        previousOutputRight = right;
        havePreviousOutput = true;
        if (activationMeterStarted && activationPostFrames < ACTIVATION_METER_FRAMES) {
            activationPostSignalEnergy += frameSignalEnergy;
            activationPostDerivativeEnergy += frameDerivativeEnergy;
            activationPostFrames++;
        }
        updateRecentMeter(frameSignalEnergy, frameDerivativeEnergy);
    }

    private void updateRecentMeter(float frameSignalEnergy, float frameDerivativeEnergy) {
        if (recentMeterCount == ACTIVATION_METER_FRAMES) {
            recentSignalEnergySum -= recentSignalEnergy[recentMeterWrite];
            recentDerivativeEnergySum -= recentDerivativeEnergy[recentMeterWrite];
        } else {
            recentMeterCount++;
        }
        recentSignalEnergy[recentMeterWrite] = frameSignalEnergy;
        recentDerivativeEnergy[recentMeterWrite] = frameDerivativeEnergy;
        recentSignalEnergySum += frameSignalEnergy;
        recentDerivativeEnergySum += frameDerivativeEnergy;
        recentMeterWrite = (recentMeterWrite + 1) % ACTIVATION_METER_FRAMES;
    }

    private static float levelDb(double energy, long frames) {
        double meanSquare = frames <= 0L ? 0.0 : energy / (frames * 2.0);
        return meanSquare <= 1e-12 ? -120f
                : (float) (-.691 + 10.0 * Math.log10(meanSquare));
    }

    private static float spectralProxy(double derivative, double signal) {
        return signal <= 1e-12 ? 0f : (float) Math.sqrt(derivative / signal);
    }

    private static float finiteOrZero(float value) {
        return Float.isFinite(value) ? value : 0f;
    }

    private static float softLimit(float value) {
        float magnitude = Math.abs(value);
        if (magnitude <= SOFT_KNEE) return value;
        float normalized = (magnitude - SOFT_KNEE) / (LIMITER_CEILING - SOFT_KNEE);
        float shaped = SOFT_KNEE + (LIMITER_CEILING - SOFT_KNEE)
                * normalized / (1f + normalized);
        shaped = Math.min(LIMITER_CEILING, shaped);
        return Math.copySign(shaped, value);
    }

    static final class ContinuityMetrics {
        final long masterFrame;
        final long processedBlocks;
        final long underrunEvents;
        final long underrunFrames;
        final long transitionActivations;
        final long lastTransitionId;
        final long lastActivationFrame;
        final long activationGapFrames;
        final long maximumActivationGapFrames;
        final long currentActivationGapFrames;
        final long currentMaximumActivationGapFrames;
        final float maximumSampleDiscontinuity;
        final float maximumDerivativeDiscontinuity;
        final float lufsProxyDb;
        final float spectralProxy;
        final float activationMaximumSampleDiscontinuity;
        final float activationMaximumDerivativeDiscontinuity;
        final float activationLufsJump;
        final float activationSpectralFluxSpike;
        final long graphRecreations;
        final long nodeRecreations;

        private ContinuityMetrics(long masterFrame, long processedBlocks, long underrunEvents,
                                  long underrunFrames, long transitionActivations,
                                  long lastTransitionId, long lastActivationFrame,
                                  long activationGapFrames, long maximumActivationGapFrames,
                                  long currentActivationGapFrames,
                                  long currentMaximumActivationGapFrames,
                                  float maximumSampleDiscontinuity,
                                  float maximumDerivativeDiscontinuity, float lufsProxyDb,
                                  float spectralProxy,
                                  float activationMaximumSampleDiscontinuity,
                                  float activationMaximumDerivativeDiscontinuity,
                                  float activationLufsJump,
                                  float activationSpectralFluxSpike,
                                  long graphRecreations, long nodeRecreations) {
            this.masterFrame = masterFrame;
            this.processedBlocks = processedBlocks;
            this.underrunEvents = underrunEvents;
            this.underrunFrames = underrunFrames;
            this.transitionActivations = transitionActivations;
            this.lastTransitionId = lastTransitionId;
            this.lastActivationFrame = lastActivationFrame;
            this.activationGapFrames = activationGapFrames;
            this.maximumActivationGapFrames = maximumActivationGapFrames;
            this.currentActivationGapFrames = currentActivationGapFrames;
            this.currentMaximumActivationGapFrames = currentMaximumActivationGapFrames;
            this.maximumSampleDiscontinuity = maximumSampleDiscontinuity;
            this.maximumDerivativeDiscontinuity = maximumDerivativeDiscontinuity;
            this.lufsProxyDb = lufsProxyDb;
            this.spectralProxy = spectralProxy;
            this.activationMaximumSampleDiscontinuity =
                    activationMaximumSampleDiscontinuity;
            this.activationMaximumDerivativeDiscontinuity =
                    activationMaximumDerivativeDiscontinuity;
            this.activationLufsJump = activationLufsJump;
            this.activationSpectralFluxSpike = activationSpectralFluxSpike;
            this.graphRecreations = graphRecreations;
            this.nodeRecreations = nodeRecreations;
        }
    }
}
