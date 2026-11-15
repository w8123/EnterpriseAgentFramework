package com.enterprise.ai.control.pageworkbench.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("control_page_action_effect_review")
public class PageActionEffectReviewEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long actionId;
    private Long pageId;
    private Long projectId;
    private String projectCode;
    private String pageKey;
    private String actionKey;
    private Long revision;
    private String decision;
    private String effect;
    private String catalogHash;
    private String implementationVersion;
    private String targetAlias;
    private String fieldLabelsJson;
    private Long actorUserId;
    private String actorSessionId;
    private LocalDateTime createdAt;
}
