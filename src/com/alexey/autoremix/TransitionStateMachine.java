package com.alexey.autoremix;

/** Control-plane transition lifecycle. No audio work runs here. */
final class TransitionStateMachine {
    enum State {
        IDLE,
        TARGET_SELECTED,
        PREPARING,
        FALLBACK_READY,
        NEURAL_CANDIDATES_PENDING,
        ARMED,
        WAITING_FOR_ACTIVATION_BOUNDARY,
        TRANSITIONING,
        LANDED,
        CANCELLED,
        FAILED
    }

    private State state = State.IDLE;
    private boolean validCandidate;
    private long activationBoundary = -1L;
    private int missedBoundaries;

    synchronized State state() {
        return state;
    }

    synchronized boolean validCandidate() {
        return validCandidate;
    }

    synchronized long activationBoundary() {
        return activationBoundary;
    }

    synchronized int missedBoundaries() {
        return missedBoundaries;
    }

    synchronized int readinessPercent() {
        switch (state) {
            case TARGET_SELECTED: return 12;
            case PREPARING: return 36;
            case FALLBACK_READY: return 68;
            case NEURAL_CANDIDATES_PENDING: return 76;
            case ARMED: return 92;
            case WAITING_FOR_ACTIVATION_BOUNDARY:
            case TRANSITIONING:
            case LANDED: return 100;
            default: return 0;
        }
    }

    synchronized void selectTarget() {
        require(State.IDLE, State.LANDED, State.CANCELLED, State.FAILED);
        validCandidate = false;
        activationBoundary = -1L;
        state = State.TARGET_SELECTED;
    }

    synchronized void reset() {
        validCandidate = false;
        activationBoundary = -1L;
        missedBoundaries = 0;
        state = State.IDLE;
    }

    synchronized void beginPreparing() {
        require(State.TARGET_SELECTED);
        state = State.PREPARING;
    }

    synchronized void fallbackReady(boolean technicallyValid) {
        require(State.PREPARING);
        if (!technicallyValid) throw new IllegalStateException("invalid fallback candidate");
        validCandidate = true;
        state = State.FALLBACK_READY;
    }

    synchronized void neuralCandidatesPending() {
        require(State.FALLBACK_READY);
        state = State.NEURAL_CANDIDATES_PENDING;
    }

    synchronized void arm(long boundary) {
        require(State.FALLBACK_READY, State.NEURAL_CANDIDATES_PENDING);
        if (!validCandidate) throw new IllegalStateException("cannot arm without valid candidate");
        if (boundary < 0L) throw new IllegalArgumentException("negative activation boundary");
        activationBoundary = boundary;
        state = State.ARMED;
    }

    synchronized void waitForActivationBoundary() {
        require(State.ARMED);
        state = State.WAITING_FOR_ACTIVATION_BOUNDARY;
    }

    synchronized boolean activateAtBoundary(long boundary) {
        require(State.WAITING_FOR_ACTIVATION_BOUNDARY);
        if (!validCandidate) throw new IllegalStateException("cannot transition without valid candidate");
        if (boundary != activationBoundary) return false;
        state = State.TRANSITIONING;
        return true;
    }

    synchronized void rollToNextBoundary(long boundary) {
        require(State.WAITING_FOR_ACTIVATION_BOUNDARY);
        if (boundary <= activationBoundary) {
            throw new IllegalArgumentException("next boundary must move forward");
        }
        activationBoundary = boundary;
        missedBoundaries++;
    }

    synchronized void land() {
        require(State.TRANSITIONING);
        validCandidate = false;
        activationBoundary = -1L;
        state = State.LANDED;
    }

    synchronized void cancel() {
        validCandidate = false;
        activationBoundary = -1L;
        state = State.CANCELLED;
    }

    synchronized void fail() {
        validCandidate = false;
        activationBoundary = -1L;
        state = State.FAILED;
    }

    private void require(State... allowed) {
        for (State candidate : allowed) {
            if (state == candidate) return;
        }
        throw new IllegalStateException("state " + state + " does not allow this operation");
    }
}
