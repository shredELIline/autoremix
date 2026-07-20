package com.alexey.autoremix;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.DefaultMediaNotificationProvider;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * V2 programme engine. Compressed files are decoded ahead, split into complementary layers, rendered
 * into one PCM timeline and sent through Oboe, with AudioTrack as Tier-C fallback.
 * Automatic mode has no whole-track cuts.
 */
@UnstableApi
public class RemixEngineService extends MediaSessionService implements SceneAudioPlayer.Listener {
    private static final String TAG = "AutoRemixService";
    public static final String ACTION_START = "com.alexey.autoremix.START";
    public static final String ACTION_PAUSE = "com.alexey.autoremix.PAUSE";
    public static final String ACTION_RESUME = "com.alexey.autoremix.RESUME";
    public static final String ACTION_SKIP = "com.alexey.autoremix.SKIP";
    public static final String ACTION_BACK = "com.alexey.autoremix.BACK";
    public static final String ACTION_LIKE = "com.alexey.autoremix.LIKE";
    public static final String ACTION_DISLIKE = "com.alexey.autoremix.DISLIKE";
    public static final String ACTION_SEEK = "com.alexey.autoremix.SEEK";
    public static final String ACTION_STORAGE_BUDGET = "com.alexey.autoremix.STORAGE_BUDGET";
    public static final String ACTION_EXPORT_DEBUG = "com.alexey.autoremix.EXPORT_DEBUG";
    public static final String ACTION_STOP = "com.alexey.autoremix.STOP";
    public static final String EXTRA_CHAOS = "chaos";
    public static final String EXTRA_OVERLAYS = "overlays";
    public static final String EXTRA_HARMONIC = "harmonic";
    public static final String EXTRA_PATIENCE = "patience";
    public static final String EXTRA_SEEK_MS = "seek_ms";
    public static final String EXTRA_STORAGE_BUDGET_MB = "storage_budget_mb";

    public static volatile String status = "Остановлено";
    public static volatile String currentTrack = "—";
    public static volatile String currentMeta = "—";
    public static volatile String nextTrack = "—";
    public static volatile String currentArtworkUri = "";
    public static volatile String transitionSourceArtworkUri = "";
    public static volatile String transitionTargetArtworkUri = "";
    public static volatile String transition = "—";
    public static volatile int librarySize;
    public static volatile int progress;
    public static volatile boolean running;
    public static volatile boolean paused;
    public static volatile int analyzedCount;
    public static volatile long playbackPositionMs;
    public static volatile long playbackDurationMs = 1L;
    public static volatile int transitionReadiness;
    public static volatile String queueSnapshot = "";
    public static volatile int feedbackState;
    public static volatile int storageBudgetMb = 256;
    public static volatile long cacheBytes;
    public static volatile boolean nativeOutputActive;
    public static volatile boolean transitionInProgress;
    public static volatile String transitionState = TransitionStateMachine.State.IDLE.name();
    public static volatile long guaranteedRenderedHorizonMs;
    public static volatile int outputUnderruns;
    public static volatile int bufferLowWatermarkEvents;
    public static volatile int missedActivationBoundaries;
    public static volatile int activeCandidateLevel = -1;
    public static volatile long naturalRunwayRemainingMs;
    public static volatile long committedHorizonMs;
    public static volatile long planningHorizonMs;
    public static volatile float repetitionScore;
    public static volatile float noveltyScore = 1f;
    public static volatile String recentFragmentIds = "";
    public static volatile int neuralUpgradesApplied;
    public static volatile String fallbackReason = "none";
    public static volatile String selectedStrategy = "NONE";
    public static volatile boolean aiPlannerUsed;
    public static volatile boolean stemSeparatorUsed;
    public static volatile String selectedAnchor = "NONE";
    public static volatile float anchorConfidence;
    public static volatile String vocalOwnerTimeline = "";
    public static volatile String stemOwnershipTimeline = "";
    public static volatile double activationGapMs;
    public static volatile int activationUnderruns;
    public static volatile float activationMaxSampleJump;
    public static volatile float activationMaxDerivativeJump;
    public static volatile double activationLufsJump;
    public static volatile double activationSpectralFluxSpike;
    public static volatile float aiLayeredTransitionRate;
    public static volatile float deterministicStemTransitionRate;
    public static volatile float legacyTransitionRate;
    public static volatile float basicCrossfadeRate;
    public static volatile String transitionDiagnosticsJson = "{}";
    public static volatile PlaybackUiSnapshot playbackUiSnapshot = PlaybackUiSnapshot.IDLE;

    private static final int NOTIFICATION_ID = 903;
    private static final String CHANNEL = "autoremix_pcm";
    /** Decode/buffer granularity only. Never expose as song or transition duration. */
    private static final long DECODE_CHUNK_TARGET_MS = 20_000L;
    private static final long FALLBACK_BRIDGE_MS = 12_000L;
    private static final long UI_PROJECTION_INTERVAL_MS = 33L;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean renderTaskPending = new AtomicBoolean();
    private final AtomicBoolean renderAgain = new AtomicBoolean();
    private final AtomicInteger planEpoch = new AtomicInteger();
    private final Object planControl = new Object();
    private final TransitionStateMachine transitionMachine = new TransitionStateMachine();
    private final TransitionStrategyStatistics transitionStatistics =
            new TransitionStrategyStatistics();
    private final Map<Long, TrackAnalysis> cache = new ConcurrentHashMap<>();
    private final Random random = new Random();
    private final Deque<Long> recent = new ArrayDeque<>();
    private final Deque<Long> recentContinuationSources = new ArrayDeque<>();
    private final Deque<Long> recentContinuationMelodies = new ArrayDeque<>();
    private final Set<Long> usedContinuationSources = new HashSet<>();
    private final Set<Long> usedContinuationFragments = new HashSet<>();
    private final Map<Long, Integer> continuationSourceUses = new ConcurrentHashMap<>();
    private final Deque<ContinuationReservoir.Fragment> continuationHistory =
            new ArrayDeque<>();

    private List<Track> library = new ArrayList<>();
    private SceneAudioPlayer player;
    private AutoRemixSessionPlayer sessionPlayer;
    private MediaSession mediaSession;
    private volatile Track planningTrack;
    private volatile TrackAnalysis planningAnalysis;
    private volatile TrackAnalysis.Fragment planningFragment;
    private volatile long planningPositionMs;
    private volatile MusicalTimelinePlanner.TrackPlan planningTrackPlan;
    private volatile MusicalTimelinePlanner.TrackPlan currentTrackPlan;
    private volatile MusicalTimelinePlanner.TrackPlan pendingTargetTrackPlan;
    private volatile Long currentLandingPositionMs;
    private volatile Track pendingTargetTrack;
    private volatile RenderedScene plannedTransitionScene;
    private volatile PlaybackPhase playbackPhase = PlaybackPhase.IDLE;
    private volatile PlaybackPhase phaseBeforePause = PlaybackPhase.IDLE;
    private volatile boolean landingPublished;
    private String deferredInvalidationReason;
    private int chaos = 62;
    private int patience = 82;
    private boolean overlays = true;
    private boolean harmonic = true;
    private int sequence;
    private volatile int generation;
    private volatile long sceneStartedAt;
    private volatile long sceneDurationMs = 1L;
    private volatile boolean replanAfterCurrent;
    private volatile RenderedScene activeScene;
    private volatile RenderedScene transitioningScene;
    private int pendingNextCount;
    private volatile int candidateOffset;
    private volatile long forcedTargetId = -1L;
    private volatile long previousAnchorId = -1L;
    private volatile boolean manualEscapePending;
    private volatile boolean belowLowWatermark;
    private volatile ContinuousScenePlanner.PlanningResult lastPlanningResult;
    private volatile AudioContinuityValidator.Metrics lastLandingMetrics;
    private long transitionIdSequence;
    private long continuationTrackId = -1L;
    private long continuationFragmentId;
    private long continuationAnchorSourceId;

    private static final class FallbackRender {
        final RenderedScene scene;
        final ContinuousScenePlanner.PlanningResult planning;

        FallbackRender(RenderedScene scene,
                       ContinuousScenePlanner.PlanningResult planning) {
            this.scene = scene;
            this.planning = planning;
        }
    }

