package com.enterprise.ai.control.a2a.infrastructure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "reachai.a2a-hub")
public class A2aHubProperties {
    private boolean enabled = false;
    private String protocolVersion = "1.0";
    private Duration agentCardCacheMaxAge = Duration.ofMinutes(5);
    /** Base64-encoded 32-byte AES key supplied by an environment secret or KMS bridge. */
    private String contentKeyBase64 = "";
    private String contentKeyId = "";
    /** Separate AES key for outbound authentication material; never reuse the content key. */
    private String credentialKeyBase64 = "";
    private String credentialKeyId = "";
    /** Runtime-only HMAC key used to pseudonymize remote addresses in transport audit. */
    private String auditIpHashKey = "";
    private int maxHistoryMessages = 50;
    private int maxPageSize = 100;
    private Duration outboxDelay = Duration.ofSeconds(1);
    private Duration outboxLease = Duration.ofMinutes(4);
    private Duration outboxHeartbeat = Duration.ofSeconds(30);
    private int outboxBatchSize = 10;
    private int outboxMaxAttempts = 5;
    private int outboxWorkers = 4;
    private Outbound outbound = new Outbound();

    @Data
    public static class Outbound {
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration responseTimeout = Duration.ofSeconds(10);
        private Duration pollScanDelay = Duration.ofSeconds(1);
        private Duration initialPollDelay = Duration.ofSeconds(2);
        private Duration maxPollDelay = Duration.ofSeconds(15);
        private Duration pollLease = Duration.ofSeconds(30);
        private int pollBatchSize = 20;
        private int pollWorkers = 4;
        private int maxAgentCardBytes = 1_048_576;
        private boolean privateNetworkAllowed = false;
        private List<Integer> allowedPorts = new ArrayList<>(List.of(443));
    }
}
