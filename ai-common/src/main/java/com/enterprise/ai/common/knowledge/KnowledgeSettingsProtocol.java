package com.enterprise.ai.common.knowledge;

import java.util.Map;

/** Fixed migration response; an obsolete request never obtains a fresh revision or changes owner state. */
public final class KnowledgeSettingsProtocol {
    public static final String REVISION_REQUIRED = "KNOWLEDGE_SETTINGS_REVISION_REQUIRED";
    private KnowledgeSettingsProtocol() { }

    public static Map<String, Object> revisionRequired() {
        return Map.of("code", REVISION_REQUIRED,
                "message", "请通过 Control 读取知识库当前设置和修订，再使用带修订的原子保存接口；旧入口不再写入。",
                "contextPath", "/ai/knowledge/kb/{kbCode}/settings-context",
                "savePath", "/ai/knowledge/kb/{kbCode}/settings-if-unchanged");
    }
}
