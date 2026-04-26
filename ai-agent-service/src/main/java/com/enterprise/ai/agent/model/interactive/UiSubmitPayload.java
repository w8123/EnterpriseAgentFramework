package com.enterprise.ai.agent.model.interactive;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 前端提交交互卡片时的载荷（resume 路径）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UiSubmitPayload {

    /** submit | cancel | modify */
    private String action;

    /** 字段 key → 用户填写值 */
    @Builder.Default
    private Map<String, Object> values = new LinkedHashMap<>();
}
