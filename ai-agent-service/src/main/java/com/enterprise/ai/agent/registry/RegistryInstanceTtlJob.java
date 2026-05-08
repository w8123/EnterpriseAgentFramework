package com.enterprise.ai.agent.registry;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RegistryInstanceTtlJob {

    private final AiRegistryService registryService;

    @Value("${eaf.registry.instance-heartbeat-ttl-seconds:180}")
    private int heartbeatTtlSeconds;

    @Scheduled(fixedDelayString = "${eaf.registry.instance-ttl-check-ms:60000}")
    public void markStaleInstancesOffline() {
        int updated = registryService.markStaleInstancesOffline(heartbeatTtlSeconds);
        if (updated > 0) {
            log.info("[AI Registry] marked stale instances OFFLINE: {}", updated);
        }
    }
}
