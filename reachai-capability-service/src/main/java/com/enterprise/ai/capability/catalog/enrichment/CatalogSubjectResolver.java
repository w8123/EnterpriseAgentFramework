package com.enterprise.ai.capability.catalog.enrichment;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectMapper;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolMapper;
import com.enterprise.ai.agent.capability.catalog.tool.definition.ToolDefinitionParameter;
import com.enterprise.ai.capability.catalog.businessmethod.BusinessMethodAssetEntity;
import com.enterprise.ai.capability.catalog.businessmethod.BusinessMethodAssetMapper;
import com.enterprise.ai.capability.catalog.businessmethod.BusinessMethodAssetStore;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiAssetEntity;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiAssetMapper;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiSourceBindingEntity;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiSourceBindingMapper;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiCatalogService;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiSourceKind;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiSourceBindingStatus;
import com.enterprise.ai.capability.catalog.scan.ScannerHttpApiSourceIdentity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Collection;
import java.util.LinkedHashSet;

/** Reads contracts from their owners; source observations provide evidence and candidates only. */
@Service
@RequiredArgsConstructor
public class CatalogSubjectResolver {
    private static final String REGEX_WHITESPACE = "[ \t\r\n\f\u000B]";
    private final ScanProjectMapper projects;
    private final ScanProjectToolMapper observations;
    private final BusinessMethodAssetMapper methods;
    private final BusinessMethodAssetStore methodStore;
    private final HttpApiAssetMapper apis;
    private final HttpApiSourceBindingMapper bindings;
    private final HttpApiCatalogService apiCatalog;
    private final CapabilityCatalogGroupingService grouping;
    private final ObjectMapper json;

    public ScanProjectEntity requireProject(Long projectId) {
        ScanProjectEntity project = projectId == null || projectId <= 0 ? null : projects.selectById(projectId);
        if (project == null) throw new IllegalArgumentException("项目不存在");
        return project;
    }

    public CatalogSubject resolve(Long projectId, String subjectType, Long subjectId) {
        ScanProjectEntity project = requireProject(projectId);
        if (subjectId == null || subjectId <= 0) throw new IllegalArgumentException("附属主体ID无效");
        String type = CatalogSubject.type(subjectType);
        if (CatalogSubject.PROJECT.equals(type) || CatalogSubject.GROUP.equals(type)) {
            String key; String title; List<CatalogSubject> items;
            if (CatalogSubject.PROJECT.equals(type)) {
                if (!Objects.equals(projectId, subjectId)) throw new IllegalArgumentException("项目主体不匹配");
                key = "project:" + project.getProjectCode(); title = project.getName(); items = operations(projectId);
            } else {
                var group = grouping.requireGroup(projectId, subjectId); key = "group:" + subjectId; title = group.getDisplayName();
                items = groupOperations(projectId, subjectId);
            }
            return new CatalogSubject(type, subjectId, projectId, key, title,
                    items.stream().map(item -> item.title() + " [" + item.subjectType() + "]").reduce("", (a, b) -> a + "\n" + b),
                    digest(items.stream().map(item -> item.subjectType() + ":" + item.subjectKey() + ":" + item.contractHash() + ":" + item.accepted()).sorted()
                            .reduce("", (a, b) -> a + "\n" + b)), false, "", "", List.of(), List.of());
        }
        if (CatalogSubject.BUSINESS_METHOD.equals(type)) {
            BusinessMethodAssetEntity asset = methods.selectById(subjectId);
            if (asset == null || !Objects.equals(projectId, asset.getProjectId())) throw new IllegalArgumentException("业务方法不存在或不属于当前项目");
            return method(asset, methodStore.acceptedSources(List.of(asset)).get(asset.getId()), methodSourceRows(projectId, List.of(asset.getQualifiedName()), false));
        }
        if (CatalogSubject.HTTP_API.equals(type)) {
            HttpApiAssetEntity asset = apis.selectById(subjectId);
            if (asset == null || !Objects.equals(projectId, asset.getProjectId())) throw new IllegalArgumentException("API不存在或不属于当前项目");
            List<ScanProjectToolEntity> rows = apiSourceRows(projectId, List.of(asset), false);
            var peers = apiRoutePeers(project, List.of(asset), List.of());
            var owners = uniqueHttpOwners(project, peers, bindingsFor(peers), rows);
            return api(asset, apiCatalog.details(List.of(asset)).get(subjectId), rows.stream().filter(row -> Objects.equals(subjectId, owners.get(row.getId()))).toList());
        }
        ScanProjectToolEntity source = observations.selectById(subjectId);
        if (source == null || !Objects.equals(projectId, source.getProjectId())) throw new IllegalArgumentException("来源观察不存在或不属于当前项目");
        // Once an owner exists both views share its supplement. Source rows never become another asset.
        CatalogSubject owner = sourceOwner(project, source);
        return owner == null ? source(source) : resolve(projectId, owner.subjectType(), owner.subjectId());
    }

