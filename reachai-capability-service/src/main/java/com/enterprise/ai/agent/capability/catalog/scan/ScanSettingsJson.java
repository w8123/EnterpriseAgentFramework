package com.enterprise.ai.agent.capability.catalog.scan;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        List<String> descriptionOrder = withoutJavadoc(settings.getDescriptionSourceOrder());
        if (!descriptionOrder.isEmpty()) {
            base.setDescriptionSourceOrder(descriptionOrder);
        }
        List<String> paramOrder = withoutJavadoc(settings.getParamDescriptionSourceOrder());
        if (!paramOrder.isEmpty()) {
            base.setParamDescriptionSourceOrder(paramOrder);
        }
        if (settings.getDescriptionSourceEnabled() != null) {
            base.setDescriptionSourceEnabled(withoutJavadoc(settings.getDescriptionSourceEnabled()));
        }
        if (settings.getParamDescriptionSourceEnabled() != null) {
            base.setParamDescriptionSourceEnabled(withoutJavadoc(settings.getParamDescriptionSourceEnabled()));
        }
        base.setHttpMethodWhitelist(settings.getHttpMethodWhitelist() == null
                ? List.of()
                : new ArrayList<>(settings.getHttpMethodWhitelist()));
        base.setSkipDeprecated(settings.isSkipDeprecated());
        if (settings.getDefaultFlags() != null) {
            ScanDefaultFlags flags = settings.getDefaultFlags();
            base.setDefaultFlags(new ScanDefaultFlags(flags.isEnabled()));
        }
        return base;
    }

    /** Javadoc 只有源码扫描能读到，SDK 运行时拿不到，旧数据里的这一项直接丢弃。 */
    private static List<String> withoutJavadoc(List<String> order) {
        if (order == null) {
            return List.of();
        }
        return order.stream().filter(item -> item != null && !item.startsWith("JAVADOC")).toList();
    }

    private static Map<String, Boolean> withoutJavadoc(Map<String, Boolean> enabled) {
        Map<String, Boolean> result = new HashMap<>(enabled);
        result.keySet().removeIf(key -> key == null || key.startsWith("JAVADOC"));
        return result;
    }
}
