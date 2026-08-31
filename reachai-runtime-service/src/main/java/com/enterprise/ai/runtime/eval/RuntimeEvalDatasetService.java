package com.enterprise.ai.runtime.eval;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.runtime.agent.RuntimeAgentEntity;
import com.enterprise.ai.runtime.agent.RuntimeAgentMapper;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsQueryService;
import com.enterprise.ai.runtime.runops.RuntimeRunOpsViews.RuntimeRunOpsDetailView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Versioned, immutable Eval dataset catalog. */
@Service
@RequiredArgsConstructor
public class RuntimeEvalDatasetService {

    private static final int MAX_ITEMS_PER_VERSION = 2_000;

    private final RuntimeEvalDatasetMapper datasetMapper;
    private final RuntimeEvalDatasetVersionMapper versionMapper;
    private final RuntimeEvalDatasetItemMapper itemMapper;
    private final RuntimeAgentMapper agentMapper;
    private final RuntimeRunOpsQueryService runOpsQueryService;
    private final RuntimeEvalJsonSupport json;

    public List<RuntimeEvalDatasetSummaryView> list(String tenantId, String targetId) {
        String tenant = normalizedTenant(tenantId);
        String canonicalTargetId = canonicalAgentId(targetId);
        return datasetMapper.selectList(Wrappers.<RuntimeEvalDatasetEntity>lambdaQuery()
                        .eq(RuntimeEvalDatasetEntity::getTenantId, tenant)
                        .eq(StringUtils.hasText(canonicalTargetId), RuntimeEvalDatasetEntity::getTargetId, canonicalTargetId)
                        .ne(RuntimeEvalDatasetEntity::getStatus, "ARCHIVED")
                        .orderByDesc(RuntimeEvalDatasetEntity::getUpdatedAt)
                        .orderByDesc(RuntimeEvalDatasetEntity::getId))
                .stream().map(this::summaryView).toList();
    }

    public RuntimeEvalDatasetDetailView get(Long datasetId) {
        RuntimeEvalDatasetEntity dataset = requiredDataset(datasetId);
        List<RuntimeEvalDatasetVersionEntity> versions = versionMapper.selectList(
                Wrappers.<RuntimeEvalDatasetVersionEntity>lambdaQuery()
                        .eq(RuntimeEvalDatasetVersionEntity::getDatasetId, datasetId)
                        .orderByDesc(RuntimeEvalDatasetVersionEntity::getVersionNo));
        RuntimeEvalDatasetVersionView current = dataset.getCurrentVersionId() == null ? null
                : getVersion(dataset.getCurrentVersionId());
        return new RuntimeEvalDatasetDetailView(
                summaryView(dataset),
                current,
                versions.stream().map(value -> versionView(value, List.of())).toList());
    }

    public RuntimeEvalDatasetVersionView getVersion(Long versionId) {
        RuntimeEvalDatasetVersionEntity version = requiredVersion(versionId);
        List<RuntimeEvalDatasetItemEntity> items = itemMapper.selectList(
                Wrappers.<RuntimeEvalDatasetItemEntity>lambdaQuery()
                        .eq(RuntimeEvalDatasetItemEntity::getDatasetVersionId, versionId)
                        .orderByAsc(RuntimeEvalDatasetItemEntity::getOrdinalNo)
                        .orderByAsc(RuntimeEvalDatasetItemEntity::getId));
        return versionView(version, items.stream().map(this::itemView).toList());
    }

