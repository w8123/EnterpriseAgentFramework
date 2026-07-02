package com.enterprise.ai.control.context;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class ContextItemController {

    private final ContextItemMapper itemMapper;
    private final ContextEvidenceMapper evidenceMapper;
    private final ContextBindingMapper bindingMapper;
    private final ContextNamespaceMapper namespaceMapper;

    @GetMapping("/api/context/items")
    public ResponseEntity<List<ItemView>> list(
            @RequestParam String tenantId,
            @RequestParam(required = false) String projectCode,
            @RequestParam(required = false) Long projectId,
            @RequestParam String memoryLane,
            @RequestParam(required = false) Long namespaceId,
            @RequestParam(required = false) String itemType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "50") Integer limit,
            @RequestParam(defaultValue = "0") Integer offset) {
        Long effectiveNamespaceId = namespaceId;
        List<Long> namespaceIds = null;
        if (effectiveNamespaceId == null) {
            namespaceIds = namespaceMapper.selectList(Wrappers.<ContextNamespaceEntity>lambdaQuery()
                            .eq(ContextNamespaceEntity::getTenantId, tenantId)
                            .eq(StringUtils.hasText(projectCode), ContextNamespaceEntity::getProjectCode, trim(projectCode))
                            .eq(projectId != null, ContextNamespaceEntity::getProjectId, projectId)
                            .ne(ContextNamespaceEntity::getStatus, "DELETED"))
                    .stream()
                    .map(ContextNamespaceEntity::getId)
                    .toList();
            if (namespaceIds.isEmpty()) {
                return ResponseEntity.ok(List.of());
            }
        }
        int boundedLimit = Math.max(1, Math.min(limit == null ? 50 : limit, 500));
        int boundedOffset = Math.max(0, offset == null ? 0 : offset);
        return ResponseEntity.ok(itemMapper.selectList(Wrappers.<ContextItemEntity>lambdaQuery()
                        .eq(effectiveNamespaceId != null, ContextItemEntity::getNamespaceId, effectiveNamespaceId)
                        .in(effectiveNamespaceId == null && namespaceIds != null && !namespaceIds.isEmpty(),
                                ContextItemEntity::getNamespaceId, namespaceIds)
                        .eq(ContextItemEntity::getMemoryLane, upper(memoryLane))
                        .eq(StringUtils.hasText(itemType), ContextItemEntity::getItemType, upper(itemType))
                        .eq(StringUtils.hasText(status), ContextItemEntity::getStatus, upper(status))
                        .and(StringUtils.hasText(keyword), q -> q
                                .like(ContextItemEntity::getTitle, trim(keyword))
                                .or()
                                .like(ContextItemEntity::getContent, trim(keyword)))
                        .orderByDesc(ContextItemEntity::getUpdatedAt)
                        .orderByDesc(ContextItemEntity::getId)
                        .last("LIMIT " + boundedOffset + ", " + boundedLimit))
                .stream()
                .map(this::itemView)
                .toList());
    }

    @GetMapping("/api/context/items/{id}")
    public ResponseEntity<ItemView> get(@PathVariable Long id) {
        ContextItemEntity entity = itemMapper.selectById(id);
        return entity == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(itemView(entity));
    }

    @PostMapping("/api/context/items")
    @Transactional
    public ResponseEntity<ItemView> create(@RequestBody ItemCommand command) {
        ContextNamespaceEntity namespace = resolveNamespace(command.namespaceId(), command.namespaceKey());
        ContextItemEntity entity = new ContextItemEntity();
        entity.setItemKey("ctx-item-" + UUID.randomUUID().toString().replace("-", ""));
        entity.setNamespaceId(namespace.getId());
        entity.setItemType(required(command.itemType(), "itemType").toUpperCase(Locale.ROOT));
        entity.setMemoryLane(required(command.memoryLane(), "memoryLane").toUpperCase(Locale.ROOT));
        entity.setTitle(trim(command.title()));
        entity.setContent(required(command.content(), "content"));
        entity.setSummary(trim(command.summary()));
        entity.setMetadataJson(trim(command.metadataJson()));
        entity.setSourceType(required(command.sourceType(), "sourceType").toUpperCase(Locale.ROOT));
        entity.setSourceRef(trim(command.sourceRef()));
        entity.setConfidence(command.confidence() == null ? BigDecimal.valueOf(0.7) : command.confidence());
        entity.setTrustLevel(defaultUpper(command.trustLevel(), "MEDIUM"));
        entity.setVisibility(defaultUpper(command.visibility(), "PRIVATE"));
        entity.setStatus("ACTIVE");
        entity.setEffectiveFrom(parseDate(command.effectiveFrom()));
        entity.setExpiresAt(parseDate(command.expiresAt()));
        entity.setCreatedBy(trim(command.createdBy()));
        entity.setUpdatedBy(trim(command.createdBy()));
        LocalDateTime now = LocalDateTime.now();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        itemMapper.insert(entity);
        insertBindings(entity, command);
        insertEvidence(entity.getId(), command.evidence());
        return ResponseEntity.ok(itemView(entity));
    }

    @PutMapping("/api/context/items/{id}")
    @Transactional
    public ResponseEntity<ItemView> update(@PathVariable Long id, @RequestBody ItemUpdateCommand command) {
        ContextItemEntity entity = requiredItem(id);
        if (command.title() != null) {
            entity.setTitle(trim(command.title()));
        }
        if (command.content() != null) {
            entity.setContent(command.content());
        }
        if (command.summary() != null) {
            entity.setSummary(trim(command.summary()));
        }
        if (command.metadataJson() != null) {
            entity.setMetadataJson(trim(command.metadataJson()));
        }
        if (command.sourceType() != null) {
            entity.setSourceType(upper(command.sourceType()));
        }
        if (command.sourceRef() != null) {
            entity.setSourceRef(trim(command.sourceRef()));
        }
        if (command.confidence() != null) {
            entity.setConfidence(command.confidence());
        }
        if (command.trustLevel() != null) {
            entity.setTrustLevel(upper(command.trustLevel()));
        }
        if (command.visibility() != null) {
            entity.setVisibility(upper(command.visibility()));
        }
        if (command.effectiveFrom() != null) {
            entity.setEffectiveFrom(parseDate(command.effectiveFrom()));
        }
        if (command.expiresAt() != null) {
            entity.setExpiresAt(parseDate(command.expiresAt()));
        }
        entity.setUpdatedBy(trim(command.updatedBy()));
        entity.setUpdatedAt(LocalDateTime.now());
        itemMapper.updateById(entity);
        return ResponseEntity.ok(itemView(entity));
    }

    @PostMapping("/api/context/items/{id}/revoke")
    public ResponseEntity<ItemView> revoke(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        return updateStatus(id, "REVOKED", body);
    }

    @PostMapping("/api/context/items/{id}/stale")
    public ResponseEntity<ItemView> stale(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        return updateStatus(id, "STALE", body);
    }

    @PostMapping("/api/context/items/{id}/verify")
    @Transactional
    public ResponseEntity<ItemView> verifyItem(@PathVariable Long id, @RequestBody VerifyCommand command) {
        ContextItemEntity entity = requiredItem(id);
        if (command.confidence() != null) {
            entity.setConfidence(command.confidence());
        }
        if (StringUtils.hasText(command.trustLevel())) {
            entity.setTrustLevel(upper(command.trustLevel()));
        }
        entity.setLastVerifiedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        itemMapper.updateById(entity);
        return ResponseEntity.ok(itemView(entity));
    }

    @DeleteMapping("/api/context/items/{id}")
    public ResponseEntity<ItemView> delete(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        return updateStatus(id, "DELETED", body);
    }

    @GetMapping("/api/context/items/{itemId}/evidence")
    public ResponseEntity<List<EvidenceView>> listEvidence(@PathVariable Long itemId) {
        return ResponseEntity.ok(evidenceMapper.selectList(Wrappers.<ContextEvidenceEntity>lambdaQuery()
                        .eq(ContextEvidenceEntity::getItemId, itemId)
                        .orderByDesc(ContextEvidenceEntity::getCreatedAt)
                        .orderByDesc(ContextEvidenceEntity::getId))
                .stream()
                .map(this::evidenceView)
                .toList());
    }

    @PostMapping("/api/context/items/{itemId}/evidence")
    @Transactional
    public ResponseEntity<EvidenceView> addEvidence(@PathVariable Long itemId, @RequestBody EvidenceCommand command) {
        requiredItem(itemId);
        ContextEvidenceEntity entity = evidenceEntity(itemId, command);
        evidenceMapper.insert(entity);
        return ResponseEntity.ok(evidenceView(entity));
    }

    @GetMapping("/api/context/items/{itemId}/bindings")
    public ResponseEntity<List<BindingView>> listBindings(@PathVariable Long itemId) {
        return ResponseEntity.ok(bindingMapper.selectList(Wrappers.<ContextBindingEntity>lambdaQuery()
                        .eq(ContextBindingEntity::getItemId, itemId)
                        .eq(ContextBindingEntity::getStatus, "ACTIVE")
                        .orderByAsc(ContextBindingEntity::getId))
                .stream()
                .map(this::bindingView)
                .toList());
    }

    private ResponseEntity<ItemView> updateStatus(Long id, String status, Map<String, Object> body) {
        ContextItemEntity entity = requiredItem(id);
        entity.setStatus(status);
        if ("DELETED".equals(status)) {
            entity.setDeletedAt(LocalDateTime.now());
        }
        entity.setUpdatedBy(body == null ? null : trim(String.valueOf(body.getOrDefault("updatedBy", ""))));
        entity.setUpdatedAt(LocalDateTime.now());
        itemMapper.updateById(entity);
        return ResponseEntity.ok(itemView(entity));
    }

    private void insertBindings(ContextItemEntity item, ItemCommand command) {
        List<BindingCommand> bindings = command.bindings() == null ? List.of() : command.bindings();
        for (BindingCommand binding : bindings) {
            bindingMapper.insert(bindingEntity(item.getId(), binding));
        }
        if (bindings.isEmpty() && StringUtils.hasText(command.projectCode())) {
            bindingMapper.insert(bindingEntity(item.getId(), new BindingCommand(
                    "PROJECT", command.projectCode(), null, command.tenantId(), command.projectId(), command.projectCode())));
        }
    }

    private void insertEvidence(Long itemId, List<EvidenceCommand> evidence) {
        if (evidence == null) {
            return;
        }
        for (EvidenceCommand command : evidence) {
            evidenceMapper.insert(evidenceEntity(itemId, command));
        }
    }

    private ContextNamespaceEntity resolveNamespace(Long namespaceId, String namespaceKey) {
        ContextNamespaceEntity namespace;
        if (namespaceId != null) {
            namespace = namespaceMapper.selectById(namespaceId);
        } else if (StringUtils.hasText(namespaceKey)) {
            namespace = namespaceMapper.selectOne(Wrappers.<ContextNamespaceEntity>lambdaQuery()
                    .eq(ContextNamespaceEntity::getNamespaceKey, trim(namespaceKey))
                    .last("LIMIT 1"));
        } else {
            throw new IllegalArgumentException("Context namespaceId or namespaceKey is required");
        }
        if (namespace == null) {
            throw new IllegalArgumentException("Context namespace not found");
        }
        return namespace;
    }

    private ContextItemEntity requiredItem(Long id) {
        ContextItemEntity entity = itemMapper.selectById(id);
        if (entity == null) {
            throw new IllegalArgumentException("Context item not found: " + id);
        }
        return entity;
    }

    private ContextEvidenceEntity evidenceEntity(Long itemId, EvidenceCommand command) {
        ContextEvidenceEntity entity = new ContextEvidenceEntity();
        entity.setItemId(itemId);
        entity.setEvidenceType(required(command.evidenceType(), "evidenceType").toUpperCase(Locale.ROOT));
        entity.setEvidenceRef(trim(command.evidenceRef()));
        entity.setEvidenceExcerpt(trim(command.evidenceExcerpt()));
        entity.setTraceId(trim(command.traceId()));
        entity.setConfidence(command.confidence());
        entity.setMetadataJson(trim(command.metadataJson()));
        entity.setCreatedAt(LocalDateTime.now());
        return entity;
    }

    private ContextBindingEntity bindingEntity(Long itemId, BindingCommand command) {
        ContextBindingEntity entity = new ContextBindingEntity();
        entity.setItemId(itemId);
        entity.setBindType(required(command.bindType(), "bindType").toUpperCase(Locale.ROOT));
        entity.setBindId(required(command.bindId(), "bindId"));
        entity.setBindKey(trim(command.bindKey()));
        entity.setTenantId(trim(command.tenantId()));
        entity.setProjectId(command.projectId());
        entity.setProjectCode(trim(command.projectCode()));
        entity.setStatus("ACTIVE");
        entity.setCreatedAt(LocalDateTime.now());
        return entity;
    }

    private ItemView itemView(ContextItemEntity entity) {
        return new ItemView(entity.getId(), entity.getItemKey(), entity.getNamespaceId(), entity.getItemType(),
                entity.getMemoryLane(), entity.getTitle(), entity.getContent(), entity.getSummary(),
                entity.getMetadataJson(), entity.getSourceType(), entity.getSourceRef(), entity.getConfidence(),
                entity.getTrustLevel(), entity.getVisibility(), entity.getStatus(), entity.getEffectiveFrom(),
                entity.getExpiresAt(), entity.getLastVerifiedAt(), entity.getStaleAfter(), entity.getCreatedBy(),
                entity.getUpdatedBy(), entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private EvidenceView evidenceView(ContextEvidenceEntity entity) {
        return new EvidenceView(entity.getId(), entity.getItemId(), entity.getEvidenceType(), entity.getEvidenceRef(),
                entity.getEvidenceExcerpt(), entity.getTraceId(), entity.getConfidence(), entity.getMetadataJson(),
                entity.getCreatedAt());
    }

    private BindingView bindingView(ContextBindingEntity entity) {
        return new BindingView(entity.getId(), entity.getItemId(), entity.getBindType(), entity.getBindId(),
                entity.getBindKey(), entity.getTenantId(), entity.getProjectId(), entity.getProjectCode(),
                entity.getStatus(), entity.getCreatedAt());
    }

    private LocalDateTime parseDate(String value) {
        String text = trim(value);
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            return OffsetDateTime.parse(text).toLocalDateTime();
        } catch (Exception ignored) {
            return LocalDateTime.parse(text);
        }
    }

    private String required(String value, String field) {
        String text = trim(value);
        if (!StringUtils.hasText(text)) {
            throw new IllegalArgumentException("Context " + field + " is required");
        }
        return text;
    }

    private String defaultUpper(String value, String fallback) {
        String text = trim(value);
        return StringUtils.hasText(text) ? text.toUpperCase(Locale.ROOT) : fallback;
    }

    private String upper(String value) {
        String text = trim(value);
        return StringUtils.hasText(text) ? text.toUpperCase(Locale.ROOT) : null;
    }

    private String trim(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    public record ItemCommand(Long namespaceId, String namespaceKey, String itemType, String memoryLane,
                              String title, String content, String summary, String metadataJson,
                              String sourceType, String sourceRef, BigDecimal confidence, String trustLevel,
                              String visibility, String effectiveFrom, String expiresAt, String tenantId,
                              Long projectId, String projectCode, String createdBy, String pageInstanceId,
                              String workflowId, String agentId, String sessionId, String userId,
                              List<BindingCommand> bindings, List<EvidenceCommand> evidence) {
    }

    public record ItemUpdateCommand(String title, String content, String summary, String metadataJson,
                                    String sourceType, String sourceRef, BigDecimal confidence, String trustLevel,
                                    String visibility, String effectiveFrom, String expiresAt, String updatedBy) {
    }

    public record VerifyCommand(BigDecimal confidence, String trustLevel) {
    }

    public record EvidenceCommand(String evidenceType, String evidenceRef, String evidenceExcerpt,
                                  String traceId, BigDecimal confidence, String metadataJson) {
    }

    public record BindingCommand(String bindType, String bindId, String bindKey,
                                 String tenantId, Long projectId, String projectCode) {
    }

    public record ItemView(Long id, String itemKey, Long namespaceId, String itemType, String memoryLane,
                           String title, String content, String summary, String metadataJson,
                           String sourceType, String sourceRef, BigDecimal confidence, String trustLevel,
                           String visibility, String status, LocalDateTime effectiveFrom, LocalDateTime expiresAt,
                           LocalDateTime lastVerifiedAt, LocalDateTime staleAfter, String createdBy,
                           String updatedBy, LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    public record EvidenceView(Long id, Long itemId, String evidenceType, String evidenceRef,
                               String evidenceExcerpt, String traceId, BigDecimal confidence,
                               String metadataJson, LocalDateTime createdAt) {
    }

    public record BindingView(Long id, Long itemId, String bindType, String bindId, String bindKey,
                              String tenantId, Long projectId, String projectCode, String status,
                              LocalDateTime createdAt) {
    }
}
