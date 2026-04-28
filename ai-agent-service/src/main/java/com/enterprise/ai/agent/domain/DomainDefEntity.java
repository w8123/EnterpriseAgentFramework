package com.enterprise.ai.agent.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 领域定义。{@code code} 是逻辑主键，前端做"领域 ↔ Tool/Skill"挂接时使用。
 * 关键词以 JSON 数组字符串存储（避免 MySQL JSON 列查询语法依赖）。
 */
@Data
@TableName("domain_def")
public class DomainDefEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务编码：finance / hr / crm / legal / ops / it 等。 */
    private String code;

    private String name;

    private String description;

    /** 关键词 JSON 数组：例如 {@code ["报销","工资","社保","公积金"]}。 */
    private String keywordsJson;

    /** 父领域 code，可空。 */
    private String parentCode;

    /** 是否对 Agent 可见（前端切换） */
    private Boolean agentVisible;

    private Boolean enabled;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
