package com.alexey.autoremix

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import org.junit.Rule
import org.junit.Test

class AutoRemixScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val coverUris by lazy { createTestCoverUris() }

    @Test fun normalTrackAt0015() {
        capture("timeline-normal-00-15-dark.png", playback(positionMs = 15_000L))
        compose.onNodeWithTag("track-timeline").assertIsDisplayed()
        compose.onNodeWithTag("track-position").assertTextEquals("0:15")
        compose.onNodeWithTag("current-artwork").assertIsDisplayed()
        compose.onNodeWithTag("like").assertIsDisplayed()
        compose.onNodeWithTag("dislike").assertIsDisplayed()
        compose.onNodeWithTag("transport-previous").assertIsDisplayed()
        compose.onNodeWithTag("play-pause").assertIsDisplayed()
        compose.onNodeWithTag("transport-next").assertIsDisplayed()
        compose.onNodeWithTag("transport-deck").assertIsDisplayed()
        compose.onAllNodesWithTag("transition-visualization").assertCountEquals(0)
    }

    @Test fun trackEnteredAt0120() {
        capture("timeline-entry-01-20-dark.png", playback(positionMs = 94_000L, entryMs = 80_000L))
        compose.onNodeWithTag("entry-marker").assertIsDisplayed()
        compose.onNodeWithTag("entry-label").assertTextEquals("Вход 1:20")
    }

    @Test fun plannedTransitionMarker() {
        capture("timeline-planned-transition-dark.png", playback(positionMs = 105_000L))
        compose.onNodeWithTag("transition-marker").assertIsDisplayed()
        compose.onNodeWithTag("transition-span").assertIsDisplayed()
    }

    @Test fun transitionPreparing() {
        capture(
            "transition-preparing-dark.png",
            playback(positionMs = 116_000L).copy(
                playbackPhase = "TRANSITION_PREPARING",
                readiness = 48,
                markerStatus = "TENTATIVE",
            ),
        )
        compose.onNodeWithTag("track-timeline").assertIsDisplayed()
        compose.onNodeWithTag("transition-preparing").assertIsDisplayed()
    }

    @Test fun transitionActiveWithGuitarAnchor() {
        capture("transition-guitar-anchor-dark.png", activeTransition(.31f, guitarOperation()))
        assertTransitionOnly("operation-GUITAR")
    }

    @Test fun bassHandoff() {
        capture(
            "transition-bass-handoff-dark.png",
            activeTransition(
                .48f,
                UiTransformation("BASS", "BASS_HANDOFF", .63f, .86f, "Передаём бас"),
            ),
        )
        assertTransitionOnly("operation-BASS")
    }

    @Test fun keyChange() {
        capture(
            "transition-key-change-dark.png",
            activeTransition(
                .62f,
                UiTransformation("HARMONY", "KEY_CHANGE", .71f, .74f, "Тональность +2"),
            ),
        )
        assertTransitionOnly("operation-HARMONY")
    }

    @Test fun vocalOwnershipTransfer() {
        capture(
            "transition-vocal-handoff-dark.png",
            activeTransition(
                .78f,
                UiTransformation("LEAD_VOCAL", "STEM_HANDOFF", .84f, .9f, "Входит вокал следующего трека"),
            ),
        )
        assertTransitionOnly("operation-LEAD_VOCAL")
    }

    @Test fun newTrackLandedAtNonZeroPosition() {
        capture(
            "timeline-landed-01-14-dark.png",
            playback(positionMs = 74_000L, entryMs = 74_000L).copy(
                current = "Glass Cities — Afterimage",
                durationMs = 236_000L,
                landingPositionMs = 74_000L,
                nextTransitionStartMs = 168_000L,
                plannedExitPositionMs = 188_000L,
                currentArtworkUri = coverUris.second,
            ),
        )
        compose.onNodeWithTag("track-timeline").assertIsDisplayed()
        compose.onNodeWithTag("track-position").assertTextEquals("1:14")
        compose.onNodeWithTag("entry-label").assertTextEquals("Вход 1:14")
    }

    @Test fun reducedMotionUsesStaticSampleState() {
        capture(
            "transition-reduced-motion-dark.png",
            activeTransition(.56f, guitarOperation()).copy(
                transitionStatus = "Переход синхронизирован со звуком",
            ),
        )
        assertTransitionOnly("operation-GUITAR")
    }

    @Test fun sampleClockProgressSmoke() {
        compose.mainClock.autoAdvance = false
        var snapshot by mutableStateOf(activeTransition(0f, guitarOperation().copy(progress = 0f)))
        compose.setContent {
            AutoRemixTheme {
                AutoRemixScreen(snapshot, onAction = { _, _ -> }, onStart = {}, fixture = ScreenshotFixture.Transition)
            }
        }
        compose.waitForIdle()
        val startedAt = SystemClock.elapsedRealtime()
        repeat(120) { frame ->
            val progress = frame / 119f
            compose.runOnIdle {
                snapshot = snapshot.copy(
                    transitionProgress = progress,
                    transitionOperations = snapshot.transitionOperations.map { it.copy(progress = progress) },
                )
            }
            compose.mainClock.advanceTimeByFrame()
        }
        compose.waitForIdle()
        check(SystemClock.elapsedRealtime() - startedAt < 20_000L)
        compose.onNodeWithTag("transition-visualization").assertIsDisplayed()
        compose.onAllNodesWithTag("track-timeline").assertCountEquals(0)
    }

    @Test fun pausedTransitionKeepsSampleDrivenScene() {
        compose.mainClock.autoAdvance = false
        val snapshot = activeTransition(.64f, guitarOperation()).copy(
            playbackPhase = "PAUSED",
            paused = true,
            transitionInProgress = true,
            transitionState = "TRANSITION_ACTIVE",
        )
        compose.setContent {
            AutoRemixTheme {
                AutoRemixScreen(snapshot, onAction = { _, _ -> }, onStart = {}, fixture = ScreenshotFixture.Transition)
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("transition-progress-value").assertTextEquals("64%")
        compose.mainClock.advanceTimeBy(1_000L)
        compose.onNodeWithTag("transition-progress-value").assertTextEquals("64%")
        compose.onAllNodesWithTag("track-timeline").assertCountEquals(0)
    }

    private fun playback(positionMs: Long, entryMs: Long = 0L): EngineUiState =
        EngineUiState.demo().copy(
            playbackPhase = "TRACK_PLAYBACK",
            transitionInProgress = false,
            transitionState = "TRACK_PLAYBACK",
            current = "Night Drive — Violet Signals",
            positionMs = positionMs,
            durationMs = 252_000L,
            progress = positionMs / 252_000f,
            entryPositionMs = entryMs,
            nextTransitionStartMs = 127_000L,
            plannedExitPositionMs = 145_500L,
            timelineConfidence = .93f,
            markerStatus = "CONFIRMED",
            transitionOperations = emptyList(),
            currentArtworkUri = coverUris.first,
        )

    private fun activeTransition(progress: Float, operation: UiTransformation): EngineUiState =
        EngineUiState.demo().copy(
            playbackPhase = "TRANSITION_ACTIVE",
            transitionInProgress = true,
            transitionState = "TRANSITION_ACTIVE",
            readiness = 100,
            transitionProgress = progress,
            transitionStatus = operation.label,
            transitionOperations = listOf(operation),
            currentArtworkUri = coverUris.first,
            transitionSourceArtworkUri = coverUris.first,
            transitionTargetArtworkUri = coverUris.second,
        )

    private fun guitarOperation() =
        UiTransformation("GUITAR", "ANCHOR_PRESERVED", .42f, .91f, "Сохраняем гитарную линию")

    private fun assertTransitionOnly(operationTag: String) {
        compose.onNodeWithTag("transition-visualization").assertIsDisplayed()
        compose.onAllNodesWithTag("track-timeline").assertCountEquals(0)
        compose.onNodeWithTag("source-artwork").assertIsDisplayed()
        compose.onNodeWithTag("target-artwork").assertIsDisplayed()
        compose.onNodeWithTag(operationTag).assertIsDisplayed()
    }

    private fun capture(name: String, snapshot: EngineUiState) {
        compose.mainClock.autoAdvance = true
        compose.setContent {
            AutoRemixTheme {
                AutoRemixScreen(
                    snapshot = snapshot,
                    onAction = { _, _ -> },
                    onStart = {},
                    fixture = if (snapshot.showsTransitionForTest()) ScreenshotFixture.Transition else ScreenshotFixture.NowPlaying,
                )
            }
        }
        val artworkTags = if (snapshot.showsTransitionForTest()) {
            listOf("source-artwork-image", "target-artwork-image")
        } else {
            listOf("current-artwork-image")
        }
        artworkTags.forEach { tag ->
            compose.waitUntil(timeoutMillis = 5_000L) {
                compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
            }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = requireNotNull(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES))
        val output = File(directory, name)
        FileOutputStream(output).use { stream ->
            compose.onRoot().captureToImage().asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        check(output.length() > 0L)
    }

    private fun EngineUiState.showsTransitionForTest() =
        playbackPhase == "TRANSITION_ACTIVE" || playbackPhase == "TRACK_LANDING"

    private fun createTestCoverUris(): Pair<String, String> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "screenshot-covers")
        check(directory.exists() || directory.mkdirs())
        val source = File(directory, "violet-signals.png")
        val target = File(directory, "afterimage.png")
        writeCover(
            source,
            startColor = Color.rgb(72, 38, 214),
            endColor = Color.rgb(0, 210, 220),
            accentColor = Color.rgb(255, 90, 150),
            circles = true,
        )
        writeCover(
            target,
            startColor = Color.rgb(255, 92, 24),
            endColor = Color.rgb(255, 210, 70),
            accentColor = Color.rgb(23, 34, 59),
            circles = false,
        )
        return Uri.fromFile(source).toString() to Uri.fromFile(target).toString()
    }

    private fun writeCover(
        file: File,
        startColor: Int,
        endColor: Int,
        accentColor: Int,
        circles: Boolean,
    ) {
        val size = 512
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), startColor, endColor, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        paint.shader = null
        paint.color = accentColor
        if (circles) {
            canvas.drawCircle(128f, 128f, 92f, paint)
            canvas.drawCircle(410f, 386f, 142f, paint)
        } else {
            repeat(5) { stripe ->
                val left = -120f + stripe * 145f
                canvas.save()
                canvas.rotate(-24f, size / 2f, size / 2f)
                canvas.drawRect(left, -80f, left + 58f, 592f, paint)
                canvas.restore()
            }
        }
        FileOutputStream(file).use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
        bitmap.recycle()
    }
}
