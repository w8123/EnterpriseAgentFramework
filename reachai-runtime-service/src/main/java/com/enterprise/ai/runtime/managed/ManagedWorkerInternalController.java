package com.enterprise.ai.runtime.managed;

import com.enterprise.ai.runtime.managed.ManagedExecutionViews.WorkerClaimView;
import com.enterprise.ai.runtime.managed.ManagedExecutionViews.WorkerCommandBatchView;
import com.enterprise.ai.runtime.managed.ManagedExecutionViews.WorkerCompleteRequest;
import com.enterprise.ai.runtime.managed.ManagedExecutionViews.WorkerEventBatchRequest;
import com.enterprise.ai.runtime.managed.ManagedExecutionViews.WorkerMutationView;
import com.enterprise.ai.runtime.managed.ManagedExecutionViews.ArtifactUploadView;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/runtime/managed-worker/executions")
@RequiredArgsConstructor
public class ManagedWorkerInternalController {

    public static final String WORKER_ID_HEADER = "X-ReachAI-Worker-Id";
    public static final String ARTIFACT_ID_HEADER = "X-ReachAI-Artifact-Id";
    public static final String ARTIFACT_SHA256_HEADER = "X-ReachAI-Artifact-Sha256";

    private final ManagedExecutionService executionService;
    private final ManagedArtifactUploadService artifactUploadService;

    @PostMapping("/{executionId}:claim")
    public ResponseEntity<WorkerClaimView> claim(
            @PathVariable String executionId,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestHeader(WORKER_ID_HEADER) String workerId) {
        return noStore(executionService.claim(executionId, bearer(authorization), workerId));
    }

    @PostMapping("/{executionId}:heartbeat")
    public ResponseEntity<WorkerMutationView> heartbeat(
            @PathVariable String executionId,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestHeader(WORKER_ID_HEADER) String workerId) {
        return noStore(executionService.heartbeat(executionId, bearer(authorization), workerId));
    }

    @PostMapping("/{executionId}/events:batch")
    public ResponseEntity<WorkerMutationView> events(
            @PathVariable String executionId,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestHeader(WORKER_ID_HEADER) String workerId,
            @RequestBody WorkerEventBatchRequest request) {
        return noStore(executionService.appendEvents(
                executionId, bearer(authorization), workerId, request));
    }

    @GetMapping("/{executionId}/commands")
    public ResponseEntity<WorkerCommandBatchView> commands(
            @PathVariable String executionId,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestHeader(WORKER_ID_HEADER) String workerId,
            @RequestParam(defaultValue = "0") long afterSequence) {
        if (afterSequence < 0) {
            throw new ManagedExecutionException(400, "MANAGED_EXECUTION_REQUEST_INVALID",
                    "afterSequence must not be negative");
        }
        return noStore(executionService.commands(
                executionId, bearer(authorization), workerId, afterSequence));
    }

    @PostMapping("/{executionId}:complete")
    public ResponseEntity<WorkerMutationView> complete(
            @PathVariable String executionId,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestHeader(WORKER_ID_HEADER) String workerId,
            @RequestBody WorkerCompleteRequest request) {
        return noStore(executionService.complete(
                executionId, bearer(authorization), workerId, request));
    }

    @PutMapping("/{executionId}/artifacts/{artifactType}")
    public ResponseEntity<ArtifactUploadView> uploadArtifact(
            @PathVariable String executionId,
            @PathVariable String artifactType,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @RequestHeader(WORKER_ID_HEADER) String workerId,
            @RequestHeader(ARTIFACT_ID_HEADER) String artifactId,
            @RequestHeader(ARTIFACT_SHA256_HEADER) String sha256,
            @RequestHeader(HttpHeaders.CONTENT_TYPE) String contentType,
            HttpServletRequest request) throws java.io.IOException {
        return noStore(artifactUploadService.upload(
                executionId,
                bearer(authorization),
                workerId,
                artifactType,
                artifactId,
                sha256,
                contentType,
                request.getContentLengthLong(),
                request.getInputStream()));
    }

    private String bearer(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ManagedExecutionException(401, "MANAGED_WORKER_AUTH_REQUIRED",
                    "Managed Executor worker authentication failed");
        }
        String token = authorization.substring("Bearer ".length()).trim();
        if (token.length() < 32 || token.length() > 256 || token.chars().anyMatch(Character::isWhitespace)) {
            throw new ManagedExecutionException(401, "MANAGED_WORKER_AUTH_REQUIRED",
                    "Managed Executor worker authentication failed");
        }
        return token;
    }

    private <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
