package com.enterprise.ai.agent.skill.interactive;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FieldSpec {

    private String key;
    private String label;
    /** text | number | date | select | multi_select | radio */
    private String type;
    private boolean required;

    @Builder.Default
    private FieldSourceSpec source = FieldSourceSpec.builder().kind("NONE").build();

    private String validateRegex;
    private String llmExtractHint;
    private Object defaultValue;

    /**
     * 非空时表示分组节点：仅用于结构化展示与提交时组装嵌套 tool 参数；
     * 槽位、预拉选项、校验仅针对叶子节点（无 children 或 children 为空）。
     */
    private List<FieldSpec> children;
}
