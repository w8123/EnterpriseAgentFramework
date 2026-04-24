package com.enterprise.ai.domain.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文件信息视图对象 — 知识库详情页文件列表展示
 */
@Data
public class FileInfoVO {
    private Long id;
    private String fileId;
    private String fileName;
    private String fileType;
    private Long fileSize;
    private Integer chunkCount;
    /** 状态: 0-解析中 1-完成 2-失败 */
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