    private final Runnable settleNext = () -> {
        if (!running || player == null) return;
        int skips = Math.max(1, pendingNextCount);
        pendingNextCount = 0;
        candidateOffset = Math.max(0, skips - 1);
        manualEscapePending = true;
        invalidateFuturePlan("Строю один безопасный escape-переход к конечной цели");
    };

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        setShowNotificationForIdlePlayer(SHOW_NOTIFICATION_FOR_IDLE_PLAYER_NEVER);
        setMediaNotificationProvider(new DefaultMediaNotificationProvider.Builder(this)
                .setNotificationId(NOTIFICATION_ID)
                .setChannelId(CHANNEL)
                .build());
        storageBudgetMb = AnalysisCacheStore.budgetMb(this);
        cacheBytes = AnalysisCacheStore.sizeBytes(this);
        analyzedCount = AnalysisCacheStore.entryCount(this);
        sessionPlayer = new AutoRemixSessionPlayer((action, value) -> {
            Intent command = new Intent(this, RemixEngineService.class).setAction(action)
                    .putExtra(EXTRA_SEEK_MS, value);
            if (ACTION_START.equals(action) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(command);
            } else {
                startService(command);
            }
        });
        mediaSession = new MediaSession.Builder(this, sessionPlayer).build();
        addSession(mediaSession);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_START.equals(action)) {
            chaos = intent == null ? chaos : intent.getIntExtra(EXTRA_CHAOS, chaos);
            patience = intent == null ? patience : intent.getIntExtra(EXTRA_PATIENCE, patience);
            overlays = intent == null || intent.getBooleanExtra(EXTRA_OVERLAYS, true);
            harmonic = intent == null || intent.getBooleanExtra(EXTRA_HARMONIC, true);
            startForeground(NOTIFICATION_ID, notification("Готовлю PCM-аудиодвижок"));
            startEngine();
        } else if (ACTION_PAUSE.equals(action)) {
            pauseEngine();
        } else if (ACTION_RESUME.equals(action)) {
            resumeEngine();
        } else if (ACTION_SKIP.equals(action)) {
            requestNext(false);
        } else if (ACTION_BACK.equals(action)) {
            requestBack();
        } else if (ACTION_LIKE.equals(action)) {
            setFeedback(1);
        } else if (ACTION_DISLIKE.equals(action)) {
            setFeedback(-1);
            requestNext(true);
        } else if (ACTION_SEEK.equals(action)) {
            seekEngine(intent == null ? 0L : intent.getLongExtra(EXTRA_SEEK_MS, 0L));
        } else if (ACTION_STORAGE_BUDGET.equals(action)) {
            storageBudgetMb = AnalysisCacheStore.setBudgetMb(this,
                    intent == null ? storageBudgetMb
                            : intent.getIntExtra(EXTRA_STORAGE_BUDGET_MB, storageBudgetMb));
            cacheBytes = AnalysisCacheStore.sizeBytes(this);
            analyzedCount = AnalysisCacheStore.entryCount(this);
            if (sessionPlayer != null) sessionPlayer.refresh();
            if (!running) {
                stopSelf(startId);
                return START_NOT_STICKY;
            }
        } else if (ACTION_EXPORT_DEBUG.equals(action)) {
            shareDebugReport();
            if (!running) {
                stopSelf(startId);
                return START_NOT_STICKY;
            }
        } else if (ACTION_STOP.equals(action)) {
            stopEngine();
            stopSelf();
        } else {
            return super.onStartCommand(intent, flags, startId);
        }
        return START_STICKY;
    }

    private void startEngine() {
        synchronized (planControl) {
            recentContinuationSources.clear();
            recentContinuationMelodies.clear();
            usedContinuationSources.clear();
            usedContinuationFragments.clear();
            continuationSourceUses.clear();
            continuationHistory.clear();
            continuationTrackId = -1L;
            continuationFragmentId = 0L;
            continuationAnchorSourceId = 0L;
            deferredInvalidationReason = null;
        }
        stopAudioOnly();
        int token = ++generation;
        running = true;
        paused = false;
        sequence = 0;
        planEpoch.incrementAndGet();
        pendingNextCount = 0;
        candidateOffset = 0;
        manualEscapePending = false;
        transitionMachine.reset();
        belowLowWatermark = false;
        forcedTargetId = -1L;
        previousAnchorId = -1L;
        activeScene = null;
        transitioningScene = null;
        plannedTransitionScene = null;
        planningTrackPlan = null;
        currentTrackPlan = null;
        pendingTargetTrackPlan = null;
        pendingTargetTrack = null;
        currentLandingPositionMs = null;
        landingPublished = false;
        playbackPhase = PlaybackPhase.IDLE;
        phaseBeforePause = PlaybackPhase.IDLE;
        playbackUiSnapshot = PlaybackUiSnapshot.IDLE;
        currentArtworkUri = "";
        transitionSourceArtworkUri = "";
        transitionTargetArtworkUri = "";
        recent.clear();
        cache.clear();
        analyzedCount = AnalysisCacheStore.entryCount(this);
        progress = 0;
        playbackPositionMs = 0L;
        playbackDurationMs = 1L;
        nativeOutputActive = false;
        transitionInProgress = false;
        transitionReadiness = 0;
        transitionState = TransitionStateMachine.State.IDLE.name();
        guaranteedRenderedHorizonMs = 0L;
        outputUnderruns = 0;
        bufferLowWatermarkEvents = 0;
        missedActivationBoundaries = 0;
        activeCandidateLevel = -1;
        naturalRunwayRemainingMs = 0L;
        committedHorizonMs = 0L;
        planningHorizonMs = 0L;
        repetitionScore = 0f;
        noveltyScore = 1f;
        recentFragmentIds = "";
        neuralUpgradesApplied = 0;
        fallbackReason = "none";
        transitionStatistics.reset();
        transitionIdSequence = 0L;
        lastPlanningResult = null;
        lastLandingMetrics = null;
        resetTransitionDiagnostics();
        queueSnapshot = "";
        feedbackState = 0;
        status = "Сканирую локальную медиатеку…";
        currentTrack = "—";
        currentMeta = "Новый single-PCM engine · без MediaPlayer-переключений";
        nextTrack = "—";
        transition = "Строгий vibe-фильтр и stem-сцены";
        player = new SceneAudioPlayer(this);
        player.start();
        if (sessionPlayer != null) sessionPlayer.refresh();
        worker.submit(() -> bootstrap(token));
        schedulePlaybackUiProjection(token);
        scheduleProgress(token);
    }

    private void bootstrap(int token) {
        int epoch = planEpoch.get();
        try {
            List<Track> found = MusicLibrary.scan(this);
            if (token != generation) return;
            library = found;
            librarySize = found.size();
            if (found.size() < 2) {
                failOnMain("Нужно минимум два обычных аудиофайла");
                return;
            }
            Track first = found.get(random.nextInt(found.size()));
            TrackAnalysis analysis = analyze(first);
            TrackAnalysis.Fragment fragment = analysis.chooseFragment(random, .55f, chaos, first.durationMs);
            MusicalTimelinePlanner.TrackPlan firstPlan = MusicalTimelinePlanner.planTrack(
                    first, analysis, fragment, fragment.cueMs, sequence);
            main.post(() -> {
                status = "Декодирую первый сольный участок…";
                currentTrack = first.displayName();
                currentMeta = analysis.summary() + " · " + analysis.vibeSummary();
            });
            long firstChunkMs = Math.max(1_000L, Math.min(DECODE_CHUNK_TARGET_MS,
                    firstPlan.transitionStartPositionMs - firstPlan.entryPositionMs));
            RenderedScene prelude = SceneRenderer.renderPrelude(
                            this, first, analysis, fragment, firstChunkMs)
                    .forPlan(token, epoch);
            synchronized (planControl) {
                if (!isCurrentPlan(token, epoch)) return;
                planningTrack = prelude.anchorTrack;
                planningAnalysis = prelude.anchorAnalysis;
                planningFragment = prelude.anchorFragment;
                planningPositionMs = prelude.anchorPositionMs;
                planningTrackPlan = firstPlan;
                currentTrackPlan = firstPlan;
                currentArtworkUri = artworkUri(first);
                currentLandingPositionMs = null;
                playbackPhase = PlaybackPhase.TRACK_PLAYBACK;
                remember(first.id);
                if (!player.offer(prelude)) {
                    throw new IllegalStateException("PCM queue rejected prelude");
                }
                publishPlaybackSnapshot();
            }
            main.post(() -> {
                status = "Играет сольный участок; строю stem-переход заранее";
                nextTrack = "Анализирую песни того же вайба…";
                updateNotification();
            });
            renderAhead(token);
        } catch (Exception error) {
            failOnMain("Ошибка PCM-движка: " + error.getClass().getSimpleName()
                    + " · " + String.valueOf(error.getMessage()));
        }
    }

    private void scheduleRenderAhead(int token) {
        if (!running || token != generation || player == null) return;
        if (!renderTaskPending.compareAndSet(false, true)) {
            renderAgain.set(true);
            return;
        }
        worker.submit(() -> {
            try {
                renderAhead(token);
            } finally {
                renderTaskPending.set(false);
                if (renderAgain.getAndSet(false) && running && token == generation) {
                    scheduleRenderAhead(token);
                }
            }
        });
    }

    private void renderAhead(int token) {
        if (!running || token != generation || player == null) return;
        int epoch = planEpoch.get();
        TransitionStateMachine.State lifecycle = transitionMachine.state();
        if (lifecycle == TransitionStateMachine.State.TRANSITIONING) {
            fillNaturalRunway(token, epoch);
            return;
        }
        if ((lifecycle == TransitionStateMachine.State.WAITING_FOR_ACTIVATION_BOUNDARY
                && player.bufferedHorizonMs() >= lowWatermarkMs())
                || lifecycle == TransitionStateMachine.State.ARMED
                || lifecycle == TransitionStateMachine.State.CANDIDATE_READY
                || lifecycle == TransitionStateMachine.State.NEURAL_CANDIDATES_PENDING
                || lifecycle == TransitionStateMachine.State.PREPARING
                || lifecycle == TransitionStateMachine.State.TARGET_SELECTED) return;
        if (transitionMachine.state()
                == TransitionStateMachine.State.WAITING_FOR_ACTIVATION_BOUNDARY) return;

        Track outgoingTrack;
        TrackAnalysis outgoingAnalysis;
        TrackAnalysis.Fragment outgoingFragment;
        long outgoingPositionMs;
        MusicalTimelinePlanner.TrackPlan outgoingTimelinePlan;
        synchronized (planControl) {
            if (!isCurrentPlan(token, epoch)) return;
            outgoingTrack = planningTrack;
            outgoingAnalysis = planningAnalysis;
            outgoingFragment = planningFragment;
            outgoingPositionMs = planningPositionMs;
            outgoingTimelinePlan = planningTrackPlan;
        }
        if (outgoingTrack == null || outgoingAnalysis == null || outgoingFragment == null) return;
        if (outgoingTimelinePlan == null || outgoingTimelinePlan.trackId != outgoingTrack.id) {
            outgoingTimelinePlan = MusicalTimelinePlanner.planTrack(outgoingTrack,
                    outgoingAnalysis, outgoingFragment, outgoingPositionMs, sequence);
            planningTrackPlan = outgoingTimelinePlan;
        }
        if (outgoingPositionMs < outgoingTimelinePlan.transitionStartPositionMs) {
            fillNaturalRunway(token, epoch);
            return;
        }

        Candidate candidate = chooseCandidate(outgoingTrack, outgoingAnalysis, outgoingFragment);
        if (candidate == null) {
            candidate = chooseEmergencyCandidate(outgoingTrack, outgoingAnalysis, outgoingFragment);
        }
        if (candidate == null) {
            fillNaturalRunway(token, epoch);
            retryRenderLater(token);
            return;
        }

        boolean escapeScene = manualEscapePending;
        synchronized (planControl) {
            if (!isCurrentPlan(token, epoch)) return;
            transitionMachine.selectTarget();
            publishTransitionState();
            transitionMachine.beginPreparing();
            publishTransitionState();
            playbackPhase = PlaybackPhase.TRANSITION_PREPARING;
            pendingTargetTrack = candidate.track;
            transitionSourceArtworkUri = artworkUri(outgoingTrack);
            transitionTargetArtworkUri = artworkUri(candidate.track);
            publishPlaybackSnapshot();
        }
        final Candidate selected = candidate;
        main.post(() -> {
            if (!isCurrentPlan(token, epoch)) return;
            nextTrack = selected.track.displayName() + " · vibe "
                    + Math.round(selected.plan.vibeScore * 100f) + "%";
            transition = selected.plan.type.label + " · " + selected.plan.reason;
            status = "Preparing continuous deterministic stem scene";
        });

        RenderedScene boundaryRunway = null;
        long activationPositionMs = nextBarBoundaryMs(
                outgoingTrack, outgoingFragment, outgoingPositionMs);
        if (activationPositionMs > outgoingPositionMs) {
            try {
                boundaryRunway = SceneRenderer.renderContinuation(this, outgoingTrack,
                        outgoingAnalysis, outgoingFragment, outgoingPositionMs,
                        activationPositionMs - outgoingPositionMs,
                        "Natural runway to the next activation boundary").forPlan(token, epoch);
                outgoingPositionMs = activationPositionMs;
            } catch (Exception error) {
                Log.w(TAG, "event=boundary_runway_failed anchor=" + outgoingTrack.id
                        + " reason=" + error.getClass().getSimpleName(), error);
                activationPositionMs = outgoingPositionMs;
            }
        }

        long boundary = player.transitionActivationSample(boundaryRunway);
        ContinuousScenePlanner.PlanningResult planningResult = null;
        AudioContinuityValidator.Metrics landingMetrics = null;
        RenderedScene prepared;
        int candidateLevel;
        try {
            SceneRenderer.ContinuousRender continuous = SceneRenderer.renderContinuousTransition(this,
                    outgoingTrack, outgoingAnalysis, outgoingFragment, outgoingPositionMs,
                    candidate.track, candidate.analysis, candidate.fragment, candidate.plan,
                    nextTransitionId(), boundary,
                    outgoingTimelinePlan.estimatedTransitionLengthMs, 0, escapeScene,
                    () -> player != null && player.bufferedHorizonMs() >= lowWatermarkMs());
            prepared = continuous.scene;
            planningResult = continuous.planning;
            landingMetrics = continuous.landingMetrics;
            candidateLevel = 1;
            fallbackReason = "NONE";
        } catch (Exception error) {
            Log.w(TAG, "event=continuous_stem_render_failed anchor=" + outgoingTrack.id
                    + " candidate=" + candidate.track.id
                    + " reason=" + error.getClass().getSimpleName(), error);
            ContinuousSceneTransitionPlan.FallbackReason reason =
                    fallbackReasonFor(error, escapeScene);
            if (reason == ContinuousSceneTransitionPlan.FallbackReason.INSUFFICIENT_BUFFER) {
                failCurrentPlan(token, epoch);
                fillNaturalRunway(token, epoch);
                retryRenderLater(token);
                return;
            }
            try {
                FallbackRender fallback = renderFallbackSequence(outgoingTrack,
                        outgoingAnalysis, outgoingFragment, outgoingPositionMs,
                        candidate, boundary, reason);
                prepared = fallback.scene;
                planningResult = fallback.planning;
                candidateLevel = 0;
                fallbackReason = reason.name();
            } catch (Exception fallbackError) {
                Log.w(TAG, "event=legacy_fallback_render_failed anchor=" + outgoingTrack.id
                        + " candidate=" + candidate.track.id
                        + " reason=" + fallbackError.getClass().getSimpleName(), fallbackError);
                failCurrentPlan(token, epoch);
                fillNaturalRunway(token, epoch);
                retryRenderLater(token);
                return;
            }
        }
        if (!running || token != generation || epoch != planEpoch.get() || player == null) return;
        prepared = prepared.asCandidate(boundary, candidateLevel, token, epoch);
        SceneTimelineMapping preparedMapping = prepared.timelineMapping;
        MusicalTimelinePlanner.TrackPlan targetTimelinePlan =
                MusicalTimelinePlanner.planTrackAfterRenderedRunway(
                        prepared.anchorTrack, prepared.anchorAnalysis,
                        prepared.anchorFragment, preparedMapping.targetLandingPositionMs,
                        prepared.anchorPositionMs, sequence + 1);
        RenderedScene landingRunway = null;
        if (prepared.preparedStemScene != null) {
            try {
                long runwayMs = Math.min(DECODE_CHUNK_TARGET_MS,
                        Math.max(0L, targetTimelinePlan.transitionStartPositionMs
                                - prepared.anchorPositionMs));
                if (runwayMs >= 1_000L) {
                    landingRunway = SceneRenderer.renderContinuation(this,
                            prepared.anchorTrack, prepared.anchorAnalysis,
                            prepared.anchorFragment, prepared.anchorPositionMs,
                            runwayMs,
                            "Prepared B runway after in-scene landing").forPlan(token, epoch);
                }
            } catch (Exception error) {
                Log.w(TAG, "event=landing_runway_prepare_failed target="
                        + prepared.anchorTrack.id + " reason="
                        + error.getClass().getSimpleName(), error);
                failCurrentPlan(token, epoch);
                fillNaturalRunway(token, epoch);
                retryRenderLater(token);
                return;
            }
        }
        boolean published = false;
        synchronized (planControl) {
            if (!isCurrentPlan(token, epoch)) return;
            try {
                transitionMachine.candidateReady(prepared.validCandidate);
                publishTransitionState();
                transitionMachine.arm(boundary);
                publishTransitionState();
                transitionMachine.waitForActivationBoundary();
                publishTransitionState();
            } catch (IllegalStateException invalid) {
                transitionMachine.fail();
                publishTransitionState();
            }
            if (transitionMachine.state()
                    == TransitionStateMachine.State.WAITING_FOR_ACTIVATION_BOUNDARY) {
                boolean accepted;
                if (boundaryRunway == null && landingRunway == null) {
                    accepted = player.offerAll(prepared);
                } else if (boundaryRunway == null) {
                    accepted = player.offerAll(prepared, landingRunway);
                } else if (landingRunway == null) {
                    accepted = player.offerAll(boundaryRunway, prepared);
                } else {
                    accepted = player.offerAll(boundaryRunway, prepared, landingRunway);
                }
                if (accepted) {
                    activeCandidateLevel = candidateLevel;
                    RenderedScene planningAnchor = landingRunway == null
                            ? prepared : landingRunway;
                    SceneTimelineMapping mapping = preparedMapping;
                    currentTrackPlan = outgoingTimelinePlan.withTransition(
                            mapping.sourceStartPositionMs,
                            mapping.sourceExitPositionMs,
                            Math.max(1L, mapping.sourceExitPositionMs
                                    - mapping.sourceStartPositionMs),
                            planningResult == null || planningResult.plan == null
                                    ? outgoingTimelinePlan.confidence
                                    : planningResult.plan.confidence);
                    pendingTargetTrackPlan = targetTimelinePlan;
                    planningTrackPlan = pendingTargetTrackPlan;
                    pendingTargetTrack = prepared.anchorTrack;
                    plannedTransitionScene = prepared;
                    landingPublished = false;
                    playbackPhase = PlaybackPhase.TRANSITION_ARMED;
                    planningTrack = planningAnchor.anchorTrack;
                    planningAnalysis = planningAnchor.anchorAnalysis;
                    planningFragment = planningAnchor.anchorFragment;
                    planningPositionMs = planningAnchor.anchorPositionMs;
                    lastPlanningResult = planningResult;
                    lastLandingMetrics = landingMetrics;
                    transitionStatistics.record(prepared.continuousPlan);
                    publishPlanningDiagnostics();
                    remember(planningTrack.id);
                    manualEscapePending = false;
                    forcedTargetId = -1L;
                    candidateOffset = 0;
                    sequence++;
                    guaranteedRenderedHorizonMs = player.bufferedHorizonMs();
                    committedHorizonMs = Math.min(guaranteedRenderedHorizonMs,
                            barsToMs(planningAnalysis, 2));
                    planningHorizonMs = barsToMs(planningAnalysis, 32);
                    Track runwayTrack = planningTrack;
                    naturalRunwayRemainingMs = runwayTrack == null ? 0L
                            : Math.max(0L, runwayTrack.durationMs - planningPositionMs
                            - FALLBACK_BRIDGE_MS - 1_000L);
                    publishPlaybackSnapshot();
                    published = true;
                } else {
                    transitionMachine.fail();
                    publishTransitionState();
                }
            }
        }
        if (!published) {
            retryRenderLater(token);
        }
    }

    private long nextTransitionId() {
        return ++transitionIdSequence;
    }

    private static ContinuousSceneTransitionPlan.FallbackReason fallbackReasonFor(
            Exception error, boolean userForcedSkip) {
        if (userForcedSkip) {
            return ContinuousSceneTransitionPlan.FallbackReason.USER_FORCED_SKIP;
        }
        String message = String.valueOf(error.getMessage()).toLowerCase(java.util.Locale.ROOT);
        if (message.contains("decode") || message.contains("codec")) {
            return ContinuousSceneTransitionPlan.FallbackReason.TARGET_DECODE_FAILED;
        }
        if (message.contains("confidence")) {
            return ContinuousSceneTransitionPlan.FallbackReason.LOW_SEPARATOR_CONFIDENCE;
        }
        if (message.contains("buffer") || message.contains("deadline")) {
            return ContinuousSceneTransitionPlan.FallbackReason.INSUFFICIENT_BUFFER;
        }
        if (message.contains("quality") || message.contains("continuity")) {
            return ContinuousSceneTransitionPlan.FallbackReason.QUALITY_SCORE_TOO_LOW;
        }
        if (message.contains("vocal")) {
            return ContinuousSceneTransitionPlan.FallbackReason.VOCAL_CONFLICT_UNRESOLVABLE;
        }
        if (message.contains("thermal")) {
            return ContinuousSceneTransitionPlan.FallbackReason.DEVICE_THERMAL_LIMIT;
        }
        if (message.contains("runway") || message.contains("anchor")) {
            return ContinuousSceneTransitionPlan.FallbackReason.NO_COMPATIBLE_ANCHOR;
        }
        return ContinuousSceneTransitionPlan.FallbackReason.STEM_SEPARATION_FAILED;
    }

    private FallbackRender renderFallbackSequence(
            Track outgoingTrack, TrackAnalysis outgoingAnalysis,
            TrackAnalysis.Fragment outgoingFragment, long outgoingPositionMs,
            Candidate candidate, long activationSample,
            ContinuousSceneTransitionPlan.FallbackReason reason) throws Exception {
        requireFallbackDeadline();
        Exception lastFailure = null;
        try {
            LayerPlan legacyHint = candidate.plan.type.returnsToA
                    ? new LayerPlan(LayerPlan.Type.DRUM_BRIDGE_TAKEOVER,
                    candidate.plan.bars, candidate.plan.vibeScore,
                    candidate.plan.tempoRatio, "explicit legacy fallback")
                    : candidate.plan;
            RenderedScene scene = SceneRenderer.renderTransition(this,
                    outgoingTrack, outgoingAnalysis, outgoingFragment, outgoingPositionMs,
                    candidate.track, candidate.analysis, candidate.fragment,
                    legacyHint, 0L);
            requireFallbackDeadline();
            return finishFallback(scene, outgoingTrack, outgoingAnalysis,
                    outgoingFragment, outgoingPositionMs, candidate, activationSample,
                    reason, ContinuousSceneTransitionPlan.Strategy.LEGACY_INTELLIGENT);
        } catch (Exception error) {
            if (isPreparationDeadline(error)) throw error;
            lastFailure = error;
            Log.w(TAG, "event=legacy_intelligent_fallback_failed reason="
                    + error.getClass().getSimpleName(), error);
        }
        try {
            RenderedScene scene = SceneRenderer.renderPhraseAwareCrossfade(this,
                    outgoingTrack, outgoingAnalysis, outgoingFragment, outgoingPositionMs,
                    candidate.track, candidate.analysis, candidate.fragment,
                    FALLBACK_BRIDGE_MS);
            requireFallbackDeadline();
            return finishFallback(scene, outgoingTrack, outgoingAnalysis,
                    outgoingFragment, outgoingPositionMs, candidate, activationSample,
                    reason, ContinuousSceneTransitionPlan.Strategy.PHRASE_AWARE_CROSSFADE);
        } catch (Exception error) {
            if (isPreparationDeadline(error)) throw error;
            lastFailure = error;
            Log.w(TAG, "event=phrase_fallback_failed reason="
                    + error.getClass().getSimpleName(), error);
        }
        try {
            RenderedScene scene = SceneRenderer.renderEmergencyCrossfade(this,
                    outgoingTrack, outgoingAnalysis, outgoingFragment, outgoingPositionMs,
                    candidate.track, candidate.analysis, candidate.fragment,
                    FALLBACK_BRIDGE_MS);
            requireFallbackDeadline();
            return finishFallback(scene, outgoingTrack, outgoingAnalysis,
                    outgoingFragment, outgoingPositionMs, candidate, activationSample,
                    reason, ContinuousSceneTransitionPlan.Strategy.BASIC_CROSSFADE);
        } catch (Exception error) {
            if (isPreparationDeadline(error)) throw error;
            lastFailure = error;
            Log.w(TAG, "event=basic_fallback_failed reason="
                    + error.getClass().getSimpleName(), error);
        }
        throw new IllegalStateException("all explicit fallbacks failed", lastFailure);
    }

    private void requireFallbackDeadline() {
        if (player == null || player.bufferedHorizonMs() < lowWatermarkMs()) {
            throw new IllegalStateException("insufficient buffer preparation deadline");
        }
    }

    private static boolean isPreparationDeadline(Exception error) {
        String message = String.valueOf(error.getMessage())
                .toLowerCase(java.util.Locale.ROOT);
        return message.contains("buffer") || message.contains("deadline");
    }

    private FallbackRender finishFallback(
            RenderedScene scene,
            Track outgoingTrack, TrackAnalysis outgoingAnalysis,
            TrackAnalysis.Fragment outgoingFragment, long outgoingPositionMs,
            Candidate candidate, long activationSample,
            ContinuousSceneTransitionPlan.FallbackReason reason,
            ContinuousSceneTransitionPlan.Strategy expectedStrategy) {
        ContinuousScenePlanner.PlanningResult planning = planFallback(
                outgoingTrack, outgoingAnalysis, outgoingFragment, outgoingPositionMs,
                candidate, activationSample, reason, expectedStrategy, scene);
        if (!planning.hasPlan() || planning.plan.selectedStrategy != expectedStrategy) {
            throw new IllegalStateException("fallback plan rejected: " + expectedStrategy);
        }
        RenderedScene planned = scene.withContinuousPlan(planning.plan)
                .withTimelineMapping(scene.timelineMapping.withMasterRange(
                        planning.plan.activationSample, planning.plan.landingSample))
                .withTransformationEvents(planning.plan.transformationEvents(
                        scene.timelineMapping.targetTempoRatio));
        return new FallbackRender(planned, planning);
    }

    private ContinuousScenePlanner.PlanningResult planFallback(
            Track outgoingTrack, TrackAnalysis outgoingAnalysis,
            TrackAnalysis.Fragment outgoingFragment, long outgoingPositionMs,
            Candidate candidate, long activationSample,
            ContinuousSceneTransitionPlan.FallbackReason reason,
            ContinuousSceneTransitionPlan.Strategy strategy,
            RenderedScene scene) {
        long transitionSamples = scene.frames();
        long samplesPerBar = Math.max(1L, Math.round(
                barsToMs(outgoingAnalysis, 1) * SceneAudioPlayer.outputRate() / 1_000.0));
        boolean separatorAvailable = reason
                != ContinuousSceneTransitionPlan.FallbackReason.STEM_SEPARATION_FAILED;
        float separatorConfidence = reason
                == ContinuousSceneTransitionPlan.FallbackReason.LOW_SEPARATOR_CONFIDENCE
                ? .2f : .9f;
        ContinuousScenePlanner.PlanningRequest request =
                ContinuousScenePlanner.PlanningRequest.builder(
                                nextTransitionId(), outgoingTrack.id, candidate.track.id,
                                activationSample, samplesPerBar)
                        .transitionSamples(transitionSamples)
                        .sourceStartSample(Math.max(0L, Math.round(
                                scene.timelineMapping.sourceStartPositionMs
                                * SceneAudioPlayer.outputRate() / 1_000.0)))
                        .targetLandingSample(Math.max(0L, Math.round(
                                scene.timelineMapping.targetLandingPositionMs
                                * SceneAudioPlayer.outputRate() / 1_000.0)))
                        .separator(separatorAvailable, separatorConfidence)
                        .buffer(true, Math.max(transitionSamples, samplesPerBar * 8L),
                                0, 0f)
                        .vocalActivity(outgoingFragment.isVocalHeavy(),
                                candidate.fragment.isVocalHeavy())
                        .legacyVocalSafe(true)
                        .fallbacks(
                                strategy == ContinuousSceneTransitionPlan.Strategy.LEGACY_INTELLIGENT,
                                strategy == ContinuousSceneTransitionPlan.Strategy.PHRASE_AWARE_CROSSFADE,
                                strategy == ContinuousSceneTransitionPlan.Strategy.BASIC_CROSSFADE)
                        .userForcedSkip(reason
                                == ContinuousSceneTransitionPlan.FallbackReason.USER_FORCED_SKIP)
                        .compatibility(.75f,
                                RemixPlanner.keyCompatibility(outgoingAnalysis,
                                        candidate.analysis),
                                candidate.plan.vibeScore, .75f, .75f, .72f)
                        .build();
        return new ContinuousScenePlanner().plan(request);
    }

    private static void resetTransitionDiagnostics() {
        selectedStrategy = "NONE";
        aiPlannerUsed = false;
        stemSeparatorUsed = false;
        selectedAnchor = "NONE";
        anchorConfidence = 0f;
        vocalOwnerTimeline = "";
        stemOwnershipTimeline = "";
        activationGapMs = 0.0;
        activationUnderruns = 0;
        activationMaxSampleJump = 0f;
        activationMaxDerivativeJump = 0f;
        activationLufsJump = 0.0;
        activationSpectralFluxSpike = 0.0;
        aiLayeredTransitionRate = 0f;
        deterministicStemTransitionRate = 0f;
        legacyTransitionRate = 0f;
        basicCrossfadeRate = 0f;
        transitionDiagnosticsJson = "{}";
    }

    private void publishPlanningDiagnostics() {
        ContinuousScenePlanner.PlanningResult result = lastPlanningResult;
        if (result != null && result.hasPlan()) {
            ContinuousSceneTransitionPlan plan = result.plan;
            selectedStrategy = plan.selectedStrategy.name();
            aiPlannerUsed = plan.aiUsed();
            stemSeparatorUsed = plan.stemSeparatorUsed;
            selectedAnchor = plan.selectedAnchorSet.isEmpty() ? "NONE"
                    : plan.selectedAnchorSet.get(0).semanticRole.name();
            anchorConfidence = plan.confidence;
            vocalOwnerTimeline = plan.vocalOwnerTimeline(24);
            stemOwnershipTimeline = plan.stemTimelineText(24);
            fallbackReason = plan.fallbackReason == null
                    ? "NONE" : plan.fallbackReason.name();
            transitionDiagnosticsJson = result.diagnosticJson();
        }
        TransitionStrategyStatistics.Snapshot statistics = transitionStatistics.snapshot();
        aiLayeredTransitionRate = statistics.aiLayeredTransitionRate;
        deterministicStemTransitionRate = statistics.deterministicStemTransitionRate;
        legacyTransitionRate = statistics.legacyTransitionRate;
        basicCrossfadeRate = statistics.basicCrossfadeRate;
    }

    private void fillNaturalRunway(int token, int epoch) {
        int chunks = 0;
        while (running && token == generation && epoch == planEpoch.get() && player != null
                && player.bufferedHorizonMs() < highWatermarkMs()
                && player.queuedScenes() < 4 && chunks < 3) {
            Track track = planningTrack;
            TrackAnalysis analysis = planningAnalysis;
            TrackAnalysis.Fragment fragment = planningFragment;
            long position = planningPositionMs;
            if (track == null || analysis == null || fragment == null) return;
            MusicalTimelinePlanner.TrackPlan timelinePlan = planningTrackPlan;
            long transitionMarker = timelinePlan != null && timelinePlan.trackId == track.id
                    ? timelinePlan.transitionStartPositionMs
                    : track.durationMs - FALLBACK_BRIDGE_MS - 1_000L;
            if (position >= transitionMarker) return;
            long available = Math.min(
                    track.durationMs - position - FALLBACK_BRIDGE_MS - 1_000L,
                    transitionMarker - position);
            naturalRunwayRemainingMs = Math.max(0L, available);
            if (available < 250L) return;
            long duration = Math.min(DECODE_CHUNK_TARGET_MS, available);
            RenderedScene continuation;
            try {
                continuation = SceneRenderer.renderContinuation(this, track, analysis, fragment,
                        position, duration, "Natural runway while the next bridge is prepared")
                        .forPlan(token, epoch);
            } catch (Exception error) {
                Log.w(TAG, "event=runway_render_failed anchor=" + track.id
                        + " reason=" + error.getClass().getSimpleName(), error);
                return;
            }
            synchronized (planControl) {
                if (!isCurrentPlan(token, epoch)) return;
                if (planningTrack != track || planningPositionMs != position) continue;
                if (!player.offer(continuation)) return;
                planningTrack = continuation.anchorTrack;
                planningAnalysis = continuation.anchorAnalysis;
                planningFragment = continuation.anchorFragment;
                planningPositionMs = continuation.anchorPositionMs;
                chunks++;
            }
        }
        guaranteedRenderedHorizonMs = player == null ? 0L : player.bufferedHorizonMs();
    }

    private void fillPlannedContinuation(int token, int epoch, Track track,
                                         TrackAnalysis analysis,
                                         TrackAnalysis.Fragment anchorFragment,
                                         int maximumChunks) {
        ContinuationReservoir reservoir =
                ContinuationReservoir.fromTrack(track.id, track.durationMs, analysis);
        if (reservoir.fragments().isEmpty()) {
            fallbackReason = "continuation reservoir empty";
            return;
        }
        long currentSourceId;
        long currentFragmentId;
        long anchorSourceId;
        List<Long> recentSources;
        List<Long> recentMelodies;
        synchronized (planControl) {
            if (!isCurrentPlan(token, epoch)) return;
            if (continuationTrackId != track.id) {
                continuationTrackId = track.id;
                continuationFragmentId = 0L;
                recentContinuationSources.clear();
                recentContinuationMelodies.clear();
                usedContinuationSources.clear();
                usedContinuationFragments.clear();
                continuationSourceUses.clear();
                continuationHistory.clear();
                continuationAnchorSourceId = reservoir.sourceIdFor(anchorFragment);
                if (continuationAnchorSourceId != 0L) {
                    continuationSourceUses.put(continuationAnchorSourceId, 1);
                }
            }
            currentSourceId = reservoir.sourceIdFor(anchorFragment);
            currentFragmentId = continuationFragmentId;
            anchorSourceId = continuationAnchorSourceId;
            recentSources = new ArrayList<>(recentContinuationSources);
            recentMelodies = new ArrayList<>(recentContinuationMelodies);
        }
        ContinuationReservoir.CurrentContext current =
                new ContinuationReservoir.CurrentContext(currentFragmentId,
                        currentSourceId, anchorSourceId,
                        anchorFragment.energy);
        NonRepeatingContinuationPlanner.Plan plan =
                new NonRepeatingContinuationPlanner(reservoir).plan(current,
                        new ContinuationReservoir.TargetTrackContext(analysis.energy),
                        ContinuationReservoir.DeviceBudget.realtime(), 16,
                        recentSources, recentMelodies);
        fallbackReason = plan.complete ? "non-repeating deterministic continuation"
                : "partial deterministic continuation";
        if (plan.fragments.isEmpty()) return;

        int rendered = 0;
        for (ContinuationReservoir.Fragment requested : plan.fragments) {
            if (rendered >= maximumChunks || player == null
                    || player.queuedScenes() >= 4
                    || player.bufferedHorizonMs() >= highWatermarkMs()) break;
            ContinuationReservoir.Fragment planned;
            synchronized (planControl) {
                if (!isCurrentPlan(token, epoch)) return;
                planned = safeVariation(reservoir, requested);
            }
            if (planned == null) continue;
            RenderedScene continuation;
            try {
                continuation = SceneRenderer.renderContinuationVariant(this, track, analysis,
                        planned.source, planned.cueMs, planned.durationMs,
                        planned.variationMask,
                        "Non-repeating continuation L0 · " + planned.kind)
                        .forPlan(token, epoch);
            } catch (Exception error) {
                Log.w(TAG, "event=planned_continuation_failed fragment="
                        + planned.fragmentId + " reason="
                        + error.getClass().getSimpleName(), error);
                continue;
            }
            synchronized (planControl) {
                if (!isCurrentPlan(token, epoch)) return;
                if (!player.offer(continuation)) return;
                planningTrack = continuation.anchorTrack;
                planningAnalysis = continuation.anchorAnalysis;
                planningFragment = continuation.anchorFragment;
                planningPositionMs = continuation.anchorPositionMs;
                continuationFragmentId = planned.fragmentId;
                rememberContinuation(planned);
                rendered++;
            }
        }
        guaranteedRenderedHorizonMs = player == null ? 0L : player.bufferedHorizonMs();
    }

    private ContinuationReservoir.Fragment safeVariation(
            ContinuationReservoir reservoir,
            ContinuationReservoir.Fragment requested) {
        boolean sourceReused = usedContinuationSources.contains(requested.sourceFragmentId);
        if (requested.sourceFragmentId == continuationAnchorSourceId
                && continuationSourceUses.getOrDefault(requested.sourceFragmentId, 0) >= 2) {
            return null;
        }
        if (!usedContinuationFragments.contains(requested.fragmentId)
                && (!sourceReused
                || requested.variationMask != ContinuationReservoir.VARIATION_NONE)) {
            return requested;
        }
        for (ContinuationReservoir.Fragment candidate : reservoir.fragments()) {
            if (candidate.sourceFragmentId == requested.sourceFragmentId
                    && candidate.barCount == requested.barCount
                    && candidate.variationMask != ContinuationReservoir.VARIATION_NONE
                    && !usedContinuationFragments.contains(candidate.fragmentId)) {
                return candidate;
            }
        }
        return null;
    }

    private void rememberContinuation(ContinuationReservoir.Fragment fragment) {
        recentContinuationSources.remove(fragment.sourceFragmentId);
        recentContinuationSources.addLast(fragment.sourceFragmentId);
        recentContinuationMelodies.remove(fragment.melodicFingerprint);
        recentContinuationMelodies.addLast(fragment.melodicFingerprint);
        usedContinuationSources.add(fragment.sourceFragmentId);
        usedContinuationFragments.add(fragment.fragmentId);
        continuationSourceUses.merge(fragment.sourceFragmentId, 1, Integer::sum);
        continuationHistory.addLast(fragment);
        while (continuationHistory.size() > 16) continuationHistory.removeFirst();
        while (recentContinuationSources.size() > 2) recentContinuationSources.removeFirst();
        while (recentContinuationMelodies.size() > 2) recentContinuationMelodies.removeFirst();
        StringBuilder ids = new StringBuilder();
        for (Long id : recentContinuationSources) {
            if (ids.length() > 0) ids.append(',');
            ids.append(Long.toUnsignedString(id));
        }
        recentFragmentIds = ids.toString();
        RepetitionQualityEvaluator.Evaluation quality =
                new RepetitionQualityEvaluator().evaluate(
                        new ArrayList<>(continuationHistory));
        repetitionScore = Math.max(quality.metrics.exactFragmentRepeatRate,
                Math.max(quality.metrics.melodicRepeatRate,
                        quality.metrics.fullArrangementRepeatRate));
        noveltyScore = quality.metrics.noveltyPerBar;
    }

    private long lowWatermarkMs() {
        return barsToMs(planningAnalysis, 8);
    }

    private long highWatermarkMs() {
        return barsToMs(planningAnalysis, 16);
    }

    private static long barsToMs(TrackAnalysis analysis, int bars) {
        float bpm = analysis == null || !Float.isFinite(analysis.bpm) ? 120f : analysis.bpm;
        return Math.round(Math.max(1, bars) * 4.0 * 60_000.0 / Math.max(65f, bpm));
    }

    private static long nextBarBoundaryMs(Track track, TrackAnalysis.Fragment fragment,
                                          long positionMs) {
        long beat = Math.max(240L, fragment.beatPeriodMs);
        long bar = beat * 4L;
        long origin = Math.max(0L, fragment.cueMs);
        long delta = Math.max(0L, positionMs - origin);
        long boundary = origin + ((delta + bar - 1L) / bar) * bar;
        long latest = Math.max(positionMs, track.durationMs - FALLBACK_BRIDGE_MS - 1_000L);
        return boundary <= latest ? Math.max(positionMs, boundary) : positionMs;
    }

    private boolean isCurrentPlan(int token, int epoch) {
        return running && token == generation && epoch == planEpoch.get() && player != null;
    }

    private void failCurrentPlan(int token, int epoch) {
        synchronized (planControl) {
            if (!isCurrentPlan(token, epoch)) return;
            deferredInvalidationReason = null;
            transitionMachine.fail();
            publishTransitionState();
        }
    }

    private void publishTransitionState() {
        transitionState = transitionMachine.state().name();
        transitionReadiness = transitionMachine.readinessPercent();
        missedActivationBoundaries = transitionMachine.missedBoundaries();
    }

    private void completeAudibleLanding(RenderedScene scene) {
        String deferredReason;
        synchronized (planControl) {
            if (scene != transitioningScene && scene != plannedTransitionScene) return;
            if (transitionMachine.state() == TransitionStateMachine.State.TRANSITIONING) {
                transitionMachine.land();
            }
            transitioningScene = null;
            plannedTransitionScene = null;
            transitionInProgress = false;
            activeCandidateLevel = -1;
            transitionSourceArtworkUri = "";
            transitionTargetArtworkUri = "";
            deferredReason = deferredInvalidationReason;
            deferredInvalidationReason = null;
            publishTransitionState();
        }
        if (deferredReason != null) {
            main.post(() -> invalidateFuturePlan(deferredReason));
        }
    }

    private void publishPlaybackSnapshot() {
        SceneAudioPlayer audioPlayer = player;
        long masterSample = audioPlayer == null ? 0L : audioPlayer.audibleMasterSample();
        PlaybackPhase underlyingPhase = playbackPhase;
        RenderedScene transitionScene = underlyingPhase == PlaybackPhase.ERROR ? null
                : transitioningScene != null ? transitioningScene : plannedTransitionScene;
        TransitionUiState transitionSnapshot = TransitionUiState.EMPTY;
        long currentPositionMs = currentTimelinePositionMs(audioPlayer, masterSample);

        if (transitionScene != null && transitionScene.continuousPlan != null) {
            ContinuousSceneTransitionPlan plan = transitionScene.continuousPlan;
            SceneTimelineMapping mapping = transitionScene.timelineMapping;
            long landingStartSample = plan.targetCoreOwnershipSample();
            PlaybackPhase transitionPhase;
            if (masterSample < plan.activationSample) {
                transitionPhase = underlyingPhase == PlaybackPhase.TRANSITION_PREPARING
                        ? PlaybackPhase.TRANSITION_PREPARING
                        : PlaybackPhase.TRANSITION_ARMED;
            } else if (masterSample < landingStartSample) {
                transitionPhase = PlaybackPhase.TRANSITION_ACTIVE;
            } else {
                transitionPhase = PlaybackPhase.TRACK_LANDING;
            }

            boolean qualityPassed = lastLandingMetrics == null
                    || lastLandingMetrics.passesTransitionGate();
            long landingProbe = Math.min(masterSample, plan.landingSample - 1L);
            boolean fullLanding = masterSample >= mapping.fullLandingSample
                    && plan.fullTrackLandingAt(landingProbe)
                    && qualityPassed;
            if (fullLanding && !landingPublished) {
                landingPublished = true;
                currentLandingPositionMs = mapping.targetLandingPositionMs;
                if (pendingTargetTrackPlan != null) {
                    currentTrackPlan = pendingTargetTrackPlan;
                }
                playbackPhase = PlaybackPhase.TRACK_PLAYBACK;
                underlyingPhase = PlaybackPhase.TRACK_PLAYBACK;
                currentPositionMs = mapping.targetPositionMs(masterSample);
                Track landedTrack = pendingTargetTrack;
                if (landedTrack != null) {
                    currentTrack = landedTrack.displayName();
                    currentArtworkUri = artworkUri(landedTrack);
                }
                currentMeta = transitionScene.anchorAnalysis.summary() + " · "
                        + transitionScene.anchorAnalysis.vibeSummary();
                completeAudibleLanding(transitionScene);
            } else if (!landingPublished) {
                playbackPhase = transitionPhase;
                underlyingPhase = transitionPhase;
                if (masterSample >= plan.activationSample) {
                    currentPositionMs = mapping.sourcePositionMs(masterSample);
                }
            } else {
                underlyingPhase = PlaybackPhase.TRACK_PLAYBACK;
                currentPositionMs = mapping.targetPositionMs(masterSample);
            }

            if (!landingPublished) {
                List<AudioTransformationEvent> activeOperations = new ArrayList<>();
                for (AudioTransformationEvent event : transitionScene.transformationEvents) {
                    if (masterSample >= event.startSample && masterSample < event.endSample) {
                        activeOperations.add(event.atSample(masterSample));
                    }
                }
                float transitionProgress = (masterSample - plan.activationSample)
                        / (float) Math.max(1L, plan.landingSample - plan.activationSample);
                String anchor = plan.selectedAnchorSet.isEmpty() ? ""
                        : plan.selectedAnchorSet.get(0).semanticRole.name();
                transitionSnapshot = new TransitionUiState(
                        plan.transitionId, mapping.sourceTrackId, mapping.targetTrackId,
                        transitionPhase, transitionProgress, activeOperations,
                        mapping.sourcePositionMs(masterSample),
                        mapping.targetPositionMs(masterSample),
                        mapping.targetLandingPositionMs, plan.confidence, anchor,
                        transitionStatus(transitionPhase), masterSample,
                        plan.activationSample, plan.landingSample);
            }
        } else if (underlyingPhase == PlaybackPhase.TRANSITION_PREPARING) {
            MusicalTimelinePlanner.TrackPlan source = currentTrackPlan;
            Track target = pendingTargetTrack;
            transitionSnapshot = new TransitionUiState(
                    -1L, source == null ? -1L : source.trackId,
                    target == null ? -1L : target.id,
                    PlaybackPhase.TRANSITION_PREPARING, 0f, List.of(),
                    currentPositionMs, 0L, 0L, 0f, "",
                    transitionStatus(PlaybackPhase.TRANSITION_PREPARING),
                    masterSample, 0L, 1L);
        }

        MusicalTimelinePlanner.TrackPlan timelinePlan = currentTrackPlan;
        TrackPlaybackTimeline trackTimeline;
        if (timelinePlan == null) {
            trackTimeline = TrackPlaybackTimeline.EMPTY;
        } else {
            trackTimeline = timelinePlan.timeline(currentPositionMs,
                    markerStatus(underlyingPhase, currentPositionMs, timelinePlan),
                    currentLandingPositionMs);
        }
        PlaybackPhase publicPhase = paused ? PlaybackPhase.PAUSED : underlyingPhase;
        PlaybackUiSnapshot snapshot = new PlaybackUiSnapshot(
                publicPhase, trackTimeline, transitionSnapshot);
        playbackUiSnapshot = snapshot;
        playbackPositionMs = trackTimeline.currentPositionMs;
        playbackDurationMs = trackTimeline.durationMs;
        progress = Math.max(0, Math.min(100,
                Math.round(playbackPositionMs * 100f / Math.max(1L, playbackDurationMs))));
    }

    private long currentTimelinePositionMs(SceneAudioPlayer audioPlayer, long masterSample) {
        RenderedScene current = audioPlayer == null ? null : audioPlayer.audibleScene();
        MusicalTimelinePlanner.TrackPlan plan = currentTrackPlan;
        if (current == null || audioPlayer == null) {
            return plan == null ? 0L : plan.entryPositionMs;
        }
        SceneTimelineMapping mapping = current.timelineMapping;
        if (!mapping.transition) {
            return mapping.normalPositionMs(audioPlayer.audibleSceneFrame(current));
        }
        return landingPublished ? mapping.targetPositionMs(masterSample)
                : mapping.sourcePositionMs(masterSample);
    }

    private static TrackPlaybackTimeline.MarkerStatus markerStatus(
            PlaybackPhase phase, long currentPositionMs,
            MusicalTimelinePlanner.TrackPlan plan) {
        if (phase == PlaybackPhase.ERROR) return TrackPlaybackTimeline.MarkerStatus.CANCELLED;
        if (phase == PlaybackPhase.TRANSITION_ACTIVE
                || phase == PlaybackPhase.TRACK_LANDING) {
            return TrackPlaybackTimeline.MarkerStatus.ACTIVE;
        }
        if (phase == PlaybackPhase.TRANSITION_PREPARING) {
            return TrackPlaybackTimeline.MarkerStatus.TENTATIVE;
        }
        if (phase == PlaybackPhase.TRANSITION_ARMED) {
            return TrackPlaybackTimeline.MarkerStatus.CONFIRMED;
        }
        if (currentPositionMs > plan.trackExitPositionMs) {
            return TrackPlaybackTimeline.MarkerStatus.PASSED;
        }
        return TrackPlaybackTimeline.MarkerStatus.CONFIRMED;
    }

    private static String transitionStatus(PlaybackPhase phase) {
        switch (phase) {
            case TRANSITION_PREPARING:
                return "Preparing musical transition";
            case TRANSITION_ARMED:
                return "Transition ready";
            case TRANSITION_ACTIVE:
                return "Transforming stems";
            case TRACK_LANDING:
                return "Landing on target track";
            default:
                return "";
        }
    }

    private static String artworkUri(Track track) {
        return track == null || track.uri == null ? "" : track.uri.toString();
    }


    private void retryRenderLater(int token) {
        main.postDelayed(() -> scheduleRenderAhead(token), 1_200L);
    }

    private Candidate chooseCandidate(Track anchor, TrackAnalysis anchorAnalysis,
                                      TrackAnalysis.Fragment anchorFragment) {
        List<Candidate> candidates = new ArrayList<>();
        List<Track> shuffled = new ArrayList<>(library);
        java.util.Collections.shuffle(shuffled, random);
        int inspected = 0;
        for (Track track : shuffled) {
            if (inspected >= 5) break;
            if (track.id == anchor.id || (recent.contains(track.id) && track.id != forcedTargetId)) continue;
            TrackAnalysis analysis = preprocessedAnalysis(track);
            if (analysis == null) continue;
            inspected++;
            TrackAnalysis.Fragment fragment = analysis.chooseCompatibleFragment(anchorFragment,
                    anchorFragment.energy, chaos, track.durationMs, false);
            LayerPlan plan = ContinuityDirector.plan(anchorAnalysis, anchorFragment,
                    analysis, fragment, sequence, overlays);
            if (plan == null) continue;
            float keyFit = harmonic ? RemixPlanner.keyCompatibility(anchorAnalysis, analysis) : .72f;
            if (harmonic && keyFit < .58f) continue;
            float role = roleComplement(anchorFragment, fragment);
            float score = plan.vibeScore * .58f + keyFit * .18f
                    + fragment.mixReadiness() * .14f + role * .10f;
            candidates.add(new Candidate(track, analysis, fragment, plan, score));
        }
        if (candidates.isEmpty()) return null;

        // Build a tiny local vibe graph. A candidate that sounds good now but has no compatible
        // neighbour afterwards creates a dead-end and forces an ugly fallback. Prefer songs that
        // keep several high-quality continuation routes open.
        for (Candidate candidate : candidates) {
            int exits = 0;
            for (Candidate other : candidates) {
                if (candidate == other) continue;
                float futureVibe = ContinuityDirector.advancedVibeSimilarity(
                        candidate.analysis, candidate.fragment, other.analysis, other.fragment);
                float futureTempo = Math.abs(1f - RemixPlanner.bestTempoRatio(
                        candidate.analysis.bpm, other.analysis.bpm));
                if (futureVibe >= .82f && futureTempo <= .065f) exits++;
            }
            candidate.futureOptions = exits;
            candidate.score += Math.min(.105f, exits * .0175f);
        }
        candidates.sort(Comparator.comparingDouble((Candidate c) -> c.score).reversed());
        StringBuilder queue = new StringBuilder();
        for (int i = 0; i < Math.min(4, candidates.size()); i++) {
            if (i > 0) queue.append('\n');
            queue.append(candidates.get(i).track.displayName());
        }
        queueSnapshot = queue.toString();

        if (forcedTargetId >= 0L) {
            for (Candidate candidate : candidates) {
                if (candidate.track.id == forcedTargetId) return candidate;
            }
        }
        int offset = Math.min(candidateOffset, candidates.size() - 1);
        if (offset > 0) return candidates.get(offset);

        // Controlled variety: randomise only inside the near-equal top beam. It remains a random
        // remix, but never sacrifices a clearly better musical route for novelty.
        float best = candidates.get(0).score;
        float tolerance = .018f + chaos / 100f * .030f;
        int beam = 1;
        while (beam < Math.min(4, candidates.size())
                && candidates.get(beam).score >= best - tolerance) beam++;
        if (beam == 1) return candidates.get(0);
        double total = 0.0;
        double[] weights = new double[beam];
        for (int i = 0; i < beam; i++) {
            weights[i] = Math.exp((candidates.get(i).score - best) * 28.0);
            total += weights[i];
        }
        double pick = random.nextDouble() * total;
        for (int i = 0; i < beam; i++) {
            pick -= weights[i];
            if (pick <= 0.0) return candidates.get(i);
        }
        return candidates.get(0);
    }

    private Candidate chooseEmergencyCandidate(Track anchor, TrackAnalysis anchorAnalysis,
                                               TrackAnalysis.Fragment anchorFragment) {
        Track best = null;
        TrackAnalysis bestAnalysis = null;
        TrackAnalysis.Fragment bestFragment = null;
        float bestScore = -Float.MAX_VALUE;
        for (Track track : library) {
            if (track.id == anchor.id) continue;
            TrackAnalysis analysis = preprocessedAnalysis(track);
            if (analysis == null) analysis = TrackAnalysis.fallback(track);
            TrackAnalysis.Fragment fragment = analysis.chooseCompatibleFragment(anchorFragment,
                    anchorFragment.energy, chaos, track.durationMs, false);
            float vibe = ContinuityDirector.advancedVibeSimilarity(
                    anchorAnalysis, anchorFragment, analysis, fragment);
            float forced = track.id == forcedTargetId ? 2f : 0f;
            float recency = recent.contains(track.id) ? -.18f : 0f;
            float score = vibe + forced + recency;
            if (score > bestScore) {
                bestScore = score;
                best = track;
                bestAnalysis = analysis;
                bestFragment = fragment;
            }
        }
        if (best == null) return null;
        float ratio = RemixPlanner.bestTempoRatio(anchorAnalysis.bpm, bestAnalysis.bpm);
        float vibe = ContinuityDirector.advancedVibeSimilarity(
                anchorAnalysis, anchorFragment, bestAnalysis, bestFragment);
        LayerPlan plan = new LayerPlan(LayerPlan.Type.ATMOSPHERE_CHAIN, 16, vibe, ratio,
                "guaranteed end-of-track fallback");
        return new Candidate(best, bestAnalysis, bestFragment, plan, vibe);
    }

    private static float roleComplement(TrackAnalysis.Fragment a, TrackAnalysis.Fragment b) {
        float score = .45f;
        if (a.isVocalHeavy() && b.isInstrumentalFriendly()) score += .42f;
        else if (a.isInstrumentalFriendly() && b.isVocalHeavy()) score += .38f;
        else if (a.isPercussive() && b.isVocalHeavy()) score += .28f;
        if (a.isVocalHeavy() && b.isVocalHeavy()) score -= .35f;
        return Math.max(0f, Math.min(1f, score));
    }

    private TrackAnalysis analyze(Track track) {
        TrackAnalysis cached = cache.get(track.id);
        if (cached != null) return cached;
        TrackAnalysis result = AnalysisCacheStore.get(this, track);
        if (result == null) {
            result = AudioAnalyzer.analyze(this, track);
            AnalysisCacheStore.put(this, track, result);
        }
        cache.put(track.id, result);
        analyzedCount = AnalysisCacheStore.entryCount(this);
        cacheBytes = AnalysisCacheStore.sizeBytes(this);
        return result;
    }

    private TrackAnalysis preprocessedAnalysis(Track track) {
        TrackAnalysis result = cache.get(track.id);
        if (result != null) return result;
        result = AnalysisCacheStore.get(this, track);
        if (result != null) cache.put(track.id, result);
        return result;
    }

    @Override public boolean canStartScene(RenderedScene scene) {
        if (scene == null) return false;
        synchronized (planControl) {
            if (!scene.belongsToPlan(generation, planEpoch.get())) return false;
            RenderedScene previous = activeScene;
            if (previous != null && previous.anchorTrack.id != scene.anchorTrack.id) {
                previousAnchorId = previous.anchorTrack.id;
            }
            if (scene.transitionScene) {
                long actualBoundary = player == null ? -1L : player.masterSampleClock();
                if (!scene.validCandidate || transitionMachine.state()
                        != TransitionStateMachine.State.WAITING_FOR_ACTIVATION_BOUNDARY
                        || actualBoundary != scene.activationBoundary
                        || !transitionMachine.activateAtBoundary(actualBoundary)) {
                    Log.e(TAG, "event=activation_boundary_mismatch planned="
                            + scene.activationBoundary + " actual=" + actualBoundary);
                    transitionMachine.fail();
                    planEpoch.incrementAndGet();
                    publishTransitionState();
                    main.post(() -> invalidateFuturePlan(
                            "Граница активации пропущена · перестраиваю план"));
                    return false;
                }
                activeCandidateLevel = scene.candidateLevel;
                transitionInProgress = true;
                transitioningScene = scene;
                publishTransitionState();
            }
            activeScene = scene;
            publishPlaybackSnapshot();
            return true;
        }
    }

    @Override public void onSceneStarted(RenderedScene scene) {
        main.post(() -> {
            if (!running) return;
            sceneStartedAt = System.currentTimeMillis();
            sceneDurationMs = Math.max(1L, scene.durationMs());
            transitionInProgress = transitionMachine.state()
                    == TransitionStateMachine.State.TRANSITIONING;
            boolean waitingForLanding = transitioningScene != null && !landingPublished;
            if (!scene.transitionScene && !waitingForLanding) {
                currentTrack = scene.title;
                currentMeta = scene.description;
                currentArtworkUri = artworkUri(scene.anchorTrack);
                feedbackState = getSharedPreferences("feedback", MODE_PRIVATE)
                        .getInt("track_" + scene.anchorTrack.id, 0);
            }
            status = scene.transitionScene
                    ? "Stem Director: один слой остаётся непрерывным, остальные плавно морфятся"
                    : "Трек играет сольный участок";
            publishPlaybackSnapshot();
            updateNotification();
            if (sessionPlayer != null) sessionPlayer.refresh();
            scheduleRenderAhead(generation);
        });
    }

    @Override public void onSceneFinished(RenderedScene scene) {
        main.post(() -> {
            if (!running) return;
            synchronized (planControl) {
                boolean currentPlan = scene.belongsToPlan(generation, planEpoch.get());
                if (scene == transitioningScene && currentPlan
                        && transitionMachine.state() == TransitionStateMachine.State.TRANSITIONING) {
                    publishPlaybackSnapshot();
                }
                if (replanAfterCurrent && currentPlan) {
                    replanAfterCurrent = false;
                    planningTrack = scene.anchorTrack;
                    planningAnalysis = scene.anchorAnalysis;
                    planningFragment = scene.anchorFragment;
                    planningPositionMs = scene.anchorPositionMs;
                }
                if (activeScene == scene) activeScene = null;
                publishPlaybackSnapshot();
            }
            if (sessionPlayer != null) sessionPlayer.refresh();
            scheduleRenderAhead(generation);
        });
    }

    @Override public void onPlaybackError(String error) {
        failOnMain("Audio output: " + error);
    }

    private void requestNext(boolean dislike) {
        if (!running || player == null) return;
        player.requestNext(0L);
        pendingNextCount++;
        main.removeCallbacks(settleNext);
        main.postDelayed(settleNext, 420L);
        nextTrack = pendingNextCount == 1
                ? "Готовлю безопасный escape-переход"
                : "Объединяю " + pendingNextCount + " запросов Next";
        transition = dislike ? "Dislike сохранён · выбираю конечную цель" : "Rapid Next coalescing";
        status = "Текущий PCM не обрывается";
    }

    private void requestBack() {
        if (!running || player == null || previousAnchorId < 0L) return;
        forcedTargetId = previousAnchorId;
        pendingNextCount = 1;
        main.removeCallbacks(settleNext);
        main.postDelayed(settleNext, 80L);
    }

    private void seekEngine(long positionMs) {
        if (!running || player == null) return;
        if (transitioningScene != null
                && transitionMachine.state() == TransitionStateMachine.State.TRANSITIONING) {
            status = "Перемотка доступна после полного landing";
            return;
        }
        long requested = Math.max(0L, Math.min(playbackDurationMs, positionMs));
        playbackPhase = PlaybackPhase.SEEKING;
        publishPlaybackSnapshot();
        RenderedScene current = activeScene;
        long localPositionMs = requested;
        if (current != null && !current.timelineMapping.transition) {
            localPositionMs = Math.max(0L,
                    requested - current.timelineMapping.sourceStartPositionMs);
        }
        player.seekTo(localPositionMs);
        playbackPhase = PlaybackPhase.TRACK_PLAYBACK;
        publishPlaybackSnapshot();
        invalidateFuturePlan("Seek применён без щелчка · старый план отменён");
    }

    private void invalidateFuturePlan(String reason) {
        synchronized (planControl) {
            if (transitioningScene != null
                    && transitionMachine.state() == TransitionStateMachine.State.TRANSITIONING) {
                deferredInvalidationReason = reason;
                status = reason + " после текущего landing";
                return;
            }
            deferredInvalidationReason = null;
            // First epoch cancels active work. Second epoch publishes the replacement anchor.
            planEpoch.incrementAndGet();
            transitionMachine.cancel();
            transitioningScene = null;
            plannedTransitionScene = null;
            pendingTargetTrack = null;
            pendingTargetTrackPlan = null;
            transitionSourceArtworkUri = "";
            transitionTargetArtworkUri = "";
            landingPublished = false;
            publishTransitionState();
            transitionInProgress = false;
            activeCandidateLevel = -1;
            player.clearQueued();
            RenderedScene current = activeScene;
            if (current != null) {
                planningTrack = current.anchorTrack;
                planningAnalysis = current.anchorAnalysis;
                planningFragment = current.anchorFragment;
                // Seeking changes the read head inside already-rendered PCM. Future audio still
                // starts after that scene reaches its fixed source endpoint.
                planningPositionMs = current.anchorPositionMs;
            } else {
                replanAfterCurrent = true;
            }
            planEpoch.incrementAndGet();
            queueSnapshot = "";
            status = reason;
        }
        scheduleRenderAhead(generation);
    }

    private void setFeedback(int value) {
        RenderedScene current = activeScene;
        if (current == null) return;
        int stored = feedbackState == value ? 0 : value;
        getSharedPreferences("feedback", MODE_PRIVATE).edit()
                .putInt("track_" + current.anchorTrack.id, stored)
                .apply();
        feedbackState = stored;
    }

    private void pauseEngine() {
        if (!running || player == null) return;
        phaseBeforePause = playbackPhase;
        paused = true;
        player.pause();
        status = "Пауза";
        publishPlaybackSnapshot();
        if (sessionPlayer != null) sessionPlayer.refresh();
        updateNotification();
    }

    private void resumeEngine() {
        if (!running || player == null) return;
        paused = false;
        if (phaseBeforePause != PlaybackPhase.IDLE
                && phaseBeforePause != PlaybackPhase.PAUSED) {
            playbackPhase = phaseBeforePause;
        }
        player.resume();
        status = "Продолжаю single-PCM remix";
        publishPlaybackSnapshot();
        if (sessionPlayer != null) sessionPlayer.refresh();
        updateNotification();
    }

    private void schedulePlaybackUiProjection(int token) {
        main.postDelayed(new Runnable() {
            @Override public void run() {
                if (!running || token != generation) return;
                publishPlaybackSnapshot();
                main.postDelayed(this, UI_PROJECTION_INTERVAL_MS);
            }
        }, UI_PROJECTION_INTERVAL_MS);
    }

    private void scheduleProgress(int token) {
        main.postDelayed(new Runnable() {
            @Override public void run() {
                if (!running || token != generation) return;
                if (player != null && sceneStartedAt > 0L) {
                    nativeOutputActive = player.nativeOutputActive();
                    publishPlaybackSnapshot();
                    guaranteedRenderedHorizonMs = player.bufferedHorizonMs();
                    committedHorizonMs = Math.min(guaranteedRenderedHorizonMs,
                            barsToMs(planningAnalysis, 2));
                    planningHorizonMs = barsToMs(planningAnalysis, 32);
                    Track runwayTrack = planningTrack;
                    MusicalTimelinePlanner.TrackPlan runwayPlan = planningTrackPlan;
                    naturalRunwayRemainingMs = runwayTrack == null ? 0L
                            : Math.max(0L, runwayPlan != null
                            && runwayPlan.trackId == runwayTrack.id
                            ? runwayPlan.transitionStartPositionMs - planningPositionMs
                            : runwayTrack.durationMs - planningPositionMs
                            - FALLBACK_BRIDGE_MS - 1_000L);
                    outputUnderruns = player.underruns();
                    RenderedScene current = activeScene;
                    if (current != null && current.transitionScene) {
                        activationUnderruns = player.activationUnderruns();
                        MasterAudioGraph.ContinuityMetrics metrics =
                                player.continuityMetrics();
                        activationGapMs = metrics.currentMaximumActivationGapFrames
                                * 1_000.0 / MasterAudioGraph.SAMPLE_RATE;
                        activationMaxSampleJump =
                                metrics.activationMaximumSampleDiscontinuity;
                        activationMaxDerivativeJump =
                                metrics.activationMaximumDerivativeDiscontinuity;
                        activationLufsJump = metrics.activationLufsJump;
                        activationSpectralFluxSpike =
                                metrics.activationSpectralFluxSpike;
                    }
                    boolean low = guaranteedRenderedHorizonMs < lowWatermarkMs();
                    if (low && !belowLowWatermark) {
                        bufferLowWatermarkEvents++;
                    }
                    belowLowWatermark = low;
                    if (low) {
                        scheduleRenderAhead(token);
                    }
                    transitionInProgress = current != null && current.transitionScene
                            && transitionMachine.state() == TransitionStateMachine.State.TRANSITIONING;
                }
                if (sessionPlayer != null) sessionPlayer.refresh();
                main.postDelayed(this, 450L);
            }
        }, 450L);
    }

    private void remember(long id) {
        recent.remove(id);
        recent.addFirst(id);
        int max = Math.max(1, Math.min(Math.max(0, library.size() - 1),
                Math.min(14, Math.max(4, library.size() / 2))));
        while (recent.size() > max) recent.removeLast();
    }

    private void stopEngine() {
        generation++;
        synchronized (planControl) {
            transitionMachine.cancel();
            transitioningScene = null;
            deferredInvalidationReason = null;
            planEpoch.incrementAndGet();
            publishTransitionState();
        }
        running = false;
        paused = false;
        playbackPhase = PlaybackPhase.IDLE;
        phaseBeforePause = PlaybackPhase.IDLE;
        playbackUiSnapshot = PlaybackUiSnapshot.IDLE;
        status = "Остановлено";
        currentTrack = currentMeta = nextTrack = transition = "—";
        currentArtworkUri = "";
        transitionSourceArtworkUri = "";
        transitionTargetArtworkUri = "";
        progress = 0;
        playbackPositionMs = 0L;
        playbackDurationMs = 1L;
        nativeOutputActive = false;
        transitionInProgress = false;
        transitionReadiness = 0;
        guaranteedRenderedHorizonMs = 0L;
        committedHorizonMs = 0L;
        planningHorizonMs = 0L;
        naturalRunwayRemainingMs = 0L;
        activeCandidateLevel = -1;
        queueSnapshot = "";
        main.removeCallbacks(settleNext);
        stopAudioOnly();
        if (sessionPlayer != null) sessionPlayer.refresh();
        stopForeground(true);
    }

    public static String anonymizedDebugReport() {
        PlaybackUiSnapshot playback = playbackUiSnapshot;
        TrackPlaybackTimeline timeline = playback.trackTimeline;
        TransitionUiState transitionUi = playback.transitionUiState;
        return "{\"schema\":2,\"timeline\":{"
                + "\"originalDurationMs\":" + timeline.durationMs + ','
                + "\"entryPositionMs\":" + timeline.entryPositionMs + ','
                + "\"currentOriginalPositionMs\":" + timeline.currentPositionMs + ','
                + "\"plannedTransitionStartMs\":"
                + jsonLong(timeline.nextTransitionStartMs) + ','
                + "\"plannedExitPositionMs\":"
                + jsonLong(timeline.plannedExitPositionMs) + ','
                + "\"targetLandingPositionMs\":"
                + jsonLong(timeline.landingPositionFromPreviousMs) + ','
                + "\"markerSource\":" + jsonString(timeline.markerSource.name()) + ','
                + "\"markerStatus\":" + jsonString(timeline.markerStatus.name())
                + "},\"playbackPhase\":" + jsonString(playback.phase.name())
                + ",\"transitionUi\":{"
                + "\"phase\":" + jsonString(transitionUi.phase.name()) + ','
                + "\"durationSamples\":"
                + Math.max(0L, transitionUi.fullLandingSample
                        - transitionUi.transitionStartSample) + ','
                + "\"audioSamplePosition\":" + transitionUi.audioSamplePosition + ','
                + "\"sourcePositionMs\":" + transitionUi.sourcePositionMs + ','
                + "\"targetPositionMs\":" + transitionUi.targetPositionMs + ','
                + "\"estimatedLandingPositionMs\":"
                + transitionUi.estimatedLandingPositionMs + ','
                + "\"activeTransformations\":"
                + transformationsJson(transitionUi.activeStemOperations)
                + "},\"ownership\":{"
                + "\"vocal\":" + jsonString(vocalOwnerTimeline) + ','
                + "\"stems\":" + jsonString(stemOwnershipTimeline)
                + "},\"transition\":" + transitionDiagnosticsJson
                + ",\"runtime\":{"
                + "\"transitionState\":" + jsonString(transitionState) + ','
                + "\"generationEta\":\"unavailable_no_neural_provider\","
                + "\"naturalRunwayRemainingMs\":" + naturalRunwayRemainingMs + ','
                + "\"guaranteedRenderedHorizonMs\":" + guaranteedRenderedHorizonMs + ','
                + "\"planningHorizonMs\":" + planningHorizonMs + ','
                + "\"committedHorizonMs\":" + committedHorizonMs + ','
                + "\"activeCandidateLevel\":" + activeCandidateLevel + ','
                + "\"recentFragmentIds\":" + jsonString(recentFragmentIds) + ','
                + "\"repetitionScore\":" + repetitionScore + ','
                + "\"noveltyScore\":" + noveltyScore + ','
                + "\"bufferLowWatermarkEvents\":" + bufferLowWatermarkEvents + ','
                + "\"outputUnderruns\":" + outputUnderruns + ','
                + "\"activationGapMs\":" + activationGapMs + ','
                + "\"activationUnderruns\":" + activationUnderruns + ','
                + "\"activationMaxSampleJump\":" + activationMaxSampleJump + ','
                + "\"activationMaxDerivativeJump\":"
                + activationMaxDerivativeJump + ','
                + "\"activationLufsJump\":" + activationLufsJump + ','
                + "\"activationSpectralFluxSpike\":"
                + activationSpectralFluxSpike + ','
                + "\"aiLayeredTransitionRate\":" + aiLayeredTransitionRate + ','
                + "\"deterministicStemTransitionRate\":"
                + deterministicStemTransitionRate + ','
                + "\"legacyTransitionRate\":" + legacyTransitionRate + ','
                + "\"basicCrossfadeRate\":" + basicCrossfadeRate + ','
                + "\"fallbackReason\":" + jsonString(fallbackReason)
                + "}}";
    }

    private static String jsonLong(Long value) {
        return value == null ? "null" : Long.toString(value);
    }

    private static String transformationsJson(List<AudioTransformationEvent> events) {
        StringBuilder json = new StringBuilder("[");
        for (AudioTransformationEvent event : events) {
            if (json.length() > 1) json.append(',');
            json.append('{')
                    .append("\"stem\":").append(jsonString(event.stemType.name())).append(',')
                    .append("\"type\":")
                    .append(jsonString(event.transformationType.name())).append(',')
                    .append("\"progress\":").append(event.progress)
                    .append('}');
        }
        return json.append(']').toString();
    }

    private static String jsonString(String value) {
        String text = value == null ? "" : value;
        return '"' + text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r") + '"';
    }

    private void shareDebugReport() {
        Intent share = new Intent(Intent.ACTION_SEND)
                .setType("application/json")
                .putExtra(Intent.EXTRA_SUBJECT, "AutoRemix anonymized debug report")
                .putExtra(Intent.EXTRA_TEXT, anonymizedDebugReport());
        Intent chooser = Intent.createChooser(share, "Export debug report")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(chooser);
    }

    private void stopAudioOnly() {
        if (player != null) player.stop();
        player = null;
        renderTaskPending.set(false);
        planningTrack = null;
        planningAnalysis = null;
        planningFragment = null;
        planningPositionMs = 0L;
        activeScene = null;
    }

    private void failOnMain(String message) {
        main.post(() -> {
            status = message;
            running = false;
            paused = false;
            playbackPhase = PlaybackPhase.ERROR;
            publishPlaybackSnapshot();
            stopAudioOnly();
            updateNotification();
            if (sessionPlayer != null) sessionPlayer.refresh();
            stopForeground(true);
            stopSelf();
        });
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL,
                    "AutoRemix PCM", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Локальный stem-based AutoDJ");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("AutoRemix PCM Stem Director")
                .setContentText(text)
                .setContentIntent(pending)
                .setOngoing(running)
                .build();
    }

    private void updateNotification() {
        if (mediaSession != null && isSessionAdded(mediaSession)) {
            triggerNotificationUpdate();
            return;
        }
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, notification(status));
    }

    @Override public void onDestroy() {
        stopEngine();
        worker.shutdownNow();
        if (mediaSession != null) {
            if (isSessionAdded(mediaSession)) removeSession(mediaSession);
            mediaSession.release();
        }
        mediaSession = null;
        if (sessionPlayer != null) sessionPlayer.release();
        sessionPlayer = null;
        super.onDestroy();
    }

    @Override public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return mediaSession;
    }

    private static final class Candidate {
        final Track track;
        final TrackAnalysis analysis;
        final TrackAnalysis.Fragment fragment;
        final LayerPlan plan;
        float score;
        int futureOptions;

        Candidate(Track track, TrackAnalysis analysis, TrackAnalysis.Fragment fragment,
                  LayerPlan plan, float score) {
            this.track = track;
            this.analysis = analysis;
            this.fragment = fragment;
            this.plan = plan;
            this.score = score;
        }
    }
}
