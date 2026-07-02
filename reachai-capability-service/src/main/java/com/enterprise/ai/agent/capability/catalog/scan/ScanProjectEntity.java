package com.enterprise.ai.agent.capability.catalog.scan;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("capability_scan_project")
public class ScanProjectEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    /** 稳定项目编码；SDK 注册和跨系统引用优先使用，旧扫描项目为空时可由 name 归一化生成。 */
    private String projectCode;

    /** 项目形态：SCAN / REGISTERED / HYBRID。 */
    private String projectKind;

    /** 环境：dev / test / prod / default。 */
    private String environment;

    /** 项目负责人或团队标识。 */
    private String owner;

    /** 可见性：PRIVATE / PROJECT / SHARED / PUBLIC。 */
    private String visibility;

    /**
     * 项目默认领域 code（Phase P1）。扫描器在落 capability_tool_definition 时，会通过 {@code capability_domain_assignment} 自动写入
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

    /** 扫描行为 JSON 配置，见 {@link com.enterprise.ai.agent.capability.catalog.scan.ScanSettings} */
    private String scanSettings;

    /** 上次成功完成扫描时间，作增量基线 */
    private LocalDateTime lastScannedAt;

    /** AI Coding tools use this project key to fetch the onboarding manifest without platform login. */
    private String aiCodingAccessKey;

    /** True when AI Coding manifest access is enabled for this project. */
    private Boolean aiCodingAccessEnabled;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
