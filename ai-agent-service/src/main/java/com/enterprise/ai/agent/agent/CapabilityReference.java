package com.enterprise.ai.agent.agent;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent 对 Tool / Skill 的稳定引用。
 * <p>历史字段仍保留裸 name；新数据优先携带 qualifiedName 与 definitionId。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CapabilityReference {

    /** TOOL / SKILL。 */
    private String kind;

    private String projectCode;

    private String name;

    private String qualifiedName;

    private Long definitionId;

    private String version;
}
