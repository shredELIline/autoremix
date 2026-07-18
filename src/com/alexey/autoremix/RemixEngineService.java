package com.alexey.autoremix;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

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

/**
 * V2 programme engine. Compressed files are decoded ahead, split into complementary layers, rendered
 * into one PCM timeline and sent through one AudioTrack. Automatic mode has no whole-track cuts.
 */
public class RemixEngineService extends Service implements SceneAudioPlayer.Listener {
    public static final String ACTION_START = "com.alexey.autoremix.START";
    public static final String ACTION_PAUSE = "com.alexey.autoremix.PAUSE";
    public static final String ACTION_RESUME = "com.alexey.autoremix.RESUME";
    public static final String ACTION_SKIP = "com.alexey.autoremix.SKIP";
    public static final String ACTION_STOP = "com.alexey.autoremix.STOP";
    public static final String EXTRA_CHAOS = "chaos";
    public static final String EXTRA_OVERLAYS = "overlays";
    public static final String EXTRA_HARMONIC = "harmonic";
    public static final String EXTRA_PATIENCE = "patience";

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

    private static final int NOTIFICATION_ID = 903;
    private static final String CHANNEL = "autoremix_pcm";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean renderTaskPending = new AtomicBoolean();
    private final Map<Long, TrackAnalysis> cache = new ConcurrentHashMap<>();
    private final Random random = new Random();
    private final Deque<Long> recent = new ArrayDeque<>();

    private List<Track> library = new ArrayList<>();
    private SceneAudioPlayer player;
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

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
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
            replanNextScene();
        } else if (ACTION_STOP.equals(action)) {
            stopEngine();
            stopSelf();
        }
        return START_STICKY;
    }

    private void startEngine() {
        stopAudioOnly();
        int token = ++generation;
        running = true;
        paused = false;
        sequence = 0;
        recent.clear();
        cache.clear();
        analyzedCount = 0;
        progress = 0;
        status = "Сканирую локальную медиатеку…";
        currentTrack = "—";
        currentMeta = "Новый single-PCM engine · без MediaPlayer-переключений";
        nextTrack = "—";
        transition = "Строгий vibe-фильтр и stem-сцены";
        player = new SceneAudioPlayer(this);
        player.start();
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
        } catch (Throwable error) {
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
        while (running && token == generation && player != null && player.queuedScenes() < 1) {
            if (planningTrack == null || planningAnalysis == null || planningFragment == null) return;
            Candidate candidate = chooseCandidate(planningTrack, planningAnalysis, planningFragment);
            RenderedScene scene;
            try {
                if (candidate == null) {
                    long available = planningTrack.durationMs - planningPositionMs;
                    if (available < 18_000L) {
                        failOnMain("Не нашёл достаточно похожую песню для безопасного stem-перехода");
                        return;
                    }
                    scene = SceneRenderer.renderContinuation(this, planningTrack, planningAnalysis,
                            planningFragment, planningPositionMs, 36_000L,
                            "Продолжаю текущий вайб: несовместимые песни пропущены");
                } else {
                    long soloMs = 50_000L + patience * 360L;
                    main.post(() -> {
                        nextTrack = candidate.track.displayName() + " · vibe "
                                + Math.round(candidate.plan.vibeScore * 100f) + "%";
                        transition = candidate.plan.type.label + " · " + candidate.plan.reason;
                        status = "Декодирую и разделяю будущую сцену на слои…";
                    });
                    scene = SceneRenderer.renderTransition(this,
                            planningTrack, planningAnalysis, planningFragment, planningPositionMs,
                            candidate.track, candidate.analysis, candidate.fragment, candidate.plan,
                            soloMs);
                }
            } catch (Throwable renderError) {
                remember(candidate == null ? planningTrack.id : candidate.track.id);
                main.post(() -> status = "Пропускаю неудачную сцену: "
                        + renderError.getClass().getSimpleName());
                continue;
            }
            if (token != generation || !running) return;
            if (!player.offer(scene)) return;
            planningTrack = scene.anchorTrack;
            planningAnalysis = scene.anchorAnalysis;
            planningFragment = scene.anchorFragment;
            planningPositionMs = scene.anchorPositionMs;
            remember(planningTrack.id);
            sequence++;
        }
    }

    private Candidate chooseCandidate(Track anchor, TrackAnalysis anchorAnalysis,
                                      TrackAnalysis.Fragment anchorFragment) {
        List<Candidate> candidates = new ArrayList<>();
        List<Track> shuffled = new ArrayList<>(library);
        java.util.Collections.shuffle(shuffled, random);
        int inspected = 0;
        for (Track track : shuffled) {
            if (track.id == anchor.id || recent.contains(track.id)) continue;
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
        TrackAnalysis result = AudioAnalyzer.analyze(this, track);
        cache.put(track.id, result);
        analyzedCount = cache.size();
        return result;
    }

    @Override public void onSceneStarted(RenderedScene scene) {
        main.post(() -> {
            if (!running) return;
            sceneStartedAt = System.currentTimeMillis();
            sceneDurationMs = Math.max(1L, scene.audio.durationMs());
            progress = 0;
            currentTrack = scene.title;
            currentMeta = scene.description;
            status = scene.transitionScene
                    ? "Stem Director: один слой остаётся непрерывным, остальные плавно морфятся"
                    : "Трек играет сольный участок";
            updateNotification();
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
            scheduleRenderAhead(generation);
        });
    }

    @Override public void onPlaybackError(String error) {
        failOnMain("AudioTrack: " + error);
    }

    private void replanNextScene() {
        if (!running || player == null) return;
        player.clearQueued();
        replanAfterCurrent = true;
        nextTrack = "Перестраиваю следующую stem-сцену";
        transition = "Текущая дорожка не обрывается; новая сцена начнётся после неё";
        status = "Сохраняю непрерывность текущего PCM-сегмента";
    }

    private void pauseEngine() {
        if (!running || player == null) return;
        paused = true;
        player.pause();
        status = "Пауза";
        updateNotification();
    }

    private void resumeEngine() {
        if (!running || player == null) return;
        paused = false;
        player.resume();
        status = "Продолжаю single-PCM remix";
        updateNotification();
    }

    private void scheduleProgress(int token) {
        main.postDelayed(new Runnable() {
            @Override public void run() {
                if (!running || token != generation) return;
                if (!paused && sceneStartedAt > 0L) {
                    long elapsed = System.currentTimeMillis() - sceneStartedAt;
                    progress = Math.max(0, Math.min(100,
                            Math.round(elapsed * 100f / Math.max(1L, sceneDurationMs))));
                }
                main.postDelayed(this, 450L);
            }
        }, 450L);
    }

    private void remember(long id) {
        recent.remove(id);
        recent.addFirst(id);
        int max = Math.min(14, Math.max(4, library.size() / 2));
        while (recent.size() > max) recent.removeLast();
    }

    private void stopEngine() {
        generation++;
        running = false;
        paused = false;
        status = "Остановлено";
        currentTrack = currentMeta = nextTrack = transition = "—";
        progress = 0;
        stopAudioOnly();
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
    }

    private void failOnMain(String message) {
        main.post(() -> {
            status = message;
            running = false;
            paused = false;
            stopAudioOnly();
            updateNotification();
            stopForeground(true);
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
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, notification(status));
    }

    @Override public void onDestroy() {
        stopEngine();
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
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
