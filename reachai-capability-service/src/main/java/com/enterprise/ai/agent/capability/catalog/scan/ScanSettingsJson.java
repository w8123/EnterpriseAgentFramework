package com.enterprise.ai.agent.capability.catalog.scan;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

public final class ScanSettingsJson {

    private ScanSettingsJson() {
    }

    public static ScanSettings parseOrDefault(String json, ObjectMapper objectMapper) {
        ScanSettings base = ScanSettings.defaults();
        if (!StringUtils.hasText(json)) {
            return base;
        }
        try {
            return merge(base, objectMapper.readValue(json, ScanSettings.class));
        } catch (Exception ex) {
            return base;
        }
    }

    private static ScanSettings merge(ScanSettings base, ScanSettings settings) {
        if (settings.getDescriptionSourceOrder() != null && !settings.getDescriptionSourceOrder().isEmpty()) {
            base.setDescriptionSourceOrder(new ArrayList<>(settings.getDescriptionSourceOrder()));
        }
        if (settings.getParamDescriptionSourceOrder() != null && !settings.getParamDescriptionSourceOrder().isEmpty()) {
            base.setParamDescriptionSourceOrder(new ArrayList<>(settings.getParamDescriptionSourceOrder()));
        }
        if (settings.getDescriptionSourceEnabled() != null) {
            base.setDescriptionSourceEnabled(new HashMap<>(settings.getDescriptionSourceEnabled()));
        }
        if (settings.getParamDescriptionSourceEnabled() != null) {
            base.setParamDescriptionSourceEnabled(new HashMap<>(settings.getParamDescriptionSourceEnabled()));
        }
        base.setOnlyRestController(settings.isOnlyRestController());
        base.setHttpMethodWhitelist(settings.getHttpMethodWhitelist() == null
                ? List.of()
                : new ArrayList<>(settings.getHttpMethodWhitelist()));
        base.setClassIncludeRegex(settings.getClassIncludeRegex());
        base.setClassExcludeRegex(settings.getClassExcludeRegex());
        base.setSkipDeprecated(settings.isSkipDeprecated());
        if (settings.getDefaultFlags() != null) {
            ScanDefaultFlags flags = settings.getDefaultFlags();
            base.setDefaultFlags(new ScanDefaultFlags(
                    flags.isEnabled(), flags.isAgentVisible(), flags.isLightweightEnabled()));
        }
        base.setIncrementalMode(normalizeIncremental(settings.getIncrementalMode()));
        return base;
    }

    private static String normalizeIncremental(String value) {
        if (!StringUtils.hasText(value)) {
            return "OFF";
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return List.of("OFF", "MTIME", "GIT_DIFF").contains(normalized) ? normalized : "OFF";
    }
}
