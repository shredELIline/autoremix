package com.alexey.autoremix;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

public final class GoldenContinuousSceneSmokeTest {
    public static void main(String[] args) throws Exception {
        Path root = repositoryRoot();
        Path golden = root.resolve("tests/fixtures/golden/continuous_stem_scene");
        Path wave = golden.resolve("continuous-stem-scene.wav");
        byte[] audio = Files.readAllBytes(wave);
        ByteBuffer header = ByteBuffer.wrap(audio).order(ByteOrder.LITTLE_ENDIAN);
        int channels = Short.toUnsignedInt(header.getShort(22));
        int sampleRate = header.getInt(24);
        int dataBytes = header.getInt(40);
        if (channels != 2 || sampleRate != 48_000 || dataBytes != 1_152_000 * 4
                || audio.length != dataBytes + 44) {
            throw new AssertionError("golden WAV format mismatch");
        }

        String plan = Files.readString(golden.resolve("transition-plan.json"));
        String quality = Files.readString(golden.resolve("quality-report.json"));
        String digest = hex(MessageDigest.getInstance("SHA-256").digest(audio));
        if (!plan.contains("\"selectedStrategy\": \"PRESERVE_GUITAR\"")
                || !plan.contains("\"origin\": \"DETERMINISTIC\"")
                || !plan.contains("\"aiPlannerUsed\": false")
                || !plan.contains("\"vocalOwnershipTimeline\"")
                || !plan.contains("\"source\": \"GENERATED\"")
                || !quality.contains(digest)
                || !quality.contains("\"activationGapMs\": 0.0")
                || !quality.contains("\"activationUnderruns\": 0")) {
            throw new AssertionError("golden plan or metrics are incomplete");
        }
        if (Files.exists(root.resolve("docs/assets/audio/procedural-bridge.wav"))
                || Files.exists(root.resolve("docs/assets/audio/combined-demo.wav"))) {
            throw new AssertionError("standalone bridge artifact still exists");
        }
        System.out.println("Continuous-scene golden fixture OK; sha256=" + digest);
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 4 && current != null; depth++, current = current.getParent()) {
            if (Files.isDirectory(current.resolve("tests/fixtures/golden"))) return current;
        }
        throw new IllegalStateException("repository root not found");
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) text.append(String.format("%02x", value & 0xff));
        return text.toString();
    }
}