    /** A group needs only its persisted owners, not the full project catalog or source matching graph. */
    private List<CatalogSubject> groupOperations(Long projectId, Long groupId) {
        var memberIds = grouping.formalMemberIds(projectId, groupId);
        List<CatalogSubject> result = new ArrayList<>();
        if (!memberIds.methodIds().isEmpty()) {
            List<BusinessMethodAssetEntity> selected = methods.selectList(Wrappers.<BusinessMethodAssetEntity>lambdaQuery()
                    .eq(BusinessMethodAssetEntity::getProjectId, projectId)
                    .in(BusinessMethodAssetEntity::getId, memberIds.methodIds())
                    .isNotNull(BusinessMethodAssetEntity::getAcceptedRevisionId)
                    .ne(BusinessMethodAssetEntity::getStatus, "UNACCEPTED")
                    .orderByAsc(BusinessMethodAssetEntity::getId));
            var accepted = methodStore.acceptedSources(selected);
            for (BusinessMethodAssetEntity asset : selected) result.add(method(asset, accepted.get(asset.getId()), List.of()));
        }
        if (!memberIds.apiIds().isEmpty()) {
            List<HttpApiAssetEntity> selected = apis.selectList(Wrappers.<HttpApiAssetEntity>lambdaQuery()
                    .eq(HttpApiAssetEntity::getProjectId, projectId)
                    .in(HttpApiAssetEntity::getId, memberIds.apiIds())
                    .orderByAsc(HttpApiAssetEntity::getId));
            var details = apiCatalog.details(selected);
            for (HttpApiAssetEntity asset : selected) result.add(api(asset, details.get(asset.getId()), List.of()));
        }
        return List.copyOf(result);
    }

    public List<CatalogSubject> operations(Long projectId) {
        return operations(projectId, true, null);
    }

    public record DocumentReference(String subjectType, Long subjectId) { }

    /** Resolve only persisted document subjects in bounded batches; unrelated contracts and owners are never hydrated. */
    public Map<DocumentReference, CatalogSubject> documentReferences(Long projectId, Collection<DocumentReference> requested) {
        if (requested == null || requested.isEmpty()) return Map.of();
        ScanProjectEntity project = requireProject(projectId);
        Set<DocumentReference> keys = new LinkedHashSet<>(requested);
        Map<DocumentReference, CatalogSubject> result = new LinkedHashMap<>();
        var projectKey = new DocumentReference(CatalogSubject.PROJECT, projectId);
        if (keys.contains(projectKey)) result.put(projectKey, projectReference(project));
        Set<Long> groupIds = referenceIds(keys, CatalogSubject.GROUP);
        if (!groupIds.isEmpty()) for (var group : grouping.list(projectId)) {
            if (!groupIds.contains(group.getId()) || !Objects.equals(projectId, group.getProjectId())) continue;
            result.put(new DocumentReference(CatalogSubject.GROUP, group.getId()),
                    new CatalogSubject(CatalogSubject.GROUP, group.getId(), projectId, "group:" + group.getId(),
                            group.getDisplayName(), "", "", false, null, null, List.of(), List.of()));
        }
        Set<Long> sourceIds = referenceIds(keys, CatalogSubject.SOURCE_OBSERVATION);
        List<ScanProjectToolEntity> requestedSources = sourceIds.isEmpty() ? List.of() : observations.selectList(
                referenceSourceQuery(projectId).in(ScanProjectToolEntity::getId, sourceIds)).stream()
                .filter(row -> Objects.equals(projectId, row.getProjectId()) && sourceIds.contains(row.getId())).toList();
        Set<Long> methodIds = referenceIds(keys, CatalogSubject.BUSINESS_METHOD);
        Set<String> methodNames = requestedSources.stream().filter(row -> CatalogSubject.BUSINESS_METHOD.equals(row.getAssetType()))
                .map(ScanProjectToolEntity::getSourceQualifiedName).filter(StringUtils::hasText).collect(java.util.stream.Collectors.toSet());
        List<BusinessMethodAssetEntity> methodRows = methodIds.isEmpty() && methodNames.isEmpty() ? List.of() : methods.selectList(
                methodReferenceQuery(projectId).and(q -> {
                    if (!methodIds.isEmpty()) q.in(BusinessMethodAssetEntity::getId, methodIds);
                    if (!methodNames.isEmpty()) q.or().in(BusinessMethodAssetEntity::getQualifiedName, methodNames);
                })).stream().filter(owner -> Objects.equals(projectId, owner.getProjectId())
                        && (methodIds.contains(owner.getId()) || methodNames.contains(owner.getQualifiedName()))).toList();
        Set<Long> apiIds = referenceIds(keys, CatalogSubject.HTTP_API);
        List<HttpApiAssetEntity> requestedApis = apiIds.isEmpty() ? List.of() : apis.selectList(
                apiReferenceQuery(projectId).in(HttpApiAssetEntity::getId, apiIds)).stream()
                .filter(owner -> Objects.equals(projectId, owner.getProjectId()) && apiIds.contains(owner.getId())).toList();
        List<HttpApiAssetEntity> peers = apiRoutePeers(project, requestedApis, requestedSources);
        Map<Long, List<HttpApiSourceBindingEntity>> sourceBindings = bindingsFor(peers);
        List<ScanProjectToolEntity> related = relatedSourceRows(projectId, methodRows, peers, true);
        Map<Long, ScanProjectToolEntity> allSources = new LinkedHashMap<>();
        related.forEach(row -> allSources.put(row.getId(), row));
        requestedSources.forEach(row -> allSources.put(row.getId(), row));
        var sourceOwners = uniqueHttpOwners(project, peers, sourceBindings, List.copyOf(allSources.values()));
        for (var owner : methodRows) {
            var ref = new DocumentReference(CatalogSubject.BUSINESS_METHOD, owner.getId());
            if (keys.contains(ref)) result.put(ref, methodClassificationReference(owner, related));
        }
        for (var owner : requestedApis) result.put(new DocumentReference(CatalogSubject.HTTP_API, owner.getId()),
                apiClassificationReference(owner, related.stream().filter(row -> Objects.equals(owner.getId(), sourceOwners.get(row.getId()))).toList()));
        // Keep the requested source key while returning the unique canonical owner, exactly as a body read does.
        for (var row : requestedSources) {
            CatalogSubject owner = canonicalSourceOwner(row, methodRows, peers, sourceOwners, List.copyOf(allSources.values()));
            if (owner == null) owner = sourceReference(row);
            result.put(new DocumentReference(CatalogSubject.SOURCE_OBSERVATION, row.getId()), owner);
        }
        return Map.copyOf(result);
    }

