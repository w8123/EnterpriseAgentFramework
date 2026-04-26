package com.enterprise.ai.agent.skill.interactive;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 字段数据来源：STATIC / DICT / TOOL_CALL / NONE。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FieldSourceSpec {

    /** STATIC | DICT | TOOL_CALL | NONE */
    private String kind;

    @Builder.Default
    private List<FieldOptionSpec> options = new ArrayList<>();

    private String dictCode;

    private String toolName;

    @Builder.Default
    private Map<String, Object> toolArgs = new LinkedHashMap<>();

    private String valueField;

    private String labelField;
}