    @Transactional
    public RuntimeEvalDatasetDetailView create(Map<String, Object> request) {
        String targetType = upper(defaultText(request, "targetType", "AGENT"));
        if (!"AGENT".equals(targetType)) {
            throw new IllegalArgumentException("Eval dataset targetType currently supports AGENT only");
        }
        String requestedTargetId = requiredText(request, "targetId", "agentId");
        RuntimeAgentEntity agent = agentMapper.selectOne(Wrappers.<RuntimeAgentEntity>lambdaQuery()
                .and(value -> value.eq(RuntimeAgentEntity::getId, requestedTargetId)
                        .or().eq(RuntimeAgentEntity::getKeySlug, requestedTargetId))
                .last("LIMIT 1"));
        if (agent == null) {
            throw new IllegalArgumentException("Eval dataset Agent target not found: " + requestedTargetId);
        }
        RuntimeEvalDatasetEntity dataset = new RuntimeEvalDatasetEntity();
        dataset.setTenantId(normalizedTenant(text(request, "tenantId")));
        dataset.setProjectCode(agent.getProjectCode());
        dataset.setTargetType(targetType);
        dataset.setTargetId(agent.getId());
        dataset.setName(requiredText(request, "name"));
        dataset.setDescription(text(request, "description"));
        dataset.setSource(upper(defaultText(request, "source", "MANUAL")));
        dataset.setStatus("ACTIVE");
        dataset.setVersionCount(0);
        dataset.setCreatedBy(text(request, "createdBy"));
        LocalDateTime now = LocalDateTime.now();
        dataset.setCreatedAt(now);
        dataset.setUpdatedAt(now);
        datasetMapper.insert(dataset);

        persistPublishedVersion(dataset, request, items(request), 1);
        return get(dataset.getId());
    }

    @Transactional
    public RuntimeEvalDatasetVersionView createVersion(Long datasetId, Map<String, Object> request) {
        RuntimeEvalDatasetEntity dataset = datasetMapper.selectByIdForUpdate(datasetId);
        if (dataset == null || "ARCHIVED".equalsIgnoreCase(dataset.getStatus())) {
            throw new IllegalArgumentException("Eval dataset not found or archived: " + datasetId);
        }
        int versionNo = (dataset.getVersionCount() == null ? 0 : dataset.getVersionCount()) + 1;
        RuntimeEvalDatasetVersionEntity version = persistPublishedVersion(
                dataset, request, items(request), versionNo);
        return getVersion(version.getId());
    }

    @Transactional
    public RuntimeEvalDatasetVersionView createVersionFromTrace(Long datasetId, Map<String, Object> request) {
        RuntimeEvalDatasetEntity dataset = datasetMapper.selectByIdForUpdate(datasetId);
        if (dataset == null || "ARCHIVED".equalsIgnoreCase(dataset.getStatus())) {
            throw new IllegalArgumentException("Eval dataset not found or archived: " + datasetId);
        }
        String traceId = requiredText(request, "traceId");
        RuntimeRunOpsDetailView trace = runOpsQueryService.detail(traceId);
        if (trace == null || trace.summary() == null || !StringUtils.hasText(trace.summary().agentId())) {
            throw new IllegalArgumentException("RunOps trace is not attributable to an Agent: " + traceId);
        }
        if (!dataset.getTargetId().equals(trace.summary().agentId())) {
            throw new IllegalArgumentException("RunOps trace belongs to another Agent: " + traceId);
        }
        if (dataset.getCurrentVersionId() == null) {
            throw new IllegalStateException("Eval dataset has no current version: " + datasetId);
        }
        List<RuntimeEvalDatasetItemEntity> current = itemMapper.selectList(
                Wrappers.<RuntimeEvalDatasetItemEntity>lambdaQuery()
                        .eq(RuntimeEvalDatasetItemEntity::getDatasetVersionId, dataset.getCurrentVersionId())
                        .orderByAsc(RuntimeEvalDatasetItemEntity::getOrdinalNo)
                        .orderByAsc(RuntimeEvalDatasetItemEntity::getId));
        List<Map<String, Object>> nextItems = new ArrayList<>();
        for (RuntimeEvalDatasetItemEntity value : current) {
            nextItems.add(json.ordered(
                    "itemKey", value.getItemKey(),
                    "message", value.getMessage(),
                    "input", json.readMap(value.getInputJson()),
                    "expected", json.readMap(value.getExpectedJson()),
                    "metadata", json.readMap(value.getMetadataJson()),
                    "tags", json.readValue(value.getTagsJson()),
                    "sourceTraceId", value.getSourceTraceId(),
                    "enabled", value.getEnabled()));
        }
        String message = firstText(text(request, "message"), trace.summary().inputSummary());
        if (!StringUtils.hasText(message)) {
            throw new IllegalArgumentException("RunOps trace has no usable input summary; provide message explicitly");
        }
        Map<String, Object> input = request != null && request.containsKey("input")
                ? json.map(request.get("input")) : Map.of("message", message);
        Map<String, Object> expected = request != null && request.containsKey("expected")
                ? json.map(request.get("expected")) : Map.of("success", true);
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("source", "RUNOPS");
        metadata.put("sourceTraceId", traceId);
        metadata.put("sourceStatus", trace.summary().status());
        metadata.put("sourceErrorCode", trace.summary().errorCode());
        metadata.put("sourceAgentConfigVersionId", trace.summary().agentConfigVersionId());
        metadata.put("sourceStartedAt", trace.summary().startedAt());
        String itemKey = firstText(text(request, "itemKey"), defaultTraceItemKey(traceId));
        nextItems.add(json.ordered(
                "itemKey", itemKey,
                "message", message,
                "input", input,
                "expected", expected,
                "metadata", metadata,
                "tags", request != null && request.containsKey("tags")
                        ? request.get("tags") : List.of("runops", "regression"),
                "sourceTraceId", traceId,
                "enabled", true));
        Map<String, Object> versionRequest = json.ordered(
                "items", nextItems,
                "changeNote", defaultText(request, "changeNote", "RunOps trace " + traceId),
                "createdBy", text(request, "createdBy"));
        int versionNo = (dataset.getVersionCount() == null ? 0 : dataset.getVersionCount()) + 1;
        RuntimeEvalDatasetVersionEntity version = persistPublishedVersion(
                dataset, versionRequest, nextItems, versionNo);
        return getVersion(version.getId());
    }