    /** Body reads need the canonical supplement identity/title, never execution contracts or graph state. */
    public CatalogSubject resolveDocumentReference(Long projectId, String subjectType, Long subjectId) {
        var project = requireProject(projectId);
        String type = CatalogSubject.type(subjectType);
        if (subjectId == null || subjectId <= 0) throw new IllegalArgumentException("附属主体ID无效");
        if (CatalogSubject.PROJECT.equals(type)) {
            if (!Objects.equals(projectId, subjectId)) throw new IllegalArgumentException("项目主体不匹配");
            return projectReference(project);
        }
        if (CatalogSubject.GROUP.equals(type)) {
            var group = grouping.requireGroup(projectId, subjectId);
            return new CatalogSubject(type, subjectId, projectId, "group:" + subjectId, group.getDisplayName(), "", "", false, null, null, List.of(), List.of());
        }
        if (CatalogSubject.BUSINESS_METHOD.equals(type)) {
            var owner = methods.selectOne(methodReferenceQuery(projectId).eq(BusinessMethodAssetEntity::getId, subjectId));
            if (owner == null || !Objects.equals(projectId, owner.getProjectId()) || owner.getAcceptedRevisionId() == null || "UNACCEPTED".equals(owner.getStatus()))
                throw new IllegalArgumentException("业务方法不存在或不属于当前项目");
            return methodClassificationReference(owner, methodSourceRows(projectId, List.of(owner.getQualifiedName()), true));
        }
        if (CatalogSubject.HTTP_API.equals(type)) {
            var owner = apis.selectOne(apiReferenceQuery(projectId).eq(HttpApiAssetEntity::getId, subjectId));
            if (owner == null || !Objects.equals(projectId, owner.getProjectId())) throw new IllegalArgumentException("API不存在或不属于当前项目");
            var rows = apiSourceRows(projectId, List.of(owner), true);
            var peers = apiRoutePeers(project, List.of(owner), List.of());
            var sourceOwners = uniqueHttpOwners(project, peers, bindingsFor(peers), rows);
            return apiClassificationReference(owner, rows.stream().filter(row -> Objects.equals(subjectId, sourceOwners.get(row.getId()))).toList());
        }
        var source = observations.selectById(subjectId);
        if (source == null || !Objects.equals(projectId, source.getProjectId())) throw new IllegalArgumentException("来源观察不存在或不属于当前项目");
        CatalogSubject owner = sourceOwner(project, source);
        return owner == null ? sourceReference(source) : resolveDocumentReference(projectId, owner.subjectType(), owner.subjectId());
    }

    /** Formal classification consumes only owner identities; source observations are not membership facts. */
    public List<CatalogSubject> operations(Long projectId, boolean includeUnownedSources, String ownerType) {
        if (ownerType != null && !includeUnownedSources) {
            requireProject(projectId);
            if (CatalogSubject.BUSINESS_METHOD.equals(ownerType)) return methods.selectList(Wrappers.<BusinessMethodAssetEntity>lambdaQuery()
                    .select(BusinessMethodAssetEntity::getId, BusinessMethodAssetEntity::getProjectId, BusinessMethodAssetEntity::getQualifiedName,
                            BusinessMethodAssetEntity::getTitle, BusinessMethodAssetEntity::getDescription)
                    .eq(BusinessMethodAssetEntity::getProjectId, projectId).isNotNull(BusinessMethodAssetEntity::getAcceptedRevisionId)
                    .ne(BusinessMethodAssetEntity::getStatus, "UNACCEPTED").orderByAsc(BusinessMethodAssetEntity::getId))
                    .stream().map(asset -> methodClassificationReference(asset, List.of())).toList();
            if (CatalogSubject.HTTP_API.equals(ownerType)) return apis.selectList(Wrappers.<HttpApiAssetEntity>lambdaQuery()
                    .select(HttpApiAssetEntity::getId, HttpApiAssetEntity::getProjectId, HttpApiAssetEntity::getQualifiedName,
                            HttpApiAssetEntity::getHttpMethod, HttpApiAssetEntity::getRouteTemplate, HttpApiAssetEntity::getAcceptedContractHash)
                    .eq(HttpApiAssetEntity::getProjectId, projectId).orderByAsc(HttpApiAssetEntity::getId))
                    .stream().map(asset -> apiClassificationReference(asset, List.of())).toList();
            throw new IllegalArgumentException("正式业务能力类型无效");
        }
        return operations(projectId, includeUnownedSources, ownerType, ownerType == null);
    }

    public java.util.Set<Long> ownerIds(Long projectId, String type, java.util.Set<Long> ids) {
        if (ids == null || ids.isEmpty()) return java.util.Set.of();
        if (CatalogSubject.BUSINESS_METHOD.equals(type)) return methods.selectList(Wrappers.<BusinessMethodAssetEntity>lambdaQuery()
                .select(BusinessMethodAssetEntity::getId).eq(BusinessMethodAssetEntity::getProjectId, projectId)
                .in(BusinessMethodAssetEntity::getId, ids).isNotNull(BusinessMethodAssetEntity::getAcceptedRevisionId)
                .ne(BusinessMethodAssetEntity::getStatus, "UNACCEPTED")).stream().map(BusinessMethodAssetEntity::getId).collect(java.util.stream.Collectors.toSet());
        if (CatalogSubject.HTTP_API.equals(type)) return apis.selectList(Wrappers.<HttpApiAssetEntity>lambdaQuery()
                .select(HttpApiAssetEntity::getId).eq(HttpApiAssetEntity::getProjectId, projectId).in(HttpApiAssetEntity::getId, ids))
                .stream().map(HttpApiAssetEntity::getId).collect(java.util.stream.Collectors.toSet());
        throw new IllegalArgumentException("能力类型无效");
    }

