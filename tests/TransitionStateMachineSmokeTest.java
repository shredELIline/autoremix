package com.alexey.autoremix;

public final class TransitionStateMachineSmokeTest {
    public static void main(String[] args) {
        validFlowRequiresCandidateAndBoundary();
        missedBoundaryRollsForward();
        invalidCandidateCannotArm();
        cancellationCanStartAReplacementRequest();
        System.out.println("Transition state machine OK");
    }

    private static void validFlowRequiresCandidateAndBoundary() {
        TransitionStateMachine machine = new TransitionStateMachine();
        machine.selectTarget();
        machine.beginPreparing();
        machine.candidateReady(true);
        machine.neuralCandidatesPending();
        machine.arm(40L);
        machine.waitForActivationBoundary();
        if (machine.activateAtBoundary(39L)) throw new AssertionError("activated before boundary");
        if (!machine.activateAtBoundary(40L)) throw new AssertionError("valid boundary rejected");
        machine.land();
        if (machine.state() != TransitionStateMachine.State.LANDED) {
            throw new AssertionError("landing state missing");
        }
    }

    private static void missedBoundaryRollsForward() {
        TransitionStateMachine machine = armedAt(12L);
        if (machine.activateAtBoundary(16L)) throw new AssertionError("missed boundary activated late");
        machine.rollToNextBoundary(16L);
        if (machine.missedBoundaries() != 1 || !machine.activateAtBoundary(16L)) {
            throw new AssertionError("next boundary was not used");
        }
    }

    private static void invalidCandidateCannotArm() {
        TransitionStateMachine machine = new TransitionStateMachine();
        machine.selectTarget();
        machine.beginPreparing();
        boolean rejected = false;
        try {
            machine.candidateReady(false);
        } catch (IllegalStateException expected) {
            rejected = true;
        }
        if (!rejected || machine.state() != TransitionStateMachine.State.PREPARING) {
            throw new AssertionError("invalid fallback was accepted");
        }
    }

    private static void cancellationCanStartAReplacementRequest() {
        TransitionStateMachine machine = armedAt(8L);
        machine.cancel();
        if (machine.state() != TransitionStateMachine.State.CANCELLED
                || machine.validCandidate()) {
            throw new AssertionError("cancellation kept an armed candidate");
        }
        machine.selectTarget();
        if (machine.state() != TransitionStateMachine.State.TARGET_SELECTED) {
            throw new AssertionError("replacement request rejected");
        }
    }

    private static TransitionStateMachine armedAt(long boundary) {
        TransitionStateMachine machine = new TransitionStateMachine();
        machine.selectTarget();
        machine.beginPreparing();
        machine.candidateReady(true);
        machine.arm(boundary);
        machine.waitForActivationBoundary();
        return machine;
    }
}
