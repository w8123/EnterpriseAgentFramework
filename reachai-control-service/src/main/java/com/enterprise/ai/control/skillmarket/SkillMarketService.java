package com.enterprise.ai.control.skillmarket;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.control.agentskill.AgentSkillAccessPolicy;
import com.enterprise.ai.control.agentskill.AgentSkillCatalogService;
import com.enterprise.ai.control.agentskill.AgentSkillContracts.ImportCommand;
import com.enterprise.ai.control.agentskill.AgentSkillContracts.SkillSummary;
import com.enterprise.ai.control.agentskill.AgentSkillPackageInspector;
import com.enterprise.ai.control.agentskill.AgentSkillPackageInspector.SelectedPackage;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.skillmarket.GitHubSkillRepositoryClient.RepositoryArchive;
import com.enterprise.ai.control.skillmarket.SkillMarketContracts.ImportOrigin;
import com.enterprise.ai.control.skillmarket.SkillMarketContracts.ImportRequest;
import com.enterprise.ai.control.skillmarket.SkillMarketContracts.MarketImportResult;
import com.enterprise.ai.control.skillmarket.SkillMarketContracts.MarketSkill;
import com.enterprise.ai.control.skillmarket.SkillMarketContracts.MarketSource;
import com.enterprise.ai.control.skillmarket.SkillMarketContracts.ProbeRequest;
import com.enterprise.ai.control.skillmarket.SkillMarketContracts.ProbeResult;
import com.enterprise.ai.control.skillmarket.SkillMarketContracts.SearchRequest;
import com.enterprise.ai.control.skillmarket.SkillMarketContracts.SearchResult;
import com.enterprise.ai.control.skillmarket.SkillMarketImportWriter.OriginDraft;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SkillMarketService {

    private final List<SkillSourceProvider> providers;
    private final SkillMarketSourceMapper sourceMapper;
    private final SkillMarketImportMapper importMapper;
    private final GitHubSkillRepositoryClient githubClient;
    private final AgentSkillPackageInspector packageInspector;
    private final SkillMarketImportWriter importWriter;
    private final AgentSkillCatalogService catalogService;
    private final AgentSkillAccessPolicy accessPolicy;

    public SearchResult search(String provider,
                               String query,
                               String view,
                               String owner,
                               int limit) {
        SkillSourceProvider sourceProvider = provider(provider);
        SearchResult result = sourceProvider.search(new SearchRequest(query, view, owner, limit));
        Map<String, MarketSource> repositories = repositorySources();
        List<MarketSkill> enriched = result.items().stream()
                .map(item -> enrich(item, repositories))
                .toList();
        return new SearchResult(result.provider(), result.mode(), result.query(), result.view(),
                enriched.size(), result.authenticatedUpstream(), result.message(), enriched);
    }

    public List<MarketSource> sources() {
        return sourceMapper.selectList(Wrappers.<SkillMarketSourceEntity>lambdaQuery()
                        .orderByAsc(SkillMarketSourceEntity::getDisplayOrder)
                        .orderByAsc(SkillMarketSourceEntity::getId))
                .stream()
                .map(this::sourceView)
                .toList();
    }

    public ProbeResult probe(ProbeRequest request) {
        return githubClient.probe(request).result();
    }

    public MarketImportResult importFromMarket(ImportRequest request,
                                               PlatformAuthenticatedSession session,
                                               String operator) {
        if (request == null) throw SkillMarketErrors.invalid("Skill market import request is required");
        RepositoryArchive archive = githubClient.fetchExact(request.sourceUrl(), request.commitSha());
        SelectedPackage selected = packageInspector.selectFromBundle(archive.archive(), request.sourceRoot());
        String expectedDigest = trim(request.expectedSelectedSourceSha256());
        if (!StringUtils.hasText(expectedDigest) || !expectedDigest.matches("^[0-9a-fA-F]{64}$")) {
            throw SkillMarketErrors.invalid("The selected package SHA-256 from the probe is required");
        }
        if (!selected.inspection().sourceSha256().equalsIgnoreCase(expectedDigest)) {
            throw SkillMarketErrors.changed(
                    "The selected Skill bytes no longer match the package inspected during the market probe");
        }
        String providerKey = limited(firstText(trim(request.marketplaceProvider()), "GITHUB"), 64,
                "marketplaceProvider").toUpperCase(Locale.ROOT);
        String marketplaceSkillId = limited(trim(request.marketplaceSkillId()), 256, "marketplaceSkillId");
        String publisher = firstText(trim(request.publisher()), archive.reference().owner().toLowerCase(Locale.ROOT));
        String sourceRoot = limited(selected.sourceRoot(), 512, "sourceRoot");
        String sourceRef = sourceRef(providerKey, marketplaceSkillId, archive, sourceRoot);
        Long userId = session == null || session.user() == null ? null : session.user().getId();
        ImportCommand command = new ImportCommand(
                publisher,
                trim(request.version()),
                trim(request.displayName()),
                trim(request.visibility()),
                "MARKET",
                sourceRef,
                operator,
                userId,
                trim(request.projectCode()));
        OriginDraft origin = new OriginDraft(
                providerKey,
                marketplaceSkillId,
                archive.reference().repositoryKey(),
                archive.metadata().repositoryUrl(),
                archive.metadata().commitSha(),
                repositoryRelativeRoot(sourceRoot),
                archive.bundleSourceSha256(),
                userId,
                operator);
        return importWriter.importSelected(selected.archive(), command, origin);
    }

    public List<ImportOrigin> imports(PlatformAuthenticatedSession session, int requestedLimit) {
        int limit = requestedLimit <= 0 ? 50 : Math.min(requestedLimit, 200);
        return importMapper.selectList(Wrappers.<SkillMarketImportEntity>lambdaQuery()
                        .orderByDesc(SkillMarketImportEntity::getCreatedAt)
                        .orderByDesc(SkillMarketImportEntity::getId)
                        .last("LIMIT " + limit))
                .stream()
                .filter(entity -> canReadImport(session, entity))
                .map(SkillMarketImportWriter::view)
                .toList();
    }

    private boolean canReadImport(PlatformAuthenticatedSession session, SkillMarketImportEntity entity) {
        try {
            SkillSummary skill = catalogService.detail(entity.getSkillId()).skill();
            return accessPolicy.canAccess(session, "skill:read", skill);
        } catch (RuntimeException missingOrInaccessible) {
            return false;
        }
    }

    private SkillSourceProvider provider(String requested) {
        String key = firstText(trim(requested), SkillsShSkillSourceProvider.PROVIDER_KEY);
        return providers.stream()
                .filter(candidate -> candidate.key().equalsIgnoreCase(key))
                .findFirst()
                .orElseThrow(() -> SkillMarketErrors.unsupportedSource(
                        "Skill market provider is not configured: " + key));
    }

    private MarketSkill enrich(MarketSkill item, Map<String, MarketSource> repositories) {
        MarketSource curated = repositories.get(item.source() == null
                ? "" : item.source().toLowerCase(Locale.ROOT));
        String trust = curated == null ? "COMMUNITY" : curated.trustLevel();
        boolean importable = item.importable() && !"RETIRED".equalsIgnoreCase(
                curated == null ? null : curated.status());
        return item.withTrust(trust, importable);
    }

    private Map<String, MarketSource> repositorySources() {
        Map<String, MarketSource> values = new LinkedHashMap<>();
        for (MarketSource source : sources()) {
            String repository = repositoryKey(source.repositoryUrl());
            if (repository != null) values.put(repository, source);
        }
        return values;
    }

    private String repositoryKey(String repositoryUrl) {
        if (!StringUtils.hasText(repositoryUrl)) return null;
        String value = repositoryUrl.trim().replace('\\', '/').replaceAll("/+$", "");
        int github = value.toLowerCase(Locale.ROOT).indexOf("github.com/");
        if (github >= 0) value = value.substring(github + "github.com/".length());
        if (value.toLowerCase(Locale.ROOT).endsWith(".git")) value = value.substring(0, value.length() - 4);
        String[] parts = value.split("/");
        return parts.length == 2 ? value.toLowerCase(Locale.ROOT) : null;
    }

    private MarketSource sourceView(SkillMarketSourceEntity entity) {
        return new MarketSource(
                entity.getId(),
                entity.getSourceKey(),
                entity.getDisplayName(),
                entity.getSourceType(),
                entity.getBaseUrl(),
                entity.getRepositoryUrl(),
                entity.getTrustLevel(),
                entity.getStatus(),
                Boolean.TRUE.equals(entity.getSupportsSearch()),
                Boolean.TRUE.equals(entity.getSupportsImport()),
                Boolean.TRUE.equals(entity.getOfficial()),
                entity.getDescription(),
                entity.getDisplayOrder() == null ? 0 : entity.getDisplayOrder(),
                entity.getUpdatedAt());
    }

    private String sourceRef(String providerKey,
                             String marketplaceSkillId,
                             RepositoryArchive archive,
                             String sourceRoot) {
        StringBuilder value = new StringBuilder();
        value.append(providerKey.toLowerCase(Locale.ROOT)).append(':');
        value.append(StringUtils.hasText(marketplaceSkillId)
                ? marketplaceSkillId : archive.reference().repositoryKey());
        value.append('@').append(archive.metadata().commitSha());
        String relativeRoot = repositoryRelativeRoot(sourceRoot);
        if (StringUtils.hasText(relativeRoot)) value.append('#').append(relativeRoot);
        String result = value.toString();
        if (result.length() <= 512) return result;
        return result.substring(0, 220) + "~" + result.substring(result.length() - 291);
    }

    private String repositoryRelativeRoot(String sourceRoot) {
        if (!StringUtils.hasText(sourceRoot)) return "";
        String normalized = sourceRoot.replace('\\', '/').replaceAll("^/+|/+$", "");
        int slash = normalized.indexOf('/');
        return slash < 0 ? "" : normalized.substring(slash + 1);
    }

    private String limited(String value, int maxLength, String field) {
        if (!StringUtils.hasText(value)) return null;
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw SkillMarketErrors.invalid(field + " must not exceed " + maxLength + " characters");
        }
        return normalized;
    }

    private String trim(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String firstText(String first, String second) {
        return StringUtils.hasText(first) ? first : second;
    }
}
