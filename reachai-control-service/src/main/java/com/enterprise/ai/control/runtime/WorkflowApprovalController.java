package com.enterprise.ai.control.runtime;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformPermissionGrant;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.enterprise.ai.control.identity.PlatformPrincipal;
import com.enterprise.ai.control.identity.PlatformRequestAuthorization;
import com.enterprise.ai.control.internalauth.InternalServiceAuthSigner;
import com.enterprise.ai.control.memory.PersonalMemoryIdentityResolver;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「我的待办」for Workflow 人工确认 nodes with named approvers. Every decision is made by a person in
 * the console; it is deliberately not exposed to platform MCP / AI tools.
 */
@RestController
@RequestMapping("/api/me/workflow-approvals")
public class WorkflowApprovalController {

    private final PlatformRequestAuthorization authorization;
    private final InternalServiceAuthSigner signer;
    private final ObjectMapper mapper;
    private final RuntimeProxyClient runtimeProxyClient;
    private final String runtimeUrl;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private PersonalMemoryIdentityResolver identityResolver;

    public WorkflowApprovalController(PlatformRequestAuthorization authorization,
                                      InternalServiceAuthSigner signer,
                                      ObjectMapper mapper,
                                      RuntimeProxyClient runtimeProxyClient,
                                      @Value("${services.runtime-service.url:http://localhost:18604}") String runtimeUrl) {
        this.authorization = authorization;
        this.signer = signer;
        this.mapper = mapper;
        this.runtimeProxyClient = runtimeProxyClient;
        this.runtimeUrl = runtimeUrl.replaceAll("/+$", "");
    }

    @Autowired(required = false)
    void setIdentityResolver(PersonalMemoryIdentityResolver identityResolver) {
        this.identityResolver = identityResolver;
    }

    public record DecisionBody(String decision, String comment) { }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(HttpServletRequest request,
                                                    @RequestParam(defaultValue = "pending") String scope,
                                                    @RequestParam(defaultValue = "50") int limit) {
        PlatformAuthenticatedSession session = authorization.requireAuthenticated(request);
        String normalized = switch (scope == null ? "" : scope.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "decided" -> "decided";
            case "all" -> "all";
            default -> "pending";
        };
        if ("all".equals(normalized) && !isAdmin(session)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有平台管理员可以查看全部审批");
        }
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("scope", normalized);
        command.put("limit", Math.max(1, Math.min(limit, 200)));
        command.put("approver", approver(session));
        return noStore(post("/internal/runtime/workflow-approvals/query", command, session));
    }

    @GetMapping("/count")
    public ResponseEntity<Map<String, Object>> count(HttpServletRequest request) {
        PlatformAuthenticatedSession session = authorization.requireAuthenticated(request);
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("scope", "count");
        command.put("approver", approver(session));
        return noStore(post("/internal/runtime/workflow-approvals/query", command, session));
    }

    @PostMapping("/{interactionId}/decision")
    public ResponseEntity<Map<String, Object>> decide(HttpServletRequest request,
                                                      @PathVariable String interactionId,
                                                      @RequestBody DecisionBody body) {
        PlatformAuthenticatedSession session = authorization.requireAuthenticated(request);
        if (interactionId == null || !interactionId.matches("[A-Za-z0-9_:-]{1,96}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "审批编号无效");
        }
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("decision", body == null ? null : body.decision());
        command.put("comment", body == null ? null : body.comment());
        command.put("approver", approver(session));
        return noStore(post("/internal/runtime/workflow-approvals/" + interactionId + "/decision", command, session));
    }

    /** Status of a waiting card in the console's own Agent conversation. */
    @GetMapping("/runner-status/{interactionId}")
    public ResponseEntity<Map<String, Object>> runnerStatus(HttpServletRequest request,
                                                            @PathVariable String interactionId,
                                                            @RequestParam String sessionId) {
        PlatformAuthenticatedSession session = authorization.requireAuthenticated(request);
        try {
            return noStore(runtimeProxyClient.workflowApprovalRunnerStatus(interactionId, sessionId,
                    runtimeUserId(session.user()), null));
        } catch (feign.FeignException.NotFound notFound) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "审批不存在");
        }
    }

    private Map<String, Object> approver(PlatformAuthenticatedSession session) {
        PlatformPrincipal user = session.user();
        Map<String, Object> approver = new LinkedHashMap<>();
        approver.put("username", user.getUsername());
        approver.put("displayName", StringUtils.hasText(user.getDisplayName()) ? user.getDisplayName() : user.getUsername());
        approver.put("roles", session.roles());
        approver.put("projectScope", projectScope(session));
        approver.put("admin", isAdmin(session));
        return approver;
    }

    /** "*" for any global grant, otherwise the projects (and workspaces) the user's role grants cover. */
    static List<String> projectScope(PlatformAuthenticatedSession session) {
        Set<String> scope = new LinkedHashSet<>();
        for (PlatformPermissionGrant grant : session.permissionGrants()) {
            if ("GLOBAL".equalsIgnoreCase(grant.scopeType())) return List.of("*");
            if ("PROJECT".equalsIgnoreCase(grant.scopeType()) && StringUtils.hasText(grant.scopeValue())) {
                scope.add(grant.scopeValue().trim());
            }
        }
        return List.copyOf(scope);
    }

    private static boolean isAdmin(PlatformAuthenticatedSession session) {
        return session.hasGlobalPermission(PlatformPermissions.PLATFORM_ADMIN);
    }

    private String runtimeUserId(PlatformPrincipal user) {
        if (identityResolver == null) return String.valueOf(user.getId());
        return identityResolver.resolveAttestedPlatformPrincipal(user, null, "default").runtimeUserId();
    }

    private ResponseEntity<Map<String, Object>> post(String path, Map<String, Object> command,
                                                     PlatformAuthenticatedSession session) {
        try {
            byte[] bytes = mapper.writeValueAsBytes(command);
            Map<String, String> headers = signer.sign("POST", path,
                    InternalServiceAuthHeaders.IDENTITY_SOURCE_PLATFORM_SESSION, "default",
                    runtimeUserId(session.user()), bytes);
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(runtimeUrl + path))
                    .timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
                    .header("Accept", "application/json");
            headers.forEach(builder::header);
            HttpResponse<byte[]> response = client.send(
                    builder.POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            byte[] result = response.body();
            if (result != null && result.length > 4 * 1024 * 1024) throw new IOException("approval response too large");
            Map<String, Object> body = result == null || result.length == 0 ? Map.of()
                    : mapper.readValue(result, new TypeReference<Map<String, Object>>() { });
            return ResponseEntity.status(response.statusCode()).body(body);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "审批服务暂时不可用");
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "审批服务暂时不可用");
        }
    }

    private static ResponseEntity<Map<String, Object>> noStore(ResponseEntity<Map<String, Object>> response) {
        return ResponseEntity.status(response.getStatusCode()).cacheControl(CacheControl.noStore()).body(response.getBody());
    }
}
