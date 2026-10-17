package com.enterprise.ai.runtime.execution.kernel;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns an engine-authored node failure into a short Chinese reason that is safe to persist in traces.
 * Only fixed phrases and GraphSpec-authored identifiers are kept; model output, tool payloads and
 * upstream messages never pass through, so callers may store the result alongside the failure code.
 */
public final class RuntimeNodeFailureReasons {

    private static final Pattern REQUIRED_FIELD = Pattern.compile(
            "^PARAMETER_EXTRACT required field is missing: ([A-Za-z0-9_.-]{1,128})$");

    private static final Map<String, String> BY_CODE = Map.ofEntries(
            Map.entry("RUNTIME_GRAPH_PARAMETER_FIELDS_REQUIRED", "参数提取节点没有配置要提取的字段"),
            Map.entry("RUNTIME_GRAPH_PARAMETER_EXTRACT_FAILED", "参数提取节点调用模型失败"),
            Map.entry("RUNTIME_GRAPH_MODEL_REQUIRED", "节点没有可用的模型，请在节点或 Workflow 上选择模型"),
            Map.entry("RUNTIME_GRAPH_LLM_EMPTY", "模型返回了空内容"),
            Map.entry("RUNTIME_GRAPH_LLM_FAILED", "模型调用失败"),
            Map.entry("RUNTIME_GRAPH_TOOL_REF_REQUIRED", "工具节点没有指定要调用的业务方法或 API"),
            Map.entry("RUNTIME_GRAPH_TOOL_BUSINESS_RESPONSE_FAILED", "业务系统返回了失败结果"),
            Map.entry("RUNTIME_GRAPH_TOOL_FAILED", "业务方法或 API 调用失败"));

    private RuntimeNodeFailureReasons() {
    }

    public static String safeReason(String code, String message) {
        if (code == null || code.isBlank()) {
            return null;
        }
        if ("RUNTIME_GRAPH_PARAMETER_REQUIRED".equals(code)) {
            String text = message == null ? "" : message.trim();
            Matcher matcher = REQUIRED_FIELD.matcher(text);
            if (matcher.matches()) {
                return "参数提取没有得到必填字段 " + matcher.group(1) + "，请检查输入数据是否包含它，或调整提取说明";
            }
            if (text.startsWith("model output is not a JSON object")) {
                return "参数提取时模型没有返回 JSON 对象";
            }
            if (text.startsWith("model output JSON is invalid")) {
                return "参数提取时模型返回的 JSON 无法解析";
            }
            if (text.startsWith("PARAMETER_EXTRACT field type conversion failed")) {
                return "参数提取结果的类型与字段声明不一致";
            }
            return "参数提取没有得到全部必填字段";
        }
        return BY_CODE.get(code);
    }
}
