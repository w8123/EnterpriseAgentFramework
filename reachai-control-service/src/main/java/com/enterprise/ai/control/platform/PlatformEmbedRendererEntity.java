package com.enterprise.ai.control.platform;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("eaf_embed_renderer")
public class PlatformEmbedRendererEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String rendererKey;
    private String appId;
    private String name;
    private String version;
    private String inputSchemaJson;
    private String allowedAgentIdsJson;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
