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
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
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

    private static final int NOTIFICATION_ID = 903;
    private static final String CHANNEL = "autoremix_pcm";
    private static final long RUNWAY_CHUNK_MS = 20_000L;
    private static final long FALLBACK_BRIDGE_MS = 12_000L;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService enhancementWorker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean renderTaskPending = new AtomicBoolean();
    private final AtomicBoolean renderAgain = new AtomicBoolean();
    private final AtomicInteger planEpoch = new AtomicInteger();
    private final AtomicInteger enhancementSerial = new AtomicInteger();
    private final Object planControl = new Object();
    private final TransitionStateMachine transitionMachine = new TransitionStateMachine();
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
    private volatile Future<?> enhancementTask;
    private long continuationTrackId = -1L;
    private long continuationFragmentId;
    private long continuationAnchorSourceId;

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
            cancelEnhancementLocked();
            recentContinuationSources.clear();
            recentContinuationMelodies.clear();
            usedContinuationSources.clear();
            usedContinuationFragments.clear();
            continuationSourceUses.clear();
            continuationHistory.clear();
            continuationTrackId = -1L;
            continuationFragmentId = 0L;
            continuationAnchorSourceId = 0L;
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
            main.post(() -> {
                status = "Декодирую первый сольный участок…";
                currentTrack = first.displayName();
                currentMeta = analysis.summary() + " · " + analysis.vibeSummary();
            });
            RenderedScene prelude = SceneRenderer.renderPrelude(this, first, analysis, fragment, 52_000L)
                    .forPlan(token, epoch);
            synchronized (planControl) {
                if (!isCurrentPlan(token, epoch)) return;
                planningTrack = prelude.anchorTrack;
                planningAnalysis = prelude.anchorAnalysis;
                planningFragment = prelude.anchorFragment;
                planningPositionMs = prelude.anchorPositionMs;
                remember(first.id);
                if (!player.offer(prelude)) {
                    throw new IllegalStateException("PCM queue rejected prelude");
                }
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
        if ((lifecycle == TransitionStateMachine.State.WAITING_FOR_ACTIVATION_BOUNDARY
                && player.bufferedHorizonMs() >= lowWatermarkMs())
                || lifecycle == TransitionStateMachine.State.ARMED
                || lifecycle == TransitionStateMachine.State.FALLBACK_READY
                || lifecycle == TransitionStateMachine.State.NEURAL_CANDIDATES_PENDING
                || lifecycle == TransitionStateMachine.State.PREPARING
                || lifecycle == TransitionStateMachine.State.TARGET_SELECTED) return;

        fillNaturalRunway(token, epoch);
        if (!running || token != generation || epoch != planEpoch.get() || player == null) return;
        if (transitionMachine.state() == TransitionStateMachine.State.TRANSITIONING
                || transitionMachine.state()
                == TransitionStateMachine.State.WAITING_FOR_ACTIVATION_BOUNDARY) return;

        Track outgoingTrack;
        TrackAnalysis outgoingAnalysis;
        TrackAnalysis.Fragment outgoingFragment;
        long outgoingPositionMs;
        synchronized (planControl) {
            if (!isCurrentPlan(token, epoch)) return;
            outgoingTrack = planningTrack;
            outgoingAnalysis = planningAnalysis;
            outgoingFragment = planningFragment;
            outgoingPositionMs = planningPositionMs;
        }
        if (outgoingTrack == null || outgoingAnalysis == null || outgoingFragment == null) return;

        Candidate candidate = chooseCandidate(outgoingTrack, outgoingAnalysis, outgoingFragment);
        boolean enhancedEligible = candidate != null;
        if (candidate == null) {
            candidate = chooseEmergencyCandidate(outgoingTrack, outgoingAnalysis, outgoingFragment);
            fallbackReason = "preprocessed pair unavailable; instant Level 0";
        } else {
            fallbackReason = "instant Level 0 while enhanced candidate renders";
        }
        if (candidate == null) {
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
        }
        final Candidate selected = candidate;
        main.post(() -> {
            if (!isCurrentPlan(token, epoch)) return;
            nextTrack = selected.track.displayName() + " · vibe "
                    + Math.round(selected.plan.vibeScore * 100f) + "%";
            transition = selected.plan.type.label + " · " + selected.plan.reason;
            status = "Preparing deterministic safety bridge";
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

        RenderedScene fallback;
        try {
            fallback = SceneRenderer.renderEmergencyCrossfade(this,
                    outgoingTrack, outgoingAnalysis, outgoingFragment, outgoingPositionMs,
                    candidate.track, candidate.analysis, candidate.fragment, FALLBACK_BRIDGE_MS);
        } catch (Exception error) {
            Log.w(TAG, "event=fallback_render_failed anchor=" + outgoingTrack.id
                    + " candidate=" + candidate.track.id
                    + " reason=" + error.getClass().getSimpleName(), error);
            failCurrentPlan(token, epoch);
            retryRenderLater(token);
            return;
        }
        if (!running || token != generation || epoch != planEpoch.get() || player == null) return;

        long boundary = Math.round(activationPositionMs * SceneAudioPlayer.outputRate() / 1000.0);
        fallback = fallback.asCandidate(boundary, 0, token, epoch);
        boolean published = false;
        synchronized (planControl) {
            if (!isCurrentPlan(token, epoch)) return;
            try {
                transitionMachine.fallbackReady(fallback.validCandidate);
                publishTransitionState();
                if (enhancedEligible && !escapeScene) {
                    transitionMachine.neuralCandidatesPending();
                    publishTransitionState();
                }
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
                boolean accepted = boundaryRunway == null
                        ? player.offerAll(fallback)
                        : player.offerAll(boundaryRunway, fallback);
                if (accepted) {
                    activeCandidateLevel = 0;
                    planningTrack = fallback.anchorTrack;
                    planningAnalysis = fallback.anchorAnalysis;
                    planningFragment = fallback.anchorFragment;
                    planningPositionMs = fallback.anchorPositionMs;
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
                    published = true;
                } else {
                    transitionMachine.fail();
                    publishTransitionState();
                }
            }
        }
        if (!published) {
            retryRenderLater(token);
            return;
        }
        if (escapeScene) {
            player.seekTo(Math.max(player.currentPositionMs(), player.currentDurationMs() - 10_000L));
        }

        if (enhancedEligible && !escapeScene) {
            renderEnhancedCandidate(token, epoch, boundary, fallback,
                    outgoingTrack, outgoingAnalysis, outgoingFragment, outgoingPositionMs, candidate);
        }
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
            long available = track.durationMs - position - FALLBACK_BRIDGE_MS - 1_000L;
            naturalRunwayRemainingMs = Math.max(0L, available);
            if (available < 8_000L) {
                fillPlannedContinuation(token, epoch, track, analysis, fragment,
                        Math.max(1, 3 - chunks));
                return;
            }
            long duration = Math.min(RUNWAY_CHUNK_MS, available);
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

    private void renderEnhancedCandidate(int token, int epoch, long boundary,
                                         RenderedScene fallback,
                                         Track outgoingTrack, TrackAnalysis outgoingAnalysis,
                                         TrackAnalysis.Fragment outgoingFragment, long outgoingPositionMs,
                                         Candidate candidate) {
        final int job;
        FutureTask<Void> task;
        synchronized (planControl) {
            if (!isCurrentPlan(token, epoch)
                    || transitionMachine.state()
                    != TransitionStateMachine.State.WAITING_FOR_ACTIVATION_BOUNDARY) return;
            cancelEnhancementLocked();
            job = enhancementSerial.incrementAndGet();
            task = new FutureTask<>(() -> {
                runEnhancedCandidate(job, token, epoch, boundary, fallback, outgoingTrack,
                        outgoingAnalysis, outgoingFragment, outgoingPositionMs, candidate);
                return null;
            });
            enhancementTask = task;
        }
        enhancementWorker.execute(task);
    }

    private void runEnhancedCandidate(int job, int token, int epoch, long boundary,
                                      RenderedScene fallback,
                                      Track outgoingTrack, TrackAnalysis outgoingAnalysis,
                                      TrackAnalysis.Fragment outgoingFragment, long outgoingPositionMs,
                                      Candidate candidate) {
        try {
            long soloMs = 50_000L + patience * 360L;
            RenderedScene enhanced = SceneRenderer.renderTransition(this,
                    outgoingTrack, outgoingAnalysis, outgoingFragment, outgoingPositionMs,
                    candidate.track, candidate.analysis, candidate.fragment, candidate.plan, soloMs)
                    .asCandidate(boundary, 1, token, epoch);
            synchronized (planControl) {
                if (job != enhancementSerial.get() || !isCurrentPlan(token, epoch)
                        || !enhanced.validCandidate
                        || transitionMachine.state()
                        != TransitionStateMachine.State.WAITING_FOR_ACTIVATION_BOUNDARY) return;
                if (player.replaceQueued(fallback, enhanced)) {
                    activeCandidateLevel = 1;
                    planningTrack = enhanced.anchorTrack;
                    planningAnalysis = enhanced.anchorAnalysis;
                    planningFragment = enhanced.anchorFragment;
                    planningPositionMs = enhanced.anchorPositionMs;
                    guaranteedRenderedHorizonMs = player.bufferedHorizonMs();
                }
            }
        } catch (Exception error) {
            if (job == enhancementSerial.get()) {
                Log.w(TAG, "event=enhanced_render_failed candidate=" + candidate.track.id
                        + " reason=" + error.getClass().getSimpleName(), error);
            }
        } finally {
            synchronized (planControl) {
                if (job == enhancementSerial.get()) enhancementTask = null;
            }
        }
    }

    private boolean isCurrentPlan(int token, int epoch) {
        return running && token == generation && epoch == planEpoch.get() && player != null;
    }

    private void failCurrentPlan(int token, int epoch) {
        synchronized (planControl) {
            if (!isCurrentPlan(token, epoch)) return;
            transitionMachine.fail();
            publishTransitionState();
        }
    }

    private void cancelEnhancementLocked() {
        enhancementSerial.incrementAndGet();
        Future<?> task = enhancementTask;
        enhancementTask = null;
        if (task != null) task.cancel(true);
    }

    private void publishTransitionState() {
        transitionState = transitionMachine.state().name();
        transitionReadiness = transitionMachine.readinessPercent();
        missedActivationBoundaries = transitionMachine.missedBoundaries();
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
                if (!scene.validCandidate || transitionMachine.state()
                        != TransitionStateMachine.State.WAITING_FOR_ACTIVATION_BOUNDARY
                        || !transitionMachine.activateAtBoundary(scene.activationBoundary)) return false;
                activeCandidateLevel = scene.candidateLevel;
                transitionInProgress = true;
                transitioningScene = scene;
                publishTransitionState();
                cancelEnhancementLocked();
            }
            activeScene = scene;
            return true;
        }
    }

    @Override public void onSceneStarted(RenderedScene scene) {
        main.post(() -> {
            if (!running) return;
            sceneStartedAt = System.currentTimeMillis();
            sceneDurationMs = Math.max(1L, scene.audio.durationMs());
            transitionInProgress = transitionMachine.state()
                    == TransitionStateMachine.State.TRANSITIONING;
            playbackPositionMs = 0L;
            playbackDurationMs = sceneDurationMs;
            progress = 0;
            currentTrack = scene.title;
            currentMeta = scene.description;
            status = scene.transitionScene
                    ? "Stem Director: один слой остаётся непрерывным, остальные плавно морфятся"
                    : "Трек играет сольный участок";
            feedbackState = getSharedPreferences("feedback", MODE_PRIVATE)
                    .getInt("track_" + scene.anchorTrack.id, 0);
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
                    transitionMachine.land();
                    transitioningScene = null;
                    publishTransitionState();
                    transitionInProgress = false;
                    activeCandidateLevel = -1;
                }
                if (replanAfterCurrent && currentPlan) {
                    replanAfterCurrent = false;
                    planningTrack = scene.anchorTrack;
                    planningAnalysis = scene.anchorAnalysis;
                    planningFragment = scene.anchorFragment;
                    planningPositionMs = scene.anchorPositionMs;
                }
                if (activeScene == scene) activeScene = null;
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
        long requested = Math.max(0L, Math.min(playbackDurationMs, positionMs));
        player.seekTo(requested);
        invalidateFuturePlan("Seek применён без щелчка · старый план отменён");
    }

    private void invalidateFuturePlan(String reason) {
        synchronized (planControl) {
            // First epoch cancels active work. Second epoch publishes the replacement anchor.
            planEpoch.incrementAndGet();
            cancelEnhancementLocked();
            transitionMachine.cancel();
            transitioningScene = null;
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
        paused = true;
        player.pause();
        status = "Пауза";
        if (sessionPlayer != null) sessionPlayer.refresh();
        updateNotification();
    }

    private void resumeEngine() {
        if (!running || player == null) return;
        paused = false;
        player.resume();
        status = "Продолжаю single-PCM remix";
        if (sessionPlayer != null) sessionPlayer.refresh();
        updateNotification();
    }

    private void scheduleProgress(int token) {
        main.postDelayed(new Runnable() {
            @Override public void run() {
                if (!running || token != generation) return;
                if (player != null && sceneStartedAt > 0L) {
                    nativeOutputActive = player.nativeOutputActive();
                    playbackPositionMs = player.currentPositionMs();
                    playbackDurationMs = Math.max(1L, player.currentDurationMs());
                    guaranteedRenderedHorizonMs = player.bufferedHorizonMs();
                    committedHorizonMs = Math.min(guaranteedRenderedHorizonMs,
                            barsToMs(planningAnalysis, 2));
                    planningHorizonMs = barsToMs(planningAnalysis, 32);
                    Track runwayTrack = planningTrack;
                    naturalRunwayRemainingMs = runwayTrack == null ? 0L
                            : Math.max(0L, runwayTrack.durationMs - planningPositionMs
                            - FALLBACK_BRIDGE_MS - 1_000L);
                    outputUnderruns = player.underruns();
                    boolean low = guaranteedRenderedHorizonMs < lowWatermarkMs();
                    if (low && !belowLowWatermark) {
                        bufferLowWatermarkEvents++;
                    }
                    belowLowWatermark = low;
                    if (low) {
                        if (transitionMachine.state()
                                == TransitionStateMachine.State.WAITING_FOR_ACTIVATION_BOUNDARY) {
                            synchronized (planControl) {
                                cancelEnhancementLocked();
                            }
                        }
                        scheduleRenderAhead(token);
                    }
                    progress = Math.max(0, Math.min(100,
                            Math.round(playbackPositionMs * 100f / playbackDurationMs)));
                    RenderedScene current = activeScene;
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
            cancelEnhancementLocked();
            transitionMachine.cancel();
            transitioningScene = null;
            planEpoch.incrementAndGet();
            publishTransitionState();
        }
        running = false;
        paused = false;
        status = "Остановлено";
        currentTrack = currentMeta = nextTrack = transition = "—";
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
        return "schema=1\n"
                + "transition_state=" + transitionState + '\n'
                + "natural_runway_remaining_ms=" + naturalRunwayRemainingMs + '\n'
                + "generation_eta=unavailable_no_neural_provider\n"
                + "guaranteed_rendered_horizon_ms=" + guaranteedRenderedHorizonMs + '\n'
                + "planning_horizon_ms=" + planningHorizonMs + '\n'
                + "committed_horizon_ms=" + committedHorizonMs + '\n'
                + "active_candidate_level=" + activeCandidateLevel + '\n'
                + "recent_fragment_ids=" + recentFragmentIds + '\n'
                + "repetition_score=" + repetitionScore + '\n'
                + "novelty_score=" + noveltyScore + '\n'
                + "buffer_low_watermark_events=" + bufferLowWatermarkEvents + '\n'
                + "neural_upgrades_applied=" + neuralUpgradesApplied + '\n'
                + "output_underruns=" + outputUnderruns + '\n'
                + "fallback_reason=" + fallbackReason + '\n';
    }

    private void shareDebugReport() {
        Intent share = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
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
        enhancementWorker.shutdownNow();
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
