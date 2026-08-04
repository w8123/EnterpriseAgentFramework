package com.enterprise.ai.control.pageworkbench.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_page_analysis_finding")
public class PageAnalysisFindingEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String findingKey;
    private Long projectId;
    private String projectCode;
    private Long pageId;
    private String pageKey;
    private String sourceTaskId;
    private String category;
    private String title;
    private String confirmedFact;
    private String technicalInference;
    private String openQuestion;
    private String useCase;
    private String businessConfirmStatus;
    private String technicalFeasibility;
    private String operationRisk;
    private String informationCompleteness;
    private String readScope;
    private String writeScope;
    private String implementationReference;
    private String acceptanceCriteria;
    private String relatedPagesJson;
    private String evidenceJson;
    private String codeReferencesJson;
    private String status;
    private String reviewedBy;
    private LocalDateTime reviewedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
