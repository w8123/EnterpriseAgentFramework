package com.enterprise.ai.control.context;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@RestController
@RequiredArgsConstructor
public class ContextGovernanceOpsController {

    private final ContextItemMapper itemMapper;
    private final ContextNamespaceMapper namespaceMapper;
    private final ContextAuditEventMapper auditMapper;
    private final ContextMemoryCandidateMapper candidateMapper;

    @GetMapping("/api/context/audit")
    public ResponseEntity<List<AuditEventView>> audit(
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String projectCode,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long itemId,
            @RequestParam(required = false) Long namespaceId,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String actorType,
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false) String decision,
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) String dateFrom,
            @RequestParam(required = false) String dateTo,
            @RequestParam(defaultValue = "100") Integer limit) {
        int boundedLimit = Math.max(1, Math.min(limit == null ? 100 : limit, 500));
        LocalDateTime from = parseDate(dateFrom);
        LocalDateTime to = parseDate(dateTo);
        return ResponseEntity.ok(auditMapper.selectList(Wrappers.<ContextAuditEventEntity>lambdaQuery()
                        .eq(StringUtils.hasText(tenantId), ContextAuditEventEntity::getTenantId, trim(tenantId))
                        .eq(StringUtils.hasText(projectCode), ContextAuditEventEntity::getProjectCode, trim(projectCode))
                        .eq(projectId != null, ContextAuditEventEntity::getProjectId, projectId)
                        .eq(itemId != null, ContextAuditEventEntity::getItemId, itemId)
                        .eq(namespaceId != null, ContextAuditEventEntity::getNamespaceId, namespaceId)
                        .eq(StringUtils.hasText(eventType), ContextAuditEventEntity::getEventType, upper(eventType))
                        .eq(StringUtils.hasText(actorType), ContextAuditEventEntity::getActorType, upper(actorType))
                        .eq(StringUtils.hasText(actorId), ContextAuditEventEntity::getActorId, trim(actorId))
                        .eq(StringUtils.hasText(decision), ContextAuditEventEntity::getDecision, upper(decision))
                        .eq(StringUtils.hasText(traceId), ContextAuditEventEntity::getTraceId, trim(traceId))
                        .ge(from != null, ContextAuditEventEntity::getCreatedAt, from)
                        .le(to != null, ContextAuditEventEntity::getCreatedAt, to)
                        .orderByDesc(ContextAuditEventEntity::getCreatedAt)
                        .orderByDesc(ContextAuditEventEntity::getId)
                        .last("LIMIT " + boundedLimit))
                .stream()
                .map(this::auditView)
                .toList());
    }

    @GetMapping("/api/context/ops/summary")
    public ResponseEntity<OpsSummaryView> summary(
            @RequestParam String tenantId,
            @RequestParam(required = false) String projectCode,
            @RequestParam(required = false) Long projectId,
            @RequestParam(defaultValue = "PROJECT_DEV") String memoryLane,
            @RequestParam(defaultValue = "false") Boolean includeRuntimeUser) {
        String lane = defaultUpper(memoryLane, "PROJECT_DEV");
        List<Long> namespaceIds = namespaceIds(tenantId, projectCode, projectId);
        List<String> warnings = new ArrayList<>();
        if (namespaceIds.isEmpty()) {
            return ResponseEntity.ok(new OpsSummaryView(tenantId, trim(projectCode), projectId, lane,
                    0L, 0L, 0L, 0L, 0L, 0L, 0L, pendingCandidates(tenantId, projectCode, projectId, lane),
                    expiredCandidates(tenantId, projectCode, projectId, lane), recentAuditEvents(tenantId, projectCode, projectId),
                    0L, runtimeUserExcluded(tenantId, projectCode, projectId, includeRuntimeUser), warnings));
        }
        warnings.addAll(runtimeUserWarning(includeRuntimeUser));
        return ResponseEntity.ok(new OpsSummaryView(tenantId, trim(projectCode), projectId, lane,
                (long) namespaceIds.size(),
                countItems(namespaceIds, lane, null, null),
                countItems(namespaceIds, lane, "ACTIVE", null),
                countItems(namespaceIds, lane, "STALE", null),
                countItems(namespaceIds, lane, "REVOKED", null),
                countItems(namespaceIds, lane, "DELETED", null),
                countExpiringItems(namespaceIds, lane),
                pendingCandidates(tenantId, projectCode, projectId, lane),
                expiredCandidates(tenantId, projectCode, projectId, lane),
                recentAuditEvents(tenantId, projectCode, projectId),
                countStaleDueItems(namespaceIds, lane),
                runtimeUserExcluded(tenantId, projectCode, projectId, includeRuntimeUser),
                warnings));
    }

    @PostMapping("/api/context/lifecycle/run")
    @Transactional
    public ResponseEntity<LifecycleRunView> runLifecycle(@RequestBody LifecycleCommand command) {
        String lane = defaultUpper(command.memoryLane(), "PROJECT_DEV");
        boolean includeRuntimeUserItems = Boolean.TRUE.equals(command.includeRuntimeUserItems());
        List<Long> namespaceIds = namespaceIds(command.tenantId(), command.projectCode(), command.projectId());
        List<ContextMemoryCandidateEntity> expiredCandidates = expiredCandidateEntities(
                command.tenantId(), command.projectCode(), command.projectId(), lane);
        List<ContextItemEntity> staleDueItems = namespaceIds.isEmpty()
                ? List.of()
                : staleDueItems(namespaceIds, lane, includeRuntimeUserItems);
        long skippedRuntimeUserItems = namespaceIds.isEmpty()
                ? 0L
                : staleDueItems(namespaceIds, "RUNTIME_USER", false).size();
        if (!command.dryRun()) {
            LocalDateTime now = LocalDateTime.now();
            for (ContextMemoryCandidateEntity candidate : expiredCandidates) {
                candidate.setStatus("EXPIRED");
                candidate.setUpdatedAt(now);
                candidateMapper.updateById(candidate);
            }
            for (ContextItemEntity item : staleDueItems) {
                item.setStatus("STALE");
                item.setUpdatedBy("context-lifecycle");
                item.setUpdatedAt(now);
                itemMapper.updateById(item);
            }
        }
        return ResponseEntity.ok(new LifecycleRunView(command.tenantId(), trim(command.projectCode()), command.projectId(),
                command.dryRun(), (long) expiredCandidates.size(), (long) staleDueItems.size(),
                skippedRuntimeUserItems, (long) staleDueItems.size(), runtimeUserWarning(includeRuntimeUserItems)));
    }

    @PostMapping("/api/context/query")
    public ResponseEntity<List<SearchResultView>> query(@RequestBody QueryCommand command) {
        return ResponseEntity.ok(queryItems(command));
    }

    @PostMapping("/api/context/package")
    public ResponseEntity<PackageView> packageContext(@RequestBody PackageCommand command) {
        QueryCommand query = command.query();
        List<SearchResultView> results = queryItems(new QueryCommand(query.tenantId(), query.projectCode(),
                query.projectId(), query.memoryLane(), query.retrievalMode(), query.query(), query.itemTypes(),
                command.maxItems()));
        int maxItems = Math.max(1, command.maxItems() == null ? results.size() : command.maxItems());
        List<SearchResultView> capped = results.stream().limit(maxItems).toList();
        return ResponseEntity.ok(new PackageView(defaultUpper(query.memoryLane(), "PROJECT_DEV"),
                query.tenantId(), trim(query.projectCode()), capped.size(), Math.max(0, results.size() - capped.size()),
                capped.stream().filter(item -> isProjectMemory(item.item().itemType())).toList(),
                List.of(),
                capped.stream().filter(item -> "PAGE_CONTEXT".equals(item.item().itemType())).toList(),
                capped.stream().filter(item -> "WORKFLOW_CONTEXT".equals(item.item().itemType())).toList(),
                capped.stream().filter(item -> "API_CONTRACT".equals(item.item().itemType())).toList(),
                capped.stream().filter(item -> "RULE".equals(item.item().itemType())).toList(),
                capped.stream().filter(item -> "TRACE_LEARNING".equals(item.item().itemType())).toList()));
    }

    private List<SearchResultView> queryItems(QueryCommand command) {
        List<Long> namespaceIds = namespaceIds(command.tenantId(), command.projectCode(), command.projectId());
        if (namespaceIds.isEmpty()) {
            return List.of();
        }
        String lane = defaultUpper(command.memoryLane(), "PROJECT_DEV");
        String keyword = trim(command.query());
        int topK = Math.max(1, Math.min(command.topK() == null ? 20 : command.topK(), 200));
        List<String> itemTypes = command.itemTypes() == null ? List.of() : command.itemTypes().stream()
                .filter(StringUtils::hasText)
                .map(this::upper)
                .toList();
        return itemMapper.selectList(Wrappers.<ContextItemEntity>lambdaQuery()
                        .in(ContextItemEntity::getNamespaceId, namespaceIds)
                        .eq(ContextItemEntity::getMemoryLane, lane)
                        .eq(ContextItemEntity::getStatus, "ACTIVE")
                        .in(!itemTypes.isEmpty(), ContextItemEntity::getItemType, itemTypes)
                        .and(StringUtils.hasText(keyword), q -> q
                                .like(ContextItemEntity::getTitle, keyword)
                                .or()
                                .like(ContextItemEntity::getContent, keyword)
                                .or()
                                .like(ContextItemEntity::getSummary, keyword))
                        .orderByDesc(ContextItemEntity::getConfidence)
                        .orderByDesc(ContextItemEntity::getUpdatedAt)
                        .last("LIMIT " + topK))
                .stream()
                .filter(item -> itemTypes.isEmpty() || itemTypes.contains(item.getItemType()))
                .filter(item -> !StringUtils.hasText(keyword) || contains(item.getTitle(), keyword)
                        || contains(item.getContent(), keyword) || contains(item.getSummary(), keyword))
                .map(item -> searchResult(item, keyword))
                .sorted(Comparator.comparing(SearchResultView::rankScore).reversed())
                .limit(topK)
                .toList();
    }

    private List<Long> namespaceIds(String tenantId, String projectCode, Long projectId) {
        return namespaceMapper.selectList(Wrappers.<ContextNamespaceEntity>lambdaQuery()
                        .eq(StringUtils.hasText(tenantId), ContextNamespaceEntity::getTenantId, trim(tenantId))
                        .eq(StringUtils.hasText(projectCode), ContextNamespaceEntity::getProjectCode, trim(projectCode))
                        .eq(projectId != null, ContextNamespaceEntity::getProjectId, projectId)
                        .ne(ContextNamespaceEntity::getStatus, "DELETED"))
                .stream()
                .map(ContextNamespaceEntity::getId)
                .toList();
    }

    private long countItems(List<Long> namespaceIds, String memoryLane, String status, String itemType) {
        return itemMapper.selectCount(Wrappers.<ContextItemEntity>lambdaQuery()
                .in(ContextItemEntity::getNamespaceId, namespaceIds)
                .eq(ContextItemEntity::getMemoryLane, memoryLane)
                .eq(StringUtils.hasText(status), ContextItemEntity::getStatus, status)
                .eq(StringUtils.hasText(itemType), ContextItemEntity::getItemType, itemType));
    }

    private long countExpiringItems(List<Long> namespaceIds, String memoryLane) {
        LocalDateTime threshold = LocalDateTime.now().plusDays(7);
        return itemMapper.selectCount(Wrappers.<ContextItemEntity>lambdaQuery()
                .in(ContextItemEntity::getNamespaceId, namespaceIds)
                .eq(ContextItemEntity::getMemoryLane, memoryLane)
                .eq(ContextItemEntity::getStatus, "ACTIVE")
                .isNotNull(ContextItemEntity::getExpiresAt)
                .le(ContextItemEntity::getExpiresAt, threshold));
    }

    private long countStaleDueItems(List<Long> namespaceIds, String memoryLane) {
        return itemMapper.selectCount(Wrappers.<ContextItemEntity>lambdaQuery()
                .in(ContextItemEntity::getNamespaceId, namespaceIds)
                .eq(ContextItemEntity::getMemoryLane, memoryLane)
                .eq(ContextItemEntity::getStatus, "ACTIVE")
                .isNotNull(ContextItemEntity::getStaleAfter)
                .le(ContextItemEntity::getStaleAfter, LocalDateTime.now()));
    }

    private long pendingCandidates(String tenantId, String projectCode, Long projectId, String memoryLane) {
        return candidateMapper.selectCount(candidateScope(tenantId, projectCode, projectId, memoryLane)
                .eq(ContextMemoryCandidateEntity::getStatus, "PENDING"));
    }

    private long expiredCandidates(String tenantId, String projectCode, Long projectId, String memoryLane) {
        return expiredCandidateEntities(tenantId, projectCode, projectId, memoryLane).size();
    }

    private List<ContextMemoryCandidateEntity> expiredCandidateEntities(
            String tenantId, String projectCode, Long projectId, String memoryLane) {
        return candidateMapper.selectList(candidateScope(tenantId, projectCode, projectId, memoryLane)
                .eq(ContextMemoryCandidateEntity::getStatus, "PENDING")
                .isNotNull(ContextMemoryCandidateEntity::getExpiresAt)
                .le(ContextMemoryCandidateEntity::getExpiresAt, LocalDateTime.now()));
    }

    private long recentAuditEvents(String tenantId, String projectCode, Long projectId) {
        return auditMapper.selectCount(Wrappers.<ContextAuditEventEntity>lambdaQuery()
                .eq(StringUtils.hasText(tenantId), ContextAuditEventEntity::getTenantId, trim(tenantId))
                .eq(StringUtils.hasText(projectCode), ContextAuditEventEntity::getProjectCode, trim(projectCode))
                .eq(projectId != null, ContextAuditEventEntity::getProjectId, projectId)
                .ge(ContextAuditEventEntity::getCreatedAt, LocalDateTime.now().minusDays(7)));
    }

    private long runtimeUserExcluded(String tenantId, String projectCode, Long projectId, Boolean includeRuntimeUser) {
        if (Boolean.TRUE.equals(includeRuntimeUser)) {
            return 0L;
        }
        List<Long> namespaceIds = namespaceIds(tenantId, projectCode, projectId);
        if (namespaceIds.isEmpty()) {
            return 0L;
        }
        return countItems(namespaceIds, "RUNTIME_USER", "ACTIVE", null);
    }

    private List<ContextItemEntity> staleDueItems(List<Long> namespaceIds, String memoryLane, boolean includeRuntimeUser) {
        if ("RUNTIME_USER".equals(memoryLane) && !includeRuntimeUser) {
            return List.of();
        }
        return itemMapper.selectList(Wrappers.<ContextItemEntity>lambdaQuery()
                .in(ContextItemEntity::getNamespaceId, namespaceIds)
                .eq(ContextItemEntity::getMemoryLane, memoryLane)
                .eq(ContextItemEntity::getStatus, "ACTIVE")
                .isNotNull(ContextItemEntity::getStaleAfter)
                .le(ContextItemEntity::getStaleAfter, LocalDateTime.now()));
    }

    private com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ContextMemoryCandidateEntity> candidateScope(
            String tenantId, String projectCode, Long projectId, String memoryLane) {
        return Wrappers.<ContextMemoryCandidateEntity>lambdaQuery()
                .eq(ContextMemoryCandidateEntity::getTenantId, trim(tenantId))
                .eq(StringUtils.hasText(projectCode), ContextMemoryCandidateEntity::getProjectCode, trim(projectCode))
                .eq(projectId != null, ContextMemoryCandidateEntity::getProjectId, projectId)
                .eq(ContextMemoryCandidateEntity::getMemoryLane, defaultUpper(memoryLane, "PROJECT_DEV"));
    }

    private SearchResultView searchResult(ContextItemEntity item, String keyword) {
        BigDecimal score = contains(item.getTitle(), keyword) ? BigDecimal.valueOf(0.95)
                : contains(item.getContent(), keyword) ? BigDecimal.valueOf(0.85)
                : contains(item.getSummary(), keyword) ? BigDecimal.valueOf(0.75)
                : BigDecimal.valueOf(0.5);
        return new SearchResultView(itemView(item), score, StringUtils.hasText(keyword) ? "KEYWORD_MATCH" : "RECENT_ACTIVE",
                "{\"mode\":\"keyword\"}");
    }

    private boolean contains(String value, String keyword) {
        return !StringUtils.hasText(keyword)
                || (value != null && value.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT)));
    }

    private boolean isProjectMemory(String itemType) {
        return !List.of("PAGE_CONTEXT", "WORKFLOW_CONTEXT", "API_CONTRACT", "RULE", "TRACE_LEARNING").contains(itemType);
    }

    private List<String> runtimeUserWarning(Boolean includeRuntimeUser) {
        return Boolean.TRUE.equals(includeRuntimeUser)
                ? List.of("RUNTIME_USER lifecycle is included for this run.")
                : List.of();
    }

    private ContextItemController.ItemView itemView(ContextItemEntity entity) {
        return new ContextItemController.ItemView(entity.getId(), entity.getItemKey(), entity.getNamespaceId(),
                entity.getItemType(), entity.getMemoryLane(), entity.getTitle(), entity.getContent(),
                entity.getSummary(), entity.getMetadataJson(), entity.getSourceType(), entity.getSourceRef(),
                entity.getConfidence(), entity.getTrustLevel(), entity.getVisibility(), entity.getStatus(),
                entity.getEffectiveFrom(), entity.getExpiresAt(), entity.getLastVerifiedAt(), entity.getStaleAfter(),
                entity.getCreatedBy(), entity.getUpdatedBy(), entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private AuditEventView auditView(ContextAuditEventEntity entity) {
        return new AuditEventView(entity.getId(), entity.getEventType(), entity.getItemId(), entity.getNamespaceId(),
                entity.getActorType(), entity.getActorId(), entity.getTenantId(), entity.getProjectCode(),
                entity.getTraceId(), entity.getDecision(), entity.getReason(), entity.getMetadataJson(),
                entity.getCreatedAt());
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

    public record QueryCommand(String tenantId, String projectCode, Long projectId, String memoryLane,
                               String retrievalMode, String query, List<String> itemTypes, Integer topK) {
    }

    public record PackageCommand(QueryCommand query, Integer maxItems, Integer tokenBudget) {
    }

    public record LifecycleCommand(String tenantId, String projectCode, Long projectId,
                                   boolean dryRun, Boolean includeRuntimeUserItems) {
        String memoryLane() {
            return "PROJECT_DEV";
        }
    }

    public record AuditEventView(Long id, String eventType, Long itemId, Long namespaceId, String actorType,
                                 String actorId, String tenantId, String projectCode, String traceId,
                                 String decision, String reason, String metadataJson, LocalDateTime createdAt) {
    }

    public record SearchResultView(ContextItemController.ItemView item, BigDecimal rankScore,
                                   String hitReason, String scoreBreakdown) {
    }

    public record PackageView(String memoryLane, String tenantId, String projectCode, int totalItems,
                              int truncatedCount, List<SearchResultView> projectMemory,
                              List<SearchResultView> userMemory, List<SearchResultView> pageContext,
                              List<SearchResultView> workflowContext, List<SearchResultView> apiContext,
                              List<SearchResultView> rules, List<SearchResultView> evidenceSummary) {
    }

    public record OpsSummaryView(String tenantId, String projectCode, Long projectId, String memoryLane,
                                 Long namespaceCount, Long itemCount, Long activeItemCount, Long staleItemCount,
                                 Long revokedItemCount, Long deletedItemCount, Long expiringItemCount,
                                 Long pendingCandidateCount, Long expiredCandidateCount, Long auditEventCountRecent,
                                 Long staleDueItemCount, Long runtimeUserExcludedCount, List<String> warnings) {
    }

    public record LifecycleRunView(String tenantId, String projectCode, Long projectId, boolean dryRun,
                                   Long expiredCandidateCount, Long staleItemCount, Long skippedRuntimeUserItemCount,
                                   Long scannedItemCount, List<String> warnings) {
    }
}
