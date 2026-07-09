package com.enterprise.ai.reach.spring;

import com.enterprise.ai.reach.sdk.auth.ReachAiSignatureHeaders;
import com.enterprise.ai.reach.sdk.auth.ReachAiSigner;
import com.enterprise.ai.reach.sdk.capability.ReachCapabilityDescriptor;
import com.enterprise.ai.reach.sdk.capability.ReachCapabilityParameter;
import com.enterprise.ai.reach.sdk.client.ReachAiClientConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public class ReachAiRegistryClient {

    private static final Logger log = LoggerFactory.getLogger(ReachAiRegistryClient.class);

    private final ReachAiRegistryProperties properties;
    private final ReachCapabilityBeanScanner capabilityBeanScanner;
    private final ReachAiRegistryTransport transport;
    private final String registryBaseUrl;
    private final String instanceId;
    private final AtomicBoolean configurationErrorLogged = new AtomicBoolean(false);

    public ReachAiRegistryClient(ReachAiRegistryProperties properties,
                                 ReachCapabilityBeanScanner capabilityBeanScanner) {
        this(properties, capabilityBeanScanner, new ReachAiHttpRegistryTransport());
    }

    public ReachAiRegistryClient(ReachAiRegistryProperties properties,
                                 ReachCapabilityBeanScanner capabilityBeanScanner,
                                 ReachAiRegistryTransport transport) {
        this.properties = properties;
        this.capabilityBeanScanner = capabilityBeanScanner;
        this.transport = transport;
        this.registryBaseUrl = trimTrailingSlash(properties == null ? null : properties.getRegistry().getUrl());
        this.instanceId = resolveInstanceId();
    }

    public ReachAiRegistryProperties getProperties() {
        return properties;
    }

    public List<ReachCapabilityDescriptor> capabilities() {
        return capabilityBeanScanner == null
                ? Collections.<ReachCapabilityDescriptor>emptyList()
                : capabilityBeanScanner.scan();
    }

    public void registerAndSync() {
        if (!isConfigured()) {
            logConfigurationErrorIfNecessary("registerAndSync");
            return;
        }
        try {
            registerProject();
            heartbeat();
        } catch (Exception e) {
            log.warn("[ReachAI Registry] registerAndSync failed project={} registryUrl={} error={}",
                    properties.getProject().getCode(), properties.getRegistry().getUrl(), e.toString());
        }
    }

    public void heartbeat() {
        if (!isConfigured()) {
            logConfigurationErrorIfNecessary("heartbeat");
            return;
        }
        post("/api/registry/projects/{projectCode}/instances/heartbeat", heartbeatBody());
    }

    public ManualCapabilitySyncResponse scanAndSyncCapabilities() {
        if (!isConfigured()) {
            throw new IllegalStateException("ReachAI registry configuration is incomplete: " + configurationProblems());
        }
        List<ReachCapabilityDescriptor> descriptors = capabilities();
        String registryResponse = syncCapabilities(descriptors);
        return new ManualCapabilitySyncResponse(instanceId, descriptors.size(), registryResponse);
    }

    String syncCapabilities(List<ReachCapabilityDescriptor> capabilities) {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("syncId", UUID.randomUUID().toString());
        body.put("source", "SDK");
        body.put("apply", Boolean.TRUE);
        body.put("capabilities", capabilityRegistrations(capabilities));
        return post("/api/registry/projects/{projectCode}/capabilities/sync", body);
    }

    List<Map<String, Object>> capabilityRegistrations(List<ReachCapabilityDescriptor> capabilities) {
        List<Map<String, Object>> registrations = new ArrayList<Map<String, Object>>();
        if (capabilities == null) {
            return registrations;
        }
        for (ReachCapabilityDescriptor descriptor : capabilities) {
            if (descriptor == null || !StringUtils.hasText(descriptor.getName())) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("name", descriptor.getName());
            item.put("title", descriptor.getTitle());
            item.put("description", descriptor.getDescription());
            item.put("httpMethod", defaultString(descriptor.getHttpMethod(), "POST"));
            item.put("baseUrl", defaultString(properties.getProject().getBaseUrl(), ""));
            item.put("contextPath", defaultString(properties.getProject().getContextPath(), ""));
            item.put("endpointPath", defaultString(descriptor.getEndpointPath(), "/reachai/capabilities/" + urlEncodePathSegment(descriptor.getName()) + "/invoke"));
            item.put("requestBodyType", defaultString(descriptor.getRequestBodyType(), "java.util.Map"));
            item.put("responseType", descriptor.getReturnType());
            item.put("sideEffect", descriptor.getSideEffect() == null ? "WRITE" : descriptor.getSideEffect().name());
            item.put("enabled", Boolean.TRUE);
            item.put("agentVisible", descriptor.isAgentVisible());
            item.put("lightweightEnabled", Boolean.FALSE);
            item.put("visibility", defaultString(properties.getProject().getVisibility(), "PRIVATE"));
            item.put("parameters", parameterRegistrations(descriptor.getParameters()));
            item.put("metadata", capabilityMetadata(descriptor));
            registrations.add(item);
        }
        return registrations;
    }

    private List<Map<String, Object>> parameterRegistrations(List<ReachCapabilityParameter> parameters) {
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        if (parameters == null) {
            return out;
        }
        for (ReachCapabilityParameter parameter : parameters) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("name", parameter.getName());
            item.put("type", parameter.getType());
            item.put("description", parameter.getDescription());
            item.put("required", parameter.isRequired());
            item.put("location", "BODY");
            Map<String, Object> metadata = new LinkedHashMap<String, Object>();
            metadata.put("example", parameter.getExample());
            metadata.put("sourceHint", parameter.getSourceHint());
            metadata.put("dictType", parameter.getDictType());
            metadata.put("sensitive", parameter.isSensitive());
            item.put("metadata", metadata);
            out.add(item);
        }
        return out;
    }

    private Map<String, Object> capabilityMetadata(ReachCapabilityDescriptor descriptor) {
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        boolean springMvcEndpoint = StringUtils.hasText(descriptor.getEndpointPath());
        metadata.put("declared", !springMvcEndpoint);
        metadata.put("source", springMvcEndpoint ? "SpringMvcController" : "ReachCapability");
        metadata.put("domain", descriptor.getDomain());
        metadata.put("module", descriptor.getModule());
        metadata.put("tags", descriptor.getTags());
        metadata.put("requiredRoles", descriptor.getRequiredRoles());
        metadata.put("timeoutMs", descriptor.getTimeoutMs());
        metadata.put("retryLimit", descriptor.getRetryLimit());
        metadata.put("className", descriptor.getClassName());
        metadata.put("methodName", descriptor.getMethodName());
        metadata.put("invokeProtocol", "REACHAI_CAPABILITY_HTTP");
        return metadata;
    }

    Map<String, Object> heartbeatBody() {
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("runtimePlacement", "CAPABILITY_HOST");
        metadata.put("runtimeTypes", Collections.singletonList("SPRING_BOOT2_CAPABILITY_HOST"));
        metadata.put("supportsGraph", Boolean.FALSE);
        metadata.put("supportsTools", Boolean.TRUE);
        metadata.put("supportsWorkflow", Boolean.FALSE);
        metadata.put("supportsAutonomous", Boolean.FALSE);
        metadata.put("supportsEmbeddedExecution", Boolean.FALSE);
        metadata.put("supportsHybridExecution", Boolean.TRUE);

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("instanceId", instanceId);
        body.put("baseUrl", defaultString(properties.getProject().getBaseUrl(), ""));
        body.put("host", hostName());
        body.put("sdkVersion", sdkVersion());
        body.put("metadata", metadata);
        return body;
    }

    private void registerProject() {
        Map<String, Object> body = new LinkedHashMap<String, Object>();
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
        if (properties.getEmbed().getAllowedOrigins() != null && !properties.getEmbed().getAllowedOrigins().isEmpty()) {
            body.put("allowedOrigins", properties.getEmbed().getAllowedOrigins());
        }
        if (properties.getEmbed().getAllowedAgentIds() != null && !properties.getEmbed().getAllowedAgentIds().isEmpty()) {
            body.put("allowedAgentIds", properties.getEmbed().getAllowedAgentIds());
        }
        if (properties.getEmbed().getTokenTtlSeconds() != null && properties.getEmbed().getTokenTtlSeconds() > 0) {
            body.put("tokenTtlSeconds", properties.getEmbed().getTokenTtlSeconds());
        }
        post("/api/registry/projects/register", body);
    }

    private String post(String uriTemplate, Object body) {
        return transportExchange("POST", uriTemplate, body);
    }

    private String transportExchange(String method, String uriTemplate, Object body) {
        try {
            return transport.exchange(method, registryBaseUrl + expandProjectCode(uriTemplate), signedHeaders(), body);
        } catch (Exception e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private Map<String, String> signedHeaders() {
        ReachAiSignatureHeaders signatureHeaders = ReachAiSigner.sign(clientConfig());
        return signatureHeaders.toHttpHeaders();
    }

    private ReachAiClientConfig clientConfig() {
        return ReachAiClientConfig.builder()
                .endpoint(properties.getRegistry().getUrl())
                .projectCode(properties.getProject().getCode())
                .projectName(properties.getProject().getName())
                .appKey(defaultString(properties.getRegistry().getAppKey(), properties.getProject().getCode()))
                .appSecret(defaultString(properties.getRegistry().getAppSecret(), ""))
                .build();
    }

    public boolean isConfigured() {
        return configurationProblems().isEmpty();
    }

    public void logConfigurationSummary() {
        if (properties == null) {
            log.error("[ReachAI Registry] config missing: reachai properties are not available");
            return;
        }
        log.info("[ReachAI Registry] config registryEnabled={} registryUrl={} projectCode={} projectName={} baseUrl={} contextPath={} appKeyConfigured={} appSecretConfigured={} scanBeans={} heartbeatIntervalMs={}",
                properties.getRegistry().isEnabled(),
                properties.getRegistry().getUrl(),
                properties.getProject().getCode(),
                properties.getProject().getName(),
                properties.getProject().getBaseUrl(),
                properties.getProject().getContextPath(),
                StringUtils.hasText(properties.getRegistry().getAppKey()),
                StringUtils.hasText(properties.getRegistry().getAppSecret()),
                properties.getCapability().isScanBeans(),
                properties.getRegistry().getHeartbeatIntervalMs());
    }

    public void logConfigurationErrorIfNecessary(String action) {
        List<String> problems = configurationProblems();
        if (problems.isEmpty() || !configurationErrorLogged.compareAndSet(false, true)) {
            return;
        }
        log.error("[ReachAI Registry] skip {} because configuration is incomplete: {}. Set reachai.registry.url/app-key/app-secret and reachai.project.code; do not log app-secret value.",
                action, problems);
    }

    List<String> configurationProblems() {
        List<String> problems = new ArrayList<String>();
        if (properties == null) {
            problems.add("reachai properties missing");
            return problems;
        }
        if (!properties.getRegistry().isEnabled()) {
            problems.add("reachai.registry.enabled=false");
            return problems;
        }
        if (!StringUtils.hasText(properties.getRegistry().getUrl())) {
            problems.add("reachai.registry.url missing");
        }
        if (!StringUtils.hasText(properties.getRegistry().getAppKey())) {
            problems.add("reachai.registry.app-key missing");
        }
        if (!StringUtils.hasText(properties.getRegistry().getAppSecret())) {
            problems.add("reachai.registry.app-secret missing");
        }
        if (!StringUtils.hasText(properties.getProject().getCode())) {
            problems.add("reachai.project.code missing");
        }
        return problems;
    }

    private String expandProjectCode(String uriTemplate) {
        return uriTemplate.replace("{projectCode}", urlEncode(properties.getProject().getCode()));
    }

    private String urlEncode(String text) {
        try {
            return URLEncoder.encode(text, "UTF-8");
        } catch (Exception e) {
            throw new IllegalArgumentException("projectCode encoding failed", e);
        }
    }

    private String urlEncodePathSegment(String text) {
        return urlEncode(text).replace("+", "%20");
    }

    private String resolveInstanceId() {
        if (properties != null && StringUtils.hasText(properties.getProject().getInstanceId())) {
            return properties.getProject().getInstanceId().trim();
        }
        String code = properties == null ? "default" : defaultString(properties.getProject().getCode(), "default");
        return hostName() + ":" + code;
    }

    private String trimTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    private String sdkVersion() {
        String version = ReachAiRegistryClient.class.getPackage().getImplementationVersion();
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

    public static class ManualCapabilitySyncResponse {
        private final String instanceId;
        private final int capabilityCount;
        private final String registryResponse;

        ManualCapabilitySyncResponse(String instanceId, int capabilityCount, String registryResponse) {
            this.instanceId = instanceId;
            this.capabilityCount = capabilityCount;
            this.registryResponse = registryResponse;
        }

        public String getInstanceId() {
            return instanceId;
        }

        public int getCapabilityCount() {
            return capabilityCount;
        }

        public String getRegistryResponse() {
            return registryResponse;
        }
    }
}
