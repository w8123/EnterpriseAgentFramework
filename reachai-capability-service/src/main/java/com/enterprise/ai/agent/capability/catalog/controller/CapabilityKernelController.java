package com.enterprise.ai.agent.capability.catalog.controller;

import com.enterprise.ai.agent.capability.CapabilityAssetService;
import com.enterprise.ai.agent.capability.CapabilityModuleEntity;
import com.enterprise.ai.agent.capability.CompositionDefinitionEntity;
import com.enterprise.ai.agent.capability.InteractionDefinitionEntity;
import com.enterprise.ai.agent.capability.ToolAssetEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/capabilities")
@RequiredArgsConstructor
public class CapabilityKernelController {

    private final CapabilityAssetService assetService;

    @GetMapping
    public List<CapabilityModuleEntity> listModules() {
        return assetService.listModules();
    }

    @PostMapping
    public ResponseEntity<?> saveModule(@RequestBody CapabilityModuleEntity request) {
        try {
            return ResponseEntity.ok(assetService.saveModule(request));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }

    @GetMapping("/{code}/tools")
    public List<ToolAssetEntity> listTools(@PathVariable String code) {
        return assetService.listTools(code);
    }

    @PostMapping("/{code}/tools")
    public ResponseEntity<?> saveTool(@PathVariable String code, @RequestBody ToolAssetEntity request) {
        try {
            return ResponseEntity.ok(assetService.saveTool(code, request));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }

    @GetMapping("/{code}/compositions")
    public List<CompositionDefinitionEntity> listCompositions(@PathVariable String code) {
        return assetService.listCompositions(code);
    }

    @PostMapping("/{code}/compositions")
    public ResponseEntity<?> saveComposition(@PathVariable String code, @RequestBody CompositionDefinitionEntity request) {
        try {
            return ResponseEntity.ok(assetService.saveComposition(code, request));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }

    @GetMapping("/{code}/interactions")
    public List<InteractionDefinitionEntity> listInteractions(@PathVariable String code) {
        return assetService.listInteractions(code);
    }

    @PostMapping("/{code}/interactions")
    public ResponseEntity<?> saveInteraction(@PathVariable String code, @RequestBody InteractionDefinitionEntity request) {
        try {
            return ResponseEntity.ok(assetService.saveInteraction(code, request));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }
}
