package com.enterprise.ai.capability.externalapi;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.capability.catalog.httpapi.*;
import com.enterprise.ai.common.capability.HttpApiFirstCallPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Server-only catalog adapter and current-selection guard. No Runtime tables or outbound HTTP. */
@Service
@RequiredArgsConstructor
public class ApiMarketHttpApiBindingService {
    private final HttpApiAssetService intake;
    private final HttpApiAssetMapper assets;
    private final HttpApiSourceBindingMapper sources;
    private final ProjectExternalApiMapper integrations;
    private final ProjectExternalApiOperationMapper selections;
    private final ExternalApiEntryMapper entries;
    private final ExternalApiVersionMapper versions;
    private final ExternalApiOperationMapper operations;
    private final HttpApiContractCanonicalizer canonicalizer;
    private final ObjectMapper json;

    public HttpApiAssetService.ObserveRequest prepare(ScanProjectEntity project, ExternalApiEntryEntity entry,
            ExternalApiVersionEntity version, ExternalApiOperationEntity operation, long selectionRevision) {
        if (selectionRevision <= 0 || project == null || entry == null || version == null || operation == null
                || !Objects.equals(entry.getId(), version.getEntryId())
                || !Objects.equals(version.getId(), operation.getVersionId())
                || !"PUBLISHED".equals(entry.getPublicationStatus()) || !"PUBLISHED".equals(version.getPublicationStatus())
                || !"ACTIVE".equals(operation.getStatus())) throw unsupported("API_MARKET_SELECTION_UNAVAILABLE", "所选目录版本或 Operation 已不可用，请显式重新选择");
        if (version.getSpecHash() == null || !version.getSpecHash().matches("[0-9a-f]{64}")
                || version.getVersionKey() == null || version.getVersionKey().isBlank()
                || operation.getOperationKey() == null || operation.getOperationKey().isBlank()) {
            throw unsupported("API_MARKET_SOURCE_FACTS_REQUIRED", "目录版本必须有明确的版本键、Operation 键和规范摘要，不能构造来源证明");
        }
        if (!"NONE".equals(entry.getAuthType()) || !Boolean.FALSE.equals(operation.getAuthRequired())) {
            throw unsupported("API_MARKET_AUTH_CONTRACT_UNSUPPORTED", "来源尚未提供受支持的结构化认证契约，不能降为无认证；当前仅接入明确无认证的只读 GET");
        }
        if (!"GET".equals(operation.getHttpMethod()) || !Set.of("READ_ONLY", "NONE").contains(operation.getSideEffect())) {
            throw unsupported("API_MARKET_READ_ONLY_GET_REQUIRED", "当前市场受控绑定仅支持明确只读的 GET Operation");
        }
        JsonNode request = schema(operation.getRequestSchemaJson());
        JsonNode response = schema(operation.getResponseSchemaJson());
        if (operation.getResponseContentType() == null || !operation.getResponseContentType().matches("application/(?:[a-zA-Z0-9.+_-]+\\+)?json")) {
            throw unsupported("API_MARKET_RESPONSE_MEDIA_REQUIRED", "来源未明确声明受支持的 JSON 响应媒体类型，不能猜测可执行契约");
        }
        if (operation.getResponseStatus() == null || operation.getResponseStatus() < 200 || operation.getResponseStatus() >= 300) {
            throw unsupported("API_MARKET_RESPONSE_STATUS_REQUIRED", "来源未明确声明成功响应状态，不能猜测为 200");
        }
        if (!"object".equals(request.path("type").asText()) || !request.path("properties").isObject()
                || request.path("properties").size() > 64 || !"object".equals(response.path("type").asText())) {
            throw unsupported("API_MARKET_SCHEMA_UNSUPPORTED", "当前仅支持明确对象 schema、标量 path/query 参数和 JSON 对象返回");
        }
        List<HttpApiOperationContract.Parameter> parameters = new ArrayList<>();
        request.path("properties").fields().forEachRemaining(field -> {
            JsonNode raw = field.getValue();
            String location = raw.path("location").asText().toUpperCase(Locale.ROOT);
            if (!Set.of("PATH", "QUERY").contains(location) || !raw.isObject()) {
                throw unsupported("API_MARKET_PARAMETER_LOCATION_REQUIRED", "来源参数必须明确声明 path/query 位置，不从名称或示例猜测");
            }
            ObjectNode value = ((ObjectNode)raw).deepCopy(); value.remove("location");
            boolean required = "PATH".equals(location) || raw.path("required").asBoolean(false);
            for (JsonNode name : request.path("required")) if (field.getKey().equals(name.asText())) required = true;
            parameters.add(new HttpApiOperationContract.Parameter(field.getKey(), HttpApiParameterLocation.valueOf(location), required, value, List.of()));
        });
        var contract = new HttpApiOperationContract("GET", "", operation.getPath(),
                new HttpApiOperationContract.MappingConditions(List.of(), List.of(operation.getResponseContentType()), List.of()),
                parameters, null, List.of(new HttpApiOperationContract.Response(String.valueOf(operation.getResponseStatus()), response, List.of(operation.getResponseContentType()))),
                new HttpApiOperationContract.AuthenticationRequirement(false, "NONE", List.of(), List.of()), operation.getSideEffect());
        HttpApiServiceScope scope;
        try { scope = new HttpApiServiceScope(project.getId(), project.getProjectCode(), project.getEnvironment(), "api-market:" + entry.getEntryKey()); }
        catch (IllegalArgumentException invalid) { throw unsupported("API_MARKET_SERVICE_SCOPE_INVALID", "项目或市场条目的稳定逻辑服务范围无效"); }
        var canonical = canonicalizer.canonicalize(scope, contract);
        try {
            String reason = HttpApiFirstCallPolicy.unsupportedReason(json.readTree(canonical.contractJson()));
            if (reason != null) throw unsupported("API_MARKET_SCHEMA_UNSUPPORTED", reason);
        } catch (com.fasterxml.jackson.core.JsonProcessingException impossible) { throw new IllegalStateException(impossible); }
        // Version/operation keys and explicit selection revision affect source freshness, never stable identity.
        String revision = digest(version.getVersionKey() + "|" + operation.getOperationKey() + "|"
                + version.getSpecHash() + "|" + canonical.contractHash() + "|" + selectionRevision);
        return new HttpApiAssetService.ObserveRequest(scope, HttpApiSourceKind.API_MARKET_OPERATION,
                "market:" + entry.getEntryKey() + ":" + canonical.identityHash(),
                "api-market/" + entry.getEntryKey() + "/" + version.getVersionKey() + "/" + operation.getOperationKey(), revision, contract);
    }

