package com.enterprise.ai.agent.model.interactive;

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
 * 下发给前端的 UI 原语协议（固化组件，禁止 LLM 直接生成 HTML）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UiRequestPayload {

    public static final String TYPE_UI_REQUEST = "ui_request";

    /** 协议类型，固定 ui_request */
    @Builder.Default
    private String type = TYPE_UI_REQUEST;

    /**
     * form | select | multi_select | confirm | summary_card | text_question
     */
    private String component;

    private String interactionId;
    private String traceId;
    private String skillName;
    private String title;
    private int ttlSeconds;

    @Builder.Default
    private List<UiFieldPayload> fields = new ArrayList<>();

    /** 已填写的键值（用于表单预填） */
    @Builder.Default
    private Map<String, Object> prefilled = new LinkedHashMap<>();

    /** 仍缺或当前批次询问的字段 key */
    @Builder.Default
    private List<String> missing = new ArrayList<>();

    /** summary_card：摘要行 label → value */
    @Builder.Default
    private Map<String, Object> summary = new LinkedHashMap<>();

    /** confirm / text_question 辅助文案 */
    private String message;
}
