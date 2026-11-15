package com.enterprise.ai.control.activity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_activity_insight")
public class ActivityInsightEntity {

    public static final String FLOW = "FLOW";
    public static final String PITFALL = "PITFALL";
    public static final String SUGGESTED = "SUGGESTED";
    public static final String RECOMMENDED = "RECOMMENDED";
    public static final String DISMISSED = "DISMISSED";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String projectCode;
    private String kind;
    private String fingerprint;
    private String title;
    private String summary;
    private String advice;
    private String shortcutPrompt;
    private String stepsJson;
    private String pagePathsJson;
    private String evidenceJson;
    private String status;
    private Integer usedCount;
    private String generatedBy;
    private String modelInstanceId;
    private String updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