    public void validateSelection(ScanProjectEntity project, ExternalApiEntryEntity entry,
            ExternalApiVersionEntity version, List<ExternalApiOperationEntity> selected) {
        Set<String> identities = new java.util.HashSet<>();
        for (var operation : selected) {
            var request = prepare(project, entry, version, operation, 1L);
            if (!identities.add(canonicalizer.canonicalize(request.scope(), request.contract()).identityHash())) {
                throw unsupported("API_MARKET_DUPLICATE_OPERATION_IDENTITY", "同一版本含重复逻辑 Operation；请只选择一个明确的 method/完整路由");
            }
        }
    }

    /** Caller holds the project lock and a transaction; every selection is prevalidated before writes. */
    public void replace(ProjectExternalApiEntity integration, ScanProjectEntity project, ExternalApiEntryEntity entry,
            ExternalApiVersionEntity version, List<ExternalApiOperationEntity> selected) {
        validateSelection(project, entry, version, selected);
        List<HttpApiAssetService.ObserveRequest> prepared = selected.stream().map(operation -> prepare(project, entry,
                version, operation, integration.getSelectionRevision())).toList();
        List<ProjectExternalApiOperationEntity> prior = selections.selectList(Wrappers.<ProjectExternalApiOperationEntity>lambdaQuery()
                .eq(ProjectExternalApiOperationEntity::getIntegrationId, integration.getId()));
        Set<Long> selectedIds = selected.stream().map(ExternalApiOperationEntity::getId).collect(java.util.stream.Collectors.toSet());
        for (ProjectExternalApiOperationEntity previous : prior) {
            if (selectedIds.contains(previous.getOperationId())) continue;
            if (previous.getApiAssetId() != null) {
                for (HttpApiSourceBindingEntity source : sources.selectList(Wrappers.<HttpApiSourceBindingEntity>lambdaQuery()
                        .eq(HttpApiSourceBindingEntity::getAssetId, previous.getApiAssetId())
                        .eq(HttpApiSourceBindingEntity::getSourceKind, HttpApiSourceKind.API_MARKET_OPERATION.name()))) {
                    intake.remove(new HttpApiAssetService.RemoveRequest(prepared.get(0).scope(), HttpApiSourceKind.API_MARKET_OPERATION, source.getSourceKey()));
                }
            }
            selections.deleteById(previous.getId());
        }
        for (int index = 0; index < selected.size(); index++) {
            var observed = intake.observe(prepared.get(index));
            Long operationId = selected.get(index).getId();
            var binding = prior.stream().filter(row -> Objects.equals(row.getOperationId(), operationId)).findFirst().orElse(null);
            if (binding == null) {
                binding = new ProjectExternalApiOperationEntity(); binding.setIntegrationId(integration.getId());
                binding.setOperationId(operationId); binding.setCreatedAt(LocalDateTime.now());
            }
            binding.setApiAssetId(observed.asset().getId());
            if (binding.getId() == null) selections.insert(binding); else selections.updateById(binding);
        }
    }

