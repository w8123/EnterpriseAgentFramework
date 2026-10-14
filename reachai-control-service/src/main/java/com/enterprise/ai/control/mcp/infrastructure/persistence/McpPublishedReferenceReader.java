package com.enterprise.ai.control.mcp.infrastructure.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.control.mcp.application.port.McpPublishedReferenceQuery;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class McpPublishedReferenceReader implements McpPublishedReferenceQuery {
    private static final int LIMIT = 10_000;
    private final McpPublicationMapper publications;
    private final McpPublicationRevisionMapper revisions;
    private final ObjectMapper json;
    private static final int CACHE_BINDINGS = 20_000, CACHE_CONTENTS = 512, BODY_BATCH = 128;
    // Parsed facts depend only on the body, never publication/revision identity or current state.
    private final java.util.LinkedHashMap<String, ParsedRevision> parsed = new java.util.LinkedHashMap<>(16, 0.75f, true);
    private int cachedBindings;

    @Override
    @Transactional(readOnly = true)
    public Evidence inspect() {
        return inspectScope(null);
    }

    @Override
    @Transactional(readOnly = true)
    public Evidence inspect(Set<String> capabilityAliases, java.util.Map<Long, String> workflowVersions) {
        return inspectScope(new Scope(Set.copyOf(capabilityAliases), java.util.Map.copyOf(workflowVersions)));
    }

    private Evidence inspectScope(Scope scope) {
        var published = publications.selectList(Wrappers.<McpPublicationEntity>lambdaQuery()
                .select(McpPublicationEntity::getId, McpPublicationEntity::getName, McpPublicationEntity::getCurrentRevisionId)
                .eq(McpPublicationEntity::getState, "PUBLISHED")
                .orderByAsc(McpPublicationEntity::getId).last("limit " + (LIMIT + 1)));
        if (published.isEmpty()) return new Evidence(true, List.of());
        boolean complete = published.size() <= LIMIT;
        var byId = new LinkedHashMap<Long, McpPublicationEntity>();
        published.stream().limit(LIMIT).forEach(value -> byId.put(value.getId(), value));
        // One bounded batch for all publications, including historical pinned revisions.
        var frozen = revisions.referenceMetadata(byId.keySet(), LIMIT + 1);
        complete &= frozen.size() <= LIMIT;
        var selected = frozen.stream().limit(LIMIT).toList();
        java.util.Map<String, ParsedRevision> facts = new java.util.HashMap<>();
        var missing = new LinkedHashMap<String, McpPublicationRevisionMapper.ReferenceMetadata>();
        for (var revision : selected) {
            String digest = revision.getSnapshotFingerprint(); var cached = cached(digest);
            if (cached == null) missing.putIfAbsent(digest, revision); else facts.put(digest, cached);
        }
        var misses = new ArrayList<>(missing.values());
        for (int offset = 0; offset < misses.size(); offset += BODY_BATCH) {
            var batch = misses.subList(offset, Math.min(offset + BODY_BATCH, misses.size()));
            var bodies = revisions.referenceBodies(batch.stream().map(McpPublicationRevisionMapper.ReferenceMetadata::getId).toList());
            var byRevision = new java.util.HashMap<Long, McpPublicationRevisionEntity>();
            bodies.forEach(body -> byRevision.put(body.getId(), body));
            for (var metadata : batch) {
                var body = byRevision.get(metadata.getId());
                // Metadata and body may race with a repair. A changed digest fails closed for
                // this request; next request reads the new fingerprint, never an old success.
                ParsedRevision value = parse(metadata, body);
                facts.put(metadata.getSnapshotFingerprint(), value);
                if (body != null && sameBody(metadata, body)) cache(metadata.getSnapshotFingerprint(), value);
            }
        }
        Set<Long> currentFound = new HashSet<>();
        List<Binding> bindings = new ArrayList<>();
        for (var revision : selected) {
            var publication = byId.get(revision.getPublicationId());
            if (publication == null) { complete = false; continue; }
            if (revision.getId().equals(publication.getCurrentRevisionId())) currentFound.add(publication.getId());
            var value = facts.get(revision.getSnapshotFingerprint());
            complete &= value != null && value.complete();
            if (value != null) for (var binding : value.selected(scope)) bindings.add(new Binding(publication.getId(),
                    publication.getName(), revision.getRevisionNo(), binding.kind(), binding.ref(), binding.version()));
        }
        complete &= currentFound.containsAll(byId.keySet());
        return new Evidence(complete, bindings);
    }

    private ParsedRevision parse(McpPublicationRevisionMapper.ReferenceMetadata metadata, McpPublicationRevisionEntity body) {
        if (body == null || !sameBody(metadata, body)) return facts(false, List.of());
        boolean complete = true; Set<FrozenBinding> result = new java.util.LinkedHashSet<>();
        try {
            var tools = json.readTree(body.getToolsSnapshotJson());
            if (tools == null || !tools.isArray()) return facts(false, List.of());
            for (var tool : tools) {
                if (!tool.isObject() || !tool.path("sourceKind").isTextual() || !tool.path("sourceRef").isTextual()) {
                    complete = false; continue;
                }
                String kind = tool.path("sourceKind").asText(""), ref = tool.path("sourceRef").asText("");
                var version = tool.path("workflowVersionId");
                if (ref.isBlank() || !("CAPABILITY".equals(kind) || "WORKFLOW".equals(kind)
                        && version.isIntegralNumber() && version.canConvertToLong() && version.longValue() > 0)) {
                    complete = false; continue;
                }
                result.add(new FrozenBinding(kind, ref, "WORKFLOW".equals(kind) ? version.longValue() : null));
            }
        } catch (Exception invalid) { complete = false; }
        return facts(complete, List.copyOf(result));
    }

    private ParsedRevision facts(boolean complete, List<FrozenBinding> bindings) {
        java.util.Map<String, List<FrozenBinding>> capabilities = new java.util.HashMap<>();
        java.util.Map<WorkflowKey, List<FrozenBinding>> workflows = new java.util.HashMap<>();
        for (var binding : bindings) {
            if ("CAPABILITY".equals(binding.kind())) capabilities.computeIfAbsent(binding.ref(), ignored -> new ArrayList<>()).add(binding);
            else workflows.computeIfAbsent(new WorkflowKey(binding.version(), binding.ref()), ignored -> new ArrayList<>()).add(binding);
        }
        capabilities.replaceAll((key, value) -> List.copyOf(value)); workflows.replaceAll((key, value) -> List.copyOf(value));
        return new ParsedRevision(complete, bindings, java.util.Map.copyOf(capabilities), java.util.Map.copyOf(workflows));
    }

    private boolean sameBody(McpPublicationRevisionMapper.ReferenceMetadata metadata, McpPublicationRevisionEntity body) {
        return java.util.Objects.equals(metadata.getPublicationId(), body.getPublicationId())
                && java.util.Objects.equals(metadata.getRevisionNo(), body.getRevisionNo())
                && java.util.Objects.equals(metadata.getPublishedAt(), body.getPublishedAt())
                && java.util.Objects.equals(metadata.getSnapshotFingerprint(), fingerprint(body.getToolsSnapshotJson()));
    }

    static String fingerprint(String value) {
        if (value == null) return null;
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private synchronized ParsedRevision cached(String digest) { return digest == null ? null : parsed.get(digest); }
    private synchronized void cache(String digest, ParsedRevision value) {
        if (digest == null) return;
        if (value.bindings().size() > CACHE_BINDINGS) return;
        var previous = parsed.put(digest, value);
        if (previous != null) cachedBindings -= previous.bindings().size();
        cachedBindings += value.bindings().size();
        while (parsed.size() > CACHE_CONTENTS || cachedBindings > CACHE_BINDINGS) {
            var oldest = parsed.entrySet().iterator(); var removed = oldest.next();
            cachedBindings -= removed.getValue().bindings().size(); oldest.remove();
        }
    }
    private record Scope(Set<String> capabilityAliases, java.util.Map<Long, String> workflowVersions) { }
    private record WorkflowKey(Long versionId, String workflowId) { }
    private record FrozenBinding(String kind, String ref, Long version) { }
    private record ParsedRevision(boolean complete, List<FrozenBinding> bindings,
                                  java.util.Map<String, List<FrozenBinding>> capabilities,
                                  java.util.Map<WorkflowKey, List<FrozenBinding>> workflows) {
        List<FrozenBinding> selected(Scope scope) {
            if (scope == null) return bindings;
            List<FrozenBinding> selected = new ArrayList<>();
            for (String alias : scope.capabilityAliases()) selected.addAll(capabilities.getOrDefault(alias, List.of()));
            scope.workflowVersions().forEach((version, owner) -> selected.addAll(workflows.getOrDefault(new WorkflowKey(version, owner), List.of())));
            return selected;
        }
    }
}