    private List<CatalogSubject> operations(Long projectId, boolean includeUnownedSources, String ownerType, boolean hydrateContracts) {
        if (ownerType != null && !List.of(CatalogSubject.BUSINESS_METHOD, CatalogSubject.HTTP_API).contains(ownerType)) {
            throw new IllegalArgumentException("正式业务能力类型无效");
        }
        return operations(requireProject(projectId), includeUnownedSources, ownerType, hydrateContracts);
    }

    private List<CatalogSubject> operations(ScanProjectEntity project, boolean includeUnownedSources, String ownerType, boolean hydrateContracts) {
        Long projectId = project.getId(); List<ScanProjectToolEntity> rows = sourceRows(projectId);
        Map<Long, Boolean> owned = new LinkedHashMap<>(); List<CatalogSubject> result = new ArrayList<>();
        List<BusinessMethodAssetEntity> methodAssets = CatalogSubject.HTTP_API.equals(ownerType) ? List.of() : methods.selectList(Wrappers.<BusinessMethodAssetEntity>lambdaQuery()
                .eq(BusinessMethodAssetEntity::getProjectId, projectId).isNotNull(BusinessMethodAssetEntity::getAcceptedRevisionId)
                .ne(BusinessMethodAssetEntity::getStatus, "UNACCEPTED").orderByAsc(BusinessMethodAssetEntity::getId));
        var acceptedMethods = hydrateContracts ? methodStore.acceptedSources(methodAssets) : Map.<Long, BusinessMethodAssetStore.AcceptedSource>of();
        for (BusinessMethodAssetEntity asset : methodAssets) {
            CatalogSubject subject = hydrateContracts ? method(asset, acceptedMethods.get(asset.getId()), rows) : methodClassificationReference(asset, rows);
            result.add(subject); subject.observations().forEach(row -> owned.put(row.getId(), true));
        }
        List<HttpApiAssetEntity> apiAssets = CatalogSubject.BUSINESS_METHOD.equals(ownerType) ? List.of() : apiAssets(projectId);
        var apiDetails = hydrateContracts ? apiCatalog.details(apiAssets) : Map.<Long, HttpApiCatalogService.ApiDetail>of();
        Map<Long, List<HttpApiSourceBindingEntity>> byAsset = CatalogSubject.BUSINESS_METHOD.equals(ownerType) ? Map.of() : bindingsByAsset(projectId);
        Map<Long, Long> sourceOwners = uniqueHttpOwners(project, apiAssets, byAsset, rows);
        for (HttpApiAssetEntity asset : apiAssets) {
            List<ScanProjectToolEntity> related = rows.stream().filter(row -> Objects.equals(asset.getId(), sourceOwners.get(row.getId()))).toList();
            CatalogSubject subject = hydrateContracts ? api(asset, apiDetails.get(asset.getId()), related) : apiClassificationReference(asset, related);
            result.add(subject); subject.observations().forEach(row -> owned.put(row.getId(), true));
        }
        if (includeUnownedSources) for (ScanProjectToolEntity row : rows) if (!owned.containsKey(row.getId())) {
            result.add(hydrateContracts ? source(row) : new CatalogSubject(CatalogSubject.SOURCE_OBSERVATION, row.getId(), projectId,
                    "source-observation:" + row.getId(), StringUtils.hasText(row.getTitle()) ? row.getTitle() : row.getName(),
                    "", "", false, null, null, List.of(), List.of(row)));
        }
        return List.copyOf(result);
    }

    private List<HttpApiAssetEntity> apiAssets(Long projectId) { return apis.selectList(Wrappers.<HttpApiAssetEntity>lambdaQuery()
            .eq(HttpApiAssetEntity::getProjectId, projectId).orderByAsc(HttpApiAssetEntity::getId)); }
    private Map<Long, List<HttpApiSourceBindingEntity>> bindingsByAsset(Long projectId) {
        return bindings.selectList(Wrappers.<HttpApiSourceBindingEntity>lambdaQuery().eq(HttpApiSourceBindingEntity::getProjectId, projectId)
                .orderByAsc(HttpApiSourceBindingEntity::getId)).stream().collect(java.util.stream.Collectors.groupingBy(HttpApiSourceBindingEntity::getAssetId));
    }

    private CatalogSubject method(BusinessMethodAssetEntity asset, BusinessMethodAssetStore.AcceptedSource accepted, List<ScanProjectToolEntity> rows) {
        if (accepted == null) throw new IllegalStateException("业务方法缺少可信接纳内容");
        var revision = accepted.revision(); var declaration = accepted.declaration();
        return new CatalogSubject(CatalogSubject.BUSINESS_METHOD, asset.getId(), asset.getProjectId(), asset.getQualifiedName(), asset.getTitle(),
                asset.getDescription(), revision.getContractHash(), true, declaration.httpMethod(), declaration.endpointPath(), withReturn(declaration.parameters(), declaration.responseType()),
                methodObservations(asset, rows));
    }

