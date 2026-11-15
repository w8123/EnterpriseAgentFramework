package com.enterprise.ai.runtime.execution.http;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** One intranet host a platform administrator allowed the platform to call. */
@Data
@TableName("runtime_http_egress_host")
public class RuntimeHttpEgressHostEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String host;
    private String note;
    private String createdBy;
    private LocalDateTime createdAt;
}
