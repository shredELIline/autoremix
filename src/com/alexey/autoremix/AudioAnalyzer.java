package com.alexey.autoremix;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

final class AudioAnalyzer {
    private static final long WINDOW_US = 5_500_000L;
    private static final int MAX_ANALYSIS_SAMPLES = 90_000;
    private static final int TARGET_RATE = 11_025;

    private AudioAnalyzer() {}

    static TrackAnalysis analyze(Context context, Track track) {
        try {
            double[] fractions = {0.08, 0.25, 0.43, 0.62, 0.80};
            List<WindowResult> windows = new ArrayList<>();
            for (double fraction : fractions) {
                long maxStart = Math.max(0, track.durationMs * 1000L - WINDOW_US - 1_000_000L);
                long startUs = Math.min(maxStart, Math.max(0, (long) (track.durationMs * 1000L * fraction)));
                DecodedPcm decoded = decodeWindow(context, track, startUs, WINDOW_US);
                if (decoded.pcm.length < 4096) continue;
                WindowResult window = analyzeWindow(decoded.pcm, TARGET_RATE, startUs / 1000L,
                        decoded.centerDominance);
                windows.add(window);
            }
            if (windows.isEmpty()) return TrackAnalysis.fallback(track);

            List<Float> bpms = new ArrayList<>();
            float totalWeight = 0f;
            float weightedEnergy = 0f;
            double[] chroma = new double[12];
            List<TrackAnalysis.Fragment> fragments = new ArrayList<>();
            for (WindowResult window : windows) {
                if (window.bpm >= 65 && window.bpm <= 190) bpms.add(window.bpm);
                float weight = .35f + window.energy;
                totalWeight += weight;
                weightedEnergy += window.energy * weight;
                for (int i = 0; i < 12; i++) chroma[i] += window.chroma[i] * weight;
                fragments.add(new TrackAnalysis.Fragment(window.cueMs, window.energy, window.bpm, window.bpmConfidence,
                        window.transientDensity, window.brightness, window.dynamicRange, window.phraseScore,
                        window.gridConfidence, window.vocalPresence, window.bassPresence,
                        window.percussiveness, window.loudnessDb, window.beatPeriodMs));
            }
            float bpm = median(bpms, 120f);
            bpm = canonicalBpm(bpm);
            float energy = totalWeight == 0 ? .5f : clamp(weightedEnergy / totalWeight, 0f, 1f);
            KeyResult key = estimateKey(chroma);
            float loudnessDb = fragments.isEmpty() ? -16f : 0f;
            for (TrackAnalysis.Fragment fragment : fragments) loudnessDb += fragment.loudnessDb;
            if (!fragments.isEmpty()) loudnessDb /= fragments.size();
            return new TrackAnalysis(bpm, energy, key.key, key.minor, key.confidence, fragments, false, loudnessDb);
        } catch (Throwable ignored) {
            return TrackAnalysis.fallback(track);
        }
    }

