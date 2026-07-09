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

    public static class Registry {
        private boolean enabled = true;
        private String url;
        private String appKey;
        private String appSecret;
        private long heartbeatIntervalMs = 180000L;

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
        private boolean requireInvocationToken = true;
        private List<String> scanPackages = new ArrayList<String>();
        private List<String> excludePackages = new ArrayList<String>();

        public boolean isScanBeans() {
            return scanBeans;
        }

        public void setScanBeans(boolean scanBeans) {
            this.scanBeans = scanBeans;
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
}
