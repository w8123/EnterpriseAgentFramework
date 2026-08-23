package com.enterprise.ai.service;

import com.enterprise.ai.domain.dto.DocumentImportAccessContext;
import com.enterprise.ai.domain.dto.DocumentImportJobResponse;
import com.enterprise.ai.domain.dto.DocumentImportResourceScope;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/** Orchestrates upload, parse preview, commit and retry without re-uploading. */
public interface DocumentImportJobService {

    DocumentImportJobResponse submit(MultipartFile file, String knowledgeBaseCode,
                                     String chunkStrategy, Integer chunkSize, Integer chunkOverlap,
                                     Map<String, Object> extraParams, boolean autoCommit,
                                     DocumentImportAccessContext accessContext);

    /** Compatibility ingress for callers that already own a stable file ID. */
    DocumentImportJobResponse submitWithFileId(MultipartFile file, String knowledgeBaseCode, String fileId,
                                               String chunkStrategy, Integer chunkSize, Integer chunkOverlap,
                                               Map<String, Object> extraParams, boolean autoCommit,
                                               DocumentImportAccessContext accessContext);

    DocumentImportJobResponse get(String jobId, boolean includePreview, DocumentImportAccessContext accessContext);

    DocumentImportJobResponse commit(String jobId, DocumentImportAccessContext accessContext);

    DocumentImportJobResponse retry(String jobId, DocumentImportAccessContext accessContext);

    DocumentImportJobResponse reparse(String fileId, DocumentImportAccessContext accessContext);

    void cancel(String jobId, DocumentImportAccessContext accessContext);

    DocumentImportResourceScope describeKnowledgeBaseScope(String knowledgeBaseCode);

    DocumentImportResourceScope describeJobScope(String jobId, DocumentImportAccessContext accessContext);

    DocumentImportResourceScope describeFileScope(String fileId);

    void dispatch(String jobId);

    void dispatchPending();
}
