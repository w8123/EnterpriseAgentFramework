package com.enterprise.ai.runtime.runops;

import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 调用方已经选定的执行事实；RunOps 据此记录审计，不接收或重新解析业务实体。 */
public final class RuntimeRunSnapshots {

    private RuntimeRunSnapshots() {
    }

    public record AgentConfiguration(
            Long versionId,
            Integer versionNo,
            String runtimeType,
            int workflowToolCount,
            List<String> workflowToolNames) {

        public AgentConfiguration {
            workflowToolNames = List.copyOf(workflowToolNames);
        }
    }

    public record Workflow(
            String workflowId,
            String keySlug,
            String name,
            Long projectId,
            String projectCode,
            String executionEngine,
            String graphSpecJson) {
    }

    public record PublishedWorkflow(
            String workflowId,
            String keySlug,
            String name,
            Long projectId,
            String projectCode,
            String executionEngine,
            Long versionId,
            String version,
            String graphSpecJson) {
    }

    /** 只保留版本审计字段，不携带 Skill 包内容、文件路径或执行权限对象。 */
    public record SkillBinding(
            Long skillId,
            Long skillVersionId,
            String publisher,
            String name,
            String visibility,
            String projectCode,
            String version,
            String sourceSha256,
            String activationMode,
            String scriptPolicy,
            boolean required) {

        public Map<String, Object> runMetadata() {
            Map<String, Object> value = commonMetadata();
            if (StringUtils.hasText(visibility)) value.put("visibility", visibility);
            if (StringUtils.hasText(projectCode)) value.put("projectCode", projectCode);
            return Map.copyOf(value);
        }

        public Map<String, Object> traceMetadata() {
            Map<String, Object> value = commonMetadata();
            value.put("identity", publisher + "/" + name + "@" + version + "#" + sourceSha256);
            value.put("required", required);
            return Map.copyOf(value);
        }

        private Map<String, Object> commonMetadata() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("skillId", skillId);
            value.put("skillVersionId", skillVersionId);
            value.put("publisher", publisher);
            value.put("name", name);
            value.put("version", version);
            value.put("sourceSha256", sourceSha256);
            value.put("activationMode", activationMode);
            value.put("scriptPolicy", scriptPolicy);
            return value;
        }
    }
}
