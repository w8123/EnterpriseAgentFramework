package com.enterprise.ai.runtime.managed;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "reachai.runtime.managed-executor.artifact-store")
public class ManagedArtifactStoreProperties {

    /** disabled, local (single-node development), or s3. */
    private String type = "disabled";

    private String localRoot = "./data/reachai-runtime-managed-artifacts";

    private String endpoint;
    private String accessKey;
    private String secretKey;
    private String region = "us-east-1";
    private String bucket = "reachai-managed-executor";
    private boolean autoCreateBucket;

    private long maxPatchBytes = 32L * 1024L * 1024L;
    private long maxJsonBytes = 4L * 1024L * 1024L;
    private long maxEventLogBytes = 16L * 1024L * 1024L;

    /** Metadata access deadline; production object-store lifecycle must use the same or shorter value. */
    private int retentionDays = 30;
}
