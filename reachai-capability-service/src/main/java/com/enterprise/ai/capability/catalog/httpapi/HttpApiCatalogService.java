package com.enterprise.ai.capability.catalog.httpapi;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectMapper;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Capability-owned API catalog and current-source acceptance boundary. */
@Service
@RequiredArgsConstructor
public class HttpApiCatalogService {
    // The aggregate asset status is maintained under the asset lock. Inventory freshness is
    // intentionally separate: it can change without rewriting every asset in a project.
    private static final String UNCONFIRMED_SOURCE_SQL = "EXISTS (SELECT 1 FROM capability_http_api_source_binding b "
            + "LEFT JOIN capability_http_api_inventory_state i ON i.project_id = b.project_id "
            + "AND i.project_code = b.project_code AND i.environment = b.environment "
            + "AND i.source_kind = b.source_kind "
            + "LEFT JOIN capability_http_api_inventory_member m ON m.binding_id = b.id "
            + "LEFT JOIN capability_scan_project p ON p.id = b.project_id "
            + "WHERE b.asset_id = capability_http_api_asset.id AND b.status <> 'REMOVED' "
            + "AND (i.id IS NULL OR i.supported IS NULL OR i.supported <> 1 "
            + "OR m.id IS NULL OR i.inventory_token <> m.inventory_token "
            + "OR (b.source_kind IN ('OPENAPI_SCAN', 'CONTROLLER_SCAN') "
            + "AND (p.id IS NULL OR LOWER(p.status) = 'failed')) "
            + "OR b.source_contract_json IS NULL OR TRIM(b.source_contract_json) = ''))";

    private final HttpApiAssetMapper assets;
    private final HttpApiSourceBindingMapper bindings;
    private final HttpApiInventoryStateMapper inventories;
    private final HttpApiInventoryMemberMapper members;
    private final HttpApiAcceptanceMapper acceptances;
    private final ScanProjectMapper projects;
    private final ObjectMapper json;
    private com.enterprise.ai.capability.externalapi.ApiMarketHttpApiBindingService marketBindings;

    @org.springframework.beans.factory.annotation.Autowired
    public void setMarketBindings(com.enterprise.ai.capability.externalapi.ApiMarketHttpApiBindingService marketBindings) {
        this.marketBindings = marketBindings;
    }

    public ApiPage list(long projectId, String environment, String keyword, String method,
                        String sourceStatus, int current, int size) {
        if (projectId <= 0) throw new IllegalArgumentException("projectId must be positive");
        int page = Math.max(1, current);
        int pageSize = Math.min(100, Math.max(1, size));
        var query = Wrappers.<HttpApiAssetEntity>lambdaQuery()
                .eq(HttpApiAssetEntity::getProjectId, projectId)
                .eq(StringUtils.hasText(environment), HttpApiAssetEntity::getEnvironment, trim(environment))
                .eq(StringUtils.hasText(method), HttpApiAssetEntity::getHttpMethod, upper(method));
        String term = trim(keyword).toLowerCase(java.util.Locale.ROOT);
        if (!term.isEmpty()) {
            String escaped = term.replace("!", "!!").replace("%", "!%").replace("_", "!_");
            query.apply("LOWER(CONCAT(http_method, ' ', route_template, ' ', qualified_name)) "
                    + "LIKE {0} ESCAPE '!'", "%" + escaped + "%");
        }
        String requestedStatus = upper(sourceStatus);
        if (!requestedStatus.isEmpty() && marketBindings != null && assets.selectCount(
                Wrappers.<HttpApiAssetEntity>lambdaQuery().eq(HttpApiAssetEntity::getProjectId, projectId)
                        .like(HttpApiAssetEntity::getQualifiedName, ":market:")) > 0) {
            // A market selection is confirmed by its current owner facts, not scanner inventory.
            // Filter the authoritative summaries before counting/paging; never label READY as accepted.
            List<ApiSummary> matching = assets.selectList(query.orderByDesc(HttpApiAssetEntity::getUpdatedAt)
                    .orderByDesc(HttpApiAssetEntity::getId)).stream().map(row -> detail(row).summary())
                    .filter(row -> requestedStatus.equals(row.sourceStatus())).toList();
            long offset = (long)(page - 1) * pageSize;
            List<ApiSummary> records = offset >= matching.size() ? List.of()
                    : matching.subList((int)offset, (int)Math.min(matching.size(), offset + pageSize));
            return new ApiPage(records, matching.size(), page, pageSize, (matching.size() + pageSize - 1) / pageSize);
        }
        if (!requestedStatus.isEmpty()) {
            if ("SOURCE_UNCONFIRMED".equals(requestedStatus)) {
                query.in(HttpApiAssetEntity::getStatus, HttpApiAssetStatus.DISCOVERED.name(),
                        HttpApiAssetStatus.ACCEPTED.name(), HttpApiAssetStatus.CONTRACT_DRIFT.name())
                        .apply(UNCONFIRMED_SOURCE_SQL);
            } else {
                query.eq(HttpApiAssetEntity::getStatus, requestedStatus);
                if (!"SOURCE_MISSING".equals(requestedStatus) && !"CONFLICT".equals(requestedStatus)) {
                    query.apply("NOT " + UNCONFIRMED_SOURCE_SQL);
                }
            }
        }
        long total = assets.selectCount(query);
        long offset = (long) (page - 1) * pageSize;
        List<HttpApiAssetEntity> rows = total <= offset ? List.of() : assets.selectList(query
                .orderByDesc(HttpApiAssetEntity::getUpdatedAt)
                .orderByDesc(HttpApiAssetEntity::getId)
                .last("LIMIT " + pageSize + " OFFSET " + offset));
        List<ApiSummary> records = new ArrayList<>(rows.size());
        for (HttpApiAssetEntity row : rows) records.add(detail(row).summary());
        return new ApiPage(records, total, page, pageSize,
                (total + pageSize - 1) / pageSize);
    }

