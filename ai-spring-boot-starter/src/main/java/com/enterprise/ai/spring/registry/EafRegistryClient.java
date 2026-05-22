package com.enterprise.ai.spring.registry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public class EafRegistryClient {

    private static final Logger log = LoggerFactory.getLogger(EafRegistryClient.class);

    private final EafRegistryProperties properties;

    private final EafCapabilityScanner scanner;

    private final EafAgentGraphScanner graphScanner;

    private final SdkDescriptionSourceSettingsHolder descriptionSettingsHolder;

    private final RestClient restClient;

    private final String instanceId;

    private final AtomicReference<RuntimeGovernanceState> governanceState =
            new AtomicReference<>(RuntimeGovernanceState.defaultState());

    public EafRegistryClient(EafRegistryProperties properties,
                             EafCapabilityScanner scanner,
                             SdkDescriptionSourceSettingsHolder descriptionSettingsHolder) {
        this(properties, scanner, new EafAgentGraphScanner(List.of()), descriptionSettingsHolder);
    }

    public EafRegistryClient(EafRegistryProperties properties,
                             EafCapabilityScanner scanner,
                             EafAgentGraphScanner graphScanner,
                             SdkDescriptionSourceSettingsHolder descriptionSettingsHolder) {
        this.properties = properties;
        this.scanner = scanner;
        this.graphScanner = graphScanner;
        this.descriptionSettingsHolder = descriptionSettingsHolder;
        this.restClient = RestClient.builder().baseUrl(trimTrailingSlash(properties.getRegistry().getUrl())).build();
        this.instanceId = resolveInstanceId();
    }

    /**
     * 从注册中心拉取「接口/参数说明来源」顺序（与 scan_settings 一致，服务端已过滤 Javadoc）。
     * 失败时使用内置默认，不抛异常。
     */
    public void refreshDescriptionSettings() {
        if (!isConfigured()) {
            return;
        }
        try {
            SdkCapabilityDescriptionSettings s = signedGet(
                    "/api/registry/projects/{projectCode}/capability-description-settings",
                    SdkCapabilityDescriptionSettings.class);
            if (s != null) {
                descriptionSettingsHolder.update(s);
            }
        } catch (Exception ex) {
            log.warn("[ReachAI Registry] capability-description-settings fetch failed, using built-in defaults: {}",
                    ex.toString());
            descriptionSettingsHolder.resetToBuiltInDefaults();
        }
    }

    public void registerAndSync() {
        if (!isConfigured()) {
            return;
        }
        try {
            refreshDescriptionSettings();
            registerProject();
            heartbeat();
            if (properties.getCapability().isSyncOnStartup()) {
                syncCapabilities(scanner.scan());
            }
            if (properties.getAgentGraph().isSyncOnStartup()) {
                syncAgentGraphs(graphScanner.scan());
            }
        } catch (Exception ex) {
            // Starter 不能因为注册中心临时不可用拖垮业务系统启动；后续心跳会继续重试。
            logFailure("registerAndSync", ex);
        }
    }

    public void heartbeat() {
        if (!isConfigured()) {
            return;
        }
        refreshDescriptionSettings();
        try {
            InstanceHeartbeatResponse response = signedPost(
                    "/api/registry/projects/{projectCode}/instances/heartbeat",
                    buildHeartbeatBody(),
                    InstanceHeartbeatResponse.class);
            RuntimeGovernancePolicy policy = response == null ? null : response.policy();
            RuntimeGovernanceState state = updateGovernanceState(policy);
            if (!state.localExecutionAllowed()) {
                log.warn("[ReachAI Registry] runtime local execution restricted by control plane: status={} minSdkVersion={} sdkVersionSatisfied={} message={}",
                        state.status(), state.minSdkVersion(), state.sdkVersionSatisfied(), state.message());
            }
        } catch (Exception ex) {
            // 定时任务上抛异常会刷屏；这里打日志即可，下一轮继续重试。
            logFailure("heartbeat", ex);
        }
    }

    public void offline() {
        if (!isConfigured()) {
            return;
        }
        try {
            signedPost("/api/registry/projects/{projectCode}/instances/offline", Map.of("instanceId", instanceId));
        } catch (Exception ignored) {
            // shutdown hook 中失败不影响业务进程退出
        }
    }

    public List<EafCapabilityDescriptor> capabilities() {
        return scanner.scan();
    }

    public List<EafAgentGraph> agentGraphs() {
        return graphScanner.scan();
    }

    public RuntimeGovernanceState governanceState() {
        return governanceState.get();
    }

    private void registerProject() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("projectCode", properties.getProject().getCode());
        body.put("name", defaultString(properties.getProject().getName(), properties.getProject().getCode()));
        body.put("environment", defaultString(properties.getProject().getEnvironment(), "default"));
        body.put("owner", defaultString(properties.getProject().getOwner(), ""));
        body.put("visibility", defaultString(properties.getProject().getVisibility(), "PRIVATE"));
        body.put("baseUrl", defaultString(properties.getProject().getBaseUrl(), ""));
        body.put("contextPath", defaultString(properties.getProject().getContextPath(), ""));
        body.put("appKey", defaultString(properties.getRegistry().getAppKey(), ""));
        if (StringUtils.hasText(properties.getRegistry().getAppSecret())) {
            body.put("appSecret", properties.getRegistry().getAppSecret());
        }
        signedPost("/api/registry/projects/register", body);
    }

    private void syncCapabilities(List<EafCapabilityDescriptor> capabilities) {
        Map<String, Object> body = Map.of(
                "syncId", UUID.randomUUID().toString(),
                "source", "SDK",
                "apply", true,
                "capabilities", capabilities
        );
        signedPost("/api/registry/projects/{projectCode}/capabilities/sync", body);
    }

    private void syncAgentGraphs(List<EafAgentGraph> graphs) {
        if (graphs == null || graphs.isEmpty()) {
            return;
        }
        Map<String, Object> body = Map.of(
                "syncId", UUID.randomUUID().toString(),
                "source", "SDK",
                "apply", true,
                "graphs", graphs
        );
        signedPost("/api/registry/projects/{projectCode}/agent-graphs/sync", body);
    }

    Map<String, Object> buildHeartbeatBody() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("runtimePlacement", "EMBEDDED");
        metadata.put("runtimeTypes", List.of("SPRING_BOOT_EMBEDDED"));
        int graphCount = graphScanner.scan().size();
        metadata.put("supportsGraph", graphCount > 0);
        metadata.put("supportsTools", true);
        metadata.put("supportsWorkflow", graphCount > 0);
        metadata.put("supportsAutonomous", false);
        metadata.put("supportsEmbeddedExecution", true);
        metadata.put("supportsHybridExecution", false);
        metadata.put("capabilityCount", scanner.scan().size());
        metadata.put("agentGraphCount", graphCount);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("instanceId", instanceId);
        body.put("baseUrl", defaultString(properties.getProject().getBaseUrl(), ""));
        body.put("host", hostName());
        body.put("sdkVersion", sdkVersion());
        body.put("metadata", metadata);
        return body;
    }

    private boolean isConfigured() {
        return properties.getRegistry().isEnabled()
                && StringUtils.hasText(properties.getRegistry().getUrl())
                && StringUtils.hasText(properties.getProject().getCode());
    }

    private String trimTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private void signedPost(String uri, Object body) {
        signedPost(uri, body, Void.class);
    }

    private <T> T signedPost(String uri, Object body, Class<T> bodyType) {
        SignatureHeaders headers = signatureHeaders();
        return restClient.post()
                .uri(uri, properties.getProject().getCode())
                .header("X-EAF-App-Key", headers.appKey())
                .header("X-EAF-Timestamp", headers.timestamp())
                .header("X-EAF-Nonce", headers.nonce())
                .header("X-EAF-Signature", headers.signature())
                .body(body)
                .retrieve()
                .body(bodyType);
    }

    private <T> T signedGet(String uriTemplate, Class<T> bodyType) {
        SignatureHeaders headers = signatureHeaders();
        return restClient.get()
                .uri(uriTemplate, properties.getProject().getCode())
                .header("X-EAF-App-Key", headers.appKey())
                .header("X-EAF-Timestamp", headers.timestamp())
                .header("X-EAF-Nonce", headers.nonce())
                .header("X-EAF-Signature", headers.signature())
                .retrieve()
                .body(bodyType);
    }

    private void logFailure(String phase, Exception ex) {
        String base = properties.getRegistry().getUrl();
        String code = properties.getProject().getCode();
        if (ex instanceof RestClientResponseException rre) {
            String body = rre.getResponseBodyAsString();
            if (body != null && body.length() > 800) {
                body = body.substring(0, 800) + "...";
            }
            log.warn("[ReachAI Registry] {} failed project={} registryUrl={} httpStatus={} responseBody={}",
                    phase, code, base, rre.getStatusCode().value(), body);
            return;
        }
        log.warn("[ReachAI Registry] {} failed project={} registryUrl={}: {}",
                phase, code, base, ex.toString());
    }

    private SignatureHeaders signatureHeaders() {
        String appKey = defaultString(properties.getRegistry().getAppKey(), properties.getProject().getCode());
        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString();
        String message = properties.getProject().getCode() + "\n" + timestamp + "\n" + nonce;
        String signature = StringUtils.hasText(properties.getRegistry().getAppSecret())
                ? hmacSha256Hex(properties.getRegistry().getAppSecret(), message)
                : "";
        return new SignatureHeaders(appKey, timestamp, nonce, signature);
    }

    private String hmacSha256Hex(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "";
        }
    }

    private String resolveInstanceId() {
        return hostName() + "-" + ManagementFactory.getRuntimeMXBean().getName();
    }

    private String sdkVersion() {
        String version = EafRegistryClient.class.getPackage().getImplementationVersion();
        return version == null ? "dev" : version;
    }

    private String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown-host";
        }
    }

    private String defaultString(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }

    RuntimeGovernanceState updateGovernanceState(RuntimeGovernancePolicy policy) {
        if (policy == null) {
            return governanceState.get();
        }
        String currentSdkVersion = sdkVersion();
        boolean sdkVersionSatisfied = isSdkVersionSatisfied(currentSdkVersion, policy.minSdkVersion());
        RuntimeGovernanceState state = new RuntimeGovernanceState(
                policy.disabled(),
                defaultString(policy.status(), "UNKNOWN"),
                blankToNull(policy.minSdkVersion()),
                sdkVersionSatisfied,
                policy.allowEmbeddedExecution() == null || policy.allowEmbeddedExecution(),
                policy.allowHybridExecution() == null || policy.allowHybridExecution(),
                defaultString(policy.message(), policy.disabled() ? "runtime disabled by control plane" : "ok")
        );
        governanceState.set(state);
        return state;
    }

    private boolean isSdkVersionSatisfied(String current, String minimum) {
        if (!StringUtils.hasText(minimum) || !StringUtils.hasText(current) || "dev".equalsIgnoreCase(current)) {
            return true;
        }
        return compareVersions(current, minimum) >= 0;
    }

    private int compareVersions(String left, String right) {
        List<Integer> l = versionSegments(left);
        List<Integer> r = versionSegments(right);
        int size = Math.max(l.size(), r.size());
        for (int i = 0; i < size; i++) {
            int lv = i < l.size() ? l.get(i) : 0;
            int rv = i < r.size() ? r.get(i) : 0;
            if (lv != rv) {
                return Integer.compare(lv, rv);
            }
        }
        return 0;
    }

    private List<Integer> versionSegments(String version) {
        String normalized = version == null ? "" : version.trim().replaceFirst("^[vV]", "");
        String[] parts = normalized.split("[^0-9]+");
        return java.util.Arrays.stream(parts)
                .filter(StringUtils::hasText)
                .map(s -> {
                    try {
                        return Integer.parseInt(s);
                    } catch (NumberFormatException ignored) {
                        return 0;
                    }
                })
                .toList();
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private record SignatureHeaders(String appKey, String timestamp, String nonce, String signature) {
    }

    private record InstanceHeartbeatResponse(Object instance, RuntimeGovernancePolicy policy) {
    }

    record RuntimeGovernancePolicy(boolean disabled,
                                           String status,
                                           String minSdkVersion,
                                           Boolean allowEmbeddedExecution,
                                           Boolean allowHybridExecution,
                                           String message) {
    }
}