    RuntimeEvalDatasetEntity requiredDataset(Long datasetId) {
        RuntimeEvalDatasetEntity dataset = datasetId == null ? null : datasetMapper.selectById(datasetId);
        if (dataset == null) {
            throw new IllegalArgumentException("Eval dataset not found: " + datasetId);
        }
        return dataset;
    }

    RuntimeEvalDatasetVersionEntity requiredVersion(Long versionId) {
        RuntimeEvalDatasetVersionEntity version = versionId == null ? null : versionMapper.selectById(versionId);
        if (version == null || !"PUBLISHED".equalsIgnoreCase(version.getStatus())) {
            throw new IllegalArgumentException("Published Eval dataset version not found: " + versionId);
        }
        return version;
    }

    List<RuntimeEvalDatasetItemEntity> enabledItems(Long versionId) {
        return itemMapper.selectList(Wrappers.<RuntimeEvalDatasetItemEntity>lambdaQuery()
                .eq(RuntimeEvalDatasetItemEntity::getDatasetVersionId, versionId)
                .eq(RuntimeEvalDatasetItemEntity::getEnabled, true)
                .orderByAsc(RuntimeEvalDatasetItemEntity::getOrdinalNo)
                .orderByAsc(RuntimeEvalDatasetItemEntity::getId));
    }

    RuntimeEvalDatasetItemEntity requiredVerifiedItem(Long versionId, Long itemId) {
        requiredVersion(versionId);
        RuntimeEvalDatasetItemEntity item = itemId == null ? null : itemMapper.selectById(itemId);
        if (item == null || !java.util.Objects.equals(versionId, item.getDatasetVersionId())) {
            throw new IllegalStateException("Eval dataset item does not belong to the experiment version: " + itemId);
        }
        Map<String, Object> canonical = json.ordered(
                "itemKey", item.getItemKey(),
                "message", item.getMessage(),
                "input", json.readMap(item.getInputJson()),
                "expected", json.readMap(item.getExpectedJson()),
                "metadata", json.readMap(item.getMetadataJson()),
                "tags", json.readValue(item.getTagsJson()),
                "sourceTraceId", item.getSourceTraceId(),
                "enabled", Boolean.TRUE.equals(item.getEnabled()),
                "ordinalNo", item.getOrdinalNo());
        String fingerprint = json.sha256(json.canonicalJson(canonical));
        if (!fingerprint.equalsIgnoreCase(item.getContentSha256())) {
            throw new IllegalStateException("Eval dataset item fingerprint verification failed: " + itemId);
        }
        return item;
    }