    public ApiDetail detail(long id) {
        HttpApiAssetEntity asset = assets.selectById(id);
        return asset == null ? null : detail(asset);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ApiDetail accept(long id, String expectedSourceSetRevision, String actor) {
        if (!StringUtils.hasText(actor) || actor.length() > 128
                || !StringUtils.hasText(expectedSourceSetRevision)
                || !expectedSourceSetRevision.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("actor and observed source revision are required");
        }
        HttpApiAssetEntity snapshot = assets.selectById(id);
        if (snapshot == null) return null;
        // Scanner and Starter write paths already serialize on the project before locking assets.
        projects.lockCapabilityChanges(snapshot.getProjectId());
        HttpApiAssetEntity locked = assets.selectByIdForUpdate(id);
        if (locked == null || !Objects.equals(snapshot.getProjectId(), locked.getProjectId())) {
            throw new Conflict("HTTP_API_PROJECT_CHANGED", "API 所属项目已变化，请刷新后重试");
        }
        ApiDetail observed = detail(locked);
        if (!expectedSourceSetRevision.equals(observed.summary().sourceSetRevision())) {
            throw new Conflict("HTTP_API_SOURCE_REVISION_CHANGED", "API 来源已变化，请刷新后比较当前契约");
        }
        if (!observed.summary().sourceConfirmed() || observed.contract() == null
                || !StringUtils.hasText(observed.summary().candidateContractHash())) {
            throw new Conflict("HTTP_API_SOURCE_UNCONFIRMED", observed.summary().sourceReason());
        }
        HttpApiAcceptanceEntity latest = latestAcceptance(id);
        if (!Objects.equals(locked.getAcceptedContractHash(), observed.summary().candidateContractHash())
                || latest == null || !expectedSourceSetRevision.equals(latest.getSourceSetRevision())) {
            HttpApiAcceptanceEntity accepted = new HttpApiAcceptanceEntity();
            accepted.setAssetId(id);
            accepted.setSourceSetRevision(expectedSourceSetRevision);
            accepted.setBeforeContractHash(locked.getAcceptedContractHash());
            accepted.setAcceptedContractHash(observed.summary().candidateContractHash());
            accepted.setAcceptedBy(actor.trim());
            accepted.setAcceptedAt(LocalDateTime.now().withNano(0));
            acceptances.insert(accepted);
            locked.setAcceptedContractHash(observed.summary().candidateContractHash());
            // Preserve the exact canonical bytes whose digest was accepted. JsonNode.toString()
            // is not itself the scanner's hash material and may reorder/normalize numbers.
            locked.setAcceptedContractJson(activeSourceContractJson(locked.getId(), observed.summary().candidateContractHash()));
            locked.setStatus(HttpApiAssetStatus.ACCEPTED.name());
            locked.setUpdatedAt(accepted.getAcceptedAt());
            assets.updateById(locked);
        }
        return detail(id);
    }

    private ApiDetail detail(HttpApiAssetEntity asset) {
        List<HttpApiSourceBindingEntity> sourceRows = bindings.selectList(
                Wrappers.<HttpApiSourceBindingEntity>lambdaQuery()
                        .eq(HttpApiSourceBindingEntity::getAssetId, asset.getId())
                        .orderByAsc(HttpApiSourceBindingEntity::getId));
        ScanProjectEntity project = projects.selectById(asset.getProjectId());
        List<SourceView> sources = new ArrayList<>();
        List<HttpApiSourceBindingEntity> active = new ArrayList<>();
        Set<String> hashes = new LinkedHashSet<>();
        StringBuilder revisionMaterial = new StringBuilder(asset.getQualifiedName());
        String reason = null;
        for (HttpApiSourceBindingEntity binding : sourceRows) {
            if (HttpApiSourceBindingStatus.REMOVED.name().equals(binding.getStatus())) {
                sources.add(source(binding, false, "来源已移除", null, null));
                continue;
            }
            active.add(binding);
            hashes.add(binding.getSourceContractHash());
            HttpApiInventoryStateEntity inventory = inventory(binding);
            HttpApiInventoryMemberEntity member = members.selectOne(
                    Wrappers.<HttpApiInventoryMemberEntity>lambdaQuery()
                            .eq(HttpApiInventoryMemberEntity::getBindingId, binding.getId()).last("LIMIT 1"));
            boolean scanFailed = isScan(binding.getSourceKind()) && (project == null
                    || "failed".equalsIgnoreCase(project.getStatus()));
            boolean confirmed = !scanFailed && inventory != null && Boolean.TRUE.equals(inventory.getSupported())
                    && member != null && Objects.equals(inventory.getInventoryToken(), member.getInventoryToken());
            String sourceReason = confirmed ? null : scanFailed ? "最近一次扫描失败，请重新扫描项目"
                    : inventory == null ? "来源尚未经过当前清单确认，请重新扫描或同步"
                    : StringUtils.hasText(inventory.getReason()) ? inventory.getReason()
                    : "本次清单未确认该 API，请重新扫描或同步";
            if (HttpApiSourceKind.API_MARKET_OPERATION.name().equals(binding.getSourceKind())) {
                sourceReason = marketBindings == null ? "市场来源确认服务不可用，请稍后重试"
                        : marketBindings.confirmationReason(asset, binding, project);
                confirmed = sourceReason == null;
            }
            if (reason == null && sourceReason != null) reason = sourceReason;
            sources.add(source(binding, confirmed, sourceReason, inventory, member));
            revisionMaterial.append('|').append(binding.getId()).append(':').append(binding.getSourceKind())
                    .append(':').append(binding.getSourceKey()).append(':').append(binding.getSourceContractHash())
                    .append(':').append(binding.getSourceRevision()).append(':')
                    .append(inventory == null ? "" : inventory.getInventoryToken()).append(':')
                    .append(member == null ? "" : member.getInventoryToken());
        }
        boolean comparable = !active.isEmpty() && hashes.size() == 1;
        if (active.isEmpty()) reason = "当前没有活动来源，请重新扫描或同步";
        else if (hashes.size() > 1) reason = "活动来源契约不一致，请先处理来源冲突";
        boolean confirmed = comparable && reason == null;
        String candidateHash = comparable ? active.get(0).getSourceContractHash() : null;
        JsonNode contract = comparable ? parse(active.get(0).getSourceContractJson()) : null;
        if (comparable && contract == null) {
            confirmed = false;
            reason = "来源契约无法读取，请重新扫描或同步";
        }
        String sourceStatus = active.isEmpty() ? "SOURCE_MISSING"
                : hashes.size() > 1 ? "CONFLICT"
                : !confirmed ? "SOURCE_UNCONFIRMED"
                : !StringUtils.hasText(asset.getAcceptedContractHash()) ? "DISCOVERED"
                : Objects.equals(asset.getAcceptedContractHash(), candidateHash) ? "ACCEPTED" : "CONTRACT_DRIFT";
        HttpApiAcceptanceEntity latest = latestAcceptance(asset.getId());
        ApiSummary summary = new ApiSummary(asset.getId(), asset.getQualifiedName(), asset.getProjectId(),
                asset.getProjectCode(), asset.getEnvironment(), asset.getHttpMethod(), asset.getRouteTemplate(),
                sourceStatus, confirmed, reason, candidateHash, asset.getAcceptedContractHash(),
                digest(revisionMaterial.toString()), active.size(),
                sources.stream().filter(item -> !"REMOVED".equals(item.status()))
                        .map(SourceView::sourceKind).distinct().toList(),
                latest == null ? null : latest.getAcceptedBy(), latest == null ? null : latest.getAcceptedAt());
        return new ApiDetail(summary, contract, parse(asset.getAcceptedContractJson()), List.copyOf(sources));
    }

    private String activeSourceContractJson(long assetId, String contractHash) {
        List<HttpApiSourceBindingEntity> rows = bindings.selectList(
                Wrappers.<HttpApiSourceBindingEntity>lambdaQuery()
                        .eq(HttpApiSourceBindingEntity::getAssetId, assetId)
                        .ne(HttpApiSourceBindingEntity::getStatus, HttpApiSourceBindingStatus.REMOVED.name())
                        .orderByAsc(HttpApiSourceBindingEntity::getId));
        if (rows.isEmpty() || rows.stream().anyMatch(row ->
                !Objects.equals(contractHash, row.getSourceContractHash())
                        || !StringUtils.hasText(row.getSourceContractJson()))) {
            throw new Conflict("HTTP_API_SOURCE_CHANGED", "API 来源契约已变化，请刷新后重试");
        }
        return rows.get(0).getSourceContractJson();
    }

    private SourceView source(HttpApiSourceBindingEntity binding, boolean confirmed, String reason,
                              HttpApiInventoryStateEntity inventory, HttpApiInventoryMemberEntity member) {
        return new SourceView(binding.getId(), binding.getSourceKind(), binding.getSourceKey(),
                binding.getSourceLocation(), binding.getSourceRevision(), binding.getSourceContractHash(),
                binding.getStatus(), binding.getObservedAt(), confirmed, reason,
                inventory == null ? null : inventory.getObservedAt(),
                inventory != null && Boolean.TRUE.equals(inventory.getComplete()),
                member == null ? null : member.getObservedAt(), parse(binding.getSourceContractJson()));
    }

    private HttpApiInventoryStateEntity inventory(HttpApiSourceBindingEntity binding) {
        return inventories.selectOne(Wrappers.<HttpApiInventoryStateEntity>lambdaQuery()
                .eq(HttpApiInventoryStateEntity::getProjectId, binding.getProjectId())
                .eq(HttpApiInventoryStateEntity::getProjectCode, binding.getProjectCode())
                .eq(HttpApiInventoryStateEntity::getEnvironment, binding.getEnvironment())
                .eq(HttpApiInventoryStateEntity::getSourceKind, binding.getSourceKind()).last("LIMIT 1"));
    }

    private HttpApiAcceptanceEntity latestAcceptance(long assetId) {
        return acceptances.selectOne(Wrappers.<HttpApiAcceptanceEntity>lambdaQuery()
                .eq(HttpApiAcceptanceEntity::getAssetId, assetId)
                .orderByDesc(HttpApiAcceptanceEntity::getId).last("LIMIT 1"));
    }

    private JsonNode parse(String value) {
        if (!StringUtils.hasText(value)) return null;
        try { return json.readTree(value); }
        catch (Exception invalid) { return null; }
    }

    private boolean isScan(String kind) {
        return HttpApiSourceKind.CONTROLLER_SCAN.name().equals(kind)
                || HttpApiSourceKind.OPENAPI_SCAN.name().equals(kind);
    }

    private String digest(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception unavailable) {
            throw new IllegalStateException("SHA-256 unavailable", unavailable);
        }
    }

    private String trim(String value) { return value == null ? "" : value.trim(); }
    private String upper(String value) { return trim(value).toUpperCase(java.util.Locale.ROOT); }

    public record ApiPage(List<ApiSummary> records, long total, long current, long size, long pages) { }
    public record ApiSummary(Long id, String qualifiedName, Long projectId, String projectCode,
                             String environment, String httpMethod, String routeTemplate,
                             String sourceStatus, boolean sourceConfirmed, String sourceReason,
                             String candidateContractHash, String acceptedContractHash,
                             String sourceSetRevision, int activeSourceCount, List<String> sourceKinds,
                             String acceptedBy, @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDateTime acceptedAt) { }
    public record ApiDetail(ApiSummary summary, JsonNode contract, JsonNode acceptedContract,
                            List<SourceView> sources) { }
    public record SourceView(Long id, String sourceKind, String sourceKey, String sourceLocation,
                             String sourceRevision, String sourceContractHash, String status,
                             @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDateTime observedAt, boolean confirmedInLatestInventory,
                             String reason, @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDateTime latestInventoryAt, boolean inventoryComplete,
                             @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDateTime confirmedAt, JsonNode contract) { }

    public static class Conflict extends RuntimeException {
        private final String code;
        public Conflict(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }
}