    /** Re-read owning catalog tables on every owner lookup, including immediately before Runtime dispatch. */
    public String confirmationReason(HttpApiAssetEntity asset, HttpApiSourceBindingEntity source, ScanProjectEntity project) {
        if (asset == null || source == null || project == null || !Objects.equals(project.getId(), asset.getProjectId())
                || !Objects.equals(project.getProjectCode(), asset.getProjectCode())) return "市场绑定所属项目已变化，请显式重新接入";
        List<ProjectExternalApiOperationEntity> selected = selections.selectList(Wrappers.<ProjectExternalApiOperationEntity>lambdaQuery()
                .eq(ProjectExternalApiOperationEntity::getApiAssetId, asset.getId()));
        if (selected.size() != 1) return "所选市场 Operation 已移除或绑定不唯一，请显式重新选择";
        ProjectExternalApiEntity integration = integrations.selectById(selected.get(0).getIntegrationId());
        if (integration == null || !Set.of("CONFIGURING", "READY").contains(integration.getStatus())
                || !Objects.equals(integration.getProjectId(), asset.getProjectId())
                || !Objects.equals(integration.getProjectCode(), asset.getProjectCode())
                || !environment(integration.getEnvironment()).equals(environment(asset.getEnvironment()))
                || !environment(project.getEnvironment()).equals(environment(asset.getEnvironment()))) {
            return invalidate(integration, "市场接入已停用或项目/环境不一致，请核对接入选择");
        }
        try {
            var entry = entries.selectById(integration.getEntryId());
            var version = versions.selectById(integration.getVersionId());
            var operation = operations.selectById(selected.get(0).getOperationId());
            var expected = prepare(project, entry, version, operation, integration.getSelectionRevision() == null ? 0 : integration.getSelectionRevision());
            var canonical = canonicalizer.canonicalize(expected.scope(), expected.contract());
            if (!Objects.equals(expected.sourceKey(), source.getSourceKey())
                    || !Objects.equals(expected.sourceRevision(), source.getSourceRevision())
                    || !Objects.equals(canonical.qualifiedName(), asset.getQualifiedName())
                    || !Objects.equals(canonical.contractHash(), source.getSourceContractHash())
                    || !Objects.equals(canonical.contractJson(), source.getSourceContractJson())) {
                return invalidate(integration, "固定市场版本/Operation 的来源事实已变化，请显式重新接入并验证");
            }
            return null;
        } catch (RuntimeException unavailable) {
            return invalidate(integration, "固定市场版本或 Operation 当前不可用，请核对目录并显式重新接入");
        }
    }