    private RuntimeEvalDatasetVersionEntity persistPublishedVersion(
            RuntimeEvalDatasetEntity dataset,
            Map<String, Object> request,
            List<Map<String, Object>> rows,
            int versionNo) {
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Eval dataset version requires at least one item");
        }
        if (rows.size() > MAX_ITEMS_PER_VERSION) {
            throw new IllegalArgumentException("Eval dataset version exceeds " + MAX_ITEMS_PER_VERSION + " items");
        }

        List<PreparedItem> prepared = prepareItems(rows);
        if (prepared.stream().noneMatch(PreparedItem::enabled)) {
            throw new IllegalArgumentException("Eval dataset version requires at least one enabled item");
        }
        String versionFingerprint = json.sha256(json.canonicalJson(json.ordered(
                "schemaVersion", 1,
                "items", prepared.stream().map(PreparedItem::canonical).toList())));
        if (dataset.getCurrentVersionId() != null) {
            RuntimeEvalDatasetVersionEntity current = versionMapper.selectById(dataset.getCurrentVersionId());
            if (current != null && versionFingerprint.equalsIgnoreCase(current.getFingerprintSha256())) {
                throw new IllegalArgumentException("Eval dataset version has no material content change");
            }
        }
        LocalDateTime now = LocalDateTime.now();
        RuntimeEvalDatasetVersionEntity version = new RuntimeEvalDatasetVersionEntity();
        version.setDatasetId(dataset.getId());
        version.setVersionNo(versionNo);
        version.setStatus("PUBLISHED");
        version.setFingerprintSha256(versionFingerprint);
        version.setItemCount(prepared.size());
        version.setChangeNote(defaultText(request, "changeNote", versionNo == 1 ? "Initial version" : null));
        version.setCreatedBy(firstText(text(request, "createdBy"), dataset.getCreatedBy()));
        version.setCreatedAt(now);
        version.setPublishedAt(now);
        versionMapper.insert(version);

        for (PreparedItem value : prepared) {
            RuntimeEvalDatasetItemEntity item = new RuntimeEvalDatasetItemEntity();
            item.setDatasetVersionId(version.getId());
            item.setItemKey(value.itemKey());
            item.setMessage(value.message());
            item.setInputJson(json.canonicalJson(value.input()));
            item.setExpectedJson(json.canonicalJson(value.expected()));
            item.setMetadataJson(json.canonicalJson(value.metadata()));
            item.setTagsJson(json.nullableJson(value.tags()));
            item.setSourceTraceId(value.sourceTraceId());
            item.setEnabled(value.enabled());
            item.setOrdinalNo(value.ordinalNo());
            item.setContentSha256(json.sha256(json.canonicalJson(value.canonical())));
            item.setCreatedAt(now);
            itemMapper.insert(item);
        }

