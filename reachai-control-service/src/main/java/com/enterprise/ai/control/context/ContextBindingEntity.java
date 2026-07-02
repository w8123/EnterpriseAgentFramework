package com.enterprise.ai.control.context;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("context_binding")
public class ContextBindingEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long itemId;

    private String bindType;

    private String bindId;

    private String bindKey;

    private String tenantId;

    private Long projectId;

    private String projectCode;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime deletedAt;
}