    private List<ScanProjectToolEntity> methodObservations(BusinessMethodAssetEntity asset, List<ScanProjectToolEntity> rows) {
        return rows.stream().filter(row -> CatalogSubject.BUSINESS_METHOD.equals(row.getAssetType()))
                .filter(row -> Objects.equals(asset.getQualifiedName(), row.getSourceQualifiedName())).toList();
    }

    private CatalogSubject methodClassificationReference(BusinessMethodAssetEntity asset, List<ScanProjectToolEntity> rows) {
        return new CatalogSubject(CatalogSubject.BUSINESS_METHOD, asset.getId(), asset.getProjectId(), asset.getQualifiedName(),
                asset.getTitle(), asset.getDescription(), "", true, null, null, List.of(), methodObservations(asset, rows));
    }

    private CatalogSubject apiClassificationReference(HttpApiAssetEntity asset, List<ScanProjectToolEntity> related) {
        return new CatalogSubject(CatalogSubject.HTTP_API, asset.getId(), asset.getProjectId(), asset.getQualifiedName(),
                asset.getHttpMethod() + " " + asset.getRouteTemplate(), "", "", StringUtils.hasText(asset.getAcceptedContractHash()),
                asset.getHttpMethod(), asset.getRouteTemplate(), List.of(), related);
    }

    private CatalogSubject api(HttpApiAssetEntity asset, HttpApiCatalogService.ApiDetail detail, List<ScanProjectToolEntity> related) {
        if (detail == null) throw new IllegalStateException("API缺少所属目录事实");
        boolean accepted = detail.acceptedContract() != null && !detail.acceptedContract().isNull();
        JsonNode contract = accepted ? detail.acceptedContract() : detail.contract();
        String value = contract == null ? null : contract.toString();
        String hash = accepted ? detail.summary().acceptedContractHash() : detail.summary().candidateContractHash();
        return new CatalogSubject(CatalogSubject.HTTP_API, asset.getId(), asset.getProjectId(), asset.getQualifiedName(),
                asset.getHttpMethod() + " " + asset.getRouteTemplate(), "来源状态：" + detail.summary().sourceStatus(), hash == null ? "" : hash, accepted,
                asset.getHttpMethod(), asset.getRouteTemplate(), httpParameters(value), related);
    }

    private Map<Long, Long> uniqueHttpOwners(ScanProjectEntity project, List<HttpApiAssetEntity> assets, Map<Long, List<HttpApiSourceBindingEntity>> byAsset, List<ScanProjectToolEntity> rows) {
        Map<Long, Long> result = new LinkedHashMap<>();
        String environment = StringUtils.hasText(project.getEnvironment()) ? project.getEnvironment().trim() : "default";
        for (ScanProjectToolEntity row : rows) {
            if (!CatalogSubject.HTTP_API.equals(row.getAssetType()) || !StringUtils.hasText(row.getSourceLocation())) continue;
            // Match the two explicit scanner wire formats, then require one source binding, not merely one asset.
            List<Long> matches = assets.stream().filter(asset -> Objects.equals(project.getId(), asset.getProjectId())
                    && Objects.equals(project.getProjectCode(), asset.getProjectCode()) && Objects.equals(environment, asset.getEnvironment()))
                    .filter(asset -> Objects.equals(asset.getHttpMethod(), ScannerHttpApiSourceIdentity.method(row.getHttpMethod()))
                    && Objects.equals(asset.getRouteTemplate(), ScannerHttpApiSourceIdentity.route(row.getContextPath(), row.getEndpointPath())))
                    .flatMap(asset -> byAsset.getOrDefault(asset.getId(), List.of()).stream()
                            .filter(binding -> !HttpApiSourceBindingStatus.REMOVED.name().equals(binding.getStatus())
                                    && Objects.equals(asset.getId(), binding.getAssetId()) && Objects.equals(asset.getProjectId(), binding.getProjectId())
                                    && Objects.equals(asset.getProjectCode(), binding.getProjectCode()) && Objects.equals(asset.getEnvironment(), binding.getEnvironment()))
                            .filter(binding -> sameSource(row, binding)).map(binding -> asset.getId())).toList();
            if (matches.size() == 1) result.put(row.getId(), matches.get(0));
        }
        return result;
    }
    private boolean sameSource(ScanProjectToolEntity observation, HttpApiSourceBindingEntity binding) {
        if (observation.getSourceLocation().startsWith("sdk:") || !"scanner".equals(observation.getSource())) {
            return "STARTER_MVC".equals(binding.getSourceKind()) && Objects.equals(binding.getSourceLocation(), observation.getSourceLocation());
        }
        if (!"CONTROLLER_SCAN".equals(binding.getSourceKind()) && !"OPENAPI_SCAN".equals(binding.getSourceKind())) return false;
        return ScannerHttpApiSourceIdentity.matches(HttpApiSourceKind.valueOf(binding.getSourceKind()), observation.getSourceLocation(), binding.getSourceLocation());
    }

    private CatalogSubject source(ScanProjectToolEntity row) {
        List<ToolDefinitionParameter> parameters;
        try { parameters = StringUtils.hasText(row.getParametersJson()) ? json.readValue(row.getParametersJson(), new TypeReference<List<ToolDefinitionParameter>>() {}) : List.of(); }
        catch (Exception malformed) { throw new IllegalArgumentException("来源参数结构无效", malformed); }
        return new CatalogSubject(CatalogSubject.SOURCE_OBSERVATION, row.getId(), row.getProjectId(), "source-observation:" + row.getId(),
                StringUtils.hasText(row.getTitle()) ? row.getTitle() : row.getName(), row.getDescription(), sourceHash(row),
                false, row.getHttpMethod(), row.getEndpointPath(), withReturn(parameters, row.getResponseType()), List.of(row));
    }

