package com.enterprise.ai.reach.spring;

import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReachAiRegistryHeartbeatSchedulerTest {

    @Test
    void startSchedulesHeartbeatUsingConfiguredInterval() {
        ReachAiRegistryProperties properties = configuredProperties();
        properties.getRegistry().setHeartbeatIntervalMs(15000L);
        CountingRegistryClient client = new CountingRegistryClient(properties);
        RecordingTaskScheduler scheduler = new RecordingTaskScheduler();

        ReachAiRegistryHeartbeatScheduler heartbeatScheduler =
                new ReachAiRegistryHeartbeatScheduler(properties, client, scheduler);

        heartbeatScheduler.start();

        assertEquals(15000L, scheduler.fixedDelay);
        scheduler.lastTask.run();
        assertEquals(1, client.heartbeatCount);
    }

    @Test
    void startDoesNotScheduleHeartbeatWhenRegistrySecretIsMissing() {
        ReachAiRegistryProperties properties = configuredProperties();
        properties.getRegistry().setAppSecret(null);
        CountingRegistryClient client = new CountingRegistryClient(properties);
        RecordingTaskScheduler scheduler = new RecordingTaskScheduler();

        ReachAiRegistryHeartbeatScheduler heartbeatScheduler =
                new ReachAiRegistryHeartbeatScheduler(properties, client, scheduler);

        heartbeatScheduler.start();

        assertNull(scheduler.lastTask);
        assertEquals(0, client.heartbeatCount);
    }

    @Test
    void periodicRunRetriesProjectRegistrationAfterStartupFailure() {
        ReachAiRegistryProperties properties = configuredProperties();
        RegistrationRequiredTransport transport = new RegistrationRequiredTransport();
        ReachAiRegistryClient client = new ReachAiRegistryClient(
                properties, new ReachCapabilityBeanScanner(new Object[0]), transport);
        RecordingTaskScheduler scheduler = new RecordingTaskScheduler();

        client.registerAndSync();
        assertEquals(1, transport.registrationAttempts);
        assertEquals(0, transport.successfulHeartbeats);

        ReachAiRegistryHeartbeatScheduler heartbeatScheduler =
                new ReachAiRegistryHeartbeatScheduler(properties, client, scheduler);
        heartbeatScheduler.start();
        scheduler.lastTask.run();

        assertEquals(2, transport.registrationAttempts);
        assertEquals(1, transport.successfulHeartbeats);
    }

    private ReachAiRegistryProperties configuredProperties() {
        ReachAiRegistryProperties properties = new ReachAiRegistryProperties();
        properties.getRegistry().setUrl("https://reachai.example.com");
        properties.getRegistry().setAppKey("demo-key");
        properties.getRegistry().setAppSecret("demo-secret");
        properties.getProject().setCode("demo");
        return properties;
    }

    private static class CountingRegistryClient extends ReachAiRegistryClient {
        private int heartbeatCount;

        CountingRegistryClient(ReachAiRegistryProperties properties) {
            super(properties, new ReachCapabilityBeanScanner(new Object[0]), new NoopTransport());
        }

        @Override
        public void heartbeat() {
            heartbeatCount++;
        }
    }

    private static class NoopTransport implements ReachAiRegistryTransport {
        @Override
        public String exchange(String method, String url, java.util.Map<String, String> headers, Object body) {
            return "{}";
        }
    }

    private static class RegistrationRequiredTransport implements ReachAiRegistryTransport {
        private int registrationAttempts;
        private int successfulHeartbeats;
        private boolean registered;

        @Override
        public String exchange(String method, String url, java.util.Map<String, String> headers, Object body)
                throws java.io.IOException {
            if (url.endsWith("/api/registry/projects/register")) {
                registrationAttempts++;
                if (registrationAttempts == 1) {
                    throw new java.io.IOException("simulated startup outage");
                }
                registered = true;
                return "{}";
            }
            if (url.contains("/instances/heartbeat")) {
                if (!registered) {
                    throw new java.io.IOException("status=400 project does not exist");
                }
                successfulHeartbeats++;
                return "{}";
            }
            return "{}";
        }
    }

    private static class RecordingTaskScheduler implements org.springframework.scheduling.TaskScheduler {
        private Runnable lastTask;
        private long fixedDelay;

        @Override
        public ScheduledFuture<?> schedule(Runnable task, org.springframework.scheduling.Trigger trigger) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Date startTime) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Date startTime, long period) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, long period) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Date startTime, long delay) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, long delay) {
            this.lastTask = task;
            this.fixedDelay = delay;
            return new DoneScheduledFuture();
        }

        // Spring Framework 6 / Spring Boot 3 TaskScheduler overloads. Keep these
        // without @Override so the same Java 8 test source also compiles on Spring 5.3.
        public ScheduledFuture<?> schedule(Runnable task, java.time.Instant startTime) {
            throw new UnsupportedOperationException();
        }

        public ScheduledFuture<?> scheduleAtFixedRate(
                Runnable task,
                java.time.Instant startTime,
                java.time.Duration period) {
            throw new UnsupportedOperationException();
        }

        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, java.time.Duration period) {
            throw new UnsupportedOperationException();
        }

        public ScheduledFuture<?> scheduleWithFixedDelay(
                Runnable task,
                java.time.Instant startTime,
                java.time.Duration delay) {
            throw new UnsupportedOperationException();
        }

        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, java.time.Duration delay) {
            this.lastTask = task;
            this.fixedDelay = delay.toMillis();
            return new DoneScheduledFuture();
        }
    }

    private static class DoneScheduledFuture implements ScheduledFuture<Object> {
        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(java.util.concurrent.Delayed other) {
            return 0;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return true;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return true;
        }

        @Override
        public Object get() {
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            return null;
        }
    }
}
