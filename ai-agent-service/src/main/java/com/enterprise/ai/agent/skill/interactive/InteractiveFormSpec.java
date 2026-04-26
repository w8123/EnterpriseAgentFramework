package com.enterprise.ai.agent.skill.interactive;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class InteractiveFormSpec {

    /** 用户确认后调用的 Tool 名（须为 TOOL） */
    private String targetTool;

    @Builder.Default
    private List<FieldSpec> fields = new ArrayList<>();

    @Builder.Default
    private int batchSize = 3;

    private String confirmTitle;

    /** 成功提示，支持占位 {{fieldKey}} */
    private String successTemplate;
}
