package com.alexey.autoremix;

import org.junit.Test;

/** Runs the legacy executable smoke checks under Gradle/JUnit. */
public final class LegacyDspSmokeTest {
    private static final String[] NO_ARGS = new String[0];

    @Test public void beatPhaseAligner() {
        BeatPhaseAlignerSmokeTest.main(NO_ARGS);
    }

    @Test public void continuityDirector() {
        ContinuityDirectorSmokeTest.main(NO_ARGS);
    }

    @Test public void directorFuzz() {
        DirectorFuzzSmokeTest.main(NO_ARGS);
    }

    @Test public void layerContinuity() {
        LayerContinuitySmokeTest.main(NO_ARGS);
    }

    @Test public void mastering() {
        MasteringSmokeTest.main(NO_ARGS);
    }

    @Test public void stemReconstruction() {
        StemReconstructionSmokeTest.main(NO_ARGS);
    }

    @Test public void wsola() {
        WsolaSmokeTest.main(NO_ARGS);
    }
}
