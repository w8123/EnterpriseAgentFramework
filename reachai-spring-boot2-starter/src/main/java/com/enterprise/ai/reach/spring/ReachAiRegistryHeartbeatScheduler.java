package com.enterprise.ai.reach.spring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.util.StringUtils;

import java.util.concurrent.ScheduledFuture;

public class ReachAiRegistryHeartbeatScheduler implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ReachAiRegistryHeartbeatScheduler.class);
    private static final long MIN_HEARTBEAT_INTERVAL_MS = 5000L;

    private final ReachAiRegistryProperties properties;
    private final ReachAiRegistryClient registryClient;
    private final TaskScheduler taskScheduler;
    private volatile boolean running;
    private ScheduledFuture<?> future;

    public ReachAiRegistryHeartbeatScheduler(ReachAiRegistryProperties properties,
                                             ReachAiRegistryClient registryClient,
                                             TaskScheduler taskScheduler) {
        this.properties = properties;
        this.registryClient = registryClient;
        this.taskScheduler = taskScheduler;
    }

    @Override
    public void start() {
        if (running || properties == null || properties.getRegistry() == null || !properties.getRegistry().isEnabled()) {
            return;
        }
        registryClient.logConfigurationSummary();
        if (!registryClient.isConfigured()) {
            registryClient.logConfigurationErrorIfNecessary("heartbeat scheduler");
            return;
        }
        long intervalMs = heartbeatIntervalMs();
        this.future = taskScheduler.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                sendHeartbeat();
            }
        }, intervalMs);
        this.running = true;
        log.info("[ReachAI Registry] heartbeat scheduler started project={} intervalMs={}",
                properties.getProject().getCode(), intervalMs);
    }

    @Override
    public void stop() {
        ScheduledFuture<?> current = this.future;
        if (current != null) {
            current.cancel(false);
        }
        this.future = null;
        this.running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    private void sendHeartbeat() {
        if (!registryClient.isConfigured()) {
            registryClient.logConfigurationErrorIfNecessary("heartbeat");
            return;
        }
        try {
            registryClient.heartbeatOrRetryRegistration();
        } catch (Exception ex) {
            if (isCredentialError(ex)) {
                log.error("[ReachAI Registry] heartbeat rejected project={} registryUrl={} error={}",
                        properties.getProject().getCode(), properties.getRegistry().getUrl(), ex.toString());
            } else {
                log.warn("[ReachAI Registry] heartbeat failed project={} registryUrl={} error={}",
                        properties.getProject().getCode(), properties.getRegistry().getUrl(), ex.toString());
            }
        }
    }

    private long heartbeatIntervalMs() {
        long configured = properties.getRegistry().getHeartbeatIntervalMs();
        return configured < MIN_HEARTBEAT_INTERVAL_MS ? MIN_HEARTBEAT_INTERVAL_MS : configured;
    }

    private boolean isCredentialError(Exception ex) {
        String message = ex == null ? null : ex.toString();
        return StringUtils.hasText(message) && (message.contains("status=401") || message.contains("status=403"));
    }
}
