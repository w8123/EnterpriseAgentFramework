package com.enterprise.ai.agent.skill.interactive;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/** 持久化在 skill_interaction.slot_state 的 JSON 文档 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SlotStateDocument {

    public static final String PHASE_COLLECT = "COLLECT";
    public static final String PHASE_CONFIRM = "CONFIRM";

    @Builder.Default
    private Map<String, Object> slots = new LinkedHashMap<>();

    /** COLLECT | CONFIRM */
    @Builder.Default
    private String phase = PHASE_COLLECT;
}
