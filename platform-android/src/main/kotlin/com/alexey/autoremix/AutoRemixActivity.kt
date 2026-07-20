@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.alexey.autoremix

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

open class AutoRemixActivity : ComponentActivity() {
    private var pendingStart = false

    private val permissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val audioPermission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        val audioGranted = ContextCompat.checkSelfPermission(this, audioPermission) == PackageManager.PERMISSION_GRANTED
        if (audioGranted && pendingStart) startEngine()
        pendingStart = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        AnalysisWork.schedule(this)
        setContent {
            AutoRemixTheme {
                val snapshot = rememberEngineSnapshot()
                AutoRemixScreen(
                    snapshot = snapshot,
                    onAction = ::dispatch,
                    onStart = ::requestStart,
                )
            }
        }
    }

    private fun requestStart() {
        val permissions = buildList {
            add(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (permissions.isEmpty()) startEngine() else {
            pendingStart = true
            permissionRequest.launch(permissions.toTypedArray())
        }
    }

    private fun startEngine() {
        dispatch(RemixEngineService.ACTION_START)
    }

    private fun dispatch(action: String, value: Long = 0L) {
        val intent = Intent(this, RemixEngineService::class.java).setAction(action)
        when (action) {
            RemixEngineService.ACTION_SEEK -> intent.putExtra(RemixEngineService.EXTRA_SEEK_MS, value)
            RemixEngineService.ACTION_STORAGE_BUDGET ->
                intent.putExtra(RemixEngineService.EXTRA_STORAGE_BUDGET_MB, value.toInt())
        }
        if (action == RemixEngineService.ACTION_START && Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
}

internal object AnalysisWork {
    fun schedule(context: android.content.Context) {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresCharging(true)
            .setRequiresDeviceIdle(true)
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .build()
        val request = PeriodicWorkRequestBuilder<LibraryAnalysisWorker>(12, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "autoremix-library-analysis",
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}

internal data class UiTransformation(
    val stemType: String,
    val transformationType: String,
    val progress: Float,
    val intensity: Float,
    val label: String,
)

internal data class EngineUiState(
    val running: Boolean,
    val paused: Boolean,
    val status: String,
    val current: String,
    val meta: String,
    val next: String,
    val transition: String,
    val progress: Float,
    val positionMs: Long,
    val durationMs: Long,
    val readiness: Int,
    val librarySize: Int,
    val analyzedCount: Int,
    val queue: List<String>,
    val feedback: Int,
    val storageBudgetMb: Int,
    val cacheBytes: Long,
    val quality: String,
    val transitionInProgress: Boolean,
    val transitionState: String,
    val guaranteedHorizonMs: Long,
    val candidateLevel: Int,
    val lowWatermarkEvents: Int,
    val outputUnderruns: Int,
    val naturalRunwayMs: Long,
    val committedHorizonMs: Long,
    val planningHorizonMs: Long,
    val repetitionScore: Float,
    val noveltyScore: Float,
    val recentFragmentIds: String,
    val neuralUpgrades: Int,
    val fallbackReason: String,
    val selectedStrategy: String,
    val aiPlannerUsed: Boolean,
    val stemSeparatorUsed: Boolean,
    val selectedAnchor: String,
    val anchorConfidence: Float,
    val vocalOwnerTimeline: String,
    val stemOwnershipTimeline: String,
    val activationGapMs: Double,
    val activationUnderruns: Int,
    val activationMaxSampleJump: Float,
    val activationMaxDerivativeJump: Float,
    val activationLufsJump: Double,
    val activationSpectralFluxSpike: Double,
    val stemPathRate: Float,
    val legacyRate: Float,
    val basicCrossfadeRate: Float,
    val playbackPhase: String = "TRACK_PLAYBACK",
    val trackId: Long = -1L,
    val entryPositionMs: Long = 0L,
    val nextTransitionStartMs: Long? = null,
    val plannedExitPositionMs: Long? = null,
    val landingPositionMs: Long? = null,
    val timelineConfidence: Float = 0f,
    val markerSource: String = "AI_AUTOMATIC",
    val markerStatus: String = "NONE",
    val transitionProgress: Float = 0f,
    val transitionStatus: String = "",
    val transitionSourcePositionMs: Long = 0L,
    val transitionTargetPositionMs: Long = 0L,
    val estimatedLandingPositionMs: Long = 0L,
    val transitionDurationMs: Long = 0L,
    val transitionOperations: List<UiTransformation> = emptyList(),
    val currentArtworkUri: String = "",
    val transitionSourceArtworkUri: String = "",
    val transitionTargetArtworkUri: String = "",
) {
    companion object {
        fun read(): EngineUiState {
            val playback = RemixEngineService.playbackUiSnapshot
            val timeline = playback.trackTimeline
            val transitionUi = playback.transitionUiState
            val duration = timeline.durationMs.coerceAtLeast(1L)
            val currentPosition = timeline.currentPositionMs.coerceIn(0L, duration)
            return EngineUiState(
            running = RemixEngineService.running,
            paused = RemixEngineService.paused,
            status = RemixEngineService.status,
            current = RemixEngineService.currentTrack,
            meta = RemixEngineService.currentMeta,
            next = RemixEngineService.nextTrack,
            transition = RemixEngineService.transition,
            progress = currentPosition.toFloat() / duration,
            positionMs = currentPosition,
            durationMs = duration,
            readiness = RemixEngineService.transitionReadiness.coerceIn(0, 100),
            librarySize = RemixEngineService.librarySize,
            analyzedCount = RemixEngineService.analyzedCount,
            queue = RemixEngineService.queueSnapshot.split('\n').filter(String::isNotBlank),
            feedback = RemixEngineService.feedbackState,
            storageBudgetMb = RemixEngineService.storageBudgetMb,
            cacheBytes = RemixEngineService.cacheBytes,
            quality = if (RemixEngineService.nativeOutputActive) "Tier C · native Oboe" else "Tier C · AudioTrack fallback",
            transitionInProgress = transitionUi.phase == PlaybackPhase.TRANSITION_ACTIVE ||
                transitionUi.phase == PlaybackPhase.TRACK_LANDING,
            transitionState = transitionUi.phase.name,
            guaranteedHorizonMs = RemixEngineService.guaranteedRenderedHorizonMs,
            candidateLevel = RemixEngineService.activeCandidateLevel,
            lowWatermarkEvents = RemixEngineService.bufferLowWatermarkEvents,
            outputUnderruns = RemixEngineService.outputUnderruns,
            naturalRunwayMs = RemixEngineService.naturalRunwayRemainingMs,
            committedHorizonMs = RemixEngineService.committedHorizonMs,
            planningHorizonMs = RemixEngineService.planningHorizonMs,
            repetitionScore = RemixEngineService.repetitionScore,
            noveltyScore = RemixEngineService.noveltyScore,
            recentFragmentIds = RemixEngineService.recentFragmentIds,
            neuralUpgrades = RemixEngineService.neuralUpgradesApplied,
            fallbackReason = RemixEngineService.fallbackReason,
            selectedStrategy = RemixEngineService.selectedStrategy,
            aiPlannerUsed = RemixEngineService.aiPlannerUsed,
            stemSeparatorUsed = RemixEngineService.stemSeparatorUsed,
            selectedAnchor = transitionUi.selectedAnchor,
            anchorConfidence = RemixEngineService.anchorConfidence,
            vocalOwnerTimeline = RemixEngineService.vocalOwnerTimeline,
            stemOwnershipTimeline = RemixEngineService.stemOwnershipTimeline,
            activationGapMs = RemixEngineService.activationGapMs,
            activationUnderruns = RemixEngineService.activationUnderruns,
            activationMaxSampleJump = RemixEngineService.activationMaxSampleJump,
            activationMaxDerivativeJump = RemixEngineService.activationMaxDerivativeJump,
            activationLufsJump = RemixEngineService.activationLufsJump,
            activationSpectralFluxSpike = RemixEngineService.activationSpectralFluxSpike,
            stemPathRate = RemixEngineService.aiLayeredTransitionRate +
                RemixEngineService.deterministicStemTransitionRate,
            legacyRate = RemixEngineService.legacyTransitionRate,
            basicCrossfadeRate = RemixEngineService.basicCrossfadeRate,
            playbackPhase = playback.phase.name,
            trackId = timeline.trackId,
            entryPositionMs = timeline.entryPositionMs,
            nextTransitionStartMs = timeline.nextTransitionStartMs,
            plannedExitPositionMs = timeline.plannedExitPositionMs,
            landingPositionMs = timeline.landingPositionFromPreviousMs,
            timelineConfidence = timeline.timelineConfidence,
            markerSource = timeline.markerSource.name,
            markerStatus = timeline.markerStatus.name,
            transitionProgress = transitionUi.progress.coerceIn(0f, 1f),
            transitionStatus = transitionUi.humanReadableStatus,
            transitionSourcePositionMs = transitionUi.sourcePositionMs,
            transitionTargetPositionMs = transitionUi.targetPositionMs,
            estimatedLandingPositionMs = transitionUi.estimatedLandingPositionMs,
            transitionDurationMs = ((transitionUi.fullLandingSample - transitionUi.transitionStartSample)
                .coerceAtLeast(0L) * 1_000L / 48_000L),
            transitionOperations = transitionUi.activeStemOperations.map { event ->
                UiTransformation(
                    stemType = event.stemType.name,
                    transformationType = event.transformationType.name,
                    progress = event.progress.coerceIn(0f, 1f),
                    intensity = event.intensity.coerceIn(0f, 1f),
                    label = event.humanReadableLabel,
                )
            },
            currentArtworkUri = RemixEngineService.currentArtworkUri,
            transitionSourceArtworkUri = RemixEngineService.transitionSourceArtworkUri,
            transitionTargetArtworkUri = RemixEngineService.transitionTargetArtworkUri,
        )
        }

        fun demo() = EngineUiState(
            running = true,
            paused = false,
            status = "Transition ready",
            current = "Night Drive — Violet Signals",
            meta = "122 BPM · A minor · vocal anchor",
            next = "Glass Cities — Afterimage",
            transition = "Vocal A stays · drums and harmonic bed move to B",
            progress = .42f,
            positionMs = 94_000,
            durationMs = 224_000,
            readiness = 86,
            librarySize = 184,
            analyzedCount = 137,
            queue = listOf("Afterimage", "Low Tide", "Parallel Lines"),
            feedback = 1,
            storageBudgetMb = 512,
            cacheBytes = 173L * 1024L * 1024L,
            quality = "Tier C · native Oboe",
            transitionInProgress = false,
            transitionState = "ARMED",
            guaranteedHorizonMs = 36_000,
            candidateLevel = 0,
            lowWatermarkEvents = 0,
            outputUnderruns = 0,
            naturalRunwayMs = 28_000,
            committedHorizonMs = 4_000,
            planningHorizonMs = 64_000,
            repetitionScore = 0f,
            noveltyScore = 1f,
            recentFragmentIds = "7,8",
            neuralUpgrades = 0,
            fallbackReason = "instant Level 0",
            selectedStrategy = "PRESERVE_GUITAR",
            aiPlannerUsed = false,
            stemSeparatorUsed = true,
            selectedAnchor = "GUITAR",
            anchorConfidence = .91f,
            vocalOwnerTimeline = "AAAAAA------BBBBBB",
            stemOwnershipTimeline = "LEAD_VOCAL     AAAAAA------BBBBBB\nGUITAR         AAAAAAAAAA--BBBBBB\nDRUMS          AAAAA---BBBBBBBBBB",
            activationGapMs = 0.0,
            activationUnderruns = 0,
            activationMaxSampleJump = .018f,
            activationMaxDerivativeJump = .031f,
            activationLufsJump = .4,
            activationSpectralFluxSpike = .03,
            stemPathRate = 1f,
            legacyRate = 0f,
            basicCrossfadeRate = 0f,
            playbackPhase = "TRANSITION_ARMED",
            trackId = 7L,
            entryPositionMs = 74_000L,
            nextTransitionStartMs = 168_000L,
            plannedExitPositionMs = 188_000L,
            landingPositionMs = 74_000L,
            timelineConfidence = .91f,
            markerSource = "AI_AUTOMATIC",
            markerStatus = "CONFIRMED",
            transitionProgress = .42f,
            transitionStatus = "Сохраняем гитарную линию",
            transitionSourcePositionMs = 132_000L,
            transitionTargetPositionMs = 61_000L,
            estimatedLandingPositionMs = 74_000L,
            transitionOperations = listOf(
                UiTransformation("GUITAR", "ANCHOR_PRESERVED", .72f, .9f, "Сохраняем гитарную линию"),
                UiTransformation("DRUMS", "STEM_HANDOFF", .54f, .82f, "Передаём ритм"),
                UiTransformation("BASS", "BASS_HANDOFF", .38f, .76f, "Передаём бас"),
                UiTransformation("LEAD_VOCAL", "STEM_HANDOFF", .18f, .68f, "Готовим вокал следующего трека"),
            ),
        )
    }
}

@Composable
internal fun rememberEngineSnapshot(): EngineUiState {
    var state by remember { mutableStateOf(EngineUiState.read()) }
    LaunchedEffect(Unit) {
        while (true) {
            state = EngineUiState.read()
            delay(33)
        }
    }
    return state
}

private val EngineUiState.showsTransitionScene: Boolean
    get() = transitionInProgress || transitionState == "TRANSITION_ACTIVE" || transitionState == "TRACK_LANDING"

private val EngineUiState.showsTransitionPreparation: Boolean
    get() = playbackPhase == "TRANSITION_PREPARING" || playbackPhase == "TRANSITION_ARMED"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AutoRemixScreen(
    snapshot: EngineUiState,
    onAction: (String, Long) -> Unit,
    onStart: () -> Unit,
    fixture: ScreenshotFixture = ScreenshotFixture.Full,
) {
    val colors = MaterialTheme.colorScheme
    Scaffold(
        containerColor = colors.background,
        modifier = Modifier.testTag("autoremix-root"),
    ) { insets ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .statusBarsPadding()
                .navigationBarsPadding(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { Header(snapshot) }
            when (fixture) {
                ScreenshotFixture.Full -> {
                    item { LibraryOverview(snapshot, onStart) }
                    if (snapshot.showsTransitionScene) {
                        item { TransitionVisualization(snapshot) }
                    } else {
                        item { NowPlaying(snapshot, onAction) }
                    }
                    item { Transport(snapshot, onAction, onStart) }
                    if (snapshot.showsTransitionPreparation) {
                        item { TransitionPreparationCard(snapshot) }
                    }
                    if (BuildConfig.DEBUG) {
                        item {
                            DebugInspector(snapshot) {
                                onAction(RemixEngineService.ACTION_EXPORT_DEBUG, 0L)
                            }
                        }
                    }
                    item { QueueCard(snapshot.queue) }
                    item { AnalysisCacheCard(snapshot) }
                    item { SettingsCard(snapshot, onAction) }
                    item {
                        Text(
                            "On-device only · no uploads · seamless engine always active",
                            color = colors.onSurfaceVariant,
                            fontSize = 12.sp,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        )
                    }
                }
                ScreenshotFixture.Library -> item { LibraryOverview(snapshot, onStart) }
                ScreenshotFixture.NowPlaying -> {
                    item { NowPlaying(snapshot, onAction) }
                    if (snapshot.showsTransitionPreparation) {
                        item { TransitionPreparationCard(snapshot) }
                    }
                    item { Transport(snapshot, onAction, onStart) }
                }
                ScreenshotFixture.Transition -> item {
                    if (snapshot.showsTransitionScene) {
                        TransitionVisualization(snapshot)
                    } else {
                        TransitionPreparationCard(snapshot)
                    }
                }
                ScreenshotFixture.Debug -> item {
                    DebugInspector(snapshot) {
                            onAction(RemixEngineService.ACTION_EXPORT_DEBUG, 0L)
                        }
                    }
                ScreenshotFixture.Queue -> item { QueueCard(snapshot.queue) }
                ScreenshotFixture.AnalysisCache -> item { AnalysisCacheCard(snapshot) }
                ScreenshotFixture.Settings -> item { SettingsCard(snapshot, onAction) }
            }
        }
    }
}

internal enum class ScreenshotFixture {
    Full,
    Library,
    NowPlaying,
    Transition,
    Debug,
    Queue,
    AnalysisCache,
    Settings,
}

@Composable
private fun LibraryOverview(state: EngineUiState, onStart: () -> Unit) {
    GlassCard {
        Text("LOCAL MUSIC LIBRARY", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Spacer(Modifier.height(10.dp))
        Text("${state.librarySize} eligible tracks", color = MaterialTheme.colorScheme.onSurface, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("${state.analyzedCount} analyzed on device · no uploads", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(7.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant)) {
            Box(
                Modifier.fillMaxWidth(
                    if (state.librarySize == 0) 0f
                    else (state.analyzedCount.toFloat() / state.librarySize).coerceIn(0f, 1f),
                )
                    .height(7.dp).background(Brush.horizontalGradient(listOf(Purple, Aqua))),
            )
        }
        Spacer(Modifier.height(14.dp))
        Button(onClick = onStart, colors = ButtonDefaults.buttonColors(containerColor = Purple)) {
            Text(if (state.running) "Engine live" else "Start seamless remix")
        }
    }
}

@Composable
private fun Header(state: EngineUiState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(Brush.linearGradient(listOf(Purple, Aqua))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.FastForward, null, tint = Color.White)
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text("AUTOREMIX", color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            Text("On-device stem continuity", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        Surface(color = if (state.running) Aqua.copy(alpha = .15f) else MaterialTheme.colorScheme.surface, shape = CircleShape) {
            Text(
                if (state.running) "LIVE" else "READY",
                color = if (state.running) Aqua else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun NowPlaying(state: EngineUiState, onAction: (String, Long) -> Unit) {
    GlassCard {
        Row(verticalAlignment = Alignment.Top) {
            ArtworkBadge(
                label = "A",
                color = PurpleLight,
                artworkUri = state.currentArtworkUri,
                size = 72.dp,
                testTag = "current-artwork",
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("NOW PLAYING", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                Text(
                    state.current.ifBlank { "Choose local music to begin" },
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(state.meta, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 2)
            }
            Row {
                IconButton(onClick = { onAction(RemixEngineService.ACTION_LIKE, 0) }, modifier = Modifier.testTag("like")) {
                    Icon(
                        if (state.feedback > 0) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = "Like",
                        tint = if (state.feedback > 0) Coral else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { onAction(RemixEngineService.ACTION_DISLIKE, 0) }, modifier = Modifier.testTag("dislike")) {
                    Icon(
                        Icons.Default.ThumbDown,
                        contentDescription = "Dislike",
                        tint = if (state.feedback < 0) Coral else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        TrackTimeline(state)
    }
}

@Composable
private fun TrackTimeline(state: EngineUiState) {
    val duration = state.durationMs.coerceAtLeast(1L)
    val current = state.positionMs.coerceIn(0L, duration)
    val entry = state.entryPositionMs.coerceIn(0L, duration)
    val transitionStart = state.nextTransitionStartMs?.coerceIn(0L, duration)
    val plannedExit = state.plannedExitPositionMs?.coerceIn(0L, duration)
    val currentFraction = current.toFloat() / duration
    val entryFraction = entry.toFloat() / duration
    val transitionFraction = transitionStart?.toFloat()?.div(duration)
    val exitFraction = plannedExit?.toFloat()?.div(duration)
    val inactiveTrack = MaterialTheme.colorScheme.outlineVariant
    val playedTrack = Brush.horizontalGradient(listOf(PurpleLight, Aqua))
    val markerDescription = when (state.markerStatus) {
        "CALCULATING" -> "рассчитываем"
        "TENTATIVE" -> "предварительно"
        "CONFIRMED" -> "подтверждён"
        "ACTIVE" -> "активен"
        "PASSED" -> "пройден"
        "CANCELLED" -> "отменён"
        else -> if (state.timelineConfidence >= .7f) "подтверждён" else "предварительно"
    }

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .testTag("track-timeline"),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val y = size.height / 2f
            drawLine(
                inactiveTrack,
                Offset(0f, y),
                Offset(size.width, y),
                strokeWidth = 8.dp.toPx(),
                cap = StrokeCap.Round,
            )
            if (transitionFraction != null && exitFraction != null && exitFraction > transitionFraction) {
                drawLine(
                    Coral.copy(alpha = .22f),
                    Offset(size.width * transitionFraction, y),
                    Offset(size.width * exitFraction, y),
                    strokeWidth = 12.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
            drawLine(
                brush = playedTrack,
                start = Offset(0f, y),
                end = Offset(size.width * currentFraction, y),
                strokeWidth = 8.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        if (transitionFraction != null && exitFraction != null && exitFraction > transitionFraction) {
            Box(
                Modifier
                    .offset(x = maxWidth * transitionFraction)
                    .width(maxWidth * (exitFraction - transitionFraction))
                    .height(12.dp)
                    .testTag("transition-span"),
            )
        }
        Box(
            Modifier
                .offset(x = (maxWidth - 3.dp) * entryFraction)
                .width(3.dp)
                .height(30.dp)
                .align(Alignment.CenterStart)
                .clip(CircleShape)
                .background(Aqua)
                .testTag("entry-marker"),
        )
        if (transitionFraction != null) {
            Box(
                Modifier
                    .offset(x = (maxWidth - 4.dp) * transitionFraction)
                    .size(4.dp, 34.dp)
                    .align(Alignment.CenterStart)
                    .clip(CircleShape)
                    .background(Coral)
                    .testTag("transition-marker"),
            )
        }
        Slider(
            value = currentFraction,
            onValueChange = {},
            enabled = false,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.Transparent,
                inactiveTrackColor = Color.Transparent,
                disabledThumbColor = Color.White,
                disabledActiveTrackColor = Color.Transparent,
                disabledInactiveTrackColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxSize().testTag("progress-seek"),
        )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(time(current), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.testTag("track-position"))
        Text(time(duration), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.testTag("track-duration"))
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Вход ${time(entry)}", color = Aqua, fontSize = 10.sp, modifier = Modifier.testTag("entry-label"))
        Text(
            transitionStart?.let {
                "Переход ${time(it)} · $markerDescription"
            } ?: "Ищем точку перехода",
            color = if (transitionStart == null) MaterialTheme.colorScheme.onSurfaceVariant else Coral,
            fontSize = 10.sp,
            modifier = Modifier.testTag("transition-label"),
        )
    }
}

@Composable
private fun Transport(
    state: EngineUiState,
    onAction: (String, Long) -> Unit,
    onStart: () -> Unit,
) {
    val deckShape = RoundedCornerShape(46.dp)
    val playDescription = if (!state.running || state.paused) "Play" else "Pause"
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Surface(
            color = Color.Transparent,
            shape = deckShape,
            shadowElevation = 10.dp,
            modifier = Modifier
                .width(244.dp)
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = .12f), deckShape)
                .testTag("transport-deck"),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .96f),
                                Purple.copy(alpha = .28f),
                                MaterialTheme.colorScheme.surface.copy(alpha = .98f),
                            ),
                        ),
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleControl(Icons.Default.SkipPrevious, "Previous") { onAction(RemixEngineService.ACTION_BACK, 0) }
                Surface(
                    onClick = {
                        if (!state.running) onStart()
                        else onAction(if (state.paused) RemixEngineService.ACTION_RESUME else RemixEngineService.ACTION_PAUSE, 0)
                    },
                    shape = CircleShape,
                    color = Color.Transparent,
                    contentColor = Color.White,
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .size(68.dp)
                        .border(1.dp, Color.White.copy(alpha = .18f), CircleShape)
                        .testTag("play-pause"),
                ) {
                    Box(
                        Modifier.fillMaxSize().background(Brush.linearGradient(listOf(PurpleLight, Purple))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (!state.running || state.paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            playDescription,
                            modifier = Modifier.size(31.dp),
                        )
                    }
                }
                CircleControl(Icons.Default.SkipNext, "Next") { onAction(RemixEngineService.ACTION_SKIP, 0) }
            }
        }
    }
}

@Composable
private fun CircleControl(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, action: () -> Unit) {
    Surface(
        onClick = action,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = .06f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = CircleShape,
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
        modifier = Modifier
            .size(52.dp)
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = .10f), CircleShape)
            .testTag("transport-${label.lowercase()}"),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, label, modifier = Modifier.size(23.dp))
        }
    }
}

@Composable
private fun TransitionPreparationCard(state: EngineUiState) {
    Box(Modifier.testTag("transition-preparing")) {
        GlassCard {
            val ready = state.playbackPhase == "TRANSITION_ARMED"
            Text(
                if (ready) "ПЕРЕХОД ГОТОВ" else "ГОТОВИМ ПЕРЕХОД",
                color = Aqua,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text(state.next, color = MaterialTheme.colorScheme.onSurface, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
            Text(
                state.nextTransitionStartMs?.let { "Начнём на ${time(it)}" } ?: "Выбираем музыкальную границу",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant)) {
                Box(
                    Modifier
                        .fillMaxWidth(state.readiness.coerceIn(0, 100) / 100f)
                        .height(5.dp)
                        .background(Brush.horizontalGradient(listOf(Purple, Aqua))),
                )
            }
        }
    }
}

@Composable
private fun TransitionVisualization(state: EngineUiState) {
    Box(Modifier.fillMaxWidth().testTag("transition-visualization")) {
        GlassCard {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("СОВЕРШАЕМ ПЕРЕХОД", color = Aqua, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                Text(
                    "${(state.transitionProgress.coerceIn(0f, 1f) * 100).roundToInt()}%",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("transition-progress-value"),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Совершаем переход",
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag("transition-title"),
            )
            Text(
                state.transitionStatus.ifBlank { "Перестраиваем музыкальную сцену" },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                maxLines = 2,
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ArtworkBadge(
                    label = "A",
                    color = PurpleLight,
                    artworkUri = state.transitionSourceArtworkUri,
                    size = 58.dp,
                    testTag = "source-artwork",
                )
                Column(Modifier.weight(1f).padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        state.current.substringBefore(" × ").ifBlank { "Текущий трек" },
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text("→", color = MaterialTheme.colorScheme.primary, fontSize = 22.sp)
                    Text(
                        state.next.ifBlank { "Следующий трек" },
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                ArtworkBadge(
                    label = "B",
                    color = Aqua,
                    artworkUri = state.transitionTargetArtworkUri,
                    size = 58.dp,
                    testTag = "target-artwork",
                )
            }
            Spacer(Modifier.height(18.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(7.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.outlineVariant)
                    .testTag("transition-progress"),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(state.transitionProgress.coerceIn(0f, 1f))
                        .height(7.dp)
                        .background(Brush.horizontalGradient(listOf(PurpleLight, Aqua))),
                )
            }
            Spacer(Modifier.height(16.dp))
            state.transitionOperations.take(6).forEach { operation ->
                TransformationRow(operation)
                Spacer(Modifier.height(10.dp))
            }
            if (state.transitionOperations.isEmpty()) {
                Text(
                    "Музыкальные линии появятся после подтверждения плана",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun ArtworkBadge(
    label: String,
    color: Color,
    artworkUri: String,
    size: Dp,
    testTag: String,
) {
    val artwork = rememberArtworkBitmap(artworkUri)
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(18.dp))
            .background(Brush.linearGradient(listOf(color, color.copy(alpha = .45f))))
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        if (artwork == null) {
            Text(label, color = Color.White, fontWeight = FontWeight.Black, fontSize = 20.sp)
        } else {
            Image(
                bitmap = artwork,
                contentDescription = "$label album artwork",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().testTag("$testTag-image"),
            )
        }
    }
}

@Composable
private fun rememberArtworkBitmap(artworkUri: String): ImageBitmap? {
    val context = LocalContext.current.applicationContext
    val artwork by produceState<ImageBitmap?>(initialValue = null, key1 = artworkUri) {
        value = if (artworkUri.isBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                loadArtworkBitmap(context, artworkUri)
            }
        }
    }
    return artwork
}

private fun loadArtworkBitmap(context: android.content.Context, artworkUri: String): ImageBitmap? {
    val uri = runCatching { Uri.parse(artworkUri) }.getOrNull() ?: return null
    if (uri.scheme.equals("file", ignoreCase = true)) {
        val path = uri.path ?: return null
        return runCatching { decodeArtwork(ImageDecoder.createSource(File(path))) }.getOrNull()
    }

    if (uri.scheme.equals("content", ignoreCase = true)) {
        runCatching {
            context.contentResolver
                .loadThumbnail(uri, Size(MAX_ARTWORK_EDGE_PX, MAX_ARTWORK_EDGE_PX), null)
                .asImageBitmap()
        }.getOrNull()?.let { return it }

        loadEmbeddedArtwork(context, uri)?.let { return it }
    }

    return runCatching {
        decodeArtwork(ImageDecoder.createSource(context.contentResolver, uri))
    }.getOrNull()
}

private fun loadEmbeddedArtwork(context: android.content.Context, uri: Uri): ImageBitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        val bytes = retriever.embeddedPicture ?: return null
        decodeArtwork(ImageDecoder.createSource(ByteBuffer.wrap(bytes)))
    } catch (_: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

private fun decodeArtwork(source: ImageDecoder.Source): ImageBitmap =
    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val largestEdge = maxOf(info.size.width, info.size.height).coerceAtLeast(1)
        if (largestEdge > MAX_ARTWORK_EDGE_PX) {
            val scale = MAX_ARTWORK_EDGE_PX.toFloat() / largestEdge
            decoder.setTargetSize(
                (info.size.width * scale).roundToInt().coerceAtLeast(1),
                (info.size.height * scale).roundToInt().coerceAtLeast(1),
            )
        }
    }.asImageBitmap()

@Composable
private fun TransformationRow(operation: UiTransformation) {
    val stem = when (operation.stemType) {
        "LEAD_VOCAL", "BACKING_VOCAL", "VOCAL_TEXTURE" -> "Вокал"
        "GUITAR" -> "Гитара"
        "DRUMS", "PERCUSSION" -> "Ударные"
        "BASS" -> "Бас"
        "HARMONY" -> "Гармония"
        "ATMOSPHERE" -> "Атмосфера"
        else -> operation.stemType.lowercase().replaceFirstChar { char -> char.uppercase() }
    }
    val label = operation.label.ifBlank {
        when (operation.transformationType) {
            "PITCH_SHIFT", "KEY_CHANGE" -> "Мягко меняем строй"
            "TIME_STRETCH", "TEMPO_MORPH" -> "Синхронизируем ритм"
            "BASS_HANDOFF" -> "Передаём бас"
            "GUITAR_MORPH", "ANCHOR_PRESERVED" -> "Сохраняем гитарную линию"
            "VOCAL_CHOP" -> "Вокальная текстура"
            "DRUM_REARRANGEMENT" -> "Перестраиваем ритм"
            "GENERATED_FILL" -> "Связываем музыкальные фразы"
            else -> "Передаём музыкальную линию"
        }
    }
    Column(Modifier.fillMaxWidth().testTag("operation-${operation.stemType}")) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stem, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1)
        }
        Spacer(Modifier.height(5.dp))
        Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(Purple.copy(alpha = .25f))) {
            Box(
                Modifier
                    .fillMaxWidth(operation.progress.coerceIn(0f, 1f))
                    .height(5.dp)
                    .background(Brush.horizontalGradient(listOf(PurpleLight, Aqua))),
            )
        }
    }
}

@Composable
private fun DebugInspector(state: EngineUiState, onExport: () -> Unit) {
    Box(Modifier.testTag("debug-inspector")) {
        GlassCard {
            Text("TIMELINE INSPECTOR", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            Spacer(Modifier.height(9.dp))
            Text("Original duration ${time(state.durationMs)}", color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp)
            Text("Entry ${time(state.entryPositionMs)} · current ${time(state.positionMs)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
            Text("Transition ${state.nextTransitionStartMs?.let(::time) ?: "—"} · exit ${state.plannedExitPositionMs?.let(::time) ?: "—"}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
            Text("Target landing ${state.estimatedLandingPositionMs.let(::time)} · state ${state.playbackPhase}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
            Text("Transition duration ${time(state.transitionDurationMs)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
            Text("Stem ownership ${state.stemOwnershipTimeline.ifBlank { "—" }}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, maxLines = 2)
            Text("Active operations ${state.transitionOperations.joinToString { it.transformationType }}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, maxLines = 2)
            Text(
                "buffer ${time(state.guaranteedHorizonMs)} · underrun ${state.outputUnderruns} · gap ${"%.2f".format(state.activationGapMs)} ms",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
            )
            Button(onClick = onExport, modifier = Modifier.padding(top = 8.dp)) {
                Text("Export anonymized debug report")
            }
        }
    }
}

@Composable
private fun QueueCard(queue: List<String>) {
    GlassCard {
        Text("UP NEXT", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Spacer(Modifier.height(9.dp))
        if (queue.isEmpty()) {
            Text("Vibe graph is choosing a safe path", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        } else {
            queue.take(4).forEachIndexed { index, track ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1}", color = if (index == 0) Aqua else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold, modifier = Modifier.width(28.dp))
                    Text(track, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(if (index == 0) "READY" else "ANALYZE", color = if (index == 0) Aqua else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 9.sp)
                }
            }
        }
    }
}

@Composable
private fun AnalysisCacheCard(state: EngineUiState) {
    GlassCard {
        Text("ANALYSIS CACHE", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Spacer(Modifier.height(10.dp))
        Text("${state.cacheBytes / (1024 * 1024)} MB used", color = MaterialTheme.colorScheme.onSurface, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("${state.analyzedCount}/${state.librarySize} tracks · schema v2", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(7.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant)) {
            val used = state.cacheBytes.toFloat() / (state.storageBudgetMb * 1024f * 1024f)
            Box(Modifier.fillMaxWidth(used.coerceIn(0f, 1f)).height(7.dp).background(Aqua))
        }
        Spacer(Modifier.height(10.dp))
        Text("Bounded LRU · ${state.storageBudgetMb} MB budget", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    }
}

@Composable
private fun SettingsCard(state: EngineUiState, onAction: (String, Long) -> Unit) {
    var storageBudget by remember(state.storageBudgetMb) { mutableFloatStateOf(state.storageBudgetMb.toFloat()) }
    GlassCard {
        Text("ENGINE SETTINGS", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        Spacer(Modifier.height(10.dp))
        Text(state.quality, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text("Storage budget ${storageBudget.toInt()} MB", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Slider(
            value = storageBudget,
            onValueChange = { storageBudget = (it / 64f).toInt() * 64f },
            onValueChangeFinished = {
                onAction(RemixEngineService.ACTION_STORAGE_BUDGET, storageBudget.toLong())
            },
            valueRange = 64f..2048f,
            steps = 30,
        )
        Surface(color = Aqua.copy(alpha = .12f), shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text("Deterministic Tier C rendering", color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text("Oboe when available · AudioTrack fallback", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun GlassCard(content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        colors = CardDefaults.cardColors(containerColor = colors.surface.copy(alpha = .96f)),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth().border(1.dp, colors.outlineVariant, RoundedCornerShape(24.dp)),
    ) {
        Column(Modifier.padding(18.dp), content = content)
    }
}

@Composable
internal fun AutoRemixTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    val scheme = if (darkTheme) {
        androidx.compose.material3.darkColorScheme(
            primary = Purple,
            secondary = Aqua,
            tertiary = Coral,
            background = Background,
            surface = Panel,
            surfaceVariant = PanelLight,
            outlineVariant = PanelLight,
            onPrimary = Color.White,
            onBackground = Color.White,
            onSurface = Color.White,
            onSurfaceVariant = Muted,
        )
    } else {
        androidx.compose.material3.lightColorScheme(
            primary = Purple,
            secondary = Color(0xFF087F75),
            tertiary = Coral,
            background = Color(0xFFF5F3FA),
            surface = Color.White,
            surfaceVariant = Color(0xFFE9E6F1),
            outlineVariant = Color(0xFFD7D2E2),
            onPrimary = Color.White,
            onBackground = Color(0xFF171420),
            onSurface = Color(0xFF171420),
            onSurfaceVariant = Color(0xFF635E70),
        )
    }
    MaterialTheme(colorScheme = scheme, typography = androidx.compose.material3.Typography(), content = content)
}

private fun time(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

private const val MAX_ARTWORK_EDGE_PX = 512
private val Background = Color(0xFF090B12)
private val Panel = Color(0xFF141824)
private val PanelLight = Color(0xFF292E41)
private val Purple = Color(0xFF7557FF)
private val PurpleLight = Color(0xFFAA98FF)
private val Aqua = Color(0xFF4CD5C6)
private val Coral = Color(0xFFFF6688)
private val Muted = Color(0xFF9BA3BD)
