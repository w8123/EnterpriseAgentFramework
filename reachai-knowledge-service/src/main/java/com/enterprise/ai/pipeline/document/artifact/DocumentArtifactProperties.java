package com.enterprise.ai.pipeline.document.artifact;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Storage for upload originals and deterministic parsing artifacts.  Local
 * storage is intended only for single-node development; deployed workloads use
 * the S3-compatible MinIO mode.
 */
@Data
@ConfigurationProperties(prefix = "reachai.knowledge.document-artifact-store")
public class DocumentArtifactProperties {

    /** local or s3 */
    private String type = "local";

    /** Development-only root for type=local. */
    private String localRoot = "./data/reachai-knowledge-artifacts";

    private String endpoint;
    private String accessKey;
    private String secretKey;
    private String region = "us-east-1";
    private String bucket = "reachai-knowledge";
    private boolean autoCreateBucket = true;
}
