package com.enterprise.ai.control.skillmarket;

import com.enterprise.ai.control.agentskill.AgentSkillPackageInspector;
import com.enterprise.ai.control.agentskill.AgentSkillPackageInspector.BundleCandidate;
import com.enterprise.ai.control.agentskill.AgentSkillPackageInspector.BundleDiscovery;
import com.enterprise.ai.control.skillmarket.SkillMarketContracts.ProbeRequest;
import com.enterprise.ai.control.skillmarket.SkillMarketContracts.ProbeResult;
import com.enterprise.ai.control.skillmarket.SkillMarketContracts.RepositoryMetadata;
import com.enterprise.ai.control.skillmarket.SkillMarketHttpTransport.HttpPayload;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class GitHubSkillRepositoryClient {

    private static final Pattern OWNER = Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$");
    private static final Pattern REPOSITORY = Pattern.compile("^[A-Za-z0-9_.-]{1,100}$");
    private static final Pattern COMMIT_SHA = Pattern.compile("^[0-9a-fA-F]{40}$");

    private final SkillMarketProperties properties;
    private final SkillMarketHttpTransport transport;
    private final AgentSkillPackageInspector packageInspector;
    private final ObjectMapper objectMapper;

    public ProbeArtifact probe(ProbeRequest request) {
        if (!properties.isEnabled()) throw SkillMarketErrors.disabled();
        RepositoryArchive archive = fetch(request == null ? null : request.sourceUrl(), null);
        BundleDiscovery discovery = packageInspector.discoverBundle(archive.archive());
        String expectedName = trim(request == null ? null : request.expectedSkillName());
        String suggestedRoot = suggestedRoot(discovery.candidates(), expectedName,
                archive.reference().requestedPath());
        List<String> warnings = new ArrayList<>();
        warnings.add("The upstream repository is untrusted until the selected package passes ReachAI review");
        warnings.add("Popularity and publisher names never grant Tool, network, credential, or script permissions");
        if (archive.metadata().archived()) {
            warnings.add("The GitHub repository is archived and may no longer receive security updates");
        }
        if (!StringUtils.hasText(archive.metadata().license())) {
            warnings.add("GitHub did not expose a repository license; reviewers must verify commercial-use rights");
        }
        ProbeResult result = new ProbeResult(
                "reachai.agent-skill-market-probe.v1",
                firstText(trim(request == null ? null : request.marketplaceProvider()), "GITHUB"),
                trim(request == null ? null : request.marketplaceSkillId()),
                archive.metadata(),
                discovery.bundleSourceSha256(),
                archive.archive().length,
                discovery.archiveFileCount(),
                discovery.candidateCount(),
                suggestedRoot,
                List.copyOf(warnings),
                discovery.candidates());
        return new ProbeArtifact(result, archive.reference(), archive.archive());
    }

    public RepositoryArchive fetchExact(String sourceUrl, String commitSha) {
        if (!StringUtils.hasText(commitSha) || !COMMIT_SHA.matcher(commitSha.trim()).matches()) {
            throw SkillMarketErrors.invalid("A full 40-character Git commit SHA from the probe is required");
        }
        return fetch(sourceUrl, commitSha.trim().toLowerCase(Locale.ROOT));
    }

    private RepositoryArchive fetch(String sourceUrl, String refOverride) {
        RepositoryRef reference = parseSource(sourceUrl);
        JsonNode repository = getJson(apiUri("/repos/" + reference.owner() + "/" + reference.repository()),
                "GitHub repository metadata");
        if (repository.path("private").asBoolean(false)) {
            throw SkillMarketErrors.unsupportedSource(
                    "Private GitHub repositories require a separately configured enterprise source connector");
        }
        String defaultBranch = requiredText(repository, "default_branch", "GitHub repository has no default branch");
        String requestedRef = firstText(refOverride, firstText(reference.ref(), defaultBranch));
        JsonNode commit = getJson(apiUri("/repos/" + reference.owner() + "/" + reference.repository()
                + "/commits/" + encodePathSegment(requestedRef)), "GitHub commit metadata");
        String commitSha = requiredText(commit, "sha", "GitHub did not return an immutable commit SHA")
                .toLowerCase(Locale.ROOT);
        if (!COMMIT_SHA.matcher(commitSha).matches()) {
            throw SkillMarketErrors.upstream("GitHub returned an invalid commit identity");
        }
        if (StringUtils.hasText(refOverride) && !commitSha.equalsIgnoreCase(refOverride)) {
            throw SkillMarketErrors.changed("The requested GitHub commit no longer resolves to the probed identity");
        }
        URI archiveUri = apiUri("/repos/" + reference.owner() + "/" + reference.repository()
                + "/zipball/" + commitSha);
        Map<String, String> headers = githubHeaders("application/vnd.github+json");
        Set<String> hosts = Set.of(apiHost(), "codeload.github.com");
        HttpPayload response = transport.get(archiveUri, headers, hosts,
                packageInspector.bundleLimits().maxArchiveBytes());
        if (response.status() < 200 || response.status() >= 300) {
            throw SkillMarketErrors.upstream("GitHub repository archive returned HTTP " + response.status());
        }
        byte[] archive = response.body();
        RepositoryMetadata metadata = new RepositoryMetadata(
                reference.owner(),
                reference.repository(),
                firstText(text(repository, "html_url"),
                        "https://github.com/" + reference.owner() + "/" + reference.repository()),
                defaultBranch,
                commitSha,
                text(repository, "description"),
                repository.path("license").isObject() ? text(repository.path("license"), "spdx_id") : null,
                repository.path("stargazers_count").asLong(0L),
                repository.path("archived").asBoolean(false),
                parseDate(text(repository, "updated_at")));
        return new RepositoryArchive(reference, metadata,
                AgentSkillPackageInspector.sha256(archive), archive);
    }

    private JsonNode getJson(URI uri, String label) {
        HttpPayload response = transport.get(uri, githubHeaders("application/vnd.github+json"),
                Set.of(apiHost()), properties.getMaxJsonBytes());
        if (response.status() < 200 || response.status() >= 300) {
            String message = null;
            try {
                message = text(objectMapper.readTree(response.body()), "message");
            } catch (Exception ignored) {
                // Stable ReachAI message below intentionally excludes arbitrary upstream bodies.
            }
            throw SkillMarketErrors.upstream(label + " returned HTTP " + response.status()
                    + (StringUtils.hasText(message) ? ": " + message : ""));
        }
        try {
            return objectMapper.readTree(response.body());
        } catch (Exception invalidJson) {
            throw SkillMarketErrors.upstream(label + " returned invalid JSON", invalidJson);
        }
    }

    private RepositoryRef parseSource(String sourceUrl) {
        String input = trim(sourceUrl);
        if (!StringUtils.hasText(input)) {
            throw SkillMarketErrors.invalid("A public GitHub repository URL is required");
        }
        if (input.matches("^[A-Za-z0-9-]{1,39}/[A-Za-z0-9_.-]{1,100}$")) {
            String[] parts = input.split("/", 2);
            return new RepositoryRef(parts[0], stripGit(parts[1]), null, null);
        }
        URI uri;
        try {
            uri = URI.create(input);
        } catch (IllegalArgumentException invalidUri) {
            throw SkillMarketErrors.unsupportedSource("GitHub source URL is invalid");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || !"github.com".equalsIgnoreCase(uri.getHost()) || uri.getPort() != -1
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw SkillMarketErrors.unsupportedSource(
                    "Only public https://github.com/{owner}/{repository} sources are supported");
        }
        List<String> segments = java.util.Arrays.stream(uri.getPath().split("/"))
                .filter(StringUtils::hasText)
                .toList();
        if (segments.size() < 2) {
            throw SkillMarketErrors.unsupportedSource("GitHub source must identify an owner and repository");
        }
        String owner = segments.get(0);
        String repository = stripGit(segments.get(1));
        String ref = null;
        String requestedPath = null;
        if (segments.size() > 2) {
            if (segments.size() < 4 || !("tree".equals(segments.get(2)) || "blob".equals(segments.get(2)))) {
                throw SkillMarketErrors.unsupportedSource(
                        "GitHub source may only append /tree/{ref}/{skill-path} or /blob/{ref}/{SKILL.md}");
            }
            ref = segments.get(3);
            if (segments.size() > 4) {
                requestedPath = String.join("/", segments.subList(4, segments.size()));
                if (requestedPath.endsWith("/SKILL.md")) {
                    requestedPath = requestedPath.substring(0,
                            requestedPath.length() - "/SKILL.md".length());
                } else if ("SKILL.md".equals(requestedPath)) {
                    requestedPath = "";
                }
            }
        }
        if (!OWNER.matcher(owner).matches() || !REPOSITORY.matcher(repository).matches()
                || ".".equals(repository) || "..".equals(repository)) {
            throw SkillMarketErrors.unsupportedSource("GitHub owner or repository name is invalid");
        }
        return new RepositoryRef(owner, repository, ref, requestedPath);
    }

    private String suggestedRoot(List<BundleCandidate> candidates,
                                 String expectedName,
                                 String requestedPath) {
        List<BundleCandidate> selectable = candidates.stream().filter(BundleCandidate::selectable).toList();
        if (StringUtils.hasText(requestedPath)) {
            String normalized = requestedPath.replace('\\', '/').replaceAll("^/+|/+$", "");
            BundleCandidate match = selectable.stream()
                    .filter(candidate -> candidate.sourceRoot().equals(normalized)
                            || candidate.sourceRoot().endsWith("/" + normalized))
                    .findFirst().orElse(null);
            if (match != null) return match.sourceRoot();
        }
        if (StringUtils.hasText(expectedName)) {
            BundleCandidate exact = selectable.stream()
                    .filter(candidate -> expectedName.equalsIgnoreCase(candidate.name()))
                    .findFirst().orElse(null);
            if (exact != null) return exact.sourceRoot();
            BundleCandidate byPath = selectable.stream()
                    .filter(candidate -> candidate.sourceRoot().toLowerCase(Locale.ROOT)
                            .endsWith("/" + expectedName.toLowerCase(Locale.ROOT)))
                    .findFirst().orElse(null);
            if (byPath != null) return byPath.sourceRoot();
        }
        return selectable.size() == 1 ? selectable.get(0).sourceRoot() : null;
    }

    private Map<String, String> githubHeaders(String accept) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", accept);
        headers.put("X-GitHub-Api-Version", "2022-11-28");
        String token = trim(properties.getGithub().getToken());
        if (StringUtils.hasText(token)) headers.put("Authorization", "Bearer " + token);
        return headers;
    }

    private URI apiUri(String path) {
        String base = trim(properties.getGithub().getApiBaseUrl());
        if (!StringUtils.hasText(base)) base = "https://api.github.com";
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        try {
            return URI.create(base + path);
        } catch (IllegalArgumentException invalidUri) {
            throw SkillMarketErrors.unsupportedSource("Configured GitHub API base URL is invalid");
        }
    }

    private String apiHost() {
        URI uri = apiUri("");
        if (!StringUtils.hasText(uri.getHost())) {
            throw SkillMarketErrors.unsupportedSource("Configured GitHub API host is invalid");
        }
        return uri.getHost().toLowerCase(Locale.ROOT);
    }

    private String requiredText(JsonNode node, String field, String message) {
        String value = text(node, field);
        if (!StringUtils.hasText(value)) throw SkillMarketErrors.upstream(message);
        return value;
    }

    private String text(JsonNode node, String field) {
        if (node == null || node.path(field).isMissingNode() || node.path(field).isNull()) return null;
        return trim(node.path(field).asText(null));
    }

    private LocalDateTime parseDate(String value) {
        if (!StringUtils.hasText(value)) return null;
        try {
            return OffsetDateTime.parse(value).toLocalDateTime();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String stripGit(String repository) {
        String value = trim(repository);
        if (value != null && value.toLowerCase(Locale.ROOT).endsWith(".git")) {
            value = value.substring(0, value.length() - 4);
        }
        return value;
    }

    private String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String trim(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String firstText(String first, String second) {
        return StringUtils.hasText(first) ? first : second;
    }

    public record RepositoryRef(String owner, String repository, String ref, String requestedPath) {
        public String repositoryKey() {
            return owner + "/" + repository;
        }
    }

    public record RepositoryArchive(
            RepositoryRef reference,
            RepositoryMetadata metadata,
            String bundleSourceSha256,
            byte[] archive) {

        public RepositoryArchive {
            archive = archive.clone();
        }

        @Override
        public byte[] archive() {
            return archive.clone();
        }
    }

    public record ProbeArtifact(ProbeResult result, RepositoryRef reference, byte[] archive) {
        public ProbeArtifact {
            archive = archive.clone();
        }

        @Override
        public byte[] archive() {
            return archive.clone();
        }
    }
}
