package com.enterprise.ai.control.aiassist;

import com.enterprise.ai.control.identity.PlatformConsoleAuthInterceptor;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.enterprise.ai.control.identity.PlatformRequestAuthorization;
import com.enterprise.ai.control.runtime.RuntimeAgentDraftGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

/** The provisioning operation retains its original permission and chooses one attested identity domain. */
@Service
@RequiredArgsConstructor
public class ControlProjectAgentProvisioningDraftGateway {
    private final ControlAiCodingAgentDraftGateway projectDrafts;
    private final RuntimeAgentDraftGateway platformDrafts;
    private final PlatformRequestAuthorization authorization;
    private final ObjectMapper json;

    public Drafts open(Long projectId, String code) {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "verified provisioning identity is required");
        var request = attributes.getRequest();
        Object projectActor = request.getAttribute(VerifiedAiCodingProject.REQUEST_ATTRIBUTE);
        if (projectActor != null) {
            if (!(projectActor instanceof VerifiedAiCodingProject actor) || !actor.projectId().equals(projectId)
                    || request.getAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE) != null)
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "provisioning identity does not match the project");
            return new Drafts() {
                public Map<String, Object> context(String id) { return projectDrafts.context(projectId, code, id); }
                public Map<String, Object> versions(String id) { return projectDrafts.versions(projectId, code, id); }
                public Map<String, Object> save(String id, String revision, Map<String, Object> changes) {
                    return projectDrafts.save(projectId, code, id, revision, changes);
                }
                public Map<String, Object> publish(String id, String revision, Long version) {
                    return projectDrafts.publish(projectId, code, id, revision, version);
                }
            };
        }
        var actor = authorization.requireResourcePermission(request, PlatformPermissions.PLATFORM_WRITE, "PROJECT", null, code);
        return new Drafts() {
            public Map<String, Object> versions(String id) {
                return ControlAiCodingAgentDraftGateway.checkedResponse(json, "versions", projectId, code, id, null,
                        platformDrafts.versions(id, projectId, code, actor));
            }
            public Map<String, Object> context(String id) {
                return ControlAiCodingAgentDraftGateway.checkedResponse(json, "context", projectId, code, id, null,
                        platformDrafts.context(id, projectId, code, actor));
            }
            public Map<String, Object> save(String id, String revision, Map<String, Object> changes) {
                return ControlAiCodingAgentDraftGateway.checkedResponse(json, "save", projectId, code, id, null,
                        platformDrafts.save(id, projectId, code, revision, changes, actor));
            }
            public Map<String, Object> publish(String id, String revision, Long version) {
                return ControlAiCodingAgentDraftGateway.checkedResponse(json, "publish", projectId, code, id, version,
                        platformDrafts.publish(id, projectId, code, revision, version, actor));
            }
        };
    }
    public interface Drafts {
        Map<String, Object> context(String agentId);
        Map<String, Object> versions(String agentId);
        Map<String, Object> save(String agentId, String revision, Map<String, Object> changes);
        Map<String, Object> publish(String agentId, String revision, Long configVersionId);
    }
}
