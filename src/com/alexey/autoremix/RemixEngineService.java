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
import java.util.List;
import java.util.Map;
import java.util.Random;
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

    private static final int NOTIFICATION_ID = 903;
    private static final String CHANNEL = "autoremix_pcm";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean renderTaskPending = new AtomicBoolean();
    private final AtomicInteger planEpoch = new AtomicInteger();
    private final Map<Long, TrackAnalysis> cache = new ConcurrentHashMap<>();
    private final Random random = new Random();
    private final Deque<Long> recent = new ArrayDeque<>();

    private List<Track> library = new ArrayList<>();
    private SceneAudioPlayer player;
    private AutoRemixSessionPlayer sessionPlayer;
    private MediaSession mediaSession;
    private Track planningTrack;
    private TrackAnalysis planningAnalysis;
    private TrackAnalysis.Fragment planningFragment;
    private long planningPositionMs;
    private int chaos = 62;
    private int patience = 82;
    private boolean overlays = true;
    private boolean harmonic = true;
    private int sequence;
    private int generation;
    private volatile long sceneStartedAt;
    private volatile long sceneDurationMs = 1L;
    private volatile boolean replanAfterCurrent;
    private volatile RenderedScene activeScene;
    private int pendingNextCount;
    private volatile int candidateOffset;
    private volatile long forcedTargetId = -1L;
    private long previousAnchorId = -1L;
    private volatile boolean manualEscapePending;

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
        } else if (ACTION_STOP.equals(action)) {
            stopEngine();
            stopSelf();
        } else {
            return super.onStartCommand(intent, flags, startId);
        }
        return START_STICKY;
    }

    private void startEngine() {
        stopAudioOnly();
        int token = ++generation;
        running = true;
        paused = false;
        sequence = 0;
        planEpoch.incrementAndGet();
        pendingNextCount = 0;
        candidateOffset = 0;
        manualEscapePending = false;
        forcedTargetId = -1L;
        previousAnchorId = -1L;
        activeScene = null;
        recent.clear();
        cache.clear();
        analyzedCount = AnalysisCacheStore.entryCount(this);
        progress = 0;
        playbackPositionMs = 0L;
        playbackDurationMs = 1L;
        nativeOutputActive = false;
        transitionInProgress = false;
        transitionReadiness = 0;
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
            RenderedScene prelude = SceneRenderer.renderPrelude(this, first, analysis, fragment, 52_000L);
            if (token != generation || !running) return;
            planningTrack = prelude.anchorTrack;
            planningAnalysis = prelude.anchorAnalysis;
            planningFragment = prelude.anchorFragment;
            planningPositionMs = prelude.anchorPositionMs;
            remember(first.id);
            if (!player.offer(prelude)) throw new IllegalStateException("PCM queue rejected prelude");
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
        if (!running || token != generation || player == null || player.queuedScenes() >= 1) return;
        if (!renderTaskPending.compareAndSet(false, true)) return;
        worker.submit(() -> {
            try {
                renderAhead(token);
            } finally {
                renderTaskPending.set(false);
            }
        });
    }

    private void renderAhead(int token) {
        int failedScenes = 0;
        while (running && token == generation && player != null && player.queuedScenes() < 1) {
            int epoch = planEpoch.get();
            Track outgoingTrack = planningTrack;
            TrackAnalysis outgoingAnalysis = planningAnalysis;
            TrackAnalysis.Fragment outgoingFragment = planningFragment;
            long outgoingPositionMs = planningPositionMs;
            if (outgoingTrack == null || outgoingAnalysis == null || outgoingFragment == null) return;
            transitionReadiness = 12;
            Candidate candidate = chooseCandidate(outgoingTrack, outgoingAnalysis, outgoingFragment);
            if (candidate == null) {
                candidate = chooseEmergencyCandidate(outgoingTrack, outgoingAnalysis, outgoingFragment);
            }
            boolean escapeScene = manualEscapePending;
            RenderedScene scene;
            try {
                if (candidate == null) {
                    long available = outgoingTrack.durationMs - outgoingPositionMs;
                    if (available < 8_000L) {
                        retryRenderLater(token);
                        return;
                    }
                    scene = SceneRenderer.renderContinuation(this, outgoingTrack, outgoingAnalysis,
                            outgoingFragment, outgoingPositionMs, 36_000L,
                            "Продолжаю текущий вайб: несовместимые песни пропущены");
                } else {
                    long soloMs = 50_000L + patience * 360L;
                    final Candidate selected = candidate;
                    main.post(() -> {
                        nextTrack = selected.track.displayName() + " · vibe "
                                + Math.round(selected.plan.vibeScore * 100f) + "%";
                        transition = selected.plan.type.label + " · " + selected.plan.reason;
                        status = "Декодирую и разделяю будущую сцену на слои…";
                        transitionReadiness = 48;
                    });
                    if (escapeScene) {
                        scene = SceneRenderer.renderEmergencyCrossfade(this,
                                outgoingTrack, outgoingAnalysis, outgoingFragment, outgoingPositionMs,
                                candidate.track, candidate.analysis, candidate.fragment, 8_000L);
                    } else {
                        scene = SceneRenderer.renderTransition(this,
                                outgoingTrack, outgoingAnalysis, outgoingFragment, outgoingPositionMs,
                                candidate.track, candidate.analysis, candidate.fragment, candidate.plan,
                                soloMs);
                    }
                }
            } catch (Exception renderError) {
                Log.w(TAG, "event=scene_render_failed anchor=" + outgoingTrack.id
                        + " candidate=" + (candidate == null ? -1L : candidate.track.id)
                        + " reason=" + renderError.getClass().getSimpleName(), renderError);
                remember(candidate == null ? outgoingTrack.id : candidate.track.id);
                main.post(() -> status = "Пропускаю неудачную сцену: "
                        + renderError.getClass().getSimpleName());
                if (++failedScenes >= 3) {
                    if (candidate == null) {
                        retryRenderLater(token);
                        return;
                    }
                    try {
                        scene = SceneRenderer.renderEmergencyCrossfade(this,
                                outgoingTrack, outgoingAnalysis, outgoingFragment, outgoingPositionMs,
                                candidate.track, candidate.analysis, candidate.fragment, 8_000L);
                    } catch (Exception fallbackError) {
                        Log.w(TAG, "event=emergency_render_failed anchor=" + outgoingTrack.id
                                + " candidate=" + candidate.track.id
                                + " reason=" + fallbackError.getClass().getSimpleName(), fallbackError);
                        retryRenderLater(token);
                        return;
                    }
                } else {
                    continue;
                }
            }
            if (token != generation || !running) return;
            if (epoch != planEpoch.get()) continue;
            if (!player.offer(scene)) return;
            if (escapeScene) {
                player.seekTo(Math.max(player.currentPositionMs(), player.currentDurationMs() - 10_000L));
            }
            planningTrack = scene.anchorTrack;
            planningAnalysis = scene.anchorAnalysis;
            planningFragment = scene.anchorFragment;
            planningPositionMs = scene.anchorPositionMs;
            remember(planningTrack.id);
            manualEscapePending = false;
            forcedTargetId = -1L;
            candidateOffset = 0;
            transitionReadiness = 100;
            sequence++;
        }
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
            if (track.id == anchor.id || (recent.contains(track.id) && track.id != forcedTargetId)) continue;
            if (++inspected > 34) break;
            TrackAnalysis analysis = analyze(track);
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
            TrackAnalysis analysis = analyze(track);
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

    @Override public void onSceneStarted(RenderedScene scene) {
        main.post(() -> {
            if (!running) return;
            RenderedScene previousScene = activeScene;
            if (previousScene != null && previousScene.anchorTrack.id != scene.anchorTrack.id) {
                previousAnchorId = previousScene.anchorTrack.id;
            }
            sceneStartedAt = System.currentTimeMillis();
            sceneDurationMs = Math.max(1L, scene.audio.durationMs());
            activeScene = scene;
            transitionInProgress = false;
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
            if (replanAfterCurrent) {
                replanAfterCurrent = false;
                planningTrack = scene.anchorTrack;
                planningAnalysis = scene.anchorAnalysis;
                planningFragment = scene.anchorFragment;
                planningPositionMs = scene.anchorPositionMs;
            }
            if (activeScene == scene) activeScene = null;
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
        player.seekTo(Math.max(0L, Math.min(playbackDurationMs, positionMs)));
        invalidateFuturePlan("Seek применён без щелчка · старый план отменён");
    }

    private void invalidateFuturePlan(String reason) {
        // First epoch cancels active work. Second epoch publishes the replacement anchor.
        planEpoch.incrementAndGet();
        player.clearQueued();
        RenderedScene current = activeScene;
        if (current != null) {
            planningTrack = current.anchorTrack;
            planningAnalysis = current.anchorAnalysis;
            planningFragment = current.anchorFragment;
            planningPositionMs = current.anchorPositionMs;
        } else {
            replanAfterCurrent = true;
        }
        planEpoch.incrementAndGet();
        transitionReadiness = 0;
        queueSnapshot = "";
        status = reason;
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
                    progress = Math.max(0, Math.min(100,
                            Math.round(playbackPositionMs * 100f / playbackDurationMs)));
                    RenderedScene current = activeScene;
                    transitionInProgress = current != null && current.transitionScene
                            && progress >= 55 && progress < 98;
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
        queueSnapshot = "";
        planEpoch.incrementAndGet();
        main.removeCallbacks(settleNext);
        stopAudioOnly();
        if (sessionPlayer != null) sessionPlayer.refresh();
        stopForeground(true);
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
