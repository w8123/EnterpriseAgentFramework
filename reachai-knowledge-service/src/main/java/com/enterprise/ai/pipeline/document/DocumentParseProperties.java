package com.enterprise.ai.pipeline.document;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Input limits are checked before a parser or remote service sees user content.
 */
@Data
@ConfigurationProperties(prefix = "reachai.knowledge.document-import")
public class DocumentParseProperties {

    /** Kept aligned with Spring multipart's default 50MiB limit. */
    private long maxFileBytes = 52_428_800L;

    /** Java fast-path accepts ordinary UTF-8 text only. */
    private long maxJavaFastFileBytes = 52_428_800L;

    /** Header bytes sufficient for the formats in the explicit allow-list. */
    private int formatProbeBytes = 64;
}
