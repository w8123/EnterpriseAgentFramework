package com.enterprise.ai.capability.catalog.composition;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionEntity;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionParameter;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionUpsertRequest;
import com.enterprise.ai.capability.internal.CapabilityToolExecutionService;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping({"/api/compositions", "/api/skills"})
public class CapabilityCompositionCatalogController {

    private static final String PLACEHOLDER_SUB_AGENT_SPEC =
            "{\"systemPrompt\":\"\",\"toolWhitelist\":[],\"maxSteps\":8,\"useMultiAgentModel\":false}";
    private static final String PLACEHOLDER_INTERACTIVE_SPEC =
            "{\"targetTool\":\"\",\"fields\":[]}";

    private final CapabilityCompositionCatalogService compositionCatalogService;
    private final ObjectMapper objectMapper;
    private final CapabilityToolExecutionService toolExecutionService;
    private final CapabilityCompositionMetricsService metricsService;
    private final CapabilityCompositionInteractionService interactionService;
    private final CapabilityCompositionInteractionResumeService interactionResumeService;
    private final CapabilityRuntimeCompositionExecutionClient runtimeCompositionExecutionClient;

    @Autowired
    public CapabilityCompositionCatalogController(CapabilityCompositionCatalogService compositionCatalogService,
                                                  ObjectMapper objectMapper,
                                                  CapabilityToolExecutionService toolExecutionService,
                                                  CapabilityCompositionMetricsService metricsService,
                                                  CapabilityCompositionInteractionService interactionService,
                                                  CapabilityCompositionInteractionResumeService interactionResumeService,
                                                  CapabilityRuntimeCompositionExecutionClient runtimeCompositionExecutionClient) {
        this.compositionCatalogService = compositionCatalogService;
        this.objectMapper = objectMapper;
        this.toolExecutionService = toolExecutionService;
        this.metricsService = metricsService;
        this.interactionService = interactionService;
        this.interactionResumeService = interactionResumeService;
        this.runtimeCompositionExecutionClient = runtimeCompositionExecutionClient;
    }

    public CapabilityCompositionCatalogController(CapabilityCompositionCatalogService compositionCatalogService,
                                                  ObjectMapper objectMapper,
                                                  CapabilityToolExecutionService toolExecutionService,
                                                  CapabilityCompositionMetricsService metricsService,
                                                  CapabilityCompositionInteractionService interactionService,
                                                  CapabilityCompositionInteractionResumeService interactionResumeService) {
        this(compositionCatalogService, objectMapper, toolExecutionService, metricsService, interactionService,
                interactionResumeService, null);
    }

    public CapabilityCompositionCatalogController(CapabilityCompositionCatalogService compositionCatalogService,
                                                  ObjectMapper objectMapper,
                                                  CapabilityToolExecutionService toolExecutionService,
                                                  CapabilityCompositionMetricsService metricsService,
                                                  CapabilityCompositionInteractionService interactionService) {
        this(compositionCatalogService, objectMapper, toolExecutionService, metricsService, interactionService, null);
    }

    public CapabilityCompositionCatalogController(CapabilityCompositionCatalogService compositionCatalogService,
                                                  ObjectMapper objectMapper,
                                                  CapabilityToolExecutionService toolExecutionService) {
        this(compositionCatalogService, objectMapper, toolExecutionService, null, null);
    }

    public CapabilityCompositionCatalogController(CapabilityCompositionCatalogService compositionCatalogService,
                                                  ObjectMapper objectMapper) {
        this(compositionCatalogService, objectMapper, null, null, null);
    }

    public CapabilityCompositionCatalogController(CapabilityCompositionCatalogService compositionCatalogService) {
        this(compositionCatalogService, new ObjectMapper(), null);
    }

