package com.enterprise.ai.agent.skill.interactive;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 字典码到选项列表的解析。
 * <p>
 * 不在此内置任何演示数据；InteractiveForm 中 {@code DICT} 字段请接入真实字典服务/HTTP，
 * 或通过 Skill 侧预拉选项等机制提供选项。
 */
@Component
public class InteractiveDictLookup {

    public List<FieldOptionSpec> options(String dictCode) {
        if (dictCode == null || dictCode.isBlank()) {
            return List.of();
        }
        return List.of();
    }
}
