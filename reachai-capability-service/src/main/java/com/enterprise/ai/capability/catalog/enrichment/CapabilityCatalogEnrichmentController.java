package com.enterprise.ai.capability.catalog.enrichment;

import com.enterprise.ai.agent.capability.catalog.scan.ScanModuleEntity;
import com.enterprise.ai.agent.capability.catalog.semantic.SemanticDocEntity;
import com.enterprise.ai.capability.catalog.semantic.CapabilitySemanticCatalogService;
import com.enterprise.ai.capability.catalog.semantic.CapabilitySemanticGenerationTask;
import com.enterprise.ai.capability.catalog.graph.CapabilityApiGraphOperationsService;
import com.enterprise.ai.capability.catalog.graph.CapabilityApiGraphParamSourceHintView;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Signed Control facade for owner-attached catalog resources. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/capability/business-capabilities/projects/{projectId}")
public class CapabilityCatalogEnrichmentController {
    private final CatalogSubjectResolver subjects;
    private final CapabilityCatalogGroupingService grouping;
    private final CapabilitySemanticCatalogService semantic;
    private final CapabilityApiGraphOperationsService graph;
    private final ObjectMapper json;
    private final CatalogClassificationService classification;
    private final CatalogClassificationEditorService editor;

    @GetMapping("/classification/{subjectType}")
    public CatalogClassificationService.ClassificationIndex classification(@PathVariable Long projectId, @PathVariable String subjectType) {
        return classification.index(projectId, subjectType);
    }

    @GetMapping("/subjects/{subjectType}/{subjectId}/enrichment")
    public Enrichment enrichment(@PathVariable Long projectId, @PathVariable String subjectType, @PathVariable Long subjectId) {
        CatalogSubject subject = subjects.resolve(projectId, subjectType, subjectId);
        SemanticDocEntity doc = semantic.find(subject).orElse(null);
        List<Group> associatedGroups = CatalogSubject.GROUP.equals(subject.subjectType()) ? List.of()
                : grouping.groups(subject).stream().map(this::group).toList();
        var paramHints = graph.paramHints(subject);
        return new Enrichment(projectId, subject.subjectType(), subject.subjectId(), subject.subjectKey(), subject.title(),
                subject.contractHash(), subject.accepted(), doc == null ? null : doc(doc, subject),
                doc != null && (!Objects.equals(doc.getBasisContractHash(), subject.contractHash()) || !Objects.equals(doc.getBasisAccepted(), subject.accepted())),
                associatedGroups, subject.observations().stream().map(row ->
                    new SourceObservation(row.getId(), row.getTitle(), row.getSourceLocation(), Boolean.TRUE.equals(row.getRemovedFromSource()), readRisk(row.getSensitiveDataJson()))).toList(), subject.operation(), paramHints.state(), paramHints.hints());
    }

    @GetMapping("/semantic-docs")
    public List<SemanticDocIndex> docs(@PathVariable Long projectId) {
        List<SemanticDocEntity> docs = semantic.listProjectDocs(projectId);
        if (docs.isEmpty()) return List.of();
        var references = subjects.documentReferences(projectId, docs.stream()
                .map(row -> new CatalogSubjectResolver.DocumentReference(row.getSubjectType(), row.getSubjectId())).toList());
        return docs.stream().map(row -> index(row, references.get(new CatalogSubjectResolver.DocumentReference(row.getSubjectType(), row.getSubjectId())))).toList();
    }

    @GetMapping("/subjects/{subjectType}/{subjectId}/semantic")
    public SemanticDoc semanticDoc(@PathVariable Long projectId, @PathVariable String subjectType, @PathVariable Long subjectId) {
        CatalogSubject subject = subjects.resolveDocumentReference(projectId, subjectType, subjectId);
        return semantic.find(subject).map(row -> doc(row, subject)).orElse(null);
    }

    @PostMapping("/subjects/{subjectType}/{subjectId}/semantic/generate")
    public SemanticDoc generate(@PathVariable Long projectId, @PathVariable String subjectType, @PathVariable Long subjectId,
                                @RequestParam(defaultValue = "true") boolean force, @RequestParam String modelInstanceId) {
        SemanticDocEntity doc = semantic.generate(projectId, subjectType, subjectId, force, modelInstanceId);
        return doc(doc, subjects.resolve(projectId, subjectType, subjectId));
    }

    @PutMapping("/subjects/{subjectType}/{subjectId}/semantic")
    public SemanticDoc edit(@PathVariable Long projectId, @PathVariable String subjectType, @PathVariable Long subjectId,
                           @RequestBody EditRequest body) {
        SemanticDocEntity doc = semantic.edit(projectId, subjectType, subjectId, body.contentMd(), body.expectedUpdatedAt());
        return doc(doc, subjects.resolve(projectId, subjectType, subjectId));
    }

    @PostMapping("/semantic/generate")
    public ResponseEntity<Map<String, String>> batch(@PathVariable Long projectId, @RequestParam(defaultValue = "false") boolean force,
                                                     @RequestParam String modelInstanceId) {
        return ResponseEntity.accepted().body(Map.of("taskId", semantic.startProjectBatch(projectId, force, modelInstanceId)));
    }