    @GetMapping
    public ResponseEntity<CompositionListPageResponse> list(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) Boolean draft,
            @RequestParam(required = false) Long projectId) {
        IPage<ToolDefinitionEntity> page =
                compositionCatalogService.page(current, size, keyword, enabled, draft, projectId);
        List<CompositionInfoDTO> records = page.getRecords().stream()
                .map(this::toDto)
                .toList();
        return ResponseEntity.ok(new CompositionListPageResponse(
                records,
                page.getTotal(),
                page.getSize(),
                page.getCurrent(),
                page.getPages()
        ));
    }

    @GetMapping("/{name}")
    public ResponseEntity<CompositionInfoDTO> get(@PathVariable String name) {
        return compositionCatalogService.findSkillByName(name)
                .map(entity -> ResponseEntity.ok(toDto(entity)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CompositionUpsertRequest request) {
        try {
            return ResponseEntity.ok(toDto(compositionCatalogService.create(request.toServiceRequest(objectMapper))));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }

    @PutMapping("/{name}")
    public ResponseEntity<?> update(@PathVariable String name,
                                    @RequestBody CompositionUpsertRequest request) {
        try {
            return ResponseEntity.ok(toDto(compositionCatalogService.update(name, request.toServiceRequest(objectMapper))));
        } catch (IllegalArgumentException ex) {
            if (isNotFound(ex)) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Void> delete(@PathVariable String name) {
        try {
            return compositionCatalogService.delete(name)
                    ? ResponseEntity.noContent().build()
                    : ResponseEntity.notFound().build();
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    @PutMapping("/{name}/toggle")
    public ResponseEntity<?> toggle(@PathVariable String name,
                                    @RequestBody CompositionToggleRequest request) {
        try {
            return ResponseEntity.ok(toDto(compositionCatalogService.toggle(name, request.enabled())));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }

    @PostMapping("/{name}/test")
    public ResponseEntity<CompositionTestResult> test(@PathVariable String name,
                                                      @RequestBody(required = false) CompositionTestRequest request) {
        long start = System.nanoTime();
        try {
            ToolDefinitionEntity composition = compositionCatalogService.findSkillByName(name)
                    .orElseThrow(() -> new IllegalArgumentException("composition does not exist: " + name));
            if (!Boolean.TRUE.equals(composition.getEnabled())) {
                throw new IllegalStateException("composition is disabled: " + name);
            }
            if (Boolean.TRUE.equals(composition.getDraft())) {
                throw new IllegalStateException("draft composition cannot be tested: " + name);
            }
            if (!CapabilityCompositionCatalogService.SKILL_KIND_INTERACTIVE_FORM.equalsIgnoreCase(composition.getSkillKind())) {
                return ResponseEntity.ok(testSubAgentComposition(composition, request, start));
            }
            if (toolExecutionService == null) {
                throw new IllegalStateException("Tool execution service is unavailable");
            }
            JsonNode spec = objectMapper.readTree(composition.getSpecJson());
            JsonNode targetToolNode = spec == null ? null : spec.get("targetTool");
            String targetTool = targetToolNode == null ? null : targetToolNode.asText(null);
            if (targetTool == null || targetTool.isBlank()) {
                throw new IllegalStateException("InteractiveForm composition targetTool is missing: " + name);
            }
            Map<String, Object> args = request == null || request.args() == null ? Map.of() : request.args();
            Map<String, Object> response = toolExecutionService.execute(targetTool, Map.of("input", args));
            return ResponseEntity.ok(new CompositionTestResult(
                    true,
                    String.valueOf(response == null ? null : response.get("data")),
                    null,
                    elapsedMs(start),
                    false,
                    null,
                    null
            ));
        } catch (Exception ex) {
            return ResponseEntity.ok(new CompositionTestResult(
                    false,
                    "",
                    ex.getMessage(),
                    elapsedMs(start),
                    false,
                    null,
                    null
            ));
        }
    }

    private CompositionTestResult testSubAgentComposition(ToolDefinitionEntity composition,
                                                          CompositionTestRequest request,
                                                          long start) {
        if (runtimeCompositionExecutionClient == null) {
            throw new IllegalStateException("Runtime composition execution service is unavailable");
        }
        Map<String, Object> args = request == null || request.args() == null ? Map.of() : request.args();
        Map<String, Object> runtimeRequest = new LinkedHashMap<>();
        runtimeRequest.put("input", args);
        runtimeRequest.put("params", args);
        Map<String, Object> response = runtimeCompositionExecutionClient.executeComposition(
                runtimeQualifiedName(composition), runtimeRequest);
        boolean success = isSuccess(response == null ? null : response.get("success"));
        String answer = text(firstNonNull(response, "answer", "result", "data"));
        return new CompositionTestResult(
                success,
                success ? answer : "",
                success ? null : firstError(response, answer),
                elapsedMs(start),
                isSuccess(response == null ? null : response.get("interactionPending")),
                text(response == null ? null : response.get("interactionId")),
                mapValue(response == null ? null : response.get("uiRequest"))
        );
    }

    private String runtimeQualifiedName(ToolDefinitionEntity composition) {
        if (composition.getQualifiedName() != null && !composition.getQualifiedName().isBlank()) {
            return composition.getQualifiedName();
        }
        return composition.getName();
    }

    private Object firstNonNull(Map<String, Object> source, String... keys) {
        if (source == null) {
            return null;
        }
        for (String key : keys) {
            Object value = source.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private String firstError(Map<String, Object> response, String answer) {
        if (answer != null && !answer.isBlank()) {
            return answer;
        }
        String code = text(response == null ? null : response.get("code"));
        return code == null || code.isBlank() ? "Runtime composition execution failed" : code;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private boolean isSuccess(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(text(value));
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @PostMapping("/{name}/test/resume")
    public ResponseEntity<CompositionTestResult> testResume(@PathVariable String name,
                                                            @RequestBody CompositionTestResumeRequest request) {
        if (compositionCatalogService.findSkillByName(name).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (request == null || request.interactionId() == null || request.interactionId().isBlank()) {
            return ResponseEntity.ok(new CompositionTestResult(
                    false,
                    "",
                    "interactionId 不能为空",
                    0,
                    false,
                    null,
                    null
            ));
        }
        if (interactionResumeService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new CompositionTestResult(
                    false,
                    "",
                    "Composition interaction resume service is unavailable",
                    0,
                    false,
                    request.interactionId(),
                    null
            ));
        }
        long start = System.nanoTime();
        CapabilityCompositionInteractionResumeService.ResumeResult result =
                interactionResumeService.resumeAdminTest(name, request.interactionId(), request.action(), request.values());
        return ResponseEntity.ok(new CompositionTestResult(
                result.success(),
                result.result(),
                result.errorMessage(),
                elapsedMs(start),
                result.interactionPending(),
                result.interactionId(),
                result.uiRequest()
        ));
    }

    @GetMapping("/{name}/metrics")
    public ResponseEntity<?> metrics(@PathVariable String name,
                                     @RequestParam(defaultValue = "7") int days) {
        if (compositionCatalogService.findSkillByName(name).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (metricsService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("message", "Composition metrics service is unavailable"));
        }
        return ResponseEntity.ok(metricsService.metrics(name, days));
    }

    @GetMapping("/pending-interactions/admin-test")
    public ResponseEntity<List<CapabilityCompositionInteractionService.PendingAdminTestInteractionDTO>> listPendingForAdminTest() {
        if (interactionService == null) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(interactionService.listPendingForAdminTest());
    }

    @DeleteMapping("/pending-interactions/admin-test/{interactionId}")
    public ResponseEntity<?> cancelPendingForAdminTest(@PathVariable String interactionId) {
        if (interactionService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("message", "Composition interaction service is unavailable"));
        }
        CapabilityCompositionInteractionService.CancelResult result =
                interactionService.cancelPendingForAdminTest(interactionId);
        return switch (result.status()) {
            case CANCELLED -> ResponseEntity.noContent().build();
            case NOT_FOUND -> ResponseEntity.notFound().build();
            case FORBIDDEN -> ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", result.message()));
            case NOT_PENDING -> ResponseEntity.badRequest().body(Map.of("message", result.message()));
        };
    }

    @PostMapping("/pending-interactions/admin-test/cancel-all")
    public ResponseEntity<Map<String, Integer>> cancelAllPendingForAdminTest() {
        if (interactionService == null) {
            return ResponseEntity.ok(Map.of("cancelled", 0));
        }
        return ResponseEntity.ok(Map.of("cancelled", interactionService.cancelAllPendingForAdminTest()));
    }

    private boolean isNotFound(IllegalArgumentException ex) {
        return ex.getMessage() != null && ex.getMessage().contains("does not exist");
    }

    private CompositionInfoDTO toDto(ToolDefinitionEntity entity) {
        List<CompositionParameterDTO> params = compositionCatalogService.parseParameters(entity.getParametersJson()).stream()
                .map(CompositionParameterDTO::from)
                .toList();
        return new CompositionInfoDTO(
                entity.getName(),
                entity.getDescription(),
                entity.getAiDescription(),
                params,
                entity.getSkillKind(),
                entity.getSideEffect(),
                entity.getProjectId(),
                entity.getProjectCode(),
                entity.getVisibility(),
                entity.getQualifiedName(),
                Boolean.TRUE.equals(entity.getEnabled()),
                Boolean.TRUE.equals(entity.getAgentVisible()),
                entity.getSource(),
                compositionCatalogService.parseSpecForDto(entity),
                Boolean.TRUE.equals(entity.getDraft())
        );
    }

    record CompositionListPageResponse(List<CompositionInfoDTO> records,
                                       long total,
                                       long size,
                                       long current,
                                       long pages) {
    }

    record CompositionInfoDTO(String name,
                              String description,
                              String aiDescription,
                              List<CompositionParameterDTO> parameters,
                              String skillKind,
                              String sideEffect,
                              Long projectId,
                              String projectCode,
                              String visibility,
                              String qualifiedName,
                              boolean enabled,
                              boolean agentVisible,
                              String source,
                              Object spec,
                              boolean draft) {
    }

    record CompositionParameterDTO(String name,
                                   String type,
                                   String description,
                                   boolean required,
                                   String location,
                                   @JsonInclude(JsonInclude.Include.NON_EMPTY)
                                   List<CompositionParameterDTO> children) {
        static CompositionParameterDTO from(ToolDefinitionParameter parameter) {
            List<ToolDefinitionParameter> rawChildren = parameter.children();
            List<CompositionParameterDTO> mappedChildren = rawChildren == null || rawChildren.isEmpty()
                    ? List.of()
                    : rawChildren.stream().map(CompositionParameterDTO::from).toList();
            return new CompositionParameterDTO(
                    parameter.name(),
                    parameter.type(),
                    parameter.description(),
                    parameter.required(),
                    parameter.location(),
                    mappedChildren
            );
        }
    }

    record CompositionUpsertRequest(String name,
                                    String description,
                                    List<ToolDefinitionParameter> parameters,
                                    String skillKind,
                                    String sideEffect,
                                    Long projectId,
                                    String projectCode,
                                    String visibility,
                                    String qualifiedName,
                                    boolean enabled,
                                    boolean agentVisible,
                                    JsonNode spec,
                                    Boolean draft) {
        ToolDefinitionUpsertRequest toServiceRequest(ObjectMapper objectMapper) {
            try {
                boolean isDraft = Boolean.TRUE.equals(draft);
                String resolvedKind = skillKind == null || skillKind.isBlank()
                        ? CapabilityCompositionCatalogService.SKILL_KIND_SUB_AGENT
                        : skillKind.trim().toUpperCase();
                String specJson = spec == null || spec.isNull() ? null : objectMapper.writeValueAsString(spec);
                if (isDraft && (specJson == null || specJson.isBlank())) {
                    specJson = CapabilityCompositionCatalogService.SKILL_KIND_INTERACTIVE_FORM.equals(resolvedKind)
                            ? PLACEHOLDER_INTERACTIVE_SPEC
                            : PLACEHOLDER_SUB_AGENT_SPEC;
                }
                return ToolDefinitionUpsertRequest.skill(
                        name,
                        description,
                        parameters == null ? List.of() : parameters,
                        "manual",
                        null,
                        enabled,
                        agentVisible,
                        sideEffect,
                        resolvedKind,
                        specJson,
                        isDraft
                ).withProjectScope(projectId, projectCode, visibility, qualifiedName);
            } catch (JsonProcessingException ex) {
                throw new IllegalArgumentException("invalid composition spec JSON: " + ex.getMessage(), ex);
            }
        }
    }

    record CompositionToggleRequest(boolean enabled) {
    }

    record CompositionTestRequest(Map<String, Object> args) {
    }

    record CompositionTestResumeRequest(String interactionId,
                                        String action,
                                        Map<String, Object> values) {
    }

    record CompositionTestResult(boolean success,
                                 String result,
                                 String errorMessage,
                                 long durationMs,
                                 boolean interactionPending,
                                 String interactionId,
                                 Map<String, Object> uiRequest) {
    }

    private long elapsedMs(long start) {
        return Math.max(0, (System.nanoTime() - start) / 1_000_000);
    }
}
