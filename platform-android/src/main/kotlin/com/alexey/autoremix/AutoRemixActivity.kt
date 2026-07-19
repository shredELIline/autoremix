@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.alexey.autoremix

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay

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
) {
    companion object {
        fun read(): EngineUiState = EngineUiState(
            running = RemixEngineService.running,
            paused = RemixEngineService.paused,
            status = RemixEngineService.status,
            current = RemixEngineService.currentTrack,
            meta = RemixEngineService.currentMeta,
            next = RemixEngineService.nextTrack,
            transition = RemixEngineService.transition,
            progress = RemixEngineService.progress.coerceIn(0, 100) / 100f,
            positionMs = RemixEngineService.playbackPositionMs,
            durationMs = RemixEngineService.playbackDurationMs.coerceAtLeast(1L),
            readiness = RemixEngineService.transitionReadiness.coerceIn(0, 100),
            librarySize = RemixEngineService.librarySize,
            analyzedCount = RemixEngineService.analyzedCount,
            queue = RemixEngineService.queueSnapshot.split('\n').filter(String::isNotBlank),
            feedback = RemixEngineService.feedbackState,
            storageBudgetMb = RemixEngineService.storageBudgetMb,
            cacheBytes = RemixEngineService.cacheBytes,
            quality = if (RemixEngineService.nativeOutputActive) "Tier C · native Oboe" else "Tier C · AudioTrack fallback",
            transitionInProgress = RemixEngineService.transitionInProgress,
        )

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
        )
    }
}

@Composable
internal fun rememberEngineSnapshot(): EngineUiState {
    var state by remember { mutableStateOf(EngineUiState.read()) }
    LaunchedEffect(Unit) {
        while (true) {
            state = EngineUiState.read()
            delay(250)
        }
    }
    return state
}

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
                    item { NowPlaying(snapshot, onAction) }
                    item { Transport(snapshot, onAction, onStart) }
                    item { TransitionCard(snapshot) }
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
                    item { Transport(snapshot, onAction, onStart) }
                }
                ScreenshotFixture.Transition -> item { TransitionCard(snapshot) }
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
            Column(Modifier.weight(1f)) {
                Text("NOW PLAYING", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                Spacer(Modifier.height(8.dp))
                AnimatedContent(state.current, label = "track") { title ->
                    Text(
                        title.ifBlank { "Choose local music to begin" },
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(state.meta, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 2)
            }
            IconButton(onClick = { onAction(RemixEngineService.ACTION_LIKE, 0) }) {
                Icon(
                    if (state.feedback > 0) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = "Like",
                    tint = if (state.feedback > 0) Coral else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        Waveform(state.progress, state.running && !state.paused)
        Spacer(Modifier.height(8.dp))
        var seek by remember(state.positionMs, state.durationMs) {
            mutableFloatStateOf((state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f))
        }
        Slider(
            value = seek,
            onValueChange = { seek = it },
            onValueChangeFinished = {
                onAction(RemixEngineService.ACTION_SEEK, (seek * state.durationMs).toLong())
            },
            colors = SliderDefaults.colors(
                thumbColor = Aqua,
                activeTrackColor = PurpleLight,
                inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant,
            ),
            modifier = Modifier.testTag("progress-seek"),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(time(state.positionMs), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            Text(time(state.durationMs), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
    }
}

@Composable
private fun Transport(
    state: EngineUiState,
    onAction: (String, Long) -> Unit,
    onStart: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        CircleControl(Icons.AutoMirrored.Filled.ArrowBack, "Back") { onAction(RemixEngineService.ACTION_BACK, 0) }
        CircleControl(Icons.Default.ThumbDown, "Dislike") { onAction(RemixEngineService.ACTION_DISLIKE, 0) }
        Button(
            onClick = {
                if (!state.running) onStart()
                else onAction(if (state.paused) RemixEngineService.ACTION_RESUME else RemixEngineService.ACTION_PAUSE, 0)
            },
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = Purple),
            modifier = Modifier.size(68.dp).testTag("play-pause"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) {
            Icon(if (!state.running || state.paused) Icons.Default.PlayArrow else Icons.Default.Pause, "Play or pause", modifier = Modifier.size(31.dp))
        }
        CircleControl(Icons.Default.SkipNext, "Next") { onAction(RemixEngineService.ACTION_SKIP, 0) }
    }
}

@Composable
private fun CircleControl(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, action: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = CircleShape,
            modifier = Modifier.size(48.dp).clickable(onClick = action),
        ) { Box(contentAlignment = Alignment.Center) { Icon(icon, label, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp)) } }
        Spacer(Modifier.height(5.dp))
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
    }
}

@Composable
private fun TransitionCard(state: EngineUiState) {
    val phaseLabel = when {
        state.transitionInProgress -> "TRANSITION IN PROGRESS"
        state.readiness >= 100 -> "TRANSITION READY"
        else -> "PREPARING SEAMLESS TRANSITION"
    }
    val pulse = rememberInfiniteTransition(label = "ready").animateFloat(
        initialValue = .45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    ).value
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(Aqua.copy(alpha = if (state.running) pulse else .35f)))
            Spacer(Modifier.width(9.dp))
            Text(phaseLabel, color = Aqua, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            Text("${state.readiness}%", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(13.dp))
        Text(state.next, color = MaterialTheme.colorScheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
        Text(state.transition, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 3)
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant)) {
            Box(
                Modifier
                    .fillMaxWidth(state.readiness / 100f)
                    .height(6.dp)
                    .background(Brush.horizontalGradient(listOf(Purple, Aqua))),
            )
        }
        Spacer(Modifier.height(9.dp))
        Text(state.status, color = MaterialTheme.colorScheme.primary, fontSize = 11.sp, maxLines = 2)
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
private fun Waveform(progress: Float, animate: Boolean) {
    val inactive = MaterialTheme.colorScheme.outlineVariant
    val phase = rememberInfiniteTransition(label = "wave").animateFloat(
        0f,
        if (animate) 1f else 0f,
        infiniteRepeatable(tween(2400), RepeatMode.Restart),
        label = "phase",
    ).value
    Canvas(Modifier.fillMaxWidth().height(74.dp).testTag("waveform")) {
        val count = 64
        val gap = size.width / count
        repeat(count) { index ->
            val x = gap * (index + .5f)
            val seeded = kotlin.math.sin(index * 1.73 + phase * 2.0).toFloat() * .5f + .5f
            val envelope = .23f + seeded * .67f
            val h = size.height * envelope
            val played = index / count.toFloat() <= progress
            drawLine(
                color = if (played) lerp(PurpleLight, Aqua, index / count.toFloat()) else inactive,
                start = Offset(x, (size.height - h) / 2),
                end = Offset(x, (size.height + h) / 2),
                strokeWidth = gap * .48f,
                cap = StrokeCap.Round,
            )
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

private val Background = Color(0xFF090B12)
private val Panel = Color(0xFF141824)
private val PanelLight = Color(0xFF292E41)
private val Purple = Color(0xFF7557FF)
private val PurpleLight = Color(0xFFAA98FF)
private val Aqua = Color(0xFF4CD5C6)
private val Coral = Color(0xFFFF6688)
private val Muted = Color(0xFF9BA3BD)
