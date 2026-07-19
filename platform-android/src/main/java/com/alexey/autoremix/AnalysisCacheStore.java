package com.alexey.autoremix;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Versioned, bounded, persistent cache for deterministic track analysis. */
final class AnalysisCacheStore {
    private static final String TAG = "AutoRemixCache";
    private static final int MAGIC = 0x41524D58;
    private static final int SCHEMA = 2;
    private static final int DEFAULT_BUDGET_MB = 256;
    private static final int MIN_BUDGET_MB = 64;
    private static final int MAX_BUDGET_MB = 2_048;
    private static final Object LOCK = new Object();

    private AnalysisCacheStore() {}

    static TrackAnalysis get(Context context, Track track) {
        File file = fileFor(context, track);
        if (!file.isFile()) return null;
        synchronized (LOCK) {
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
                if (in.readInt() != MAGIC || in.readInt() != SCHEMA) return deleteAndNull(file, "schema");
                long duration = in.readLong();
                String identity = in.readUTF();
                if (duration != track.durationMs || !identity.equals(identity(track))) return deleteAndNull(file, "identity");
                float bpm = in.readFloat();
                float energy = in.readFloat();
                int key = in.readInt();
                boolean minor = in.readBoolean();
                float keyConfidence = in.readFloat();
                boolean fallback = in.readBoolean();
                float loudness = in.readFloat();
                int count = in.readInt();
                if (count < 0 || count > 512) return deleteAndNull(file, "fragment_count");
                List<TrackAnalysis.Fragment> fragments = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    fragments.add(new TrackAnalysis.Fragment(
                            in.readLong(), in.readFloat(), in.readFloat(), in.readFloat(),
                            in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat(),
                            in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat(),
                            in.readFloat(), in.readLong()));
                }
                if (!file.setLastModified(System.currentTimeMillis())) {
                    Log.w(TAG, "event=lru_touch_failed file=" + file.getName());
                }
                return new TrackAnalysis(bpm, energy, key, minor, keyConfidence,
                        fragments, fallback, loudness);
            } catch (Exception error) {
                Log.w(TAG, "event=read_failed trackId=" + track.id + " file=" + file.getName()
                        + " reason=" + error.getClass().getSimpleName(), error);
                return deleteAndNull(file, "read_failed");
            }
        }
    }

    static void put(Context context, Track track, TrackAnalysis analysis) {
        if (analysis == null || analysis.fallback) return;
        File file = fileFor(context, track);
        File temporary = new File(file.getParentFile(), file.getName() + ".tmp");
        synchronized (LOCK) {
            if (!file.getParentFile().isDirectory() && !file.getParentFile().mkdirs()) {
                Log.w(TAG, "event=directory_create_failed path=" + file.getParentFile().getName());
                return;
            }
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(temporary)))) {
                out.writeInt(MAGIC);
                out.writeInt(SCHEMA);
                out.writeLong(track.durationMs);
                out.writeUTF(identity(track));
                out.writeFloat(analysis.bpm);
                out.writeFloat(analysis.energy);
                out.writeInt(analysis.key);
                out.writeBoolean(analysis.minor);
                out.writeFloat(analysis.keyConfidence);
                out.writeBoolean(analysis.fallback);
                out.writeFloat(analysis.loudnessDb);
                out.writeInt(analysis.fragments.size());
                for (TrackAnalysis.Fragment fragment : analysis.fragments) {
                    out.writeLong(fragment.cueMs);
                    out.writeFloat(fragment.energy);
                    out.writeFloat(fragment.bpm);
                    out.writeFloat(fragment.confidence);
                    out.writeFloat(fragment.transientDensity);
                    out.writeFloat(fragment.brightness);
                    out.writeFloat(fragment.dynamicRange);
                    out.writeFloat(fragment.phraseScore);
                    out.writeFloat(fragment.gridConfidence);
                    out.writeFloat(fragment.vocalPresence);
                    out.writeFloat(fragment.bassPresence);
                    out.writeFloat(fragment.percussiveness);
                    out.writeFloat(fragment.loudnessDb);
                    out.writeLong(fragment.beatPeriodMs);
                }
            } catch (Exception error) {
                Log.w(TAG, "event=write_failed trackId=" + track.id + " file=" + file.getName()
                        + " reason=" + error.getClass().getSimpleName(), error);
                deleteTemporary(temporary, "write_failed");
                return;
            }
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "event=replace_delete_failed file=" + file.getName());
                deleteTemporary(temporary, "replace_delete_failed");
            } else if (!temporary.renameTo(file)) {
                Log.w(TAG, "event=rename_failed from=" + temporary.getName() + " to=" + file.getName());
                deleteTemporary(temporary, "rename_failed");
            }
            trim(context);
        }
    }

    static int budgetMb(Context context) {
        SharedPreferences preferences = context.getSharedPreferences("autoremix_settings", Context.MODE_PRIVATE);
        return Math.max(MIN_BUDGET_MB, Math.min(MAX_BUDGET_MB,
                preferences.getInt("analysis_cache_budget_mb", DEFAULT_BUDGET_MB)));
    }

    static int setBudgetMb(Context context, int requestedMb) {
        int budgetMb = Math.max(MIN_BUDGET_MB, Math.min(MAX_BUDGET_MB, requestedMb));
        context.getSharedPreferences("autoremix_settings", Context.MODE_PRIVATE)
                .edit().putInt("analysis_cache_budget_mb", budgetMb).apply();
        synchronized (LOCK) {
            trim(context);
        }
        return budgetMb;
    }

    static long sizeBytes(Context context) {
        File[] files = directory(context).listFiles((dir, name) -> name.endsWith(".analysis"));
        if (files == null) return 0L;
        long size = 0L;
        for (File file : files) size += file.length();
        return size;
    }

    static int entryCount(Context context) {
        File[] files = directory(context).listFiles((dir, name) -> name.endsWith(".analysis"));
        return files == null ? 0 : files.length;
    }

    private static void trim(Context context) {
        File[] files = directory(context).listFiles((dir, name) -> name.endsWith(".analysis"));
        if (files == null) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        long budget = budgetMb(context) * 1024L * 1024L;
        long total = 0L;
        for (File file : files) total += file.length();
        for (File file : files) {
            if (total <= budget) break;
            long length = file.length();
            if (file.delete()) total -= length;
            else Log.w(TAG, "event=trim_delete_failed file=" + file.getName());
        }
    }

    private static File fileFor(Context context, Track track) {
        String name = track.id + "-" + track.durationMs + "-"
                + Integer.toUnsignedString(identity(track).hashCode(), 36) + ".analysis";
        return new File(directory(context), name);
    }

    private static File directory(Context context) {
        return new File(context.getFilesDir(), "analysis-v" + SCHEMA);
    }

    private static String identity(Track track) {
        return track.id + "|" + track.durationMs + "|" + track.mime + "|" + track.uri;
    }

    private static TrackAnalysis deleteAndNull(File file, String reason) {
        if (file.exists() && !file.delete()) {
            Log.w(TAG, "event=invalid_delete_failed file=" + file.getName() + " reason=" + reason);
        }
        return null;
    }

    private static void deleteTemporary(File file, String reason) {
        if (file.exists() && !file.delete()) {
            Log.w(TAG, "event=temp_delete_failed file=" + file.getName() + " reason=" + reason);
        }
    }
}
