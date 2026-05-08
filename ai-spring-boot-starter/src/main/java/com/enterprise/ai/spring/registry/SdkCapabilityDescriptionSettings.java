package com.enterprise.ai.spring.registry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 与注册中心 {@code GET .../capability-description-settings} 响应字段一致，供 Jackson 反序列化。
 */
public record SdkCapabilityDescriptionSettings(
        List<String> descriptionSourceOrder,
        List<String> paramDescriptionSourceOrder,
        Map<String, Boolean> descriptionSourceEnabled,
        Map<String, Boolean> paramDescriptionSourceEnabled
) {
    public static SdkCapabilityDescriptionSettings builtInDefaults() {
        List<String> desc = List.of("SWAGGER_API_OPERATION", "OPENAPI_OPERATION", "METHOD_NAME");
        List<String> param = List.of("SCHEMA_ANNO", "PARAMETER_ANNO", "FIELD_NAME");
        Map<String, Boolean> de = new LinkedHashMap<>();
        for (String k : desc) {
            de.put(k, true);
        }
        Map<String, Boolean> pe = new LinkedHashMap<>();
        for (String k : param) {
            pe.put(k, true);
        }
        return new SdkCapabilityDescriptionSettings(desc, param, de, pe);
    }
}
