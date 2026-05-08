package com.enterprise.ai.spring.registry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 当前生效的 SDK 描述来源顺序（来自注册中心拉取或内置默认）。
 */
public class SdkDescriptionSourceSettingsHolder {

    private volatile SdkCapabilityDescriptionSettings current = SdkCapabilityDescriptionSettings.builtInDefaults();

    public SdkCapabilityDescriptionSettings current() {
        return current;
    }

    public void update(SdkCapabilityDescriptionSettings fromServer) {
        if (fromServer == null) {
            return;
        }
        SdkCapabilityDescriptionSettings def = SdkCapabilityDescriptionSettings.builtInDefaults();
        List<String> d = nonEmptyList(fromServer.descriptionSourceOrder(), def.descriptionSourceOrder());
        List<String> p = nonEmptyList(fromServer.paramDescriptionSourceOrder(), def.paramDescriptionSourceOrder());
        this.current = new SdkCapabilityDescriptionSettings(
                List.copyOf(d),
                List.copyOf(p),
                mergeEnabled(d, fromServer.descriptionSourceEnabled()),
                mergeEnabled(p, fromServer.paramDescriptionSourceEnabled()));
    }

    public void resetToBuiltInDefaults() {
        this.current = SdkCapabilityDescriptionSettings.builtInDefaults();
    }

    private static List<String> nonEmptyList(List<String> in, List<String> fallback) {
        if (in == null || in.isEmpty()) {
            return new ArrayList<>(fallback);
        }
        return new ArrayList<>(in);
    }

    private static Map<String, Boolean> mergeEnabled(List<String> keys, Map<String, Boolean> raw) {
        Map<String, Boolean> m = new LinkedHashMap<>();
        for (String k : keys) {
            Boolean v = raw == null ? null : raw.get(k);
            m.put(k, v != Boolean.FALSE);
        }
        return m;
    }
}
