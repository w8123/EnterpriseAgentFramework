package com.enterprise.ai.capability.registry;

import com.enterprise.ai.agent.registry.RegistryContracts.ProjectRegisterRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/registry/projects")
@RequiredArgsConstructor
public class CapabilityRegistryCompatibilityController {

    private final CapabilityRegistryService registryService;

    @PostMapping("/register")
    public ResponseEntity<?> registerProject(@RequestBody(required = false) ProjectRegisterRequest request) {
        try {
            return ResponseEntity.ok(registryService.registerProject(request));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(new ApiErrorResponse(ex.getMessage()));
        }
    }

    @GetMapping("/{projectCode}/instances")
    public ResponseEntity<?> listInstances(@PathVariable String projectCode) {
        try {
            return ResponseEntity.ok(registryService.listInstances(projectCode));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    record ApiErrorResponse(String message) {
    }
}
