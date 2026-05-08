package com.enterprise.ai.agent.tools.dynamic;

import com.enterprise.ai.agent.scan.ScanProjectEntity;
import org.springframework.util.StringUtils;

import java.net.URI;

/**
 * {@link DynamicHttpAiTool} 依赖 Spring {@code RestClient} 的 {@code baseUrl} 为含主机名的绝对 URI；
 * 扫描结果或配置中常出现仅路径、{@code http://} 等无效值，在此统一校验与回退扫描项目「项目域名」。
 */
public final class DynamicHttpToolBaseUrlSupport {

    private static final String INVALID_BASE_URL_MESSAGE =
            "无法发起 HTTP 调用：Base URL 无效或未配置。请填写带主机名的绝对地址（例如 https://api.example.com），"
                    + "勿使用仅路径、缺少主机名或形如 http:// 的地址。可在扫描项目「项目域名」或该 Tool / 接口的 Base URL 中配置；"
                    + "若行上曾被写入无效地址，保存有效的项目域名后会自动回退。";

    private DynamicHttpToolBaseUrlSupport() {
    }

    /**
     * 工具自身 base 已有效则用之；否则若存在扫描项目且其「项目域名」非空则回退为项目域名。
     */
    public static String resolveEffectiveBaseUrl(String toolBaseUrl, ScanProjectEntity project) {
        if (isValidRestClientBaseUrl(toolBaseUrl)) {
            return toolBaseUrl.trim();
        }
        if (project != null && StringUtils.hasText(project.getBaseUrl())) {
            return project.getBaseUrl().trim();
        }
        return toolBaseUrl == null ? "" : toolBaseUrl.trim();
    }

    public static void requireValidRestClientBaseUrl(String baseUrl) {
        if (!isValidRestClientBaseUrl(baseUrl)) {
            throw new IllegalStateException(INVALID_BASE_URL_MESSAGE);
        }
    }

    public static boolean isValidRestClientBaseUrl(String baseUrl) {
        if (!StringUtils.hasText(baseUrl)) {
            return false;
        }
        try {
            URI uri = URI.create(baseUrl.trim());
            if (!uri.isAbsolute()) {
                return false;
            }
            if (uri.getScheme() == null || uri.getScheme().isBlank()) {
                return false;
            }
            String host = uri.getHost();
            return host != null && !host.isBlank();
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
