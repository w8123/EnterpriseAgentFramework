package com.enterprise.ai.reach.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

@ConfigurationProperties(prefix = "reachai")
public class ReachAiRegistryProperties {

    private final Registry registry = new Registry();
    private final Project project = new Project();
    private final Capability capability = new Capability();
    private final Embed embed = new Embed();
    private final AgentGraph agentGraph = new AgentGraph();
    private final Activity activity = new Activity();

    public Registry getRegistry() {
        return registry;
    }

    public Project getProject() {
        return project;
    }

    public Capability getCapability() {
        return capability;
    }

    public Embed getEmbed() {
        return embed;
    }

    public AgentGraph getAgentGraph() {
        return agentGraph;
    }

    public Activity getActivity() {
        return activity;
    }

    public static class Registry {
        private boolean enabled = true;
        private String url;
        private String appKey;
        private String appSecret;
        private String enrollmentToken;
        private String credentialStorePath;
        private long heartbeatIntervalMs = 180000L;
        private int connectTimeoutMs = 5000;
        private int readTimeoutMs = 120000;

        public int getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(int value) { connectTimeoutMs = value; }
        public int getReadTimeoutMs() { return readTimeoutMs; }
        public void setReadTimeoutMs(int value) { readTimeoutMs = value; }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getAppKey() {
            return appKey;
        }

        public void setAppKey(String appKey) {
            this.appKey = appKey;
        }

        public String getAppSecret() {
            return appSecret;
        }

        public void setAppSecret(String appSecret) {
            this.appSecret = appSecret;
        }

        public String getEnrollmentToken() {
            return enrollmentToken;
        }

        public void setEnrollmentToken(String enrollmentToken) {
            this.enrollmentToken = enrollmentToken;
        }

        public String getCredentialStorePath() {
            return credentialStorePath;
        }

        public void setCredentialStorePath(String credentialStorePath) {
            this.credentialStorePath = credentialStorePath;
        }

        public long getHeartbeatIntervalMs() {
            return heartbeatIntervalMs;
        }

        public void setHeartbeatIntervalMs(long heartbeatIntervalMs) {
            this.heartbeatIntervalMs = heartbeatIntervalMs;
        }
    }

    public static class Project {
        private String code;
        private String name;
        private String baseUrl;
        private String contextPath = "";
        private String environment = "default";
        private String owner;
        private String visibility = "PRIVATE";
        private String instanceId;

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getContextPath() {
            return contextPath;
        }

        public void setContextPath(String contextPath) {
            this.contextPath = contextPath;
        }

        public String getEnvironment() {
            return environment;
        }

        public void setEnvironment(String environment) {
            this.environment = environment;
        }

        public String getOwner() {
            return owner;
        }

        public void setOwner(String owner) {
            this.owner = owner;
        }

        public String getVisibility() {
            return visibility;
        }

        public void setVisibility(String visibility) {
            this.visibility = visibility;
        }

        public String getInstanceId() {
            return instanceId;
        }

        public void setInstanceId(String instanceId) {
            this.instanceId = instanceId;
        }
    }

    public static class Capability {
        private boolean scanBeans = true;
        private ScanMode scanMode = ScanMode.ALL_CONTROLLERS;
        private boolean requireInvocationToken = true;
        private List<String> scanPackages = new ArrayList<String>();
        private List<String> excludePackages = new ArrayList<String>();

        public boolean isScanBeans() {
            return scanBeans;
        }

        public void setScanBeans(boolean scanBeans) {
            this.scanBeans = scanBeans;
        }

        public ScanMode getScanMode() {
            return scanMode;
        }

        public void setScanMode(ScanMode scanMode) {
            this.scanMode = scanMode == null ? ScanMode.ALL_CONTROLLERS : scanMode;
        }

        public boolean isRequireInvocationToken() {
            return requireInvocationToken;
        }

        public void setRequireInvocationToken(boolean requireInvocationToken) {
            this.requireInvocationToken = requireInvocationToken;
        }

        public List<String> getScanPackages() {
            return scanPackages;
        }

