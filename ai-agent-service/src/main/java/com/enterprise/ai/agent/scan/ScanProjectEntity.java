package com.enterprise.ai.agent.scan;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("scan_project")
public class ScanProjectEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    /**
     * 项目默认领域 code（Phase P1）。扫描器在落 tool_definition 时，会通过 {@code domain_assignment} 自动写入
     * {@code source=AUTO_FROM_PROJECT}，让该项目下所有 Tool 默认归属到该域。
     */
    private String defaultDomainCode;

    private String baseUrl;

    private String contextPath;

    private String scanPath;

    private String scanType;

    private String specFile;

    private Integer toolCount;

    private String status;

    private String errorMessage;

    /** 鉴权类型：none / api_key */
    private String authType;

    /** api_key 时：header / query */
    private String authApiKeyIn;

    private String authApiKeyName;

    private String authApiKeyValue;

    /** 扫描行为 JSON 配置，见 {@link com.enterprise.ai.agent.scan.ScanSettings} */
    private String scanSettings;

    /** 上次成功完成扫描时间，作增量基线 */
    private LocalDateTime lastScannedAt;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