    @GetMapping("/semantic/status")
    public CapabilitySemanticGenerationTask status(@PathVariable Long projectId, @RequestParam(required = false) String taskId) {
        return semantic.task(projectId, taskId).orElse(null);
    }

    @GetMapping("/groups")
    public List<Group> groups(@PathVariable Long projectId) { subjects.requireProject(projectId); return grouping.list(projectId).stream().map(this::group).toList(); }

    @PostMapping("/groups")
    public Group createGroup(@PathVariable Long projectId, @RequestBody CreateGroupRequest body) {
        subjects.requireProject(projectId); return group(grouping.create(projectId, body.displayName()));
    }

    @PutMapping("/groups/{groupId}")
    public Group rename(@PathVariable Long projectId, @PathVariable Long groupId, @RequestBody RenameRequest body) {
        subjects.requireProject(projectId); return group(grouping.rename(projectId, groupId, body.displayName()));
    }

    @GetMapping("/subjects/{subjectType}/{subjectId}/classification")
    public CatalogClassificationEditorService.Assignment assignment(@PathVariable Long projectId, @PathVariable String subjectType, @PathVariable Long subjectId) {
        return editor.read(projectId, subjectType, subjectId);
    }

    @GetMapping("/classification/{subjectType}/assignments")
    public List<CatalogClassificationEditorService.Assignment> assignments(@PathVariable Long projectId, @PathVariable String subjectType,
            @RequestParam List<Long> subjectIds) {
        return editor.readBatch(projectId, subjectType, subjectIds);
    }

    @PutMapping("/classification/{subjectType}")
    public List<CatalogClassificationEditorService.Assignment> replaceClassification(@PathVariable Long projectId, @PathVariable String subjectType,
            @RequestBody CatalogClassificationEditorService.EditRequest body) {
        return editor.replace(projectId, subjectType, body);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException invalid) {
        return ResponseEntity.badRequest().body(Map.of("code", "CATALOG_SUBJECT_INVALID", "message", invalid.getMessage()));
    }
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException conflict) {
        return ResponseEntity.status(409).body(Map.of("code", "CATALOG_ENRICHMENT_CHANGED", "message", conflict.getMessage()));
    }

    private SemanticDoc doc(SemanticDocEntity doc, CatalogSubject subject) {
        return new SemanticDoc(doc.getId(), doc.getProjectId(), doc.getSubjectType(), doc.getSubjectId(), doc.getSubjectKey(),
                subject == null ? doc.getSubjectKey() : subject.title(), doc.getBasisContractHash(), doc.getContentMd(), doc.getPromptVersion(),
                doc.getModelName(), doc.getTokenUsage() == null ? 0 : doc.getTokenUsage(), doc.getStatus(), doc.getUpdateTime(),
                subject == null ? List.of() : subject.observations().stream().map(row -> row.getId()).toList());
    }
    private SemanticDocIndex index(SemanticDocEntity doc, CatalogSubject subject) {
        return new SemanticDocIndex(doc.getId(), doc.getProjectId(), doc.getSubjectType(), doc.getSubjectId(), doc.getSubjectKey(),
                subject == null ? doc.getSubjectKey() : subject.title(), doc.getBasisContractHash(), doc.getPromptVersion(),
                doc.getModelName(), doc.getTokenUsage() == null ? 0 : doc.getTokenUsage(), doc.getStatus(), doc.getUpdateTime(),
                subject == null ? List.of() : subject.observations().stream().map(row -> row.getId()).toList());
    }
    private Group group(ScanModuleEntity group) { return new Group(group.getId(), group.getProjectId(), group.getName(),
            group.getDisplayName(), grouping.parseClasses(group.getSourceClasses())); }
    private JsonNode readRisk(String value) {
        if (value == null || value.isBlank()) return null;
        try { return json.readTree(value); } catch (Exception invalid) { return json.createObjectNode().put("status", "UNKNOWN").put("reason", "来源风险记录无法解析"); }
    }
    public record EditRequest(String contentMd, String expectedUpdatedAt) { }
    public record CreateGroupRequest(String displayName) { }
    public record RenameRequest(String displayName) { }
    public record Group(Long id, Long projectId, String name, String displayName, List<String> sourceClasses) { }
    public record SemanticDoc(Long id, Long projectId, String subjectType, Long subjectId, String subjectKey, String title,
                              String basisContractHash, String contentMd, String promptVersion, String modelName, int tokenUsage,
                              String status, @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDateTime updatedAt, List<Long> sourceObservationIds) { }
    public record SemanticDocIndex(Long id, Long projectId, String subjectType, Long subjectId, String subjectKey, String title,
                              String basisContractHash, String promptVersion, String modelName, int tokenUsage,
                              String status, @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDateTime updatedAt, List<Long> sourceObservationIds) { }
    public record SourceObservation(Long id, String title, String sourceLocation, boolean removedFromSource, JsonNode sensitiveData) { }
    public record Enrichment(Long projectId, String subjectType, Long subjectId, String subjectKey, String title, String contractHash,
                             boolean accepted, SemanticDoc semanticDoc, boolean semanticStale, List<Group> groups,
                             List<SourceObservation> sourceObservations, boolean graphAvailable, String graphState,
                             List<CapabilityApiGraphParamSourceHintView> paramHints) { }
}
