package com.enterprise.ai.capability.externalapi;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ExternalApiCatalogService {

    private static final String PUBLISHED = "PUBLISHED";
    private static final Set<String> INTEGRATION_STATUSES = Set.of(
            "CONFIGURING", "READY", "BROKEN", "DISABLED");
    private static final Set<String> INTEGRATION_ENVIRONMENTS = Set.of(
            "DEVELOPMENT", "TEST", "STAGING", "PRODUCTION");
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {
    };
    private static final Map<String, String> CATEGORY_NAMES = Map.ofEntries(
            Map.entry("AI", "AI 与智能服务"),
            Map.entry("BUSINESS", "商业服务"),
            Map.entry("DATA", "公共数据"),
            Map.entry("DEVELOPER", "开发者工具"),
            Map.entry("FINANCE", "金融"),
            Map.entry("GEO", "地图与地理"),
            Map.entry("MEDIA", "媒体内容"),
            Map.entry("SCIENCE", "科学"),
            Map.entry("SOCIAL", "社交"),
            Map.entry("TEXT", "文本与翻译"),
            Map.entry("WEATHER", "天气"),
            Map.entry("OTHER", "其他")
    );

    private final ExternalApiSourceMapper sourceMapper;
    private final ExternalApiProviderMapper providerMapper;
    private final ExternalApiEntryMapper entryMapper;
    private final ExternalApiVersionMapper versionMapper;
    private final ExternalApiOperationMapper operationMapper;
    private final ExternalApiVerificationMapper verificationMapper;
    private final ProjectExternalApiMapper integrationMapper;
    private final ProjectExternalApiOperationMapper integrationOperationMapper;
    private final ScanProjectMapper projectMapper;
    private final ObjectMapper objectMapper;

    public ExternalApiCatalogViews.PageView listEntries(int current,
                                                         int size,
                                                         String keyword,
                                                         String category,
                                                         String authType,
                                                         String verificationStatus,
                                                         String specStatus,
                                                         String sourceKey,
                                                         Boolean specAvailable) {
        int safeCurrent = Math.max(1, current);
        int safeSize = Math.min(60, Math.max(1, size));
        LambdaQueryWrapper<ExternalApiEntryEntity> query = Wrappers.lambdaQuery();
        query.eq(ExternalApiEntryEntity::getPublicationStatus, PUBLISHED);

        List<Long> keywordProviderIds = providerIdsMatching(keyword);
        if (StringUtils.hasText(keyword)) {
            String term = keyword.trim();
            query.and(nested -> {
                nested.like(ExternalApiEntryEntity::getTitle, term)
                        .or().like(ExternalApiEntryEntity::getSummary, term)
                        .or().like(ExternalApiEntryEntity::getDescription, term)
                        .or().like(ExternalApiEntryEntity::getTagsJson, term)
                        .or().like(ExternalApiEntryEntity::getSourceCategory, term);
                if (!keywordProviderIds.isEmpty()) {
                    nested.or().in(ExternalApiEntryEntity::getProviderId, keywordProviderIds);
                }
            });
        }
        if (StringUtils.hasText(category)) {
            query.eq(ExternalApiEntryEntity::getCategoryCode, normalize(category));
        }
        if (StringUtils.hasText(authType)) {
            query.eq(ExternalApiEntryEntity::getAuthType, normalize(authType));
        }
        if (StringUtils.hasText(verificationStatus)) {
            query.eq(ExternalApiEntryEntity::getVerificationStatus, normalize(verificationStatus));
        }
        if (StringUtils.hasText(specStatus)) {
            query.eq(ExternalApiEntryEntity::getSpecStatus, normalize(specStatus));
        }
        if (StringUtils.hasText(sourceKey)) {
            ExternalApiSourceEntity source = findSource(sourceKey);
            if (source == null) {
                return new ExternalApiCatalogViews.PageView(List.of(), 0, safeCurrent, safeSize, 0);
            }
            query.eq(ExternalApiEntryEntity::getSourceId, source.getId());
        }
        if (Boolean.TRUE.equals(specAvailable)) {
            query.in(ExternalApiEntryEntity::getSpecStatus, List.of("VALID", "CHANGED"));
        } else if (Boolean.FALSE.equals(specAvailable)) {
            query.eq(ExternalApiEntryEntity::getSpecStatus, "NONE");
        }
        query.orderByDesc(ExternalApiEntryEntity::getFeatured)
                .orderByDesc(ExternalApiEntryEntity::getPopularityScore)
                .orderByDesc(ExternalApiEntryEntity::getUpdatedAt);

        IPage<ExternalApiEntryEntity> page = entryMapper.selectPage(
                new Page<>(safeCurrent, safeSize, true), query);
        Lookup lookup = lookup(page.getRecords());
        List<ExternalApiCatalogViews.EntrySummary> records = page.getRecords().stream()
                .map(entry -> summary(entry, lookup.providers(), lookup.sources()))
                .toList();
        return new ExternalApiCatalogViews.PageView(
                records, page.getTotal(), page.getCurrent(), page.getSize(), page.getPages());
    }

    public ExternalApiCatalogViews.EntryDetail getEntry(String entryKey) {
        ExternalApiEntryEntity entry = requireEntry(entryKey);
        Lookup lookup = lookup(List.of(entry));
        List<ExternalApiVersionEntity> versions = versionMapper.selectList(
                Wrappers.<ExternalApiVersionEntity>lambdaQuery()
                        .eq(ExternalApiVersionEntity::getEntryId, entry.getId())
                        .eq(ExternalApiVersionEntity::getPublicationStatus, PUBLISHED)
                        .orderByDesc(ExternalApiVersionEntity::getPublishedAt)
                        .orderByDesc(ExternalApiVersionEntity::getId));
        List<ExternalApiVerificationEntity> verifications = verificationMapper.selectList(
                Wrappers.<ExternalApiVerificationEntity>lambdaQuery()
                        .eq(ExternalApiVerificationEntity::getEntryId, entry.getId())
                        .orderByDesc(ExternalApiVerificationEntity::getCheckedAt)
                        .orderByDesc(ExternalApiVerificationEntity::getId)
                        .last("LIMIT 20"));
        return new ExternalApiCatalogViews.EntryDetail(
                summary(entry, lookup.providers(), lookup.sources()),
                entry.getDescription(),
                entry.getDocsUrl(),
                entry.getTermsUrl(),
                entry.getSourceEntryKey(),
                entry.getSourceCategory(),
                versions.stream().map(version -> versionView(version, true)).toList(),
                verifications.stream().map(this::verificationView).toList()
        );
    }

    public ExternalApiCatalogViews.StatsView stats() {
        long published = countEntries(Wrappers.<ExternalApiEntryEntity>lambdaQuery()
                .eq(ExternalApiEntryEntity::getPublicationStatus, PUBLISHED));
        long verified = countEntries(Wrappers.<ExternalApiEntryEntity>lambdaQuery()
                .eq(ExternalApiEntryEntity::getPublicationStatus, PUBLISHED)
                .eq(ExternalApiEntryEntity::getVerificationStatus, "VERIFIED"));
        long specs = countEntries(Wrappers.<ExternalApiEntryEntity>lambdaQuery()
                .eq(ExternalApiEntryEntity::getPublicationStatus, PUBLISHED)
                .in(ExternalApiEntryEntity::getSpecStatus, List.of("VALID", "CHANGED")));
        Long integrations = integrationMapper.selectCount(Wrappers.lambdaQuery());
        return new ExternalApiCatalogViews.StatsView(
                published, verified, specs, integrations == null ? 0 : integrations);
    }

    public List<ExternalApiCatalogViews.CategoryView> categories() {
        Map<String, Long> counts = entryMapper.selectList(
                        Wrappers.<ExternalApiEntryEntity>lambdaQuery()
                                .select(ExternalApiEntryEntity::getCategoryCode)
                                .eq(ExternalApiEntryEntity::getPublicationStatus, PUBLISHED))
                .stream()
                .map(ExternalApiEntryEntity::getCategoryCode)
                .filter(StringUtils::hasText)
                .collect(Collectors.groupingBy(Function.identity(), LinkedHashMap::new, Collectors.counting()));
        return counts.entrySet().stream()
                .map(item -> new ExternalApiCatalogViews.CategoryView(
                        item.getKey(), CATEGORY_NAMES.getOrDefault(item.getKey(), item.getKey()), item.getValue()))
                .sorted(Comparator.comparingLong(ExternalApiCatalogViews.CategoryView::count).reversed()
                        .thenComparing(ExternalApiCatalogViews.CategoryView::name))
                .toList();
    }

    public List<ExternalApiCatalogViews.SourceView> sources() {
        return sourceMapper.selectList(Wrappers.<ExternalApiSourceEntity>lambdaQuery()
                        .orderByAsc(ExternalApiSourceEntity::getName))
                .stream()
                .map(this::sourceView)
                .toList();
    }

    @Transactional
    public ExternalApiCatalogViews.IntegrationView createIntegration(
            String entryKey,
            ExternalApiCatalogViews.IntegrationCreateRequest request) {
        if (request == null) {
            throw badRequest("API_MARKET_INTEGRATION_REQUIRED", "接入参数不能为空");
        }
        ExternalApiEntryEntity entry = requireEntry(entryKey);
        ScanProjectEntity project = requireProject(request.projectId(), request.projectCode());
        ExternalApiVersionEntity version = requireVersion(entry.getId(), request.versionId());
        List<ExternalApiOperationEntity> operations = requireOperations(version.getId(), request.operationIds());
        String environment = normalizeEnvironment(request.environment(), project.getEnvironment());

        ProjectExternalApiEntity integration = integrationMapper.selectOne(
                Wrappers.<ProjectExternalApiEntity>lambdaQuery()
                        .eq(ProjectExternalApiEntity::getProjectId, project.getId())
                        .eq(ProjectExternalApiEntity::getEntryId, entry.getId())
                        .eq(ProjectExternalApiEntity::getEnvironment, environment)
                        .last("LIMIT 1"));
        LocalDateTime now = LocalDateTime.now().withNano(0);
        if (integration == null) {
            integration = new ProjectExternalApiEntity();
            integration.setProjectId(project.getId());
            integration.setProjectCode(project.getProjectCode());
            integration.setEntryId(entry.getId());
            integration.setEnvironment(environment);
            integration.setCreatedAt(now);
        }
        integration.setVersionId(version.getId());
        integration.setStatus(requiresCredential(entry) ? "CONFIGURING" : "READY");
        integration.setNote(trimTo(request.note(), 512));
        integration.setUpdatedAt(now);
        if (integration.getId() == null) {
            integrationMapper.insert(integration);
        } else {
            integrationMapper.updateById(integration);
            integrationOperationMapper.delete(Wrappers.<ProjectExternalApiOperationEntity>lambdaQuery()
                    .eq(ProjectExternalApiOperationEntity::getIntegrationId, integration.getId()));
        }
        for (ExternalApiOperationEntity operation : operations) {
            ProjectExternalApiOperationEntity binding = new ProjectExternalApiOperationEntity();
            binding.setIntegrationId(integration.getId());
            binding.setOperationId(operation.getId());
            binding.setCreatedAt(now);
            integrationOperationMapper.insert(binding);
        }
        return integrationView(integration, project, entry, version, operations);
    }

    public List<ExternalApiCatalogViews.IntegrationView> listIntegrations(
            Long projectId,
            String projectCode,
            String status) {
        LambdaQueryWrapper<ProjectExternalApiEntity> query = Wrappers.lambdaQuery();
        if (projectId != null) {
            query.eq(ProjectExternalApiEntity::getProjectId, projectId);
        }
        if (StringUtils.hasText(projectCode)) {
            query.eq(ProjectExternalApiEntity::getProjectCode, projectCode.trim());
        }
        if (StringUtils.hasText(status)) {
            query.eq(ProjectExternalApiEntity::getStatus, normalize(status));
        }
        query.orderByDesc(ProjectExternalApiEntity::getUpdatedAt);
        return integrationViews(integrationMapper.selectList(query));
    }

    @Transactional
    public ExternalApiCatalogViews.IntegrationView updateIntegrationStatus(
            Long integrationId,
            ExternalApiCatalogViews.IntegrationStatusRequest request) {
        ProjectExternalApiEntity integration = requireIntegration(integrationId);
        String status = request == null ? null : normalize(request.status());
        if (!INTEGRATION_STATUSES.contains(status)) {
            throw badRequest("API_MARKET_INTEGRATION_STATUS_INVALID",
                    "接入状态必须是 CONFIGURING、READY、BROKEN 或 DISABLED");
        }
        ExternalApiEntryEntity entry = entryMapper.selectById(integration.getEntryId());
        if ("READY".equals(status) && entry != null && requiresCredential(entry)) {
            throw badRequest("API_MARKET_CREDENTIAL_PROOF_REQUIRED",
                    "需要认证的 API 不能由市场直接标记为 READY，请先在 Workflow Runtime 配置并验证凭据");
        }
        integration.setStatus(status);
        if (request.note() != null) {
            integration.setNote(trimTo(request.note(), 512));
        }
        integration.setUpdatedAt(LocalDateTime.now().withNano(0));
        integrationMapper.updateById(integration);
        return integrationView(integration);
    }

    private ExternalApiCatalogViews.IntegrationView integrationView(ProjectExternalApiEntity integration) {
        ScanProjectEntity project = projectMapper.selectById(integration.getProjectId());
        ExternalApiEntryEntity entry = entryMapper.selectById(integration.getEntryId());
        ExternalApiVersionEntity version = versionMapper.selectById(integration.getVersionId());
        List<Long> operationIds = integrationOperationMapper.selectList(
                        Wrappers.<ProjectExternalApiOperationEntity>lambdaQuery()
                                .eq(ProjectExternalApiOperationEntity::getIntegrationId, integration.getId()))
                .stream()
                .map(ProjectExternalApiOperationEntity::getOperationId)
                .filter(Objects::nonNull)
                .toList();
        List<ExternalApiOperationEntity> operations = operationIds.isEmpty()
                ? List.of()
                : operationMapper.selectBatchIds(operationIds);
        return integrationView(integration, project, entry, version, operations);
    }

    private List<ExternalApiCatalogViews.IntegrationView> integrationViews(
            List<ProjectExternalApiEntity> integrations) {
        if (integrations == null || integrations.isEmpty()) {
            return List.of();
        }
        Set<Long> projectIds = integrations.stream().map(ProjectExternalApiEntity::getProjectId)
                .filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> entryIds = integrations.stream().map(ProjectExternalApiEntity::getEntryId)
                .filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> versionIds = integrations.stream().map(ProjectExternalApiEntity::getVersionId)
                .filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> integrationIds = integrations.stream().map(ProjectExternalApiEntity::getId)
                .filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));

        Map<Long, ScanProjectEntity> projects = projectIds.isEmpty() ? Map.of()
                : projectMapper.selectBatchIds(projectIds).stream()
                .collect(Collectors.toMap(ScanProjectEntity::getId, Function.identity()));
        Map<Long, ExternalApiEntryEntity> entries = entryIds.isEmpty() ? Map.of()
                : entryMapper.selectBatchIds(entryIds).stream()
                .collect(Collectors.toMap(ExternalApiEntryEntity::getId, Function.identity()));
        Map<Long, ExternalApiVersionEntity> versions = versionIds.isEmpty() ? Map.of()
                : versionMapper.selectBatchIds(versionIds).stream()
                .collect(Collectors.toMap(ExternalApiVersionEntity::getId, Function.identity()));
        List<ProjectExternalApiOperationEntity> bindings = integrationIds.isEmpty() ? List.of()
                : integrationOperationMapper.selectList(
                Wrappers.<ProjectExternalApiOperationEntity>lambdaQuery()
                        .in(ProjectExternalApiOperationEntity::getIntegrationId, integrationIds)
                        .orderByAsc(ProjectExternalApiOperationEntity::getId));
        Map<Long, List<Long>> operationIdsByIntegration = bindings.stream()
                .filter(binding -> binding.getIntegrationId() != null && binding.getOperationId() != null)
                .collect(Collectors.groupingBy(
                        ProjectExternalApiOperationEntity::getIntegrationId,
                        LinkedHashMap::new,
                        Collectors.mapping(ProjectExternalApiOperationEntity::getOperationId, Collectors.toList())));
        Set<Long> operationIds = bindings.stream().map(ProjectExternalApiOperationEntity::getOperationId)
                .filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, ExternalApiOperationEntity> operations = operationIds.isEmpty() ? Map.of()
                : operationMapper.selectBatchIds(operationIds).stream()
                .collect(Collectors.toMap(ExternalApiOperationEntity::getId, Function.identity()));

        return integrations.stream().map(integration -> {
            List<ExternalApiOperationEntity> selected = operationIdsByIntegration
                    .getOrDefault(integration.getId(), List.of()).stream()
                    .map(operations::get)
                    .filter(Objects::nonNull)
                    .toList();
            return integrationView(
                    integration,
                    projects.get(integration.getProjectId()),
                    entries.get(integration.getEntryId()),
                    versions.get(integration.getVersionId()),
                    selected);
        }).toList();
    }

    private ExternalApiCatalogViews.IntegrationView integrationView(
            ProjectExternalApiEntity integration,
            ScanProjectEntity project,
            ExternalApiEntryEntity entry,
            ExternalApiVersionEntity version,
            List<ExternalApiOperationEntity> operations) {
        Lookup lookup = lookup(entry == null ? List.of() : List.of(entry));
        return new ExternalApiCatalogViews.IntegrationView(
                integration.getId(),
                integration.getProjectId(),
                integration.getProjectCode(),
                project == null ? integration.getProjectCode() : project.getName(),
                integration.getEnvironment(),
                integration.getStatus(),
                integration.getNote(),
                integration.getCreatedAt(),
                integration.getUpdatedAt(),
                entry == null ? null : summary(entry, lookup.providers(), lookup.sources()),
                version == null ? null : versionView(version, false),
                operations.stream().map(this::operationView).toList(),
                entry != null && requiresCredential(entry)
        );
    }

    private ExternalApiCatalogViews.VersionView versionView(ExternalApiVersionEntity version, boolean includeOperations) {
        List<ExternalApiCatalogViews.OperationView> operations = includeOperations
                ? operationMapper.selectList(Wrappers.<ExternalApiOperationEntity>lambdaQuery()
                        .eq(ExternalApiOperationEntity::getVersionId, version.getId())
                        .eq(ExternalApiOperationEntity::getStatus, "ACTIVE")
                        .orderByAsc(ExternalApiOperationEntity::getPath)
                        .orderByAsc(ExternalApiOperationEntity::getHttpMethod))
                        .stream().map(this::operationView).toList()
                : List.of();
        return new ExternalApiCatalogViews.VersionView(
                version.getId(), version.getVersionKey(), version.getBaseUrl(), version.getOpenapiUrl(),
                version.getSpecHash(), version.getPublicationStatus(), version.getPublishedAt(),
                version.getDeprecatedAt(), operations);
    }

    private ExternalApiCatalogViews.OperationView operationView(ExternalApiOperationEntity operation) {
        return new ExternalApiCatalogViews.OperationView(
                operation.getId(),
                operation.getOperationKey(),
                operation.getOperationId(),
                operation.getTitle(),
                operation.getDescription(),
                operation.getHttpMethod(),
                operation.getPath(),
                operation.getSideEffect(),
                Boolean.TRUE.equals(operation.getAuthRequired()),
                parseJson(operation.getRequestSchemaJson()),
                parseJson(operation.getResponseSchemaJson()),
                parseObjectMap(operation.getExampleParamsJson()),
                operation.getStatus()
        );
    }

    private ExternalApiCatalogViews.VerificationView verificationView(
            ExternalApiVerificationEntity verification) {
        return new ExternalApiCatalogViews.VerificationView(
                verification.getId(),
                verification.getVersionId(),
                verification.getVerificationType(),
                verification.getStatus(),
                verification.getHttpStatus(),
                verification.getLatencyMs(),
                verification.getCheckedUrl(),
                verification.getEvidenceSummary(),
                verification.getCheckedAt()
        );
    }

    private ExternalApiCatalogViews.EntrySummary summary(
            ExternalApiEntryEntity entry,
            Map<Long, ExternalApiProviderEntity> providers,
            Map<Long, ExternalApiSourceEntity> sources) {
        return new ExternalApiCatalogViews.EntrySummary(
                entry.getId(),
                entry.getEntryKey(),
                entry.getTitle(),
                entry.getSummary(),
                entry.getCategoryCode(),
                parseTags(entry.getTagsJson()),
                entry.getAuthType(),
                entry.getPricingType(),
                Boolean.TRUE.equals(entry.getHttpsSupported()),
                entry.getCorsPolicy(),
                entry.getPublicationStatus(),
                entry.getVerificationStatus(),
                entry.getSpecStatus(),
                Boolean.TRUE.equals(entry.getFeatured()),
                entry.getPopularityScore() == null ? 0 : entry.getPopularityScore(),
                entry.getLastVerifiedAt(),
                providerView(entry.getProviderId() == null ? null : providers.get(entry.getProviderId())),
                sourceView(entry.getSourceId() == null ? null : sources.get(entry.getSourceId()), false)
        );
    }

    private ExternalApiCatalogViews.ProviderView providerView(ExternalApiProviderEntity provider) {
        if (provider == null) {
            return null;
        }
        return new ExternalApiCatalogViews.ProviderView(
                provider.getId(), provider.getProviderKey(), provider.getName(), provider.getHomepageUrl(),
                provider.getLogoUrl(), Boolean.TRUE.equals(provider.getVerified()), provider.getStatus());
    }

    private ExternalApiCatalogViews.SourceView sourceView(ExternalApiSourceEntity source) {
        return sourceView(source, true);
    }

    private ExternalApiCatalogViews.SourceView sourceView(ExternalApiSourceEntity source, boolean includeCount) {
        if (source == null) {
            return null;
        }
        long entryCount = includeCount
                ? countEntries(Wrappers.<ExternalApiEntryEntity>lambdaQuery()
                        .eq(ExternalApiEntryEntity::getSourceId, source.getId())
                        .eq(ExternalApiEntryEntity::getPublicationStatus, PUBLISHED))
                : 0;
        return new ExternalApiCatalogViews.SourceView(
                source.getId(), source.getSourceKey(), source.getName(), source.getSourceType(),
                source.getSourceUrl(), source.getHomepageUrl(), source.getDescription(),
                source.getSyncStrategy(), source.getTrustLevel(), source.getStatus(),
                source.getLastSyncedAt(), entryCount);
    }

    private Lookup lookup(Collection<ExternalApiEntryEntity> entries) {
        Set<Long> providerIds = entries.stream().map(ExternalApiEntryEntity::getProviderId)
                .filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> sourceIds = entries.stream().map(ExternalApiEntryEntity::getSourceId)
                .filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, ExternalApiProviderEntity> providers = providerIds.isEmpty()
                ? Map.of()
                : providerMapper.selectBatchIds(providerIds).stream()
                .collect(Collectors.toMap(ExternalApiProviderEntity::getId, Function.identity()));
        Map<Long, ExternalApiSourceEntity> sources = sourceIds.isEmpty()
                ? Map.of()
                : sourceMapper.selectBatchIds(sourceIds).stream()
                .collect(Collectors.toMap(ExternalApiSourceEntity::getId, Function.identity()));
        return new Lookup(providers, sources);
    }

    private List<Long> providerIdsMatching(String keyword) {
        if (!StringUtils.hasText(keyword)) {
            return List.of();
        }
        String term = keyword.trim();
        return providerMapper.selectList(Wrappers.<ExternalApiProviderEntity>lambdaQuery()
                        .like(ExternalApiProviderEntity::getName, term)
                        .or().like(ExternalApiProviderEntity::getProviderKey, term))
                .stream().map(ExternalApiProviderEntity::getId).toList();
    }

    private ExternalApiSourceEntity findSource(String sourceKey) {
        return sourceMapper.selectOne(Wrappers.<ExternalApiSourceEntity>lambdaQuery()
                .eq(ExternalApiSourceEntity::getSourceKey, sourceKey.trim())
                .last("LIMIT 1"));
    }

    private ExternalApiEntryEntity requireEntry(String entryKey) {
        if (!StringUtils.hasText(entryKey)) {
            throw badRequest("API_MARKET_ENTRY_KEY_REQUIRED", "API 标识不能为空");
        }
        ExternalApiEntryEntity entry = entryMapper.selectOne(Wrappers.<ExternalApiEntryEntity>lambdaQuery()
                .eq(ExternalApiEntryEntity::getEntryKey, entryKey.trim())
                .eq(ExternalApiEntryEntity::getPublicationStatus, PUBLISHED)
                .last("LIMIT 1"));
        if (entry == null) {
            throw notFound("API_MARKET_ENTRY_NOT_FOUND", "未找到已发布的 API：" + entryKey);
        }
        return entry;
    }

    private ScanProjectEntity requireProject(Long projectId, String projectCode) {
        ScanProjectEntity project = null;
        if (projectId != null) {
            project = projectMapper.selectById(projectId);
        } else if (StringUtils.hasText(projectCode)) {
            project = projectMapper.selectOne(Wrappers.<ScanProjectEntity>lambdaQuery()
                    .eq(ScanProjectEntity::getProjectCode, projectCode.trim())
                    .last("LIMIT 1"));
        }
        if (project == null) {
            throw notFound("API_MARKET_PROJECT_NOT_FOUND", "请选择有效的接入项目");
        }
        if (StringUtils.hasText(projectCode)
                && !projectCode.trim().equals(project.getProjectCode())) {
            throw badRequest("API_MARKET_PROJECT_MISMATCH", "projectId 与 projectCode 不匹配");
        }
        return project;
    }

    private ExternalApiVersionEntity requireVersion(Long entryId, Long versionId) {
        ExternalApiVersionEntity version;
        if (versionId != null) {
            version = versionMapper.selectById(versionId);
            if (version == null
                    || !entryId.equals(version.getEntryId())
                    || !PUBLISHED.equals(version.getPublicationStatus())) {
                throw badRequest("API_MARKET_VERSION_INVALID", "所选版本不属于当前 API");
            }
        } else {
            version = versionMapper.selectOne(Wrappers.<ExternalApiVersionEntity>lambdaQuery()
                    .eq(ExternalApiVersionEntity::getEntryId, entryId)
                    .eq(ExternalApiVersionEntity::getPublicationStatus, PUBLISHED)
                    .orderByDesc(ExternalApiVersionEntity::getPublishedAt)
                    .orderByDesc(ExternalApiVersionEntity::getId)
                    .last("LIMIT 1"));
        }
        if (version == null) {
            throw badRequest("API_MARKET_VERSION_MISSING", "当前 API 没有可接入的已发布版本");
        }
        return version;
    }

    private List<ExternalApiOperationEntity> requireOperations(Long versionId, List<Long> requestedIds) {
        List<Long> ids = requestedIds == null ? List.of() : requestedIds.stream()
                .filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            throw badRequest("API_MARKET_OPERATION_REQUIRED", "请至少选择一个 Operation");
        }
        Map<Long, ExternalApiOperationEntity> byId = operationMapper.selectBatchIds(ids).stream()
                .filter(operation -> versionId.equals(operation.getVersionId()))
                .filter(operation -> "ACTIVE".equals(operation.getStatus()))
                .collect(Collectors.toMap(ExternalApiOperationEntity::getId, Function.identity()));
        List<ExternalApiOperationEntity> ordered = new ArrayList<>();
        for (Long id : ids) {
            ExternalApiOperationEntity operation = byId.get(id);
            if (operation == null) {
                throw badRequest("API_MARKET_OPERATION_INVALID", "所选 Operation 不属于当前 API 版本：" + id);
            }
            ordered.add(operation);
        }
        return List.copyOf(ordered);
    }

    private ProjectExternalApiEntity requireIntegration(Long integrationId) {
        if (integrationId == null) {
            throw badRequest("API_MARKET_INTEGRATION_ID_REQUIRED", "接入记录 ID 不能为空");
        }
        ProjectExternalApiEntity integration = integrationMapper.selectById(integrationId);
        if (integration == null) {
            throw notFound("API_MARKET_INTEGRATION_NOT_FOUND", "未找到 API 接入记录：" + integrationId);
        }
        return integration;
    }

    private long countEntries(LambdaQueryWrapper<ExternalApiEntryEntity> query) {
        Long count = entryMapper.selectCount(query);
        return count == null ? 0 : count;
    }

    private boolean requiresCredential(ExternalApiEntryEntity entry) {
        return !"NONE".equalsIgnoreCase(entry.getAuthType());
    }

    private String normalizeEnvironment(String requested, String fallback) {
        String value = StringUtils.hasText(requested) ? requested : fallback;
        String normalized = StringUtils.hasText(value) ? normalize(value) : "DEVELOPMENT";
        if ("DEV".equals(normalized)) {
            normalized = "DEVELOPMENT";
        } else if ("PROD".equals(normalized)) {
            normalized = "PRODUCTION";
        }
        if (!INTEGRATION_ENVIRONMENTS.contains(normalized)) {
            throw badRequest("API_MARKET_ENVIRONMENT_INVALID",
                    "接入环境必须是 DEVELOPMENT、TEST、STAGING 或 PRODUCTION");
        }
        return normalized;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String trimTo(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private List<String> parseTags(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private Object parseJson(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Map<String, Object> parseObjectMap(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, OBJECT_MAP);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private ExternalApiCatalogException badRequest(String code, String message) {
        return new ExternalApiCatalogException(HttpStatus.BAD_REQUEST, code, message);
    }

    private ExternalApiCatalogException notFound(String code, String message) {
        return new ExternalApiCatalogException(HttpStatus.NOT_FOUND, code, message);
    }

    private record Lookup(
            Map<Long, ExternalApiProviderEntity> providers,
            Map<Long, ExternalApiSourceEntity> sources) {
    }
}
