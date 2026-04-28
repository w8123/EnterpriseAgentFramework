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
     * 非 null 且非空列表：分组节点，递归展开叶子；
     * {@code children == []}：显式空嵌套对象，不产生表单项，提交时在对应路径写入 {@code {}}。
     */
    private List<FieldSpec> children;
}
