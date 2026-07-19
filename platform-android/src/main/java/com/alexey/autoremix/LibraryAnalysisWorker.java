package com.alexey.autoremix;

import android.content.Context;
import android.os.Build;
import android.os.PowerManager;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.work.Data;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.List;

/** Opportunistic local analysis. WorkManager applies charging, idle and battery constraints. */
@OptIn(markerClass = UnstableApi.class)
public final class LibraryAnalysisWorker extends Worker {
    public LibraryAnalysisWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    @NonNull @Override public Result doWork() {
        if (isThermallyConstrained()) return Result.retry();
        List<Track> tracks;
        try {
            tracks = MusicLibrary.scan(getApplicationContext());
        } catch (SecurityException permissionMissing) {
            return Result.success();
        }
        int attempted = 0;
        int completed = 0;
        int limit = Math.min(24, tracks.size());
        for (Track track : tracks) {
            if (isStopped() || attempted >= limit) break;
            if (isThermallyConstrained()) return Result.retry();
            if (AnalysisCacheStore.get(getApplicationContext(), track) != null) continue;
            attempted++;
            TrackAnalysis analysis = AudioAnalyzer.analyze(getApplicationContext(), track);
            AnalysisCacheStore.put(getApplicationContext(), track, analysis);
            if (!analysis.fallback) completed++;
            setProgressAsync(new Data.Builder()
                    .putInt("completed", attempted)
                    .putInt("total", limit)
                    .build());
        }
        RemixEngineService.analyzedCount = AnalysisCacheStore.entryCount(getApplicationContext());
        RemixEngineService.cacheBytes = AnalysisCacheStore.sizeBytes(getApplicationContext());
        return Result.success(new Data.Builder().putInt("analyzed", completed).build());
    }

    private boolean isThermallyConstrained() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false;
        PowerManager power = getApplicationContext().getSystemService(PowerManager.class);
        return power != null
                && power.getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_MODERATE;
    }
}