        dataset.setCurrentVersionId(version.getId());
        dataset.setVersionCount(versionNo);
        dataset.setUpdatedAt(now);
        datasetMapper.updateById(dataset);
        return version;
    }

    private List<PreparedItem> prepareItems(List<Map<String, Object>> rows) {
        List<PreparedItem> result = new ArrayList<>();
        Set<String> uniqueKeys = new HashSet<>();
        for (int index = 0; index < rows.size(); index++) {
            Map<String, Object> row = rows.get(index);
            String itemKey = firstText(text(row, "itemKey"), text(row, "caseNo"), "ITEM-" + (index + 1));
            if (!uniqueKeys.add(itemKey.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Duplicate Eval dataset itemKey: " + itemKey);
            }
            Map<String, Object> input = json.map(firstValue(row, "input", "inputParams"));
            Map<String, Object> expected = row.containsKey("expected")
                    ? json.map(row.get("expected")) : Map.of("success", true);
            Map<String, Object> metadata = json.map(row.get("metadata"));
            Object tags = row.get("tags");
            boolean enabled = booleanValue(row.get("enabled"), true);
            String message = firstText(text(row, "message"), text(input, "message"));
            String sourceTraceId = text(row, "sourceTraceId");
            int ordinalNo = index + 1;
            Map<String, Object> canonical = json.ordered(
                    "itemKey", itemKey,
                    "message", message,
                    "input", input,
                    "expected", expected,
                    "metadata", metadata,
                    "tags", tags,
                    "sourceTraceId", sourceTraceId,
                    "enabled", enabled,
                    "ordinalNo", ordinalNo);
            result.add(new PreparedItem(itemKey, message, input, expected, metadata, tags,
                    sourceTraceId, enabled, ordinalNo, canonical));
        }
        return List.copyOf(result);
    }

    private List<Map<String, Object>> items(Map<String, Object> request) {
        return json.mapList(firstValue(request, "items", "cases"));
    }

    private RuntimeEvalDatasetSummaryView summaryView(RuntimeEvalDatasetEntity value) {
        return new RuntimeEvalDatasetSummaryView(
                value.getId(), value.getTenantId(), value.getProjectCode(), value.getTargetType(), value.getTargetId(),
                value.getName(), value.getDescription(), value.getSource(), value.getStatus(),
                value.getCurrentVersionId(), value.getVersionCount(), value.getCreatedAt(), value.getUpdatedAt());
    }

    private RuntimeEvalDatasetVersionView versionView(RuntimeEvalDatasetVersionEntity value,
                                                       List<RuntimeEvalDatasetItemView> items) {
        return new RuntimeEvalDatasetVersionView(
                value.getId(), value.getDatasetId(), value.getVersionNo(), value.getStatus(),
                value.getFingerprintSha256(), value.getItemCount(), value.getChangeNote(), value.getCreatedBy(),
                value.getCreatedAt(), value.getPublishedAt(), items);
    }

    private RuntimeEvalDatasetItemView itemView(RuntimeEvalDatasetItemEntity value) {
        return new RuntimeEvalDatasetItemView(
                value.getId(), value.getDatasetVersionId(), value.getItemKey(), value.getMessage(),
                json.readMap(value.getInputJson()), json.readMap(value.getExpectedJson()),
                json.readMap(value.getMetadataJson()), json.readValue(value.getTagsJson()),
                value.getSourceTraceId(), value.getEnabled(), value.getOrdinalNo(), value.getContentSha256());
    }

    private String requiredText(Map<String, Object> request, String... fields) {
        for (String field : fields) {
            String value = text(request, field);
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Eval dataset " + String.join("/", fields) + " is required");
    }

    private Object firstValue(Map<String, Object> values, String... fields) {
        if (values == null) return null;
        for (String field : fields) {
            if (values.containsKey(field) && values.get(field) != null) return values.get(field);
        }
        return null;
    }

    private String text(Map<String, Object> values, String field) {
        Object value = values == null ? null : values.get(field);
        return value == null ? null : trim(String.valueOf(value));
    }

    private String defaultText(Map<String, Object> values, String field, String fallback) {
        return firstText(text(values, field), fallback);
    }

    private boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean bool) return bool;
        if (value instanceof Number number) return number.intValue() != 0;
        if (value instanceof String text && StringUtils.hasText(text)) return Boolean.parseBoolean(text);
        return fallback;
    }

    private String normalizedTenant(String value) {
        return firstText(value, "default");
    }

    private String canonicalAgentId(String value) {
        if (!StringUtils.hasText(value)) return null;
        String lookup = value.trim();
        RuntimeAgentEntity agent = agentMapper.selectOne(Wrappers.<RuntimeAgentEntity>lambdaQuery()
                .and(query -> query.eq(RuntimeAgentEntity::getId, lookup)
                        .or().eq(RuntimeAgentEntity::getKeySlug, lookup))
                .last("LIMIT 1"));
        return agent == null || !StringUtils.hasText(agent.getId()) ? lookup : agent.getId();
    }

    private String upper(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : null;
    }

    private String trim(String value) {
        return value == null ? null : value.trim();
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) return value.trim();
        }
        return null;
    }

    private String defaultTraceItemKey(String traceId) {
        String compact = traceId.replaceAll("[^A-Za-z0-9_-]", "");
        if (compact.length() > 48) compact = compact.substring(compact.length() - 48);
        return "trace-" + compact;
    }

    private record PreparedItem(
            String itemKey,
            String message,
            Map<String, Object> input,
            Map<String, Object> expected,
            Map<String, Object> metadata,
            Object tags,
            String sourceTraceId,
            boolean enabled,
            int ordinalNo,
            Map<String, Object> canonical) {
    }
}
