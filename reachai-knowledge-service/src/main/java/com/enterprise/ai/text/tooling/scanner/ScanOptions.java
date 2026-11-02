package com.enterprise.ai.text.tooling.scanner;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * OpenAPI 文档解析选项。旧版源码扫描字段已移除，未知字段忽略以兼容旧调用方。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ScanOptions {

    private List<String> httpMethodWhitelist;
    private Boolean skipDeprecated;

    public static ScanOptions empty() {
        return new ScanOptions();
    }

    public List<String> getHttpMethodWhitelist() {
        return httpMethodWhitelist;
    }

    public void setHttpMethodWhitelist(List<String> httpMethodWhitelist) {
        this.httpMethodWhitelist = httpMethodWhitelist;
    }

    public Boolean getSkipDeprecated() {
        return skipDeprecated;
    }

    public void setSkipDeprecated(Boolean skipDeprecated) {
        this.skipDeprecated = skipDeprecated;
    }
}
