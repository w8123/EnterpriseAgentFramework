package com.enterprise.ai.control.platform;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.common.page.TrustedPageDraftInputContract;
import com.enterprise.ai.control.identity.PlatformAuthAuditService;
import com.enterprise.ai.control.identity.PlatformPermissions;
import com.enterprise.ai.control.pageworkbench.application.PageCatalogApplicationService;
import com.enterprise.ai.control.pageworkbench.persistence.PageActionEffectGenerationMapper;
import com.enterprise.ai.control.pageworkbench.persistence.PageActionEffectReviewEntity;
import com.enterprise.ai.control.pageworkbench.persistence.PageActionEffectReviewMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Administrator attestation of host behavior; it is not a remote proof of that behavior. */
@Service
@RequiredArgsConstructor
public class PageActionEffectReviewService {
    private final PageCatalogApplicationService catalog;
    private final PageActionEffectGenerationMapper generations;
    private final PageActionEffectReviewMapper reviews;
    private final PlatformAuthAuditService audit;
    private final ObjectMapper objectMapper;

    @Transactional
    public ContractPreview preview(PlatformAuthenticatedSession actor, String projectCode, String pageKey, String actionKey) {
        requirePublisher(actor, projectCode);
        var locked = locked(projectCode, pageKey, actionKey);
        Long revision = generations.lock(locked.action().id());
        return new ContractPreview(locked.page().id(), locked.action().id(), locked.page().projectCode(),
                locked.page().pageKey(), locked.action().actionKey(), locked.page().routePattern(),
                locked.action().title(), locked.action().description(), locked.action().implementationRef(),
                locked.action().inputSchema(), locked.action().riskLevel(), locked.action().confirmRequired(),
                PageActionContractFingerprint.of(locked.action(), locked.page(), objectMapper), revision == null ? 0 : revision,
                resolveApprovedForUpdate(projectCode, pageKey, actionKey).orElse(null));
    }

