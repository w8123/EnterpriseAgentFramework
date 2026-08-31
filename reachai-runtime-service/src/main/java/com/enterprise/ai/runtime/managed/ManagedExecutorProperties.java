package com.enterprise.ai.runtime.managed;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class ManagedExecutorProperties {

    private final boolean enabled;
    private final boolean autoRouteEnabled;
    private final Set<String> allowedProjects;
    private final Set<String> allowedProfiles;
    private final int maxClusterConcurrency;
    private final int workerTokenTtlSeconds;
    private final int workerLeaseSeconds;
    private final int maxObjectiveCharacters;
    private final int maxEventBatchSize;
    private final int maxEventBytes;
    private final int maxWorkerRequestBytes;

    public ManagedExecutorProperties(
            @Value("${reachai.runtime.managed-executor.enabled:false}") boolean enabled,
            @Value("${reachai.runtime.managed-executor.auto-route-enabled:false}") boolean autoRouteEnabled,
            @Value("${reachai.runtime.managed-executor.allowed-projects:}") String allowedProjects,
            @Value("${reachai.runtime.managed-executor.allowed-profiles:ANALYZE_READONLY}") String allowedProfiles,
            @Value("${reachai.runtime.managed-executor.max-cluster-concurrency:0}") int maxClusterConcurrency,
            @Value("${reachai.runtime.managed-executor.worker-token-ttl-seconds:14400}") int workerTokenTtlSeconds,
            @Value("${reachai.runtime.managed-executor.worker-lease-seconds:90}") int workerLeaseSeconds,
            @Value("${reachai.runtime.managed-executor.max-objective-characters:65535}") int maxObjectiveCharacters,
            @Value("${reachai.runtime.managed-executor.max-event-batch-size:100}") int maxEventBatchSize,
            @Value("${reachai.runtime.managed-executor.max-event-bytes:262144}") int maxEventBytes,
            @Value("${reachai.runtime.managed-executor.max-worker-request-bytes:1048576}") int maxWorkerRequestBytes) {
        this.enabled = enabled;
        this.autoRouteEnabled = autoRouteEnabled;
        this.allowedProjects = csv(allowedProjects);
        this.allowedProfiles = csv(allowedProfiles);
        this.maxClusterConcurrency = bounded(maxClusterConcurrency, 0, 10_000);
        this.workerTokenTtlSeconds = bounded(workerTokenTtlSeconds, 300, 86_400);
        this.workerLeaseSeconds = bounded(workerLeaseSeconds, 30, 900);
        this.maxObjectiveCharacters = bounded(maxObjectiveCharacters, 1_000, 262_144);
        this.maxEventBatchSize = bounded(maxEventBatchSize, 1, 500);
        this.maxEventBytes = bounded(maxEventBytes, 4_096, 1_048_576);
        this.maxWorkerRequestBytes = bounded(maxWorkerRequestBytes, 4_096, 8_388_608);
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean autoRouteEnabled() {
        return autoRouteEnabled;
    }

    public int maxClusterConcurrency() {
        return maxClusterConcurrency;
    }

    public int workerTokenTtlSeconds() {
        return workerTokenTtlSeconds;
    }

    public int workerLeaseSeconds() {
        return workerLeaseSeconds;
    }

    public int maxObjectiveCharacters() {
        return maxObjectiveCharacters;
    }

    public int maxEventBatchSize() {
        return maxEventBatchSize;
    }

    public int maxEventBytes() {
        return maxEventBytes;
    }

    public int maxWorkerRequestBytes() {
        return maxWorkerRequestBytes;
    }

    public boolean allowsProject(String projectCode) {
        if (!StringUtils.hasText(projectCode) || allowedProjects.isEmpty()) return false;
        String normalized = projectCode.trim().toUpperCase(Locale.ROOT);
        return allowedProjects.contains("*") || allowedProjects.contains(normalized);
    }

    public boolean allowsProfile(String profile) {
        return StringUtils.hasText(profile)
                && allowedProfiles.contains(profile.trim().toUpperCase(Locale.ROOT));
    }

    private static Set<String> csv(String value) {
        if (!StringUtils.hasText(value)) return Set.of();
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(item -> item.toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    private static int bounded(int value, int minimum, int maximum) {
        return Math.min(maximum, Math.max(minimum, value));
    }
}
