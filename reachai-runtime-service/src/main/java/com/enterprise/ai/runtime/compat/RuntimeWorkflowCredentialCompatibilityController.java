package com.enterprise.ai.runtime.compat;

import com.enterprise.ai.runtime.credential.RuntimeWorkflowCredentialRequest;
import com.enterprise.ai.runtime.credential.RuntimeWorkflowCredentialService;
import com.enterprise.ai.runtime.credential.RuntimeWorkflowCredentialView;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class RuntimeWorkflowCredentialCompatibilityController {

    private final RuntimeWorkflowCredentialService credentialService;

    @GetMapping({"/api/agent/workflow-credentials", "/api/workflows/credentials"})
    public List<RuntimeWorkflowCredentialView> list(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String projectCode) {
        return credentialService.list(projectId, projectCode);
    }

    @PostMapping({"/api/agent/workflow-credentials", "/api/workflows/credentials"})
    public RuntimeWorkflowCredentialView create(@RequestBody RuntimeWorkflowCredentialRequest request) {
        return credentialService.create(request);
    }

    @PutMapping({"/api/agent/workflow-credentials/{id}", "/api/workflows/credentials/{id}"})
    public RuntimeWorkflowCredentialView update(
            @PathVariable Long id,
            @RequestBody RuntimeWorkflowCredentialRequest request) {
        return credentialService.update(id, request);
    }

    @DeleteMapping({"/api/agent/workflow-credentials/{id}", "/api/workflows/credentials/{id}"})
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        credentialService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
