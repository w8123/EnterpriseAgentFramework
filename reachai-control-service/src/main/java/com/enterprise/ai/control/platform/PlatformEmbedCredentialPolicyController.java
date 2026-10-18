package com.enterprise.ai.control.platform;

import com.enterprise.ai.control.client.capability.CapabilityProxyClient;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class PlatformEmbedCredentialPolicyController {

    private static final TypeReference<List<Map<String, Object>>> POLICY_LIST = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final CapabilityProxyClient capabilityProxyClient;
    private final PlatformEmbedOriginPolicy originPolicy;
    private final RuntimeProxyClient runtimeProxyClient;
    private final ObjectMapper objectMapper;

    @GetMapping("/api/platform/embed/credentials")
    public ResponseEntity<Object> listPolicies(@RequestParam(required = false) String projectCode,
                                               @RequestParam(required = false) String status,
                                               @RequestParam(defaultValue = "200") int limit) {
        return capabilityProxyClient.listEmbedCredentialPolicies(projectCode, status, limit);
    }

    @PutMapping("/api/platform/embed/credentials/{id}/policy")
    public ResponseEntity<Object> updatePolicy(@PathVariable Long id,
                                               @RequestBody(required = false) Map<String, Object> request) {
        ResponseEntity<Object> response =
                capabilityProxyClient.updateEmbedCredentialPolicy(id, request == null ? Map.of() : request);
        originPolicy.invalidate();
        return response;
    }

    /**
     * Answers "will this Agent open on that business page?" step by step, using
     * the same rules as Embed Token exchange and embed CORS, so an operator can
     * see which setting blocks it before touching the business system.
     */
    @GetMapping("/api/platform/embed/readiness")
    public ResponseEntity<?> readiness(@RequestParam(required = false) String projectCode,
                                       @RequestParam(required = false) String agentId,
                                       @RequestParam(required = false) String origin) {
        if (!StringUtils.hasText(projectCode) || !StringUtils.hasText(agentId) || !StringUtils.hasText(origin)) {
            return ResponseEntity.badRequest().body(Map.of("message", "请选择业务系统并填写 Agent 和来源域名"));
        }
        String normalizedOrigin = normalizeOrigin(origin);
        List<EmbedReadinessCheck> checks = new ArrayList<>();
        Map<String, Object> policy = activePolicy(projectCode);
        checks.add(new EmbedReadinessCheck("credential", policy != null,
                policy != null ? "业务系统已有可用的接入凭据" : "业务系统没有可用的接入凭据，先完成 SDK 接入"));

        List<String> origins = policy == null ? List.of() : readList(policy.get("allowedOriginsJson"));
        boolean originAllowed = normalizedOrigin != null && (origins.isEmpty()
                ? localDevelopmentOrigin(normalizedOrigin)
                : origins.stream().anyMatch(pattern -> PlatformEmbedOriginPolicy.originMatches(pattern, normalizedOrigin)));
        checks.add(new EmbedReadinessCheck("origin", policy != null && originAllowed,
                normalizedOrigin == null ? "来源格式不对，应类似 https://erp.example.com"
                        : originAllowed ? "来源在白名单内"
                        : origins.isEmpty() ? "白名单为空时只允许 localhost，请把该来源加入白名单"
                        : "来源不在白名单内"));

        List<String> agents = policy == null ? List.of() : readList(policy.get("allowedAgentIdsJson"));
        Map<?, ?> agent = findAgent(projectCode, agentId);
        boolean agentAllowed = agents.isEmpty() || agents.contains(agentId)
                || (agent != null && agents.contains(String.valueOf(agent.get("id"))))
                || (agent != null && agents.contains(String.valueOf(agent.get("keySlug"))));
        checks.add(new EmbedReadinessCheck("agentAllowed", policy != null && agentAllowed,
                agentAllowed ? (agents.isEmpty() ? "业务系统允许嵌入所有 Agent" : "Agent 在允许列表内")
                        : "Agent 不在该业务系统的允许列表内"));
        checks.add(new EmbedReadinessCheck("agentAvailable", agent != null,
                agent != null ? "Agent 已启用" : "找不到已启用的 Agent，或 Agent 属于其他项目"));

        boolean corsAllowed = normalizedOrigin != null && originPolicy.allows(normalizedOrigin);
        checks.add(new EmbedReadinessCheck("cors", corsAllowed,
                corsAllowed ? "浏览器跨域已放行" : "浏览器跨域未放行（白名单生效后最多 60 秒内自动更新）"));

        boolean ready = checks.stream().allMatch(EmbedReadinessCheck::ok);
        return ResponseEntity.ok(new EmbedReadinessView(projectCode, agentId, normalizedOrigin, ready, checks));
    }

    private Map<String, Object> activePolicy(String projectCode) {
        ResponseEntity<Object> response = capabilityProxyClient.listEmbedCredentialPolicies(projectCode, "ACTIVE", 20);
        if (response == null || !response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            return null;
        }
        List<Map<String, Object>> policies = objectMapper.convertValue(response.getBody(), POLICY_LIST);
        return policies.stream()
                .filter(item -> projectCode.equals(String.valueOf(item.get("projectCode"))))
                .findFirst()
                .orElse(null);
    }

    private Map<?, ?> findAgent(String projectCode, String agentId) {
        try {
            ResponseEntity<Object> response = runtimeProxyClient.listAgents(null, projectCode);
            if (response == null || !response.getStatusCode().is2xxSuccessful()) {
                return null;
            }
            return PlatformEmbedAgentLookup.find(response.getBody(), projectCode, agentId).orElse(null);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private List<String> readList(Object json) {
        if (!(json instanceof String text) || !StringUtils.hasText(text)) {
            return List.of();
        }
        try {
            List<String> values = objectMapper.readValue(text, STRING_LIST);
            return values == null ? List.of() : values;
        } catch (Exception ex) {
            return List.of();
        }
    }

    private static String normalizeOrigin(String origin) {
        if (!StringUtils.hasText(origin)) {
            return null;
        }
        try {
            URI uri = URI.create(origin.trim());
            if (uri.getScheme() == null || uri.getHost() == null
                    || !(uri.getScheme().equalsIgnoreCase("http") || uri.getScheme().equalsIgnoreCase("https"))) {
                return null;
            }
            return uri.getScheme().toLowerCase() + "://" + uri.getHost().toLowerCase()
                    + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static boolean localDevelopmentOrigin(String origin) {
        URI uri = URI.create(origin);
        String host = uri.getHost();
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
    }

    public record EmbedReadinessCheck(String key, boolean ok, String message) {
    }

    public record EmbedReadinessView(String projectCode,
                                     String agentId,
                                     String origin,
                                     boolean ready,
                                     List<EmbedReadinessCheck> checks) {
    }
}
