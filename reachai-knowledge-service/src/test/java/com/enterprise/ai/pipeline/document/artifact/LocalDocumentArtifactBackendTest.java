package com.enterprise.ai.pipeline.document.artifact;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class LocalDocumentArtifactBackendTest {
    private static final String KEY = "knowledge-document-import/dij_12345678901234567890123456789012/source/original";
    @TempDir Path directory;

    @Test
    void backendIdentitySurvivesReconstructionAndIsDistinctForAnotherRoot() throws Exception {
        var first = backend(directory.resolve("first"));
        var restarted = backend(directory.resolve("first"));
        var other = backend(directory.resolve("other"));
        assertEquals(first.storageId(), restarted.storageId());
        assertNotEquals(first.storageId(), other.storageId());
        assertTrue(first.storageId().matches("[a-f0-9]{64}"));
    }

    @Test
    void writesAndDeletesTheExactChineseContentWithoutDeletingBackendIdentity() throws Exception {
        var root = directory.resolve("原件目录");
        var store = backend(root);
        byte[] content = "原件与解析产物的中文回读".getBytes(StandardCharsets.UTF_8);
        store.put(KEY, new ByteArrayInputStream(content), content.length, "text/plain");
        try (var input = store.open(KEY)) {
            assertEquals("原件与解析产物的中文回读", new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        store.delete(KEY);
        store.delete(KEY);
        assertFalse(Files.exists(root.resolve(KEY)));
        assertTrue(Files.exists(root.resolve(".reachai-storage-id")));
        assertEquals(store.storageId(), backend(root).storageId());
    }

    @Test
    void aNewBackendRemovesTheStagingFileLeftByAnAbruptWriterJvm() throws Exception {
        var root = directory.resolve("crashed-write");
        var initialized = backend(root);
        Path log = directory.resolve("writer.log");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classpath, LocalDocumentArtifactBackendTest.class.getName(), root.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "The owned crash probe must terminate");
            assertEquals(24, process.exitValue(), Files.readString(log));
            assertTrue(Files.readString(log).contains("ARTIFACT_WRITER_HALTING_AFTER_FIRST_BLOCK"));
            try (var pending = Files.list(root.resolve(".reachai-upload"))) {
                var staged = pending.toList();
                assertEquals(1, staged.size());
                assertEquals(8192, Files.size(staged.get(0)));
            }
            assertFalse(Files.exists(root.resolve(KEY)));
            var recovered = backend(root);
            assertEquals(initialized.storageId(), recovered.storageId());
            recovered.delete(KEY);
            try (var pending = Files.list(root.resolve(".reachai-upload"))) {
                assertEquals(0, pending.count());
            }
            System.out.println("LOCAL_ARTIFACT_PROCESS_CRASH_VERIFIED pid=" + process.pid() + " exit=24 stagingBytes=8192 removed=true");
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "The owned crash probe must not survive the test");
            }
        }
    }

    @Test
    void rejectsPathsOutsideItsArtifactNamespace() throws Exception {
        var root = directory.resolve("owned");
        var store = backend(root);
        Path preserved = directory.resolve("preserved.txt");
        Files.writeString(preserved, "保持不变", StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> store.delete("../preserved.txt"));
        assertThrows(IllegalArgumentException.class, () -> store.delete(".reachai-storage-id"));
        assertEquals("保持不变", Files.readString(preserved));
    }

    private static LocalDocumentArtifactStore backend(Path root) {
        var properties = new DocumentArtifactProperties();
        properties.setLocalRoot(root.toString());
        var backend = new LocalDocumentArtifactStore(properties);
        backend.initialize();
        return backend;
    }

    /** Isolated child process: halt bypasses finally/stream close, leaving a real partial local write. */
    public static void main(String[] args) {
        if (args.length != 1) throw new IllegalArgumentException("An owned temporary root is required");
        var backend = backend(Path.of(args[0]));
        backend.put(KEY, new InputStream() {
            private int blocks;
            @Override public int read() { throw new UnsupportedOperationException("Block reader only"); }
            @Override public int read(byte[] bytes, int offset, int length) {
                if (blocks++ == 0) {
                    int count = Math.min(8192, length);
                    Arrays.fill(bytes, offset, offset + count, (byte) 'x');
                    return count;
                }
                System.out.println("ARTIFACT_WRITER_HALTING_AFTER_FIRST_BLOCK");
                System.out.flush();
                Runtime.getRuntime().halt(24);
                throw new AssertionError("halt returned");
            }
        }, 16384, "application/octet-stream");
    }
}
