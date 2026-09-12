package com.enterprise.ai.reach.spring;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ReachAiRegistryEnrollmentRecoveryTest {
    @TempDir Path directory;

    @Test
    void persistenceFailureRetriesReceivedCredentialBeforeSendingAnotherEnrollment() throws Exception {
        Path target = blockedTarget();
        ReachAiRegistryProperties properties = properties(target);
        OneTimeTransport transport = new OneTimeTransport();
        ReachAiRegistryClient client = client(properties, transport);

        client.registerAndSync();
        assertEquals(1, transport.enrollments.get());
        assertNull(properties.getRegistry().getAppSecret());
        assertThrows(IllegalStateException.class, client::heartbeatOrRetryRegistration);
        assertEquals(1, transport.enrollments.get(), "a failed local write must not consume the token again");
        assertEquals(0, transport.signedRequests.get(), "the credential must be durable before use");

        Files.delete(target.resolve("blocker"));
        Files.delete(target);
        client.heartbeatOrRetryRegistration();
        assertEquals(1, transport.enrollments.get());
        assertEquals(2, transport.signedRequests.get());
        assertNull(properties.getRegistry().getEnrollmentToken());
        ReachAiRegistryProperties restarted = properties(target);
        client(restarted, transport).registerAndSync();
        assertEquals("ras_recovery_test", restarted.getRegistry().getAppSecret());
        assertEquals(1, transport.enrollments.get(), "restart reuses the recovered durable credential");
    }

    @Test
    void failedPersistenceRemovesTemporaryCredentialFiles() throws Exception {
        Path target = blockedTarget();
        client(properties(target), new OneTimeTransport()).registerAndSync();
        try (Stream<Path> paths = Files.list(directory)) {
            assertEquals(0, paths.filter(path -> path.getFileName().toString().endsWith(".tmp")).count(),
                    "a failed replacement must not leave credential-bearing temporary files");
        }
        assertEquals("occupied", new String(Files.readAllBytes(target.resolve("blocker")), java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void retryLoadsCredentialPersistedByAnotherClientSinceStartup() throws Exception {
        Path target = directory.resolve("credential.properties");
        OneTimeTransport transport = new OneTimeTransport();
        ReachAiRegistryProperties waitingProperties = properties(target);
        ReachAiRegistryClient waiting = client(waitingProperties, transport);
        client(properties(target), transport).registerAndSync();

        assertDoesNotThrow(waiting::heartbeatOrRetryRegistration);

        assertEquals(1, transport.enrollments.get());
        assertEquals("ras_recovery_test", waitingProperties.getRegistry().getAppSecret());
        assertEquals(3, transport.signedRequests.get());
    }

    @Test
    void concurrentStartupAndHeartbeatShareOneEnrollment() throws Exception {
        OneTimeTransport transport = new OneTimeTransport();
        transport.holdFirstEnrollment = true;
        ReachAiRegistryClient client = client(properties(directory.resolve("credential.properties")), transport);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch secondCompleted = new CountDownLatch(1);
        AtomicReference<Throwable> secondFailure = new AtomicReference<Throwable>();
        Future<?> first = executor.submit(client::registerAndSync);
        Future<?> second = null;
        try {
            assertTrue(transport.firstEnrollmentEntered.await(5, TimeUnit.SECONDS));
            second = executor.submit(() -> {
                secondStarted.countDown();
                try { client.heartbeatOrRetryRegistration(); }
                catch (Throwable failure) { secondFailure.set(failure); }
                finally { secondCompleted.countDown(); }
            });
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
            assertFalse(secondCompleted.await(300, TimeUnit.MILLISECONDS),
                    "a concurrent attempt must wait for the in-flight enrollment result");
            assertEquals(1, transport.enrollments.get());
        } finally {
            transport.releaseFirstEnrollment.countDown();
            try {
                first.get(5, TimeUnit.SECONDS);
                if (second != null) second.get(5, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
        assertEquals(1, transport.enrollments.get());
        assertEquals(3, transport.signedRequests.get());
        assertNull(secondFailure.get(), "both callers recover through the same committed credential");
    }

    @Test
    void invalidResponsesNeverBecomePendingOrInstalledCredentials() {
        Path target = directory.resolve("credential.properties");
        ReachAiRegistryProperties properties = properties(target);
        ReachAiRegistryCredentialStore store = new ReachAiRegistryCredentialStore(properties);
        for (String response : new String[]{"not-json", "{}",
                "{\"projectCode\":\"orders\",\"appKey\":\"rak_test\"}",
                "{\"projectCode\":\"other\",\"appKey\":\"rak_test\",\"appSecret\":\"ras_test\"}"}) {
            assertThrows(IllegalStateException.class, () -> store.persistFromRegistrationResponse(response));
            assertDoesNotThrow(store::prepareForRegistration);
            assertNull(properties.getRegistry().getAppKey());
            assertNull(properties.getRegistry().getAppSecret());
            assertFalse(Files.exists(target));
        }
    }

    private Path blockedTarget() throws IOException {
        Path target = Files.createDirectory(directory.resolve("credential.properties"));
        Files.write(target.resolve("blocker"), "occupied".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return target;
    }

    private ReachAiRegistryProperties properties(Path target) {
        ReachAiRegistryProperties properties = new ReachAiRegistryProperties();
        properties.getRegistry().setUrl("https://registry.test.invalid");
        properties.getRegistry().setEnrollmentToken("ren_recovery_test");
        properties.getRegistry().setCredentialStorePath(target.toString());
        properties.getProject().setCode("orders");
        properties.getProject().setBaseUrl("https://orders.test.invalid");
        return properties;
    }

    private ReachAiRegistryClient client(ReachAiRegistryProperties properties, OneTimeTransport transport) {
        return new ReachAiRegistryClient(properties, new ReachCapabilityBeanScanner(new Object[0]), transport);
    }

    private static final class OneTimeTransport implements ReachAiRegistryTransport {
        final AtomicInteger enrollments = new AtomicInteger();
        final AtomicInteger signedRequests = new AtomicInteger();
        final CountDownLatch firstEnrollmentEntered = new CountDownLatch(1);
        final CountDownLatch releaseFirstEnrollment = new CountDownLatch(1);
        boolean holdFirstEnrollment;

        @Override public String exchange(String method, String url, Map<String, String> headers, Object body) throws IOException {
            if (headers.containsKey(ReachAiRegistryClient.ENROLLMENT_TOKEN_HEADER)) {
                if (enrollments.incrementAndGet() != 1) throw new IOException("one-time enrollment has already committed");
                firstEnrollmentEntered.countDown();
                if (holdFirstEnrollment) {
                    try {
                        if (!releaseFirstEnrollment.await(5, TimeUnit.SECONDS)) throw new IOException("test enrollment wait expired");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("test enrollment interrupted", interrupted);
                    }
                }
                return "{\"projectCode\":\"orders\",\"appKey\":\"rak_recovery_test\",\"appSecret\":\"ras_recovery_test\"}";
            }
            assertEquals("rak_recovery_test", headers.get("X-ReachAI-App-Key"));
            assertNotNull(headers.get("X-ReachAI-Signature"));
            signedRequests.incrementAndGet();
            return "{}";
        }
    }
}
