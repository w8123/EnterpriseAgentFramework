package com.enterprise.ai.control.mcp.application.platform;

import com.enterprise.ai.control.identity.CapabilityRegistryEnrollmentGateway;
import com.enterprise.ai.control.identity.PlatformAiApprovalService;
import com.enterprise.ai.control.identity.PlatformAiConnectionService.ConnectionIdentity;
import com.enterprise.ai.control.identity.PlatformAuthorizationService;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.enterprise.ai.control.mcp.application.port.McpPlatformOnboardingResourcesPort;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** A one-use enrollment token can be issued only after its project scope is reviewed. */
@Service
@RequiredArgsConstructor
public class McpPlatformOnboardingEnrollmentService {
    private final PlatformAiApprovalService approvals;
    private final PlatformAuthorizationService authorization;
    private final CapabilityRegistryEnrollmentGateway enrollments;
    private final McpPlatformOnboardingResourcesPort artifacts;

    public ResponseEntity<?> resources() {
        String baseUrl = McpPlatformIntegrationService.publicBaseUrl();
        if (baseUrl == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "public ReachAI address is unavailable");
        return ResponseEntity.ok(artifacts.manifest(baseUrl));
    }

    public ResponseEntity<?> request(ConnectionIdentity actor, String projectCode, String invocationId) {
        String code = validProjectCode(projectCode);
        if (invocationId == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invocationId is required");
        requireProjectWrite(actor, code);
        return ResponseEntity.ok(approvals.prepare(actor, PlatformAiApprovalService.REGISTRY_ENROLLMENT,
                subject(code), Map.of("contractVersion", 1, "projectCode", code, "ttlHours", 72, "invocationId", invocationId)));
    }

    public ResponseEntity<?> execute(ConnectionIdentity actor, String approvalId) {
        var claim = approvals.claim(actor, approvalId, PlatformAiApprovalService.REGISTRY_ENROLLMENT);
        String code;
        try {
            if (!Integer.valueOf(1).equals(claim.arguments().get("contractVersion"))
                    || !Integer.valueOf(72).equals(claim.arguments().get("ttlHours"))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "approved enrollment contract changed");
            }
            code = validProjectCode((String) claim.arguments().get("projectCode"));
            if (!subject(code).equals(claim.subject())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "approved project changed");
            }
            requireProjectWrite(actor, code);
        } catch (RuntimeException denied) {
            approvals.finish(actor, approvalId, "FAILED", "PLATFORM_APPROVAL_PRE_DISPATCH_FAILED");
            throw denied;
        }

        CapabilityRegistryEnrollmentGateway.IssuedEnrollment issued;
        try {
            issued = enrollments.issue(actor.session(), code);
        } catch (RuntimeException failure) {
            approvals.finish(actor, approvalId, "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");
            return ResponseEntity.accepted().body(Map.of("status", "UNKNOWN", "approvalId", approvalId,
                    "projectCode", code, "message", "接入令牌签发结果未知，请核对原审批，不要自动重试。"));
        }
        if (!code.equals(issued.projectCode())) {
            approvals.finish(actor, approvalId, "UNKNOWN", "PLATFORM_APPROVAL_EXECUTION_UNKNOWN");
            return ResponseEntity.accepted().body(Map.of("status", "UNKNOWN", "approvalId", approvalId,
                    "projectCode", code, "message", "接入令牌签发结果未知，请核对原审批，不要自动重试。"));
        }
        approvals.finish(actor, approvalId, "SUCCEEDED", null);
        return ResponseEntity.ok(Map.of("projectCode", code, "enrollmentToken", issued.enrollmentToken(),
                "expiresAt", issued.expiresAt(), "ttlHours", issued.ttlHours(),
                "message", "令牌仅显示一次。通过受保护的环境变量或密钥管理器提供给业务服务，勿写入代码、日志或报告。"));
    }

    private void requireProjectWrite(ConnectionIdentity actor, String code) {
        authorization.requireResourcePermission(actor.session(), PlatformPermissions.PLATFORM_WRITE, "PROJECT", null, code);
    }

    private static String validProjectCode(String code) {
        if (code == null || code.contains("--") || !code.matches("[a-z0-9_](?:[a-z0-9_-]{0,62}[a-z0-9_])?")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "projectCode must be 1–64 lowercase letters, numbers, underscores or hyphens");
        }
        return code;
    }

    private static String subject(String code) {
        return "REGISTRY_ENROLLMENT:" + code;
    }
}