    private List<ToolDefinitionParameter> withReturn(List<ToolDefinitionParameter> parameters, String responseType) {
        List<ToolDefinitionParameter> result = new ArrayList<>(parameters == null ? List.of() : parameters);
        if (result.stream().noneMatch(parameter -> "RESPONSE".equalsIgnoreCase(parameter.location()))
                && StringUtils.hasText(responseType) && !List.of("void", "java.lang.Void").contains(responseType.trim())) {
            result.add(new ToolDefinitionParameter("return", responseType.trim(), "来源声明的返回类型；未声明字段结构", false, "RESPONSE"));
        }
        return List.copyOf(result);
    }
    private String sourceHash(ScanProjectToolEntity row) {
        try {
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("parameters", row.getParametersJson()); facts.put("responseType", row.getResponseType());
            facts.put("requestBodyType", row.getRequestBodyType()); facts.put("httpMethod", row.getHttpMethod());
            facts.put("endpointPath", row.getEndpointPath()); facts.put("contextPath", row.getContextPath());
            facts.put("assetType", row.getAssetType()); facts.put("sourceQualifiedName", row.getSourceQualifiedName());
            return digest(json.writeValueAsString(facts));
        } catch (Exception invalid) { throw new IllegalArgumentException("来源结构无法序列化", invalid); }
    }

    private List<ScanProjectToolEntity> sourceRows(Long projectId) {
        return observations.selectList(Wrappers.<ScanProjectToolEntity>lambdaQuery().eq(ScanProjectToolEntity::getProjectId, projectId).orderByAsc(ScanProjectToolEntity::getId));
    }

