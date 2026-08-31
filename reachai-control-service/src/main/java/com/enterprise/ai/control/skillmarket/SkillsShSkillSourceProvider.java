package com.enterprise.ai.control.skillmarket;

import com.enterprise.ai.control.skillmarket.SkillMarketContracts.MarketSkill;
import com.enterprise.ai.control.skillmarket.SkillMarketContracts.SearchRequest;
import com.enterprise.ai.control.skillmarket.SkillMarketContracts.SearchResult;
import com.enterprise.ai.control.skillmarket.SkillMarketHttpTransport.HttpPayload;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class SkillsShSkillSourceProvider implements SkillSourceProvider {

    public static final String PROVIDER_KEY = "SKILLS_SH";
    private static final Set<String> VIEWS = Set.of("all-time", "trending", "hot");

    private final SkillMarketProperties properties;
    private final SkillMarketHttpTransport transport;
    private final ObjectMapper objectMapper;

    @Override
    public String key() {
        return PROVIDER_KEY;
    }

    @Override
    public SearchResult search(SearchRequest request) {
        if (!properties.isEnabled()) throw SkillMarketErrors.disabled();
        SearchRequest input = normalize(request);
        String token = trim(properties.getSkillsSh().getOidcToken());
        if (StringUtils.hasText(token)) {
            return officialSearch(input, token);
        }
        if (!properties.getSkillsSh().isLegacySearchEnabled()) {
            return new SearchResult(PROVIDER_KEY, "SOURCE_DISCOVERY_ONLY", input.query(), input.view(),
                    0, false,
                    "skills.sh official API requires Vercel OIDC; public search fallback is disabled",
                    List.of());
        }
        if (!StringUtils.hasText(input.query())) {
            return new SearchResult(PROVIDER_KEY, "SOURCE_DISCOVERY_ONLY", null, input.view(),
                    0, false,
                    "Enter at least two characters to search. Leaderboards require a configured Vercel OIDC token.",
                    List.of());
        }
        return legacySearch(input);
    }

    private SearchResult officialSearch(SearchRequest input, String token) {
        String path;
        if (StringUtils.hasText(input.query())) {
            path = "/api/v1/skills/search?q=" + encode(input.query()) + "&limit=" + input.limit();
            if (StringUtils.hasText(input.owner())) path += "&owner=" + encode(input.owner());
        } else {
            path = "/api/v1/skills?view=" + encode(input.view()) + "&page=0&per_page=" + input.limit();
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        headers.put("Authorization", "Bearer " + token);
        HttpPayload response = transport.get(uri(path), headers, Set.of("skills.sh", "www.skills.sh"),
                properties.getMaxJsonBytes());
        JsonNode root = requireJson(response, "skills.sh official API");
        List<MarketSkill> items = parseItems(root.path("data"));
        return new SearchResult(PROVIDER_KEY, "OFFICIAL_V1", input.query(), input.view(),
                items.size(), true,
                "Official skills.sh API response; package bytes are still re-resolved from the immutable GitHub commit",
                items);
    }

    private SearchResult legacySearch(SearchRequest input) {
        String path = "/api/search?q=" + encode(input.query()) + "&limit=" + input.limit();
        HttpPayload response = transport.get(uri(path), Map.of("Accept", "application/json"),
                Set.of("skills.sh", "www.skills.sh"), properties.getMaxJsonBytes());
        JsonNode root = requireJson(response, "skills.sh public search");
        List<MarketSkill> items = parseItems(root.path("skills"));
        if (StringUtils.hasText(input.owner())) {
            String ownerPrefix = input.owner().toLowerCase(Locale.ROOT) + "/";
            items = items.stream()
                    .filter(item -> item.source() != null
                            && item.source().toLowerCase(Locale.ROOT).startsWith(ownerPrefix))
                    .toList();
        }
        return new SearchResult(PROVIDER_KEY, "LEGACY_PUBLIC_SEARCH", input.query(), input.view(),
                items.size(), false,
                "Public skills.sh search fallback; popularity is discovery metadata, not a ReachAI trust decision",
                items);
    }

    private List<MarketSkill> parseItems(JsonNode array) {
        if (array == null || !array.isArray()) return List.of();
        List<MarketSkill> items = new ArrayList<>();
        for (JsonNode node : array) {
            String id = text(node, "id");
            String source = text(node, "source");
            String slug = firstText(text(node, "slug"), text(node, "skillId"));
            if (!StringUtils.hasText(id) && StringUtils.hasText(source) && StringUtils.hasText(slug)) {
                id = source + "/" + slug;
            }
            if (!StringUtils.hasText(id) || !StringUtils.hasText(source) || !StringUtils.hasText(slug)) continue;
            String sourceType = firstText(text(node, "sourceType"), isGithubSource(source) ? "github" : "well-known");
            String installUrl = text(node, "installUrl");
            if (!StringUtils.hasText(installUrl) && "github".equalsIgnoreCase(sourceType)
                    && isGithubSource(source)) {
                installUrl = "https://github.com/" + source;
            }
            String marketUrl = firstText(text(node, "url"), "https://skills.sh/" + id);
            items.add(new MarketSkill(
                    id,
                    slug,
                    firstText(text(node, "name"), slug),
                    text(node, "description"),
                    source,
                    sourceType,
                    node.path("installs").asLong(0L),
                    installUrl,
                    marketUrl,
                    node.path("isDuplicate").asBoolean(false),
                    "AGGREGATED",
                    "github".equalsIgnoreCase(sourceType) && StringUtils.hasText(installUrl)));
        }
        return List.copyOf(items);
    }

    private JsonNode requireJson(HttpPayload response, String upstreamName) {
        if (response.status() < 200 || response.status() >= 300) {
            String message = null;
            try {
                JsonNode error = objectMapper.readTree(response.body());
                message = firstText(text(error, "message"), text(error, "error"));
            } catch (Exception ignored) {
                // Stable ReachAI error below intentionally hides arbitrary upstream bodies.
            }
            throw SkillMarketErrors.upstream(upstreamName + " returned HTTP " + response.status()
                    + (StringUtils.hasText(message) ? ": " + message : ""));
        }
        try {
            return objectMapper.readTree(response.body());
        } catch (Exception invalidJson) {
            throw SkillMarketErrors.upstream(upstreamName + " returned invalid JSON", invalidJson);
        }
    }

    private SearchRequest normalize(SearchRequest request) {
        String query = trim(request == null ? null : request.query());
        if (StringUtils.hasText(query) && (query.length() < 2 || query.length() > 120)) {
            throw SkillMarketErrors.invalid("Skill search query must contain 2 to 120 characters");
        }
        String owner = trim(request == null ? null : request.owner());
        if (StringUtils.hasText(owner) && !owner.matches("^[A-Za-z0-9_.-]{1,100}$")) {
            throw SkillMarketErrors.invalid("Skill search owner is invalid");
        }
        String view = firstText(trim(request == null ? null : request.view()), "all-time").toLowerCase(Locale.ROOT);
        if (!VIEWS.contains(view)) {
            throw SkillMarketErrors.invalid("Skill market view must be all-time, trending, or hot");
        }
        int limit = request == null ? 24 : request.limit();
        if (limit <= 0) limit = 24;
        limit = Math.min(limit, 50);
        return new SearchRequest(query, view, owner, limit);
    }

    private URI uri(String pathAndQuery) {
        String base = trim(properties.getSkillsSh().getBaseUrl());
        if (!StringUtils.hasText(base)) base = "https://skills.sh";
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        try {
            return URI.create(base + pathAndQuery);
        } catch (IllegalArgumentException invalidUri) {
            throw SkillMarketErrors.unsupportedSource("Configured skills.sh base URL is invalid");
        }
    }

    private String text(JsonNode node, String field) {
        if (node == null || node.path(field).isMissingNode() || node.path(field).isNull()) return null;
        String value = node.path(field).asText(null);
        return trim(value);
    }

    private boolean isGithubSource(String source) {
        return StringUtils.hasText(source)
                && source.matches("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$");
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String trim(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String firstText(String first, String second) {
        return StringUtils.hasText(first) ? first : second;
    }
}
