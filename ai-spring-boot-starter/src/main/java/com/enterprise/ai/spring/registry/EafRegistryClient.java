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

public class EafRegistryClient {

    private static final Logger log = LoggerFactory.getLogger(EafRegistryClient.class);

    private final EafRegistryProperties properties;

    private final EafCapabilityScanner scanner;

    private final SdkDescriptionSourceSettingsHolder descriptionSettingsHolder;

    private final RestClient restClient;

    private final String instanceId;

    public EafRegistryClient(EafRegistryProperties properties,
                             EafCapabilityScanner scanner,
                             SdkDescriptionSourceSettingsHolder descriptionSettingsHolder) {
        this.properties = properties;
        this.scanner = scanner;
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
            log.warn("[EAF Registry] capability-description-settings fetch failed, using built-in defaults: {}",
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
        Map<String, Object> body = Map.of(
                "instanceId", instanceId,
                "baseUrl", defaultString(properties.getProject().getBaseUrl(), ""),
                "host", hostName(),
                "sdkVersion", EafRegistryClient.class.getPackage().getImplementationVersion() == null
                        ? "dev"
                        : EafRegistryClient.class.getPackage().getImplementationVersion()
        );
        try {
            signedPost("/api/registry/projects/{projectCode}/instances/heartbeat", body);
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
        SignatureHeaders headers = signatureHeaders();
        restClient.post()
                .uri(uri, properties.getProject().getCode())
                .header("X-EAF-App-Key", headers.appKey())
                .header("X-EAF-Timestamp", headers.timestamp())
                .header("X-EAF-Nonce", headers.nonce())
                .header("X-EAF-Signature", headers.signature())
                .body(body)
                .retrieve()
                .toBodilessEntity();
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
            log.warn("[EAF Registry] {} failed project={} registryUrl={} httpStatus={} responseBody={}",
                    phase, code, base, rre.getStatusCode().value(), body);
            return;
        }
        log.warn("[EAF Registry] {} failed project={} registryUrl={}: {}",
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

    private record SignatureHeaders(String appKey, String timestamp, String nonce, String signature) {
    }
}
