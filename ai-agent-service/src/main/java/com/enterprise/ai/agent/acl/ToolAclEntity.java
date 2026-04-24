package com.enterprise.ai.agent.acl;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * {@code tool_acl} 表的 MyBatis-Plus 实体。
 * <p>
 * Phase 3.1 Tool ACL 基础结构：
 * <ul>
 *   <li>{@code roleCode} — 角色编码，来源于业务身份（JWT / 请求头 / 网关注入）；</li>
 *   <li>{@code targetKind} — {@code TOOL} / {@code SKILL} / {@code ALL}，{@code ALL} 等价于 TOOL ∪ SKILL；</li>
 *   <li>{@code targetName} — 具体 tool / skill 名，或通配 {@code *}；</li>
 *   <li>{@code permission} — {@code ALLOW} / {@code DENY}，DENY 在决策时优先级最高。</li>
 * </ul>
 */
@Data
@TableName("tool_acl")
public class ToolAclEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String roleCode;

    private String targetKind;

    private String targetName;

    private String permission;

    private String note;

    private Boolean enabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
