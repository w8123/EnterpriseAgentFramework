package com.enterprise.ai.agent.registry;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("capability_diff_item")
public class CapabilityDiffItemEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long snapshotId;

    private String syncId;

    private Long projectId;

    private String projectCode;

    private String qualifiedName;

    private String name;

    private String storageName;

    /** ADDED / CHANGED / UNCHANGED / DELETED。 */
    private String changeType;

    private Long existingToolId;

    /** 字段级差异 JSON，便于前端评审展示。 */
    private String fieldDiffJson;

    /** 被 Agent / Tool / ACL / MCP / A2A 等引用的影响分析 JSON。 */
    private String impactJson;

    /**
     * 执行评审前的能力目录状态。仅保存可被 SDK apply 改写的扫描目录与运行时调用投影字段，
     * 用于把回滚落实到真实执行资产，而不是只回写 review_status。
     */
    private String beforeStateJson;

    /** PENDING / APPLIED / IGNORED / ROLLED_BACK。 */
    private String reviewStatus;

    private String reviewNote;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
