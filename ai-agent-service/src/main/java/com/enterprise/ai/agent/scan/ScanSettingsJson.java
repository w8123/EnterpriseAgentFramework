package com.enterprise.ai.agent.scan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 解析/序列化/合并 scan_project.scan_settings 列与默认值。
 */
public final class ScanSettingsJson {

    private ScanSettingsJson() {
    }

    public static ScanSettings parseOrDefault(String json, ObjectMapper objectMapper) {
        ScanSettings base = ScanSettings.defaults();
        if (json == null || json.isBlank()) {
            return base;
        }
        try {
            ScanSettings s = objectMapper.readValue(json, ScanSettings.class);
            return merge(base, s);
        } catch (Exception ex) {
            return base;
        }
    }

    public static String toJson(ScanSettings settings, ObjectMapper objectMapper) {
        if (settings == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(merge(ScanSettings.defaults(), settings));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("无法序列化扫描设置", e);
        }
    }

    /** 将管理端请求体与默认值合并后存库 */
    public static ScanSettings fromRequest(ScanSettings body) {
        if (body == null) {
            return ScanSettings.defaults();
        }
        return merge(ScanSettings.defaults(), body);
    }

    private static ScanSettings merge(ScanSettings base, ScanSettings s) {
        if (s.getDescriptionSourceOrder() != null && !s.getDescriptionSourceOrder().isEmpty()) {
            base.setDescriptionSourceOrder(new ArrayList<>(s.getDescriptionSourceOrder()));
        }
        if (s.getParamDescriptionSourceOrder() != null && !s.getParamDescriptionSourceOrder().isEmpty()) {
            base.setParamDescriptionSourceOrder(new ArrayList<>(s.getParamDescriptionSourceOrder()));
        }
        if (s.getDescriptionSourceEnabled() != null) {
            base.setDescriptionSourceEnabled(new HashMap<>(s.getDescriptionSourceEnabled()));
        }
        if (s.getParamDescriptionSourceEnabled() != null) {
            base.setParamDescriptionSourceEnabled(new HashMap<>(s.getParamDescriptionSourceEnabled()));
        }
        base.setOnlyRestController(s.isOnlyRestController());
        if (s.getHttpMethodWhitelist() != null) {
            base.setHttpMethodWhitelist(new ArrayList<>(s.getHttpMethodWhitelist()));
        } else {
            base.setHttpMethodWhitelist(List.of());
        }
        base.setClassIncludeRegex(s.getClassIncludeRegex());
        base.setClassExcludeRegex(s.getClassExcludeRegex());
        base.setSkipDeprecated(s.isSkipDeprecated());
        if (s.getDefaultFlags() != null) {
            ScanDefaultFlags d = s.getDefaultFlags();
            base.setDefaultFlags(new ScanDefaultFlags(
                    d.isEnabled(), d.isAgentVisible(), d.isLightweightEnabled()));
        }
        if (StringUtils.hasText(s.getIncrementalMode())) {
            base.setIncrementalMode(normalizeIncremental(s.getIncrementalMode()));
        } else {
            base.setIncrementalMode("OFF");
        }
        return base;
    }

    public static void validate(ScanSettings s) {
        if (s == null) {
            return;
        }
        if (StringUtils.hasText(s.getClassIncludeRegex())) {
            try {
                Pattern.compile(s.getClassIncludeRegex());
            } catch (PatternSyntaxException ex) {
                throw new IllegalArgumentException("类名包含正则无效: " + ex.getDescription());
            }
        }
        if (StringUtils.hasText(s.getClassExcludeRegex())) {
            try {
                Pattern.compile(s.getClassExcludeRegex());
            } catch (PatternSyntaxException ex) {
                throw new IllegalArgumentException("类名排除正则无效: " + ex.getDescription());
            }
        }
        if (s.getHttpMethodWhitelist() != null) {
            for (String m : s.getHttpMethodWhitelist()) {
                if (m == null || m.isBlank()) {
                    throw new IllegalArgumentException("HTTP 方法白名单不能为空项");
                }
            }
        }
    }

    public static String normalizeIncremental(String value) {
        if (value == null || value.isBlank()) {
            return "OFF";
        }
        String u = value.trim().toUpperCase(Locale.ROOT);
        if (List.of("OFF", "MTIME", "GIT_DIFF").contains(u)) {
            return u;
        }
        return "OFF";
    }

    public static boolean isControllerOnlySettingsApply(String scanType) {
        if (scanType == null) {
            return true;
        }
        String t = scanType.trim().toLowerCase(Locale.ROOT);
        return "controller".equals(t) || "auto".equals(t);
    }

    public static boolean isIncrementalOn(ScanSettings s) {
        if (s == null) {
            return false;
        }
        String m = s.getIncrementalMode() == null ? "OFF" : s.getIncrementalMode();
        return !"OFF".equals(m);
    }

    public static boolean isHttpWhitelistActive(ScanSettings s) {
        return s != null && s.getHttpMethodWhitelist() != null
                && !s.getHttpMethodWhitelist().isEmpty();
    }
}