    /** Latch a detected invalid selection. Catalog reappearance alone must not reactivate old proof/pins. */
    private String invalidate(ProjectExternalApiEntity integration, String reason) {
        if (integration != null && Set.of("CONFIGURING", "READY").contains(integration.getStatus())) {
            integrations.update(null, Wrappers.<ProjectExternalApiEntity>lambdaUpdate()
                    .eq(ProjectExternalApiEntity::getId, integration.getId())
                    .eq(ProjectExternalApiEntity::getSelectionRevision, integration.getSelectionRevision())
                    .in(ProjectExternalApiEntity::getStatus, List.of("CONFIGURING", "READY"))
                    .set(ProjectExternalApiEntity::getStatus, "BROKEN")
                    .set(ProjectExternalApiEntity::getUpdatedAt, LocalDateTime.now().withNano(0)));
        }
        return reason;
    }

    public List<ExternalApiCatalogViews.ApiBindingView> views(ProjectExternalApiEntity integration, ScanProjectEntity project) {
        if (project == null) return List.of();
        List<ExternalApiCatalogViews.ApiBindingView> result = new ArrayList<>();
        for (var selected : selections.selectList(Wrappers.<ProjectExternalApiOperationEntity>lambdaQuery()
                .eq(ProjectExternalApiOperationEntity::getIntegrationId, integration.getId()))) {
            var asset = selected.getApiAssetId() == null ? null : assets.selectById(selected.getApiAssetId());
            if (asset == null) continue;
            var source = sources.selectOne(Wrappers.<HttpApiSourceBindingEntity>lambdaQuery()
                    .eq(HttpApiSourceBindingEntity::getAssetId, asset.getId()).eq(HttpApiSourceBindingEntity::getSourceKind, HttpApiSourceKind.API_MARKET_OPERATION.name()).last("LIMIT 1"));
            String reason = confirmationReason(asset, source, project);
            result.add(new ExternalApiCatalogViews.ApiBindingView(selected.getOperationId(), asset.getId(),
                    asset.getQualifiedName(), asset.getEnvironment(), reason));
        }
        return List.copyOf(result);
    }

    public static String environment(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return "DEV".equals(normalized) ? "DEVELOPMENT" : "PROD".equals(normalized) ? "PRODUCTION" : normalized;
    }

    private JsonNode schema(String value) {
        try {
            JsonNode node = value == null ? null : json.readTree(value);
            if (node == null || !node.isObject() || unsupportedSchema(node, 0)) throw new IllegalArgumentException();
            return node;
        } catch (Exception invalid) { throw unsupported("API_MARKET_SCHEMA_UNSUPPORTED", "目录 schema 缺失、不可读取或含未支持的引用/组合约束"); }
    }

    private boolean unsupportedSchema(JsonNode node, int depth) {
        if (depth > 12) return true;
        if (node.isObject()) {
            for (String key : List.of("$ref", "oneOf", "anyOf", "allOf", "not", "patternProperties")) if (node.has(key)) return true;
        }
        if (node.isContainerNode()) for (JsonNode child : node) if (unsupportedSchema(child, depth + 1)) return true;
        return false;
    }
    private String digest(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
    private ExternalApiCatalogException unsupported(String code, String reason) { return new ExternalApiCatalogException(HttpStatus.BAD_REQUEST, code, reason); }
}
