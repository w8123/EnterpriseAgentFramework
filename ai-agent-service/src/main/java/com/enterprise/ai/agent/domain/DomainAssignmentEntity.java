package com.enterprise.ai.agent.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 领域 ↔ Tool/Skill/Project/Agent 归属关系。
 * <ul>
 *     <li>{@code targetKind} ∈ {@code TOOL / SKILL / PROJECT / AGENT}；</li>
 *     <li>{@code targetName} 对 TOOL/SKILL/AGENT 是 name；对 PROJECT 是 project_id 字符串；</li>
 *     <li>{@code source} ∈ {@code MANUAL / AUTO_FROM_PROJECT}：扫描期由 project 默认领域继承的标 AUTO；</li>
 *     <li>{@code weight} 仅用于"软排序"，未来可拓展 0..1 的多领域归属。</li>
 * </ul>
 */
@Data
@TableName("domain_assignment")
public class DomainAssignmentEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String targetKind;

    private String targetName;

    private String domainCode;

    private Double weight;

    private String source;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
