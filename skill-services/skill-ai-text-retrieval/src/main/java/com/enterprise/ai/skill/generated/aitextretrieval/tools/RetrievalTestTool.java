package com.enterprise.ai.skill.generated.aitextretrieval.tools;

import com.enterprise.ai.skill.generated.aitextretrieval.AiTextRetrievalClient;
import com.enterprise.ai.skill.AiTool;
import com.enterprise.ai.skill.ToolParameter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class RetrievalTestTool implements AiTool {

    private final AiTextRetrievalClient aiTextRetrievalClient;

    @Override
    public String name() {
        return "retrieval_test";
    }

    @Override
    public String description() {
        return "POST /ai/retrieval/test — 检索测试";
    }

    @Override
    public List<ToolParameter> parameters() {
        return List.of(
                ToolParameter.required("body_json", "json", "JSON 请求体，对应 RetrievalTestRequest")
        );
    }

    @Override
    public Object execute(Map<String, Object> args) {
        Map<String, Object> pathVariables = new LinkedHashMap<>();
        Map<String, Object> queryParameters = new LinkedHashMap<>();
        Object body = args.get("body_json");
        return aiTextRetrievalClient.invoke("POST", "/retrieval/test", pathVariables, queryParameters, body);
    }
}