        public void setScanPackages(List<String> scanPackages) {
            this.scanPackages = scanPackages == null ? new ArrayList<String>() : scanPackages;
        }

        public List<String> getExcludePackages() {
            return excludePackages;
        }

        public void setExcludePackages(List<String> excludePackages) {
            this.excludePackages = excludePackages == null ? new ArrayList<String>() : excludePackages;
        }
    }

    public enum ScanMode {
        /** Explicit {@code @ReachCapability} declarations plus inferred Spring MVC controller endpoints. */
        ALL_CONTROLLERS,
        /** Only explicit {@code @ReachCapability} declarations. */
        ANNOTATED_ONLY
    }

    public static class Embed {
        private List<String> allowedOrigins = new ArrayList<String>();
        private List<String> allowedAgentIds = new ArrayList<String>();
        private Integer tokenTtlSeconds;

        public List<String> getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins == null ? new ArrayList<String>() : allowedOrigins;
        }

        public List<String> getAllowedAgentIds() {
            return allowedAgentIds;
        }

        public void setAllowedAgentIds(List<String> allowedAgentIds) {
            this.allowedAgentIds = allowedAgentIds == null ? new ArrayList<String>() : allowedAgentIds;
        }

        public Integer getTokenTtlSeconds() {
            return tokenTtlSeconds;
        }

        public void setTokenTtlSeconds(Integer tokenTtlSeconds) {
            this.tokenTtlSeconds = tokenTtlSeconds;
        }
    }

    public static class AgentGraph {
        private boolean syncOnStartup = true;

        public boolean isSyncOnStartup() {
            return syncOnStartup;
        }

        public void setSyncOnStartup(boolean syncOnStartup) {
            this.syncOnStartup = syncOnStartup;
        }
    }

    /**
     * 主动感知: record which operations and searches logged-in employees make in this system, so ReachAI can show
     * managers the common operations and problems and recommend shortcuts. Off by default; request bodies and
     * responses are never sent.
     */
    public static class Activity {
        private boolean enabled = false;
        /** GET parameters whose value is treated as a search word. */
        private List<String> searchParameters = new ArrayList<String>(java.util.Arrays.asList(
                "keyword", "keywords", "q", "query", "search", "searchKey", "name", "title", "code", "no"));
        /** Ant-style paths never recorded (ReachAI's own endpoints, health checks…). */
        private List<String> excludePaths = new ArrayList<String>(java.util.Arrays.asList(
                "/reachai/**", "/actuator/**", "/error", "/favicon.ico"));
        /** Values of a response body's {@code code} field that mean success (e.g. 芋道 CommonResult uses 0). */
        private List<Integer> successCodes = new ArrayList<Integer>(java.util.Arrays.asList(0, 200));
        /** Only record requests sent by a browser page (Referer or Origin header), not scripts or server calls. */
        private boolean browserRequestsOnly = true;
        private int maxQueueSize = 2000;
        private int batchSize = 200;
        private long flushIntervalMs = 10000L;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public List<String> getSearchParameters() { return searchParameters; }
        public void setSearchParameters(List<String> value) { searchParameters = value == null ? new ArrayList<String>() : value; }
        public List<String> getExcludePaths() { return excludePaths; }
        public void setExcludePaths(List<String> value) { excludePaths = value == null ? new ArrayList<String>() : value; }
        public List<Integer> getSuccessCodes() { return successCodes; }
        public void setSuccessCodes(List<Integer> value) { successCodes = value == null ? new ArrayList<Integer>() : value; }
        public boolean isBrowserRequestsOnly() { return browserRequestsOnly; }
        public void setBrowserRequestsOnly(boolean value) { browserRequestsOnly = value; }
        public int getMaxQueueSize() { return maxQueueSize; }
        public void setMaxQueueSize(int value) { maxQueueSize = Math.max(10, value); }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int value) { batchSize = Math.max(1, Math.min(500, value)); }
        public long getFlushIntervalMs() { return flushIntervalMs; }
        public void setFlushIntervalMs(long value) { flushIntervalMs = Math.max(1000L, value); }
    }
}
