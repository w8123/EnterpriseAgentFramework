package com.enterprise.ai.pipeline.document.artifact;

import org.simpleframework.xml.Element;
import org.simpleframework.xml.ElementList;
import org.simpleframework.xml.Root;

import java.util.List;

/** Cleanup needs upload identity and pagination, not optional provider ownership/storage metadata. */
@Root(name = "ListMultipartUploadsResult", strict = false)
final class S3MultipartUploads {
    @Element(name = "Bucket")
    private String bucket;
    @Element(name = "IsTruncated")
    private boolean truncated;
    @Element(name = "NextKeyMarker", required = false)
    private String nextKeyMarker;
    @Element(name = "NextUploadIdMarker", required = false)
    private String nextUploadIdMarker;
    @ElementList(entry = "Upload", inline = true, required = false)
    private List<Upload> uploads;

    void validate(String expectedBucket) {
        if (!expectedBucket.equals(bucket)) throw new DocumentArtifactException("S3 分片列表存储桶不匹配");
        for (Upload upload : uploads()) {
            if (upload.key == null || upload.key.isBlank() || upload.id == null || upload.id.isBlank()) {
                throw new DocumentArtifactException("S3 分片列表缺少对象或上传身份");
            }
        }
    }

    boolean isTruncated() { return truncated; }
    String nextKeyMarker() { return nextKeyMarker; }
    String nextUploadIdMarker() { return nextUploadIdMarker; }
    List<Upload> uploads() { return uploads == null ? List.of() : List.copyOf(uploads); }

    @Root(name = "Upload", strict = false)
    static final class Upload {
        @Element(name = "Key")
        private String key;
        @Element(name = "UploadId")
        private String id;

        String objectName() { return key; }
        String uploadId() { return id; }
    }
}
