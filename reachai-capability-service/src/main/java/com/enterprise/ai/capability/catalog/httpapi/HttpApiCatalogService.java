package com.enterprise.ai.capability.catalog.httpapi;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectMapper;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
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
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

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
            + "OR b.source_contract_json IS NULL OR TRIM(b.source_contract_json) = '' "
            + "OR JSON_VALID(b.source_contract_json) = 0 "
            + "OR SHA2(b.source_contract_json, 256) <> b.source_contract_hash "
            + "OR CASE WHEN JSON_VALID(b.source_contract_json) = 1 "
            + "THEN JSON_TYPE(b.source_contract_json) <> 'OBJECT' ELSE FALSE END))";

    private final HttpApiAssetMapper assets;
    private final HttpApiSourceBindingMapper bindings;
    private final HttpApiInventoryStateMapper inventories;
    private final HttpApiInventoryMemberMapper members;
    private final HttpApiAcceptanceMapper acceptances;
    private final ScanProjectMapper projects;
    private final ObjectMapper json;
    private final HttpApiContractCanonicalizer contracts;
    private com.enterprise.ai.capability.externalapi.ApiMarketHttpApiBindingService marketBindings;
    private org.springframework.beans.factory.ObjectProvider<com.enterprise.ai.capability.catalog.semantic.CapabilitySemanticDocumentStore> adoption;

    @org.springframework.beans.factory.annotation.Autowired
    public void setAdoption(org.springframework.beans.factory.ObjectProvider<com.enterprise.ai.capability.catalog.semantic.CapabilitySemanticDocumentStore> adoption) {
        this.adoption = adoption;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setMarketBindings(com.enterprise.ai.capability.externalapi.ApiMarketHttpApiBindingService marketBindings) {
        this.marketBindings = marketBindings;
    }

    public ApiPage list(long projectId, String environment, String keyword, String method,
                        String sourceStatus, int current, int size) {
        return list(projectId, environment, keyword, method, sourceStatus, current, size, null);
    }

    public ApiPage list(long projectId, String environment, String keyword, String method,
                        String sourceStatus, int current, int size, List<Long> selectedIds) {
        return list(projectId, environment, keyword, method, sourceStatus, current, size, selectedIds, null);
    }

    public ApiPage listFiltered(long projectId, String environment, String keyword, String method,
                               String sourceStatus, int current, int size,
                               com.enterprise.ai.capability.catalog.enrichment.CatalogGroupSelection.Filter filter) {
        return list(projectId, environment, keyword, method, sourceStatus, current, size, null, filter);
    }

    private ApiPage list(long projectId, String environment, String keyword, String method,
                         String sourceStatus, int current, int size, List<Long> selectedIds,
                         com.enterprise.ai.capability.catalog.enrichment.CatalogGroupSelection.Filter filter) {
        if (projectId <= 0) throw new IllegalArgumentException("projectId must be positive");
        int page = Math.max(1, current);
        int pageSize = Math.min(100, Math.max(1, size));
        var query = Wrappers.<HttpApiAssetEntity>lambdaQuery()
                .eq(HttpApiAssetEntity::getProjectId, projectId)
                .eq(StringUtils.hasText(environment), HttpApiAssetEntity::getEnvironment, trim(environment))
                .eq(StringUtils.hasText(method), HttpApiAssetEntity::getHttpMethod, upper(method));
        if (selectedIds != null) query.in(HttpApiAssetEntity::getId, selectedIds.isEmpty() ? List.of(-1L) : selectedIds);
        if (filter != null) filter.apply(query, "capability_http_api_asset", "HTTP_API");
        String term = trim(keyword).toLowerCase(java.util.Locale.ROOT);
        if (!term.isEmpty()) {
            String escaped = term.replace("!", "!!").replace("%", "!%").replace("_", "!_");
            query.apply("LOWER(CONCAT(http_method, ' ', route_template, ' ', qualified_name)) "
                    + "LIKE {0} ESCAPE '!'", "%" + escaped + "%");
        }
        String requestedStatus = upper(sourceStatus);
        if (!requestedStatus.isEmpty() && marketBindings != null && assets.selectCount(
                Wrappers.<HttpApiAssetEntity>lambdaQuery().eq(HttpApiAssetEntity::getProjectId, projectId)
                        .apply("EXISTS (SELECT 1 FROM capability_http_api_source_binding market "
                                + "WHERE market.asset_id = capability_http_api_asset.id "
                                + "AND market.source_kind = 'API_MARKET_OPERATION' AND market.status <> 'REMOVED')")) > 0) {
            // A market selection is confirmed by its current owner facts, not scanner inventory.
            // Filter the authoritative summaries before counting/paging; never label READY as accepted.
            // Owner freshness may latch an invalid market selection. Scan bounded batches so exact
            // status totals do not materialize the entire project or perform a query per row.
            query.orderByDesc(HttpApiAssetEntity::getUpdatedAt).orderByDesc(HttpApiAssetEntity::getId);
            var confirmation = new com.enterprise.ai.capability.externalapi.ApiMarketHttpApiBindingService.ConfirmationSession();
            long offset = (long)(page - 1) * pageSize;
            long matched = 0;
            long scanned = 0;
            List<ApiSummary> summaries = new ArrayList<>();
            while (true) {
                List<HttpApiAssetEntity> batch = assets.selectList(query.last("LIMIT 100 OFFSET " + scanned));
                if (batch.isEmpty()) break;
                CatalogFacts facts = readFacts(batch, confirmation);
                for (HttpApiAssetEntity row : batch) {
                    summaries.add(detail(row, facts).summary());
                }
                scanned += batch.size();
                if (batch.size() < 100) break;
            }
            List<ApiSummary> records = new ArrayList<>(pageSize);
            // A later broken operation must invalidate earlier rows from that integration too.
            // Retain only light summaries; contracts and owner tables are read once per batch.
            for (ApiSummary observed : summaries) {
                ApiSummary summary = observed.sourceConfirmed() && confirmation.invalidated(observed.id())
                        ? unconfirmed(observed) : observed;
                if (!requestedStatus.equals(summary.sourceStatus())) continue;
                if (matched >= offset && records.size() < pageSize) records.add(summary);
                matched++;
            }
            return new ApiPage(List.copyOf(records), matched, page, pageSize,
                    (matched + pageSize - 1) / pageSize);
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
        CatalogFacts facts = readFacts(rows);
        for (HttpApiAssetEntity row : rows) records.add(detail(row, facts).summary());
        return new ApiPage(records, total, page, pageSize,
                (total + pageSize - 1) / pageSize);
    }

    public ApiDetail detail(long id) {
        HttpApiAssetEntity asset = assets.selectById(id);
        return asset == null ? null : detail(asset);
    }

    public ApiIdentity identity(long id) {
        HttpApiAssetEntity asset = assets.selectOne(Wrappers.<HttpApiAssetEntity>lambdaQuery()
                .select(HttpApiAssetEntity::getId, HttpApiAssetEntity::getProjectId, HttpApiAssetEntity::getProjectCode,
                        HttpApiAssetEntity::getQualifiedName, HttpApiAssetEntity::getEnvironment)
                .eq(HttpApiAssetEntity::getId, id));
        if (asset == null || asset.getProjectId() == null) return null;
        var project = projects.selectOne(Wrappers.<ScanProjectEntity>lambdaQuery()
                .select(ScanProjectEntity::getId, ScanProjectEntity::getProjectCode)
                .eq(ScanProjectEntity::getId, asset.getProjectId()));
        // Compare in Java: a case-insensitive database collation must not attest a stale
        // project code that differs from the canonical scope used by Control authorization.
        return project == null || !Objects.equals(project.getProjectCode(), asset.getProjectCode()) ? null
                : new ApiIdentity(asset.getId(), project.getId(), project.getProjectCode(),
                    asset.getQualifiedName(), asset.getEnvironment());
    }

    /** Shared typed owner facts for graph/semantic reads; callers supply rows from the owner table. */
    public Map<Long, ApiDetail> details(List<HttpApiAssetEntity> ownerRows) {
        CatalogFacts facts = readFacts(ownerRows);
        Map<Long, ApiDetail> result = new HashMap<>();
        for (HttpApiAssetEntity row : ownerRows) result.put(row.getId(), detail(row, facts));
        return Map.copyOf(result);
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
        if (adoption != null) adoption.getObject().adoptAcceptedOwner(locked.getProjectId(), "HTTP_API", locked.getId());
        return detail(id);
    }

    private ApiDetail detail(HttpApiAssetEntity asset) {
        return detail(asset, readFacts(List.of(asset)));
    }

    private ApiSummary unconfirmed(ApiSummary summary) {
        return new ApiSummary(summary.id(), summary.qualifiedName(), summary.projectId(), summary.projectCode(),
                summary.environment(), summary.httpMethod(), summary.routeTemplate(), "SOURCE_UNCONFIRMED", false,
                "市场接入已停用或项目/环境不一致，请核对接入选择", summary.candidateContractHash(),
                summary.acceptedContractHash(), summary.sourceSetRevision(), summary.activeSourceCount(),
                summary.sourceKinds(), summary.acceptedBy(), summary.acceptedAt());
    }

    /** Request-local owner facts. Execution/acceptance still re-read immediately at their boundary. */
    private CatalogFacts readFacts(List<HttpApiAssetEntity> rows) {
        return readFacts(rows, new com.enterprise.ai.capability.externalapi.ApiMarketHttpApiBindingService.ConfirmationSession());
    }

    private CatalogFacts readFacts(List<HttpApiAssetEntity> rows,
                                  com.enterprise.ai.capability.externalapi.ApiMarketHttpApiBindingService.ConfirmationSession confirmation) {
        if (rows.isEmpty()) return new CatalogFacts(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        List<Long> assetIds = rows.stream().map(HttpApiAssetEntity::getId).distinct().toList();
        List<Long> projectIds = rows.stream().map(HttpApiAssetEntity::getProjectId).distinct().toList();
        List<HttpApiSourceBindingEntity> sourceRows = bindings.selectList(Wrappers.<HttpApiSourceBindingEntity>lambdaQuery()
                .in(HttpApiSourceBindingEntity::getAssetId, assetIds).orderByAsc(HttpApiSourceBindingEntity::getId));
        Map<Long, ScanProjectEntity> projectRows = projects.selectBatchIds(projectIds).stream()
                .collect(Collectors.toMap(ScanProjectEntity::getId, Function.identity()));
        List<HttpApiSourceBindingEntity> active = sourceRows.stream()
                .filter(row -> !HttpApiSourceBindingStatus.REMOVED.name().equals(row.getStatus())).toList();
        Map<InventoryScope, HttpApiInventoryStateEntity> inventoryRows = active.isEmpty() ? Map.of()
                : inventories.selectList(Wrappers.<HttpApiInventoryStateEntity>lambdaQuery()
                        .in(HttpApiInventoryStateEntity::getProjectId, projectIds)
                        .in(HttpApiInventoryStateEntity::getEnvironment,
                                active.stream().map(HttpApiSourceBindingEntity::getEnvironment).distinct().toList())
                        .in(HttpApiInventoryStateEntity::getSourceKind,
                                active.stream().map(HttpApiSourceBindingEntity::getSourceKind).distinct().toList()))
                        .stream().collect(Collectors.toMap(InventoryScope::of, Function.identity()));
        Map<Long, HttpApiInventoryMemberEntity> memberRows = active.isEmpty() ? Map.of()
                : members.selectList(Wrappers.<HttpApiInventoryMemberEntity>lambdaQuery()
                        .in(HttpApiInventoryMemberEntity::getBindingId,
                                active.stream().map(HttpApiSourceBindingEntity::getId).toList()))
                        .stream().collect(Collectors.toMap(HttpApiInventoryMemberEntity::getBindingId, Function.identity()));
        Map<Long, HttpApiAcceptanceEntity> latest = acceptances.selectLatestByAssetIds(assetIds).stream()
                .collect(Collectors.toMap(HttpApiAcceptanceEntity::getAssetId, Function.identity()));
        Map<Long, String> marketReasons = marketBindings == null ? Map.of()
                : marketBindings.confirmationReasons(rows, active, projectRows, confirmation);
        return new CatalogFacts(sourceRows.stream().collect(Collectors.groupingBy(HttpApiSourceBindingEntity::getAssetId)),
                projectRows, inventoryRows, memberRows, latest, marketReasons);
    }

    private ApiDetail detail(HttpApiAssetEntity asset, CatalogFacts facts) {
        List<HttpApiSourceBindingEntity> sourceRows = facts.sources().getOrDefault(asset.getId(), List.of());
        ScanProjectEntity project = facts.projects().get(asset.getProjectId());
        List<SourceView> sources = new ArrayList<>();
        List<HttpApiSourceBindingEntity> active = new ArrayList<>();
        Set<String> hashes = new LinkedHashSet<>();
        // A source pin describes current semantic facts, not the UUID of the latest observation.
        // Structured hash material also avoids ambiguity in source keys containing ':' or '|'.
        var revisionMaterial = json.createObjectNode().put("format", "http-source-facts-v2")
                .put("assetId", asset.getId()).put("projectId", asset.getProjectId())
                .put("projectCode", asset.getProjectCode()).put("environment", asset.getEnvironment())
                .put("qualifiedName", asset.getQualifiedName()).put("identityHash", asset.getIdentityHash())
                .put("httpMethod", asset.getHttpMethod()).put("routeTemplate", asset.getRouteTemplate());
        var revisionSources = revisionMaterial.putArray("activeSources");
        String reason = null;
        for (HttpApiSourceBindingEntity binding : sourceRows) {
            if (HttpApiSourceBindingStatus.REMOVED.name().equals(binding.getStatus())) {
                sources.add(source(binding, false, "来源已移除", null, null));
                continue;
            }
            active.add(binding);
            hashes.add(binding.getSourceContractHash());
            HttpApiInventoryStateEntity inventory = facts.inventories().get(InventoryScope.of(binding));
            HttpApiInventoryMemberEntity member = facts.members().get(binding.getId());
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
                        : facts.marketReasons().getOrDefault(binding.getId(), "市场来源确认事实缺失，请刷新后重试");
                confirmed = sourceReason == null;
            }
            if (sourceContract(binding) == null) {
                confirmed = false;
                sourceReason = "来源契约缺失、损坏或与指纹不一致，请重新扫描或同步";
            }
            if (reason == null && sourceReason != null) reason = sourceReason;
            sources.add(source(binding, confirmed, sourceReason, inventory, member));
            revisionSources.addObject().put("bindingId", binding.getId()).put("assetId", binding.getAssetId())
                    .put("projectId", binding.getProjectId()).put("projectCode", binding.getProjectCode())
                    .put("environment", binding.getEnvironment()).put("sourceKind", binding.getSourceKind())
                    .put("sourceKey", binding.getSourceKey()).put("sourceLocation", binding.getSourceLocation())
                    .put("contractHash", binding.getSourceContractHash()).put("confirmed", confirmed)
                    // Starter's revision is syncId. Market/scanner revisions protect real owner content/selection.
                    .put("sourceRevision", HttpApiSourceKind.STARTER_MVC.name().equals(binding.getSourceKind())
                            ? null : binding.getSourceRevision());
        }
        boolean comparable = !active.isEmpty() && hashes.size() == 1;
        if (active.isEmpty()) reason = "当前没有活动来源，请重新扫描或同步";
        else if (hashes.size() > 1) reason = "活动来源契约不一致，请先处理来源冲突";
        boolean confirmed = comparable && reason == null;
        String candidateHash = comparable ? active.get(0).getSourceContractHash() : null;
        JsonNode contract = comparable ? sourceContract(active.get(0)) : null;
        if (comparable && contract == null) {
            confirmed = false;
            reason = "来源契约无法读取，请重新扫描或同步";
        }
        String sourceStatus = active.isEmpty() ? "SOURCE_MISSING"
                : hashes.size() > 1 ? "CONFLICT"
                : !confirmed ? "SOURCE_UNCONFIRMED"
                : !StringUtils.hasText(asset.getAcceptedContractHash()) ? "DISCOVERED"
                : Objects.equals(asset.getAcceptedContractHash(), candidateHash) ? "ACCEPTED" : "CONTRACT_DRIFT";
        HttpApiAcceptanceEntity latest = facts.acceptances().get(asset.getId());
        ApiSummary summary = new ApiSummary(asset.getId(), asset.getQualifiedName(), asset.getProjectId(),
                asset.getProjectCode(), asset.getEnvironment(), asset.getHttpMethod(), asset.getRouteTemplate(),
                sourceStatus, confirmed, reason, candidateHash, asset.getAcceptedContractHash(),
                digest(revisionMaterial.toString()), active.size(),
                sources.stream().filter(item -> !"REMOVED".equals(item.status()))
                        .map(SourceView::sourceKind).distinct().toList(),
                latest == null ? null : latest.getAcceptedBy(), latest == null ? null : latest.getAcceptedAt());
        JsonNode acceptedContract = null;
        if (StringUtils.hasText(asset.getAcceptedContractHash())) {
            acceptedContract = contracts.readStoredContract(asset,
                    asset.getAcceptedContractHash(), asset.getAcceptedContractJson());
            if (acceptedContract == null) throw new IllegalStateException("API 接纳契约损坏或与所属资产身份不一致");
        }
        return new ApiDetail(summary, contract, acceptedContract, List.copyOf(sources));
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
                member == null ? null : member.getObservedAt(), sourceContract(binding));
    }

    private HttpApiAcceptanceEntity latestAcceptance(long assetId) {
        return acceptances.selectOne(Wrappers.<HttpApiAcceptanceEntity>lambdaQuery()
                .eq(HttpApiAcceptanceEntity::getAssetId, assetId)
                .orderByDesc(HttpApiAcceptanceEntity::getId).last("LIMIT 1"));
    }

    private JsonNode parse(String value) {
        if (!StringUtils.hasText(value)) return null;
        try { return json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(value); }
        catch (Exception invalid) { return null; }
    }

    private JsonNode sourceContract(HttpApiSourceBindingEntity binding) {
        String raw = binding.getSourceContractJson();
        JsonNode contract = parse(raw);
        return contract != null && contract.isObject() && Objects.equals(binding.getSourceContractHash(), digest(raw))
                ? contract : null;
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

    private record CatalogFacts(Map<Long, List<HttpApiSourceBindingEntity>> sources,
                                Map<Long, ScanProjectEntity> projects,
                                Map<InventoryScope, HttpApiInventoryStateEntity> inventories,
                                Map<Long, HttpApiInventoryMemberEntity> members,
                                Map<Long, HttpApiAcceptanceEntity> acceptances,
                                Map<Long, String> marketReasons) { }
    private record InventoryScope(Long projectId, String projectCode, String environment, String sourceKind) {
        static InventoryScope of(HttpApiSourceBindingEntity row) {
            return new InventoryScope(row.getProjectId(), row.getProjectCode(), row.getEnvironment(), row.getSourceKind());
        }
        static InventoryScope of(HttpApiInventoryStateEntity row) {
            return new InventoryScope(row.getProjectId(), row.getProjectCode(), row.getEnvironment(), row.getSourceKind());
        }
    }

    public record ApiPage(List<ApiSummary> records, long total, long current, long size, long pages) { }
    public record ApiIdentity(Long id, Long projectId, String projectCode, String qualifiedName, String environment) { }
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