    @Transactional
    public ActiveApproval approve(PlatformAuthenticatedSession actor, String projectCode, String pageKey,
                                  String actionKey, ReviewCommand command) {
        requirePublisher(actor, projectCode);
        if (command == null || !command.responsibilityAccepted()
                || command.effect() == null || !Set.of("PAGE_LOCAL_DRAFT", "PERSISTENT").contains(command.effect())
                || !StringUtils.hasText(command.expectedCatalogHash())
                || command.expectedRevision() == null || command.expectedRevision() < 0
                || !StringUtils.hasText(command.implementationVersion())
                || command.implementationVersion().trim().length() > 160
                || containsControl(command.implementationVersion())
                || !StringUtils.hasText(command.targetAlias())
                || command.targetAlias().trim().length() < 2 || command.targetAlias().trim().length() > 80) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "effect, version, target and responsibility acceptance are required");
        }
        var locked = locked(projectCode, pageKey, actionKey);
        String hash = PageActionContractFingerprint.of(locked.action(), locked.page(), objectMapper);
        if (!Objects.equals(hash, command.expectedCatalogHash())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "page action declaration changed");
        }
        Map<String, String> fieldLabels = validateFieldLabels(locked.action().inputSchema(), command.fieldLabels(), command.effect());
        if ("PAGE_LOCAL_DRAFT".equals(command.effect())) validateTrustedDraft(locked.page().projectCode(),
                locked.page().pageKey(), locked.action().actionKey(), locked.action().title(), locked.page().routePattern(),
                command.implementationVersion().trim(), command.targetAlias().trim(), locked.action().inputSchema());
        long currentRevision = generation(locked.action().id(), locked.page().id());
        if (currentRevision != command.expectedRevision()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "effect review changed; refresh before approval");
        }
        long revision = currentRevision + 1;
        generations.invalidateAction(locked.action().id());
        var row = newRecord(actor, locked, revision, "APPROVED");
        row.setEffect(command.effect());
        row.setCatalogHash(hash);
        row.setImplementationVersion(command.implementationVersion().trim());
        row.setTargetAlias(command.targetAlias().trim());
        try { row.setFieldLabelsJson(objectMapper.writeValueAsString(fieldLabels)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalArgumentException("field labels cannot be serialized", invalid);
        }
        reviews.insert(row);
        audit.record(actor, "PAGE_ACTION_EFFECT_APPROVED", "PAGE_ACTION", String.valueOf(locked.action().id()),
                Map.of("projectCode", projectCode, "approvalId", row.getId(), "effect", row.getEffect(), "revision", revision));
        return view(row, locked, true);
    }

    @Transactional
    public void revoke(PlatformAuthenticatedSession actor, String projectCode, String pageKey, String actionKey,
                       RevokeCommand command) {
        requirePublisher(actor, projectCode);
        if (command == null || command.expectedApprovalId() == null || command.expectedRevision() == null
                || !StringUtils.hasText(command.expectedCatalogHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "current effect review version is required");
        }
        var locked = locked(projectCode, pageKey, actionKey);
        var current = resolveApprovedForUpdate(projectCode, pageKey, actionKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "effect review is no longer active"));
        if (!Objects.equals(current.approvalId(), command.expectedApprovalId())
                || current.revision() != command.expectedRevision()
                || !Objects.equals(current.catalogHash(), command.expectedCatalogHash())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "effect review changed; refresh before revocation");
        }
        long revision = generation(locked.action().id(), locked.page().id());
        generations.invalidateAction(locked.action().id());
        var row = newRecord(actor, locked, revision + 1, "REVOKED");
        reviews.insert(row);
        audit.record(actor, "PAGE_ACTION_EFFECT_REVOKED", "PAGE_ACTION", String.valueOf(locked.action().id()),
                Map.of("projectCode", projectCode, "reviewId", row.getId(), "revision", revision + 1));
    }

    /** Must be called inside the transaction that pins/uses the returned approval. */
    @Transactional
    public Optional<ActiveApproval> resolveApproved(String projectCode, String pageKey, String actionKey) {
        return resolveApprovedForUpdate(projectCode, pageKey, actionKey);
    }

    public Optional<ActiveApproval> resolveApprovedForUpdate(String projectCode, String pageKey, String actionKey) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("transaction is required");
        var locked = catalog.findActiveActionAndPageForUpdate(projectCode, pageKey, actionKey).orElse(null);
        if (locked == null) return Optional.empty();
        Long generation = generations.lock(locked.action().id());
        if (generation == null) return Optional.empty();
        var row = reviews.selectOne(Wrappers.<PageActionEffectReviewEntity>lambdaQuery()
                .eq(PageActionEffectReviewEntity::getActionId, locked.action().id())
                .orderByDesc(PageActionEffectReviewEntity::getId).last("LIMIT 1"));
        if (row == null || !"APPROVED".equals(row.getDecision()) || !Objects.equals(generation, row.getRevision())
                || !Objects.equals(row.getPageId(), locked.page().id())
                || !Objects.equals(row.getProjectId(), locked.page().projectId())
                || !Objects.equals(row.getProjectCode(), projectCode) || !Objects.equals(row.getPageKey(), pageKey)
                || !Objects.equals(row.getActionKey(), actionKey)
                || !Objects.equals(row.getCatalogHash(), PageActionContractFingerprint.of(locked.action(), locked.page(), objectMapper))
                || !validVersion(row.getImplementationVersion())
                || !StringUtils.hasText(row.getTargetAlias()) || row.getTargetAlias().trim().length() < 2
                || row.getEffect() == null || !Set.of("PAGE_LOCAL_DRAFT", "PERSISTENT").contains(row.getEffect())) return Optional.empty();
        try {
            var raw = objectMapper.readTree(row.getFieldLabelsJson());
            if (raw == null || !raw.isObject()) return Optional.empty();
            Map<String, String> labels = new LinkedHashMap<>();
            var entries = raw.fields();
            while (entries.hasNext()) {
                var entry = entries.next();
                if (!entry.getValue().isTextual()) return Optional.empty();
                labels.put(entry.getKey(), entry.getValue().textValue());
            }
            validateFieldLabels(locked.action().inputSchema(), labels, row.getEffect());
        } catch (Exception invalid) { return Optional.empty(); }
        return Optional.of(view(row, locked, true));
    }

    private Map<String, String> validateFieldLabels(com.fasterxml.jackson.databind.JsonNode schema,
                                                     Map<String, String> labels, String effect) {
        if (schema == null || !schema.isObject() || !"object".equals(schema.path("type").asText()) || labels == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "an object input schema and field labels are required");
        var properties = schema.get("properties");
        if (properties != null && !properties.isObject())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "input schema properties must be an object");
        var keys = new java.util.LinkedHashSet<String>();
        if (properties != null) properties.fieldNames().forEachRemaining(keys::add);
        if (keys.size() > 100 || ("PAGE_LOCAL_DRAFT".equals(effect) && keys.isEmpty()) || !keys.equals(labels.keySet()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "field labels must cover all input fields exactly");
        var result = new LinkedHashMap<String, String>();
        var normalized = new ArrayList<String>();
        for (String key : keys) {
            var field = properties.get(key);
            if (field == null || !field.isObject() || !Set.of("string", "number", "integer", "boolean").contains(field.path("type").asText()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "only scalar input fields are supported");
            String raw = labels.get(key);
            if (!StringUtils.hasText(raw) || containsControl(raw))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "each field needs a readable label");
            String label = raw.trim();
            if (label.length() < 2 || label.length() > 40)
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "field labels must be 2 to 40 characters");
            String canonical = java.text.Normalizer.normalize(label, java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
            if (normalized.stream().anyMatch(other -> other.contains(canonical) || canonical.contains(other)))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "field labels must not overlap");
            normalized.add(canonical);
            result.put(key, label);
        }
        return result;
    }
    /** Also called before creating a draft so older reviews cannot bypass newer constraints. */
    static void validateTrustedDraft(String project, String page, String action, String title, String route,
                                     String implementationVersion, String targetAlias, com.fasterxml.jackson.databind.JsonNode schema) {
        if (!TrustedPageDraftInputContract.literal(project) || !TrustedPageDraftInputContract.literal(page)
                || !TrustedPageDraftInputContract.literal(action) || !TrustedPageDraftInputContract.literal(title)
                || !TrustedPageDraftInputContract.literal(implementationVersion) || !TrustedPageDraftInputContract.literal(targetAlias)
                || !TrustedPageDraftInputContract.staticRoute(route) || !TrustedPageDraftInputContract.validSchema(schema)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "page draft requires a static route and supported scalar input schema with simple field names");
        }
    }
    private static boolean containsControl(String value) {
        return value != null && value.codePoints().anyMatch(Character::isISOControl);
    }
    private static boolean validVersion(String value) {
        return StringUtils.hasText(value) && value.trim().length() <= 160 && !containsControl(value);
    }

    private PageCatalogApplicationService.LockedPageAction locked(String project, String page, String action) {
        return catalog.findActiveActionAndPageForUpdate(project, page, action)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "active page action not found"));
    }
    private long generation(Long actionId, Long pageId) {
        Long current = generations.lock(actionId);
        if (current != null) return current;
        generations.create(actionId, pageId);
        return 0;
    }
    private PageActionEffectReviewEntity newRecord(PlatformAuthenticatedSession actor,
            PageCatalogApplicationService.LockedPageAction locked, long revision, String decision) {
        var row = new PageActionEffectReviewEntity();
        row.setActionId(locked.action().id()); row.setPageId(locked.page().id());
        row.setProjectId(locked.page().projectId()); row.setProjectCode(locked.page().projectCode());
        row.setPageKey(locked.page().pageKey()); row.setActionKey(locked.action().actionKey());
        row.setRevision(revision); row.setDecision(decision); row.setActorUserId(actor.user().getId());
        row.setActorSessionId(actor.sessionId()); row.setCreatedAt(LocalDateTime.now());
        return row;
    }
    private static void requirePublisher(PlatformAuthenticatedSession actor, String projectCode) {
        if (actor == null || actor.user() == null || actor.user().getId() == null || !StringUtils.hasText(actor.sessionId()))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "live platform session is required");
        if (!StringUtils.hasText(projectCode)
                || !actor.hasResourcePermission(PlatformPermissions.WORKFLOW_PUBLISH, "PROJECT", null, projectCode)
                || !actor.hasResourcePermission(PlatformPermissions.EMBED_MANAGE, "PROJECT", null, projectCode))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "project publish and embed management permissions are required");
    }
    private ActiveApproval view(PageActionEffectReviewEntity row, PageCatalogApplicationService.LockedPageAction locked, boolean active) {
        Map<String, String> labels;
        try { labels = objectMapper.readValue(row.getFieldLabelsJson(), new com.fasterxml.jackson.core.type.TypeReference<>() { }); }
        catch (Exception invalid) { throw new IllegalStateException("review field labels are unavailable", invalid); }
        return new ActiveApproval(row.getId(), row.getRevision(), row.getEffect(), row.getCatalogHash(),
                row.getImplementationVersion(), row.getTargetAlias(), row.getProjectCode(), row.getPageKey(),
                row.getActionKey(), row.getPageId(), row.getActionId(), locked.page().routePattern(),
                locked.action().title(), locked.action().description(), active, locked.action().inputSchema(), labels);
    }
    public record ReviewCommand(String effect, String expectedCatalogHash, String implementationVersion,
                                String targetAlias, Long expectedRevision, boolean responsibilityAccepted,
                                Map<String, String> fieldLabels) { }
    public record RevokeCommand(Long expectedApprovalId, Long expectedRevision, String expectedCatalogHash) { }
    public record ContractPreview(Long pageId, Long actionId, String projectCode, String pageKey, String actionKey,
            String routePattern, String actionTitle, String actionDescription, String implementationVersion,
            com.fasterxml.jackson.databind.JsonNode inputSchema, String riskLevel, boolean confirmRequired,
            String catalogHash, long currentRevision, ActiveApproval approval) { }
    public record ActiveApproval(Long approvalId, long revision, String effect, String catalogHash,
            String implementationVersion, String targetAlias, String projectCode, String pageKey, String actionKey,
            Long pageId, Long actionId, String routePattern, String actionTitle, String actionDescription, boolean active,
            com.fasterxml.jackson.databind.JsonNode inputSchema, Map<String, String> fieldLabels) { }
}
