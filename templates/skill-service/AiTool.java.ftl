package ${basePackage}.tools;

import ${basePackage}.${clientClassName};
import com.enterprise.ai.skill.AiTool;
import com.enterprise.ai.skill.ToolParameter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ${toolClassName} implements AiTool {

    private final ${clientClassName} ${clientFieldName};

    @Override
    public String name() {
        return "${toolName}";
    }

    @Override
    public String description() {
        return "${toolDescription?replace("\\", "\\\\")?replace("\"", "\\\"")}";
    }

    @Override
    public List<ToolParameter> parameters() {
<#if allParameters?size == 0>
        return List.of();
<#else>
        return List.of(
<#list allParameters as parameter>
                ToolParameter.${parameter.required?string("required","optional")}("${parameter.name}", "${parameter.type}", "${parameter.description?replace("\\", "\\\\")?replace("\"", "\\\"")}")<#if parameter_has_next>,</#if>
</#list>
        );
</#if>
    }

    @Override
    public Object execute(Map<String, Object> args) {
        Map<String, Object> pathVariables = new LinkedHashMap<>();
<#list pathParameters as parameter>
        pathVariables.put("${parameter.name}", args.get("${parameter.name}"));
</#list>
        Map<String, Object> queryParameters = new LinkedHashMap<>();
<#list queryParameters as parameter>
        queryParameters.put("${parameter.name}", args.get("${parameter.name}"));
</#list>
<#if hasBodyParameter>
        Object body = args.get("${bodyParameter.name}");
<#else>
        Object body = null;
</#if>
        return ${clientFieldName}.invoke("${httpMethod}", "${path}", pathVariables, queryParameters, body);
    }
}
