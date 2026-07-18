package com.alexey.autoremix;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Decodes an exact local-file segment to interleaved stereo PCM for the single-output mixer. */
final class PcmDecoder {
    private PcmDecoder() {}

    static PcmAudio decode(Context context, Track track, long startMs, long durationMs,
                           int targetSampleRate) throws Exception {
        long startUs = Math.max(0L, startMs) * 1000L;
        long durationUs = Math.max(250L, durationMs) * 1000L;
        long endUs = Math.min(track.durationMs * 1000L, startUs + durationUs);
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        StereoCollector collector = new StereoCollector((int) Math.min(4_000_000L,
                Math.max(65_536L, durationMs * 96L)));
        try {
            extractor.setDataSource(context, track.uri, null);
            int selected = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat candidate = extractor.getTrackFormat(i);
                String mime = candidate.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    selected = i;
                    format = candidate;
                    break;
                }
            }
            if (selected < 0 || format == null) return PcmAudio.silence(targetSampleRate, durationMs);
            extractor.selectTrack(selected);
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime == null) throw new IllegalArgumentException("missing audio mime");
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            int sampleRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44_100;
            int channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? Math.max(1, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)) : 2;
            int pcmEncoding = AudioFormat.ENCODING_PCM_16BIT;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            long deadline = System.currentTimeMillis() + Math.max(16_000L, durationMs * 2L + 8_000L);

            while (!outputDone && System.currentTimeMillis() < deadline) {
                if (!inputDone) {
                    int index = codec.dequeueInputBuffer(10_000);
                    if (index >= 0) {
                        ByteBuffer input = codec.getInputBuffer(index);
                        if (input != null) {
                            input.clear();
                            int size = extractor.readSampleData(input, 0);
                            long timeUs = extractor.getSampleTime();
                            if (size < 0 || timeUs < 0 || timeUs > endUs + 500_000L) {
                                codec.queueInputBuffer(index, 0, 0,
                                        Math.max(startUs, timeUs), MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputDone = true;
                            } else {
                                codec.queueInputBuffer(index, 0, size, timeUs, 0);
                                extractor.advance();
                            }
                        }
                    }
                }

                int outIndex = codec.dequeueOutputBuffer(info, 10_000);
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat out = codec.getOutputFormat();
                    if (out.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (out.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = Math.max(1, out.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                    if (Build.VERSION.SDK_INT >= 24 && out.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        pcmEncoding = out.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    }
                } else if (outIndex >= 0) {
                    ByteBuffer raw = codec.getOutputBuffer(outIndex);
                    if (raw != null && info.size > 0) {
                        ByteBuffer data = raw.duplicate().order(ByteOrder.LITTLE_ENDIAN);
                        data.position(info.offset);
                        data.limit(Math.min(data.capacity(), info.offset + info.size));
                        int bytesPerSample = pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT ? 4 : 2;
                        int frameBytes = Math.max(bytesPerSample, channels * bytesPerSample);
                        int frameCount = data.remaining() / frameBytes;
                        long bufferStartUs = info.presentationTimeUs;
                        for (int frame = 0; frame < frameCount; frame++) {
                            long frameTimeUs = bufferStartUs + Math.round(frame * 1_000_000.0 / sampleRate);
                            float left;
                            float right;
                            if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                left = sanitize(data.getFloat());
                                right = channels > 1 ? sanitize(data.getFloat()) : left;
                                for (int c = 2; c < channels; c++) data.getFloat();
                            } else {
                                left = data.getShort() / 32768f;
                                right = channels > 1 ? data.getShort() / 32768f : left;
                                for (int c = 2; c < channels; c++) data.getShort();
                            }
                            if (frameTimeUs >= startUs && frameTimeUs < endUs) collector.add(left, right);
                        }
                    }
                    outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            || info.presentationTimeUs >= endUs;
                    codec.releaseOutputBuffer(outIndex, false);
                }
            }
            PcmAudio source = new PcmAudio(sampleRate, collector.toArray());
            if (source.frames() == 0) return PcmAudio.silence(targetSampleRate, durationMs);
            return source.sampleRate == targetSampleRate ? source : resample(source, targetSampleRate);
        } finally {
            try { if (codec != null) codec.stop(); } catch (Throwable ignored) {}
            try { if (codec != null) codec.release(); } catch (Throwable ignored) {}
            try { extractor.release(); } catch (Throwable ignored) {}
        }
    }

    static PcmAudio resample(PcmAudio input, int targetRate) {
        if (input.sampleRate == targetRate || input.frames() < 2) return input;
        double ratio = targetRate / (double) input.sampleRate;
        int outputFrames = Math.max(1, (int) Math.round(input.frames() * ratio));
        float[] out = new float[outputFrames * 2];
        for (int i = 0; i < outputFrames; i++) {
            double sourcePosition = i / ratio;
            int a = Math.min(input.frames() - 1, (int) sourcePosition);
            int b = Math.min(input.frames() - 1, a + 1);
            float t = (float) (sourcePosition - a);
            out[i * 2] = lerp(input.stereo[a * 2], input.stereo[b * 2], t);
            out[i * 2 + 1] = lerp(input.stereo[a * 2 + 1], input.stereo[b * 2 + 1], t);
        }
        return new PcmAudio(targetRate, out);
    }

    private static float sanitize(float value) {
        return Float.isFinite(value) ? Math.max(-1f, Math.min(1f, value)) : 0f;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static final class StereoCollector {
        private float[] values;
        private int size;

        StereoCollector(int initialSamples) {
            values = new float[Math.max(4_096, initialSamples)];
        }

        void add(float left, float right) {
            ensure(size + 2);
            values[size++] = left;
            values[size++] = right;
        }

        private void ensure(int requested) {
            if (requested <= values.length) return;
            int next = values.length;
            while (next < requested) next = Math.min(Integer.MAX_VALUE - 8, next + Math.max(4_096, next / 2));
            values = Arrays.copyOf(values, next);
        }

        float[] toArray() {
            return Arrays.copyOf(values, size);
        }
    }
}
