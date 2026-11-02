package com.enterprise.ai.control.aiassist;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.control.client.runtime.RuntimeProxyClient;
import com.enterprise.ai.control.internalauth.InternalServiceAuthSigner;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ControlAiCodingWorkflowAttachmentGateway {
    private final RuntimeProxyClient runtime;
    private final InternalServiceAuthSigner signer;
    private final ObjectMapper json;

    public ResponseEntity<Object> attach(Long id, Map<String, Object> project, Map<String, Object> request) {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)
                || !(attributes.getRequest().getAttribute(VerifiedAiCodingProject.REQUEST_ATTRIBUTE) instanceof VerifiedAiCodingProject actor)
                || !actor.projectId().equals(id)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "verified AI Coding project is required");
        var view = json.valueToTree(project);
        if (!view.path("id").isIntegralNumber() || !view.path("id").canConvertToLong() || view.path("id").asLong() != id
                || !view.path("projectCode").isTextual() || view.path("projectCode").asText().isBlank())
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "project lookup returned a different or incomplete project");
        byte[] body;
        try { body = json.writeValueAsBytes(request); }
        catch (Exception malformed) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid attachment command"); }
        if (body.length > 512 * 1024) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
        String path = "/internal/runtime/projects/" + id + "/agent-supervisor/workflow-tools/attach";
        return runtime.attachProjectWorkflowToolSigned(id, signer.sign("POST", path,
                InternalServiceAuthHeaders.IDENTITY_SOURCE_PROJECT_CREDENTIAL, view.path("projectCode").asText(), actor.actorId(), body), body);
    }
}
