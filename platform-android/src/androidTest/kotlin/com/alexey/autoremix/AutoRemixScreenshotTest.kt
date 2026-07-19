package com.alexey.autoremix

import android.graphics.Bitmap
import android.os.Environment
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import org.junit.Rule
import org.junit.Test

class AutoRemixScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test fun libraryDark() = capture("library-dark.png", ScreenshotFixture.Library)
    @Test fun nowPlayingDark() = capture("now-playing-dark.png", ScreenshotFixture.NowPlaying)
    @Test fun transitionDark() = capture("transition-dark.png", ScreenshotFixture.Transition)
    @Test fun transitionInProgressDark() = capture(
        "transition-in-progress-dark.png",
        ScreenshotFixture.Transition,
        snapshot = EngineUiState.demo().copy(
            transitionInProgress = true,
            readiness = 64,
            status = "Morphing drums and harmonic bed · vocal anchor continuous",
        ),
    )
    @Test fun queueDark() = capture("queue-dark.png", ScreenshotFixture.Queue)
    @Test fun analysisCacheDark() = capture("analysis-cache-dark.png", ScreenshotFixture.AnalysisCache)
    @Test fun settingsDark() = capture("settings-dark.png", ScreenshotFixture.Settings)
    @Test fun nowPlayingLight() = capture("now-playing-light.png", ScreenshotFixture.NowPlaying, false)

    private fun capture(
        name: String,
        fixture: ScreenshotFixture,
        darkTheme: Boolean = true,
        snapshot: EngineUiState = EngineUiState.demo(),
    ) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            AutoRemixTheme(darkTheme = darkTheme) {
                AutoRemixScreen(
                    snapshot = snapshot,
                    onAction = { _, _ -> },
                    onStart = {},
                    fixture = fixture,
                )
            }
        }
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = requireNotNull(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES))
        val output = File(directory, name)
        FileOutputStream(output).use { stream ->
            compose.onRoot().captureToImage().asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        check(output.length() > 0L)
    }
}
