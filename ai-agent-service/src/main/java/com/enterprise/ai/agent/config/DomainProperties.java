package com.enterprise.ai.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "ai.domain")
public class DomainProperties {

    /** 总开关；关闭后 {@link com.enterprise.ai.agent.domain.DomainTagger#tag(String)} 直接返回 null。 */
    private boolean enabled = false;

    /** 分类器 top-K。 */
    private int topK = 2;

    /**
     * 召回时若按 domain 过滤后命中为空，是否回退到不带 domain 的全量召回。
     * 默认 true（软过滤），避免误分类导致整链路失败。
     */
    private boolean softFallback = true;
}
