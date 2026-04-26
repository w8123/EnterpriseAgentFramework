package com.enterprise.ai.agent.skill.interactive;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 字典码到选项列表的解析；当前为进程内占位实现，生产请接入真实字典服务或 HTTP。
 */
@Component
public class InteractiveDictLookup {

    private static final Map<String, List<FieldOptionSpec>> DICTS = new ConcurrentHashMap<>();

    static {
        DICTS.put("SHIFT_TYPE", List.of(
                FieldOptionSpec.builder().value("DAY").label("白班").build(),
                FieldOptionSpec.builder().value("NIGHT").label("夜班").build(),
                FieldOptionSpec.builder().value("ROTATE").label("轮班").build()
        ));
    }

    public List<FieldOptionSpec> options(String dictCode) {
        if (dictCode == null || dictCode.isBlank()) {
            return List.of();
        }
        return DICTS.getOrDefault(dictCode.trim().toUpperCase(), List.of());
    }
}