    private static DecodedPcm decodeWindow(Context context, Track track, long startUs, long durationUs) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        FloatCollector output = new FloatCollector(MAX_ANALYSIS_SAMPLES);
        try {
            extractor.setDataSource(context, track.uri, null);
            int audioTrack = -1;
            MediaFormat inputFormat = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrack = i;
                    inputFormat = format;
                    break;
                }
            }
            if (audioTrack < 0 || inputFormat == null) return new DecodedPcm(new float[0], .5f);
            extractor.selectTrack(audioTrack);
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            String mime = inputFormat.getString(MediaFormat.KEY_MIME);
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(inputFormat, null, null, 0);
            codec.start();

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            long endUs = startUs + durationUs;
            int sampleRate = inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44_100;
            int channels = inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? Math.max(1, inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)) : 2;
            int pcmEncoding = 2;
            int resamplePhase = 0;
            double centerEnergy = 1e-9;
            double sideEnergy = 1e-9;
            long stereoFrames = 0L;
            long deadlineMs = System.currentTimeMillis() + 12_000L;

            while (!outputDone && output.size() < MAX_ANALYSIS_SAMPLES && System.currentTimeMillis() < deadlineMs) {
                if (!inputDone) {
                    int inputIndex = codec.dequeueInputBuffer(8_000);
                    if (inputIndex >= 0) {
                        ByteBuffer input = codec.getInputBuffer(inputIndex);
                        if (input != null) {
                            int size = extractor.readSampleData(input, 0);
                            long timeUs = extractor.getSampleTime();
                            if (size < 0 || timeUs < 0 || timeUs > endUs) {
                                codec.queueInputBuffer(inputIndex, 0, 0, Math.max(startUs, timeUs), MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone = true;
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, size, timeUs, 0);
                                extractor.advance();
                            }
                        }
                    }
                }

                int outputIndex = codec.dequeueOutputBuffer(info, 8_000);
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outFormat = codec.getOutputFormat();
                    if (outFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (outFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = Math.max(1, outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                    if (Build.VERSION.SDK_INT >= 24 && outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        pcmEncoding = outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    }

                } else if (outputIndex >= 0) {
                    ByteBuffer buffer = codec.getOutputBuffer(outputIndex);
                    if (buffer != null && info.size > 0 && info.presentationTimeUs >= startUs - 200_000L) {
                        ByteBuffer data = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
                        data.position(info.offset);
                        data.limit(Math.min(data.capacity(), info.offset + info.size));
                        if (pcmEncoding == 4) {
                            while (data.remaining() >= channels * 4 && output.size() < MAX_ANALYSIS_SAMPLES) {
                                float left = data.getFloat();
                                float right = channels > 1 ? data.getFloat() : left;
                                for (int c = 2; c < channels; c++) data.getFloat();
                                float mono = (left + right) * .5f;
                                float side = (left - right) * .5f;
                                centerEnergy += mono * mono;
                                sideEnergy += side * side;
                                stereoFrames++;
                                resamplePhase += TARGET_RATE;
                                if (resamplePhase >= sampleRate) {
                                    output.add(clamp(mono, -1f, 1f));
                                    resamplePhase -= sampleRate;
                                }
                            }
                        } else {
                            while (data.remaining() >= channels * 2 && output.size() < MAX_ANALYSIS_SAMPLES) {
                                float left = data.getShort() / 32768f;
                                float right = channels > 1 ? data.getShort() / 32768f : left;
                                for (int c = 2; c < channels; c++) data.getShort();
                                float mono = (left + right) * .5f;
                                float side = (left - right) * .5f;
                                centerEnergy += mono * mono;
                                sideEnergy += side * side;
                                stereoFrames++;
                                resamplePhase += TARGET_RATE;
                                if (resamplePhase >= sampleRate) {
                                    output.add(mono);
                                    resamplePhase -= sampleRate;
                                }
                            }
                        }
                    }
                    outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 || info.presentationTimeUs >= endUs;
                    codec.releaseOutputBuffer(outputIndex, false);
                }
            }
            float centerDominance = stereoFrames <= 0 ? .55f : clamp((float)
                    (centerEnergy / Math.max(1e-9, centerEnergy + sideEnergy)), 0f, 1f);
            return new DecodedPcm(output.toArray(), centerDominance);
        } finally {
            try { if (codec != null) codec.stop(); } catch (Throwable ignored) {}
            try { if (codec != null) codec.release(); } catch (Throwable ignored) {}
            try { extractor.release(); } catch (Throwable ignored) {}
        }
    }

    private static WindowResult analyzeWindow(float[] pcm, int sampleRate, long baseMs, float centerDominance) {
        float rms = rms(pcm);
        float loudnessDb = (float) (20.0 * Math.log10(Math.max(1e-6, rms)));
        float energy = clamp((loudnessDb + 42.0f) / 38.0f, 0f, 1f);
        BpmResult bpm = estimateBpm(pcm, sampleRate);
        double[] chroma = computeChroma(pcm, sampleRate);
        OnsetResult onset = strongestOnset(pcm, sampleRate);
        float brightness = brightnessProxy(pcm);
        float dynamicRange = dynamicRange(pcm, sampleRate);
        BandStats bands = bandStats(pcm, sampleRate, bpm.transientDensity, dynamicRange, chroma, centerDominance);

        // Snap the strongest onset to the inferred 4/4 grid. This matters more than a BPM value alone:
        // two equal tempos still flam if their beat phases are offset.
        long rawOnsetMs = onset.offsetMs;
        long snappedBeatMs = bpm.nearestDownbeatMs(rawOnsetMs);
        long cueMs = baseMs + Math.max(0L, snappedBeatMs);
        float phraseScore = clamp(onset.strength * .22f + bpm.confidence * .24f
                + bpm.gridConfidence * .26f + dynamicRange * .12f
                + bpm.transientDensity * .10f + bands.percussiveness * .06f, 0f, 1f);
        return new WindowResult(cueMs, energy, bpm.bpm, bpm.confidence, chroma,
                bpm.transientDensity, brightness, dynamicRange, phraseScore,
                bpm.gridConfidence, bands.vocalPresence, bands.bassPresence,
                bands.percussiveness, loudnessDb, bpm.beatPeriodMs);
    }

    private static BpmResult estimateBpm(float[] pcm, int sampleRate) {
        int hop = 256;
        int frame = 512;
        if (pcm.length < frame * 4) return new BpmResult(120f, 0f, .35f, .10f, 500L, 0L, 0);
        int n = 1 + (pcm.length - frame) / hop;
        float[] env = new float[n];
        float last = 0f;
        for (int i = 0; i < n; i++) {
            double sum = 0;
            int frameStart = i * hop;
            for (int j = 0; j < frame; j++) {
                float x = pcm[frameStart + j];
                sum += x * x;
            }
            float value = (float) Math.sqrt(sum / frame);
            float baseline = last * .72f + value * .28f;
            float onset = Math.max(0f, value - baseline);
            env[i] = onset;
            last = baseline;
        }
        float mean = 0f;
        for (float v : env) mean += v;
        mean /= env.length;
        int strongOnsets = 0;
        float threshold = mean * 1.45f;
        for (int i = 0; i < env.length; i++) {
            env[i] = Math.max(0f, env[i] - mean * .7f);
            if (env[i] > threshold) strongOnsets++;
        }

        float seconds = pcm.length / (float) sampleRate;
        float transientDensity = clamp(strongOnsets / Math.max(1f, seconds * 5.5f), 0f, 1f);
        float envRate = sampleRate / (float) hop;
        int minLag = Math.max(1, Math.round(envRate * 60f / 190f));
        int maxLag = Math.min(env.length - 2, Math.round(envRate * 60f / 65f));
        float best = 0f;
        int bestLag = Math.round(envRate * 60f / 120f);
        double norm = 1e-9;
        for (float v : env) norm += v * v;
        for (int lag = minLag; lag <= maxLag; lag++) {
            double corr = 0;
            for (int i = lag; i < env.length; i++) corr += env[i] * env[i - lag];
            float score = (float) (corr / norm);
            float bpm = envRate * 60f / lag;
            if (bpm < 82) score *= .82f;
            if (bpm > 172) score *= .88f;
            if (score > best) {
                best = score;
                bestLag = lag;
            }
        }

        // Infer beat phase by finding the onset-envelope phase with the most periodic energy.
        int bestPhase = 0;
        double bestPhaseScore = -1;
        double totalOnset = 1e-9;
        for (float value : env) totalOnset += value;
        for (int phase = 0; phase < bestLag; phase++) {
            double score = 0;
            int count = 0;
            for (int i = phase; i < env.length; i += bestLag) {
                score += env[i] * (1.0 + Math.min(8, count) * .015);
                count++;
            }
            if (score > bestPhaseScore) {
                bestPhaseScore = score;
                bestPhase = phase;
            }
        }

        // Approximate the downbeat class by accumulating every fourth beat. It is intentionally
        // conservative: low-confidence grids will never unlock aggressive DJ-cut scenes.
        double[] barClass = new double[4];
        int beatIndex = 0;
        for (int i = bestPhase; i < env.length; i += bestLag) {
            barClass[beatIndex & 3] += env[i];
            beatIndex++;
        }
        int downbeatClass = 0;
        for (int i = 1; i < 4; i++) if (barClass[i] > barClass[downbeatClass]) downbeatClass = i;

        float bpm = canonicalBpm(envRate * 60f / bestLag);
        long beatPeriodMs = Math.max(240L, Math.min(1_000L, Math.round(bestLag * 1000f / envRate)));
        long phaseMs = Math.max(0L, Math.round(bestPhase * 1000f / envRate));
        float phaseConcentration = clamp((float) (bestPhaseScore / totalOnset * 4.2), 0f, 1f);
        float barConcentration = clamp((float) (barClass[downbeatClass] /
                Math.max(1e-9, barClass[0] + barClass[1] + barClass[2] + barClass[3]) * 2.6), 0f, 1f);
        float gridConfidence = clamp(best * 2.2f * .48f + phaseConcentration * .34f
                + barConcentration * .18f, 0f, 1f);
        return new BpmResult(bpm, clamp(best * 3.2f, 0f, 1f), transientDensity,
                gridConfidence, beatPeriodMs, phaseMs, downbeatClass);
    }

    private static BandStats bandStats(float[] pcm, int sampleRate, float transientDensity,
                                       float dynamicRange, double[] chroma, float centerDominance) {
        if (pcm.length == 0) return new BandStats(.42f, .45f, transientDensity);
        double alphaLow = 1.0 - Math.exp(-2.0 * Math.PI * 220.0 / sampleRate);
        double alphaMid = 1.0 - Math.exp(-2.0 * Math.PI * 3_400.0 / sampleRate);
        double lowState = 0;
        double midState = 0;
        double lowEnergy = 0;
        double midEnergy = 0;
        double highEnergy = 0;
        double total = 1e-9;
        int zeroCrossings = 0;
        float previous = pcm[0];
        for (float sample : pcm) {
            lowState += alphaLow * (sample - lowState);
            midState += alphaMid * (sample - midState);
            double low = lowState;
            double mid = midState - lowState;
            double high = sample - midState;
            lowEnergy += low * low;
            midEnergy += mid * mid;
            highEnergy += high * high;
            total += sample * sample;
            if ((sample >= 0f) != (previous >= 0f)) zeroCrossings++;
            previous = sample;
        }
        float bass = clamp((float) (lowEnergy / total * 2.2), 0f, 1f);
        float mid = clamp((float) (midEnergy / total * 1.35), 0f, 1f);
        float high = clamp((float) (highEnergy / total * 1.9), 0f, 1f);
        float zcr = clamp(zeroCrossings / Math.max(1f, pcm.length * .18f), 0f, 1f);
        double maxChroma = 0;
        double sumChroma = 1e-9;
        for (double value : chroma) {
            maxChroma = Math.max(maxChroma, value);
            sumChroma += value;
        }
        float tonalConcentration = clamp((float) (maxChroma / sumChroma * 4.2), 0f, 1f);
        float percussiveness = clamp(transientDensity * .66f + high * .16f
                + zcr * .10f + (1f - tonalConcentration) * .08f, 0f, 1f);
        float vocalPresence = clamp(mid * .60f + tonalConcentration * .18f
                + dynamicRange * .12f + centerDominance * .24f
                - percussiveness * .30f - bass * .08f, 0f, 1f);
        return new BandStats(vocalPresence, bass, percussiveness);
    }

    private static OnsetResult strongestOnset(float[] pcm, int sampleRate) {
        int frame = 512;
        int hop = 256;
        int maxFrames = Math.min((pcm.length - frame) / hop, Math.round(sampleRate * 2.5f / hop));
        if (maxFrames <= 2) return new OnsetResult(0, .20f);
        float previous = 0f;
        float best = 0f;
        float average = 0f;
        int bestIndex = 0;
        for (int i = 0; i < maxFrames; i++) {
            double sum = 0;
            int frameStart = i * hop;
            for (int j = 0; j < frame; j++) {
                float x = pcm[frameStart + j];
                sum += x * x;
            }
            float value = (float) Math.sqrt(sum / frame);
            float onset = Math.max(0f, value - previous);
            average += onset;
            if (onset > best) {
                best = onset;
                bestIndex = i;
            }
            previous = value * .85f + previous * .15f;
        }
        average /= Math.max(1, maxFrames);
        float strength = clamp((best / Math.max(1e-5f, average)) / 8f, 0f, 1f);
        return new OnsetResult(Math.round(bestIndex * hop * 1000.0 / sampleRate), strength);
    }

    private static float brightnessProxy(float[] pcm) {
        if (pcm.length < 2) return .4f;
        double diff = 0;
        double level = 1e-9;
        float previous = pcm[0];
        for (int i = 1; i < pcm.length; i++) {
            float value = pcm[i];
            diff += Math.abs(value - previous);
            level += Math.abs(value);
            previous = value;
        }
        return clamp((float) (diff / level * 1.45), 0f, 1f);
    }

    private static float dynamicRange(float[] pcm, int sampleRate) {
        int frame = Math.max(128, sampleRate / 25);
        if (pcm.length < frame * 2) return .35f;
        int count = pcm.length / frame;
        double mean = 0;
        double[] values = new double[count];
        for (int i = 0; i < count; i++) {
            double sum = 0;
            int offset = i * frame;
            for (int j = 0; j < frame; j++) {
                float x = pcm[offset + j];
                sum += x * x;
            }
            values[i] = Math.sqrt(sum / frame);
            mean += values[i];
        }
        mean /= count;
        double variance = 0;
        for (double value : values) {
            double delta = value - mean;
            variance += delta * delta;
        }
        variance /= count;
        double coefficient = Math.sqrt(variance) / Math.max(1e-6, mean);
        return clamp((float) (coefficient * 1.8), 0f, 1f);
    }

    private static double[] computeChroma(float[] pcm, int sampleRate) {
        double[] chroma = new double[12];
        int frameSize = 2048;
        int hop = 1024;
        if (pcm.length < frameSize) return chroma;
        int frameCount = Math.min(72, 1 + (pcm.length - frameSize) / hop);
        for (int frame = 0; frame < frameCount; frame++) {
            int offset = frame * hop;
            for (int midi = 40; midi <= 83; midi++) {
                double frequency = 440.0 * Math.pow(2.0, (midi - 69) / 12.0);
                double magnitude = goertzel(pcm, offset, frameSize, sampleRate, frequency);
                chroma[midi % 12] += Math.sqrt(Math.max(0, magnitude));
            }
        }
        double sum = 1e-12;
        for (double v : chroma) sum += v;
        for (int i = 0; i < 12; i++) chroma[i] /= sum;
        return chroma;
    }

    private static double goertzel(float[] data, int offset, int length, int sampleRate, double frequency) {
        double omega = 2.0 * Math.PI * frequency / sampleRate;
        double coeff = 2.0 * Math.cos(omega);
        double s0, s1 = 0, s2 = 0;
        for (int i = 0; i < length; i++) {
            double window = .5 - .5 * Math.cos(2.0 * Math.PI * i / (length - 1));
            s0 = data[offset + i] * window + coeff * s1 - s2;
            s2 = s1;
            s1 = s0;
        }
        return s1 * s1 + s2 * s2 - coeff * s1 * s2;
    }

    private static KeyResult estimateKey(double[] chroma) {
        double[] major = {6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88};
        double[] minor = {6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17};
        double best = -Double.MAX_VALUE;
        double second = -Double.MAX_VALUE;
        int bestKey = -1;
        boolean bestMinor = false;
        for (int root = 0; root < 12; root++) {
            double majorScore = correlation(chroma, major, root);
            double minorScore = correlation(chroma, minor, root);
            if (majorScore > best) { second = best; best = majorScore; bestKey = root; bestMinor = false; }
            else if (majorScore > second) second = majorScore;
            if (minorScore > best) { second = best; best = minorScore; bestKey = root; bestMinor = true; }
            else if (minorScore > second) second = minorScore;
        }
        float confidence = (float) clamp((float) ((best - second) / (Math.abs(best) + 1e-9) * 6.0), 0f, 1f);
        return new KeyResult(bestKey, bestMinor, confidence);
    }

    private static double correlation(double[] chroma, double[] profile, int root) {
        double score = 0;
        for (int i = 0; i < 12; i++) score += chroma[(root + i) % 12] * profile[i];
        return score;
    }

    private static float rms(float[] pcm) {
        double sum = 0;
        for (float v : pcm) sum += v * v;
        return pcm.length == 0 ? 0f : (float) Math.sqrt(sum / pcm.length);
    }

    static float canonicalBpm(float bpm) {
        if (!Float.isFinite(bpm) || bpm <= 0) return 120f;
        while (bpm < 78f) bpm *= 2f;
        while (bpm > 178f) bpm *= .5f;
        return clamp(bpm, 70f, 190f);
    }

    private static float median(List<Float> values, float fallback) {
        if (values == null || values.isEmpty()) return fallback;
        Collections.sort(values);
        int mid = values.size() / 2;
        if ((values.size() & 1) == 1) return values.get(mid);
        return (values.get(mid - 1) + values.get(mid)) * .5f;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class DecodedPcm {
        final float[] pcm;
        final float centerDominance;
        DecodedPcm(float[] pcm, float centerDominance) {
            this.pcm = pcm;
            this.centerDominance = centerDominance;
        }
    }

    private static final class WindowResult {
        final long cueMs;
        final float energy;
        final float bpm;
        final float bpmConfidence;
        final double[] chroma;
        final float transientDensity;
        final float brightness;
        final float dynamicRange;
        final float phraseScore;
        final float gridConfidence;
        final float vocalPresence;
        final float bassPresence;
        final float percussiveness;
        final float loudnessDb;
        final long beatPeriodMs;

        WindowResult(long cueMs, float energy, float bpm, float bpmConfidence, double[] chroma,
                     float transientDensity, float brightness, float dynamicRange, float phraseScore,
                     float gridConfidence, float vocalPresence, float bassPresence,
                     float percussiveness, float loudnessDb, long beatPeriodMs) {
            this.cueMs = cueMs;
            this.energy = energy;
            this.bpm = bpm;
            this.bpmConfidence = bpmConfidence;
            this.chroma = chroma;
            this.transientDensity = transientDensity;
            this.brightness = brightness;
            this.dynamicRange = dynamicRange;
            this.phraseScore = phraseScore;
            this.gridConfidence = gridConfidence;
            this.vocalPresence = vocalPresence;
            this.bassPresence = bassPresence;
            this.percussiveness = percussiveness;
            this.loudnessDb = loudnessDb;
            this.beatPeriodMs = beatPeriodMs;
        }
    }

    private static final class BpmResult {
        final float bpm;
        final float confidence;
        final float transientDensity;
        final float gridConfidence;
        final long beatPeriodMs;
        final long phaseMs;
        final int downbeatClass;

        BpmResult(float bpm, float confidence, float transientDensity, float gridConfidence,
                  long beatPeriodMs, long phaseMs, int downbeatClass) {
            this.bpm = bpm;
            this.confidence = confidence;
            this.transientDensity = transientDensity;
            this.gridConfidence = gridConfidence;
            this.beatPeriodMs = beatPeriodMs;
            this.phaseMs = phaseMs;
            this.downbeatClass = downbeatClass;
        }

        long nearestDownbeatMs(long aroundMs) {
            long beat = Math.max(240L, beatPeriodMs);
            long origin = phaseMs + downbeatClass * beat;
            long bar = beat * 4L;
            long index = Math.round((aroundMs - origin) / (double) bar);
            long candidate = origin + index * bar;
            while (candidate < 0) candidate += bar;
            return candidate;
        }
    }

    private static final class BandStats {
        final float vocalPresence;
        final float bassPresence;
        final float percussiveness;
        BandStats(float vocalPresence, float bassPresence, float percussiveness) {
            this.vocalPresence = vocalPresence;
            this.bassPresence = bassPresence;
            this.percussiveness = percussiveness;
        }
    }

    private static final class OnsetResult {
        final long offsetMs;
        final float strength;
        OnsetResult(long offsetMs, float strength) {
            this.offsetMs = offsetMs;
            this.strength = strength;
        }
    }

    private static final class KeyResult {
        final int key; final boolean minor; final float confidence;
        KeyResult(int key, boolean minor, float confidence) { this.key = key; this.minor = minor; this.confidence = confidence; }
    }

    private static final class FloatCollector {
        private float[] data;
        private int size;
        FloatCollector(int capacity) { data = new float[Math.min(capacity, 16_384)]; }
        void add(float value) {
            if (size >= data.length) data = Arrays.copyOf(data, Math.min(MAX_ANALYSIS_SAMPLES, data.length * 2));
            if (size < data.length) data[size++] = value;
        }
        int size() { return size; }
        float[] toArray() { return Arrays.copyOf(data, size); }
    }
}