    private Set<Long> referenceIds(Collection<DocumentReference> references, String type) {
        return references.stream().filter(ref -> type.equals(ref.subjectType()) && ref.subjectId() != null && ref.subjectId() > 0)
                .map(DocumentReference::subjectId).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private CatalogSubject projectReference(ScanProjectEntity project) {
        return new CatalogSubject(CatalogSubject.PROJECT, project.getId(), project.getId(), "project:" + project.getProjectCode(),
                project.getName(), "", "", false, null, null, List.of(), List.of());
    }

    private CatalogSubject sourceReference(ScanProjectToolEntity source) {
        return new CatalogSubject(CatalogSubject.SOURCE_OBSERVATION, source.getId(), source.getProjectId(), "source-observation:" + source.getId(),
                StringUtils.hasText(source.getTitle()) ? source.getTitle() : source.getName(), "", "", false, null, null, List.of(), List.of(source));
    }

    private com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<BusinessMethodAssetEntity> methodReferenceQuery(Long projectId) {
        return Wrappers.<BusinessMethodAssetEntity>lambdaQuery().select(BusinessMethodAssetEntity::getId, BusinessMethodAssetEntity::getProjectId,
                BusinessMethodAssetEntity::getQualifiedName, BusinessMethodAssetEntity::getTitle, BusinessMethodAssetEntity::getDescription,
                BusinessMethodAssetEntity::getAcceptedRevisionId, BusinessMethodAssetEntity::getStatus)
                .eq(BusinessMethodAssetEntity::getProjectId, projectId).isNotNull(BusinessMethodAssetEntity::getAcceptedRevisionId)
                .ne(BusinessMethodAssetEntity::getStatus, "UNACCEPTED").orderByAsc(BusinessMethodAssetEntity::getId);
    }

    private com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<HttpApiAssetEntity> apiReferenceQuery(Long projectId) {
        return Wrappers.<HttpApiAssetEntity>lambdaQuery().select(HttpApiAssetEntity::getId, HttpApiAssetEntity::getProjectId,
                HttpApiAssetEntity::getProjectCode, HttpApiAssetEntity::getEnvironment, HttpApiAssetEntity::getQualifiedName,
                HttpApiAssetEntity::getHttpMethod, HttpApiAssetEntity::getRouteTemplate, HttpApiAssetEntity::getAcceptedContractHash)
                .eq(HttpApiAssetEntity::getProjectId, projectId).orderByAsc(HttpApiAssetEntity::getId);
    }

    private com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ScanProjectToolEntity> referenceSourceQuery(Long projectId) {
        return Wrappers.<ScanProjectToolEntity>lambdaQuery().select(ScanProjectToolEntity::getId, ScanProjectToolEntity::getProjectId,
                ScanProjectToolEntity::getAssetType, ScanProjectToolEntity::getSourceQualifiedName, ScanProjectToolEntity::getSource,
                ScanProjectToolEntity::getSourceLocation, ScanProjectToolEntity::getHttpMethod, ScanProjectToolEntity::getContextPath,
                ScanProjectToolEntity::getEndpointPath, ScanProjectToolEntity::getName, ScanProjectToolEntity::getTitle)
                .eq(ScanProjectToolEntity::getProjectId, projectId).orderByAsc(ScanProjectToolEntity::getId);
    }

    private List<ScanProjectToolEntity> methodSourceRows(Long projectId, Collection<String> qualifiedNames, boolean referencesOnly) {
        List<String> names = qualifiedNames.stream().filter(StringUtils::hasText).distinct().toList();
        if (names.isEmpty()) return List.of();
        var query = referencesOnly ? referenceSourceQuery(projectId) : Wrappers.<ScanProjectToolEntity>lambdaQuery()
                .eq(ScanProjectToolEntity::getProjectId, projectId).orderByAsc(ScanProjectToolEntity::getId);
        return observations.selectList(query.eq(ScanProjectToolEntity::getAssetType, CatalogSubject.BUSINESS_METHOD)
                .in(ScanProjectToolEntity::getSourceQualifiedName, names)).stream()
                .filter(row -> Objects.equals(projectId, row.getProjectId()) && names.contains(row.getSourceQualifiedName())
                        && CatalogSubject.BUSINESS_METHOD.equals(row.getAssetType())).toList();
    }

    private List<ScanProjectToolEntity> apiSourceRows(Long projectId, List<HttpApiAssetEntity> owners, boolean referencesOnly) {
        return relatedSourceRows(projectId, List.of(), owners, referencesOnly);
    }

    private record RouteIdentity(String method, String route) { }

    private List<ScanProjectToolEntity> relatedSourceRows(Long projectId, List<BusinessMethodAssetEntity> methodOwners,
                                                         List<HttpApiAssetEntity> apiOwners, boolean referencesOnly) {
        Set<String> names = methodOwners.stream().map(BusinessMethodAssetEntity::getQualifiedName).filter(StringUtils::hasText)
                .collect(java.util.stream.Collectors.toSet());
        Set<RouteIdentity> routes = apiOwners.stream().filter(owner -> validRoute(owner.getHttpMethod(), owner.getRouteTemplate()))
                .map(owner -> new RouteIdentity(owner.getHttpMethod(), owner.getRouteTemplate()))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (names.isEmpty() && routes.isEmpty()) return List.of();
        var query = referencesOnly ? referenceSourceQuery(projectId) : Wrappers.<ScanProjectToolEntity>lambdaQuery()
                .eq(ScanProjectToolEntity::getProjectId, projectId).orderByAsc(ScanProjectToolEntity::getId);
        query.and(q -> {
            if (!names.isEmpty()) q.nested(method -> method.eq(ScanProjectToolEntity::getAssetType, CatalogSubject.BUSINESS_METHOD)
                    .in(ScanProjectToolEntity::getSourceQualifiedName, names));
            if (!routes.isEmpty()) q.or().nested(http -> http.eq(ScanProjectToolEntity::getAssetType, CatalogSubject.HTTP_API)
                    .and(matching -> { for (RouteIdentity route : routes) matching.or().nested(pair -> pair
                            .apply("UPPER(http_method) REGEXP {0}", "^" + REGEX_WHITESPACE + "*" + regexLiteral(route.method()) + REGEX_WHITESPACE + "*$")
                            .apply("CONCAT(COALESCE(context_path, ''), '/', COALESCE(endpoint_path, '')) REGEXP {0}", routePattern(route.route()))); }));
        });
        // SQL is a conservative prefilter. Exact Java normalization preserves case and all scanner wire formats.
        return observations.selectList(query).stream().filter(row -> Objects.equals(projectId, row.getProjectId()))
                .filter(row -> CatalogSubject.BUSINESS_METHOD.equals(row.getAssetType()) ? names.contains(row.getSourceQualifiedName())
                        : CatalogSubject.HTTP_API.equals(row.getAssetType()) && routes.contains(new RouteIdentity(
                                ScannerHttpApiSourceIdentity.method(row.getHttpMethod()), ScannerHttpApiSourceIdentity.route(row.getContextPath(), row.getEndpointPath())))).toList();
    }

    /** MySQL 5.7-compatible POSIX prefilter; bound parameters keep route punctuation outside SQL syntax. */
    private String routePattern(String route) {
        String whitespace = REGEX_WHITESPACE;
        StringBuilder pattern = new StringBuilder("^(" + whitespace + "*/)*" + whitespace + "*");
        String[] parts = route.substring(1).split("/", -1);
        for (int index = 0; index < parts.length; index++) {
            if (index > 0) pattern.append(whitespace).append("*(/").append(whitespace).append("*)+");
            pattern.append(regexLiteral(parts[index]));
        }
        return pattern.append('(').append(whitespace).append("*/)*").append(whitespace).append("*$").toString();
    }

    private String regexLiteral(String value) {
        StringBuilder literal = new StringBuilder();
        for (char character : value.toCharArray()) {
            if ("\\.^$|?*+()[]{}".indexOf(character) >= 0) literal.append('\\');
            literal.append(character);
        }
        return literal.toString();
    }

    private boolean validRoute(String method, String route) {
        return StringUtils.hasText(method) && route != null && route.startsWith("/");
    }

    private List<HttpApiAssetEntity> apiRoutePeers(ScanProjectEntity project, List<HttpApiAssetEntity> owners,
                                                 List<ScanProjectToolEntity> sources) {
        String environment = StringUtils.hasText(project.getEnvironment()) ? project.getEnvironment().trim() : "default";
        Set<RouteIdentity> routes = new LinkedHashSet<>();
        for (var owner : owners) if (Objects.equals(project.getId(), owner.getProjectId())
                && Objects.equals(project.getProjectCode(), owner.getProjectCode()) && Objects.equals(environment, owner.getEnvironment())
                && validRoute(owner.getHttpMethod(), owner.getRouteTemplate())) {
            routes.add(new RouteIdentity(owner.getHttpMethod(), owner.getRouteTemplate()));
        }
        for (var row : sources) if (CatalogSubject.HTTP_API.equals(row.getAssetType()) && StringUtils.hasText(row.getSourceLocation())) {
            routes.add(new RouteIdentity(ScannerHttpApiSourceIdentity.method(row.getHttpMethod()),
                    ScannerHttpApiSourceIdentity.route(row.getContextPath(), row.getEndpointPath())));
        }
        if (routes.isEmpty()) return List.of();
        return apis.selectList(apiReferenceQuery(project.getId()).eq(HttpApiAssetEntity::getProjectCode, project.getProjectCode())
                .eq(HttpApiAssetEntity::getEnvironment, environment).and(q -> {
                    for (RouteIdentity route : routes) q.or().nested(pair -> pair.eq(HttpApiAssetEntity::getHttpMethod, route.method())
                            .eq(HttpApiAssetEntity::getRouteTemplate, route.route()));
                })).stream().filter(owner -> Objects.equals(project.getId(), owner.getProjectId())
                        && Objects.equals(project.getProjectCode(), owner.getProjectCode()) && Objects.equals(environment, owner.getEnvironment())
                        && routes.contains(new RouteIdentity(owner.getHttpMethod(), owner.getRouteTemplate()))).toList();
    }

    private Map<Long, List<HttpApiSourceBindingEntity>> bindingsFor(List<HttpApiAssetEntity> owners) {
        if (owners.isEmpty()) return Map.of();
        return bindings.selectList(Wrappers.<HttpApiSourceBindingEntity>lambdaQuery().select(HttpApiSourceBindingEntity::getId,
                HttpApiSourceBindingEntity::getAssetId, HttpApiSourceBindingEntity::getProjectId, HttpApiSourceBindingEntity::getProjectCode,
                HttpApiSourceBindingEntity::getEnvironment, HttpApiSourceBindingEntity::getSourceKind,
                HttpApiSourceBindingEntity::getSourceLocation, HttpApiSourceBindingEntity::getStatus)
                .in(HttpApiSourceBindingEntity::getAssetId, owners.stream().map(HttpApiAssetEntity::getId).toList())
                .orderByAsc(HttpApiSourceBindingEntity::getId)).stream().collect(java.util.stream.Collectors.groupingBy(HttpApiSourceBindingEntity::getAssetId));
    }

    private CatalogSubject sourceOwner(ScanProjectEntity project, ScanProjectToolEntity source) {
        List<BusinessMethodAssetEntity> methodOwners = !CatalogSubject.BUSINESS_METHOD.equals(source.getAssetType())
                || !StringUtils.hasText(source.getSourceQualifiedName()) ? List.of() : methods.selectList(methodReferenceQuery(project.getId())
                .eq(BusinessMethodAssetEntity::getQualifiedName, source.getSourceQualifiedName()));
        var peers = apiRoutePeers(project, List.of(), List.of(source));
        return canonicalSourceOwner(source, methodOwners, peers, uniqueHttpOwners(project, peers, bindingsFor(peers), List.of(source)), List.of(source));
    }

    private CatalogSubject canonicalSourceOwner(ScanProjectToolEntity source, List<BusinessMethodAssetEntity> methods,
                                                List<HttpApiAssetEntity> apis, Map<Long, Long> httpOwners, List<ScanProjectToolEntity> rows) {
        if (CatalogSubject.BUSINESS_METHOD.equals(source.getAssetType())) {
            var matching = methods.stream().filter(owner -> Objects.equals(source.getProjectId(), owner.getProjectId())
                    && Objects.equals(source.getSourceQualifiedName(), owner.getQualifiedName())).toList();
            return matching.size() == 1 ? methodClassificationReference(matching.get(0), rows) : null;
        }
        Long ownerId = httpOwners.get(source.getId());
        var matching = apis.stream().filter(owner -> Objects.equals(ownerId, owner.getId())).toList();
        return matching.size() == 1 ? apiClassificationReference(matching.get(0), rows.stream()
                .filter(row -> Objects.equals(ownerId, httpOwners.get(row.getId()))).toList()) : null;
    }

    private List<ToolDefinitionParameter> httpParameters(String value) {
        if (!StringUtils.hasText(value)) return List.of();
        try {
            JsonNode root = json.readTree(value); List<ToolDefinitionParameter> result = new ArrayList<>();
            for (JsonNode parameter : root.path("parameters")) result.add(field(parameter.path("name").asText(), parameter.path("schema"), parameter.path("required").asBoolean(), parameter.path("location").asText()));
            JsonNode body = root.path("requestBody"); if (!body.isMissingNode() && !body.isNull()) result.add(field("body", body.path("schema"), body.path("required").asBoolean(), "BODY"));
            for (JsonNode response : root.path("responses")) result.add(field("response" + response.path("status").asText(), response.path("schema"), false, "RESPONSE"));
            return List.copyOf(result);
        } catch (Exception malformed) { throw new IllegalArgumentException("API所属契约结构无效", malformed); }
    }

    private ToolDefinitionParameter field(String name, JsonNode schema, boolean required, String location) {
        List<ToolDefinitionParameter> children = new ArrayList<>(); JsonNode properties = schema.path("properties");
        if (properties.isObject()) properties.fields().forEachRemaining(entry -> children.add(field(entry.getKey(), entry.getValue(), contains(schema.path("required"), entry.getKey()), location)));
        if ("array".equals(schema.path("type").asText())) children.add(field("[]", schema.path("items"), false, location));
        String type = schema.path("type").asText("object"); String ref = schema.path("$ref").asText(); if (!ref.isBlank()) type = ref;
        return new ToolDefinitionParameter(name, type, schema.path("description").asText(), required, location, children, Map.of("schemaHash", digest(schema.toString())));
    }
    private boolean contains(JsonNode values, String name) { for (JsonNode value : values) if (name.equals(value.asText())) return true; return false; }
    public static String digest(String value) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception unavailable) { throw new IllegalStateException("SHA-256 unavailable", unavailable); }
    }
}
