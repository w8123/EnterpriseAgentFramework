package com.enterprise.ai.control.agentskill;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.control.agentskill.AgentSkillContracts.ImportCommand;
import com.enterprise.ai.control.agentskill.AgentSkillContracts.ImportResult;
import com.enterprise.ai.control.agentskill.AgentSkillContracts.BindingDescriptor;
import com.enterprise.ai.control.agentskill.AgentSkillContracts.FilePreview;
import com.enterprise.ai.control.agentskill.AgentSkillContracts.ReviewCommand;
import com.enterprise.ai.control.agentskill.AgentSkillContracts.ReviewView;
import com.enterprise.ai.control.agentskill.AgentSkillContracts.SkillDetail;
import com.enterprise.ai.control.agentskill.AgentSkillContracts.SkillSummary;
import com.enterprise.ai.control.agentskill.AgentSkillContracts.VersionView;
import com.enterprise.ai.control.agentskill.AgentSkillPackageInspector.PackageInspection;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class AgentSkillCatalogService {

    public static final String STATUS_REVIEW_PENDING = "REVIEW_PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_DEPRECATED = "DEPRECATED";
    public static final String STATUS_REVOKED = "REVOKED";

    private static final Pattern PUBLISHER = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,63}$");
    private static final Pattern VERSION = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._+-]{0,63}$");
    private static final Set<String> VISIBILITIES = Set.of("PRIVATE", "PROJECT", "SHARED", "PUBLIC");
    private static final Set<String> SOURCE_TYPES = Set.of("UPLOAD", "GIT", "MARKET", "BUILTIN");
    private static final Set<String> RESERVED_PUBLISHERS = Set.of("reachai");

    private final AgentSkillMapper skillMapper;
    private final AgentSkillVersionMapper versionMapper;
    private final AgentSkillReviewMapper reviewMapper;
    private final AgentSkillPackageInspector packageInspector;
    private final AgentSkillArtifactStore artifactStore;
    private final ObjectMapper objectMapper;

    public List<SkillSummary> list(String search, String status) {
        String normalizedSearch = trimToNull(search, 128, "search");
        String normalizedStatus = upperOrNull(status);
        List<Long> versionStatusSkillIds = null;
        if (StringUtils.hasText(normalizedStatus) && !"ACTIVE".equals(normalizedStatus)) {
            versionStatusSkillIds = versionMapper.selectList(Wrappers.<AgentSkillVersionEntity>lambdaQuery()
                            .eq(AgentSkillVersionEntity::getStatus, normalizedStatus)
                            .select(AgentSkillVersionEntity::getSkillId))
                    .stream()
                    .map(AgentSkillVersionEntity::getSkillId)
                    .distinct()
                    .toList();
            if (versionStatusSkillIds.isEmpty()) return List.of();
        }
        List<Long> statusSkillIds = versionStatusSkillIds;
        return skillMapper.selectList(Wrappers.<AgentSkillEntity>lambdaQuery()
                        .eq("ACTIVE".equals(normalizedStatus), AgentSkillEntity::getStatus, normalizedStatus)
                        .in(statusSkillIds != null, AgentSkillEntity::getId, statusSkillIds == null ? List.of() : statusSkillIds)
                        .and(StringUtils.hasText(normalizedSearch), query -> query
                                .like(AgentSkillEntity::getStandardName, normalizedSearch)
                                .or()
                                .like(AgentSkillEntity::getDisplayName, normalizedSearch)
                                .or()
                                .like(AgentSkillEntity::getPublisher, normalizedSearch)
                                .or()
                                .like(AgentSkillEntity::getDescription, normalizedSearch))
                        .orderByDesc(AgentSkillEntity::getUpdatedAt)
                        .orderByDesc(AgentSkillEntity::getId))
                .stream()
                .map(this::summary)
                .toList();
    }

    public SkillDetail detail(Long skillId) {
        AgentSkillEntity skill = requireSkill(skillId);
        List<VersionView> versions = versionMapper.selectList(Wrappers.<AgentSkillVersionEntity>lambdaQuery()
                        .eq(AgentSkillVersionEntity::getSkillId, skillId)
                        .orderByDesc(AgentSkillVersionEntity::getCreatedAt)
                        .orderByDesc(AgentSkillVersionEntity::getId))
                .stream()
                .map(this::versionView)
                .toList();
        return new SkillDetail(summary(skill, versions.size()), versions);
    }

    public VersionView version(Long skillId, Long versionId) {
        return versionView(requireVersion(skillId, versionId));
    }

    public BindingDescriptor publishedBindingDescriptor(Long skillId, Long versionId) {
        AgentSkillEntity skill = requireSkill(skillId);
        AgentSkillVersionEntity version = requireVersion(skillId, versionId);
        if (!STATUS_PUBLISHED.equals(version.getStatus())) {
            throw AgentSkillException.invalidState(
                    "Only a PUBLISHED Skill version can be attached to a new Agent config");
        }
        return new BindingDescriptor(summary(skill), versionView(version));
    }

    public List<ReviewView> reviews(Long skillId, Long versionId) {
        requireVersion(skillId, versionId);
        return reviewMapper.selectList(Wrappers.<AgentSkillReviewEntity>lambdaQuery()
                        .eq(AgentSkillReviewEntity::getSkillId, skillId)
                        .eq(AgentSkillReviewEntity::getSkillVersionId, versionId)
                        .orderByDesc(AgentSkillReviewEntity::getCreatedAt)
                        .orderByDesc(AgentSkillReviewEntity::getId))
                .stream()
                .map(this::reviewView)
                .toList();
    }

    @Transactional
    public ImportResult importPackage(byte[] archive, ImportCommand command) {
        PackageInspection inspection = packageInspector.inspect(archive);
        ImportCommand input = command == null
                ? new ImportCommand(null, null, null, null, null, null, null, null, null)
                : command;
        String publisher = publisher(input.publisher());
        String version = version(input.version(), inspection.declaredVersion());
        String visibility = visibility(input.visibility());
        String projectCode = projectCode(visibility, input.projectCode());
        String scopeKey = scopeKey(visibility, input.ownerUserId(), projectCode);
        String sourceType = sourceType(input.sourceType());
        if (RESERVED_PUBLISHERS.contains(publisher) && !"BUILTIN".equals(sourceType)) {
            throw AgentSkillException.forbidden(
                    "The publisher namespace is reserved for trusted built-in packages: " + publisher);
        }
        String sourceRef = trimToNull(input.sourceRef(), 512, "sourceRef");
        String operator = operator(input.operator());
        LocalDateTime now = LocalDateTime.now();

        AgentSkillEntity skill = findSkill(scopeKey, publisher, inspection.name());
        boolean createdSkill = false;
        if (skill == null) {
            AgentSkillEntity candidate = new AgentSkillEntity();
            candidate.setScopeKey(scopeKey);
            candidate.setPublisher(publisher);
            candidate.setStandardName(inspection.name());
            candidate.setDisplayName(firstText(
                    trimToNull(input.displayName(), 128, "displayName"), inspection.name()));
            candidate.setDescription(inspection.description());
            candidate.setVisibility(visibility);
            candidate.setOwnerUserId(input.ownerUserId());
            candidate.setProjectCode(projectCode);
            candidate.setStatus("ACTIVE");
            candidate.setCreatedBy(operator);
            candidate.setUpdatedBy(operator);
            candidate.setCreatedAt(now);
            candidate.setUpdatedAt(now);
            try {
                skillMapper.insert(candidate);
                skill = candidate;
                createdSkill = true;
            } catch (DuplicateKeyException concurrentCreate) {
                skill = findSkill(scopeKey, publisher, inspection.name());
                if (skill == null) {
                    throw AgentSkillException.conflict(
                            "Skill identity was created concurrently; retry the import");
                }
            }
        }
        if (!createdSkill) {
            if (!visibility.equalsIgnoreCase(skill.getVisibility())
                    || !java.util.Objects.equals(projectCode, trimToNull(skill.getProjectCode(), 128, "projectCode"))) {
                throw AgentSkillException.conflict(
                        "Skill distribution scope cannot be changed while importing a new version");
            }
            if ("PRIVATE".equals(visibility)
                    && skill.getOwnerUserId() != null
                    && !skill.getOwnerUserId().equals(input.ownerUserId())) {
                throw AgentSkillException.forbidden("Private Skill is owned by another platform user");
            }
            AgentSkillVersionEntity existing = findVersion(skill.getId(), version);
            if (existing != null) {
                if (inspection.sourceSha256().equals(existing.getSourceSha256())) {
                    ensureArtifact(existing, archive);
                    return new ImportResult(summary(skill), versionView(existing), false);
                }
                throw AgentSkillException.conflict(
                        "Skill version already exists with different content: " + publisher + "/"
                                + inspection.name() + "@" + version);
            }
            skill.setDisplayName(firstText(trimToNull(input.displayName(), 128, "displayName"), skill.getDisplayName()));
            skill.setDescription(inspection.description());
            skill.setVisibility(visibility);
            skill.setUpdatedBy(operator);
            skill.setUpdatedAt(now);
            skillMapper.updateById(skill);
        }

        AgentSkillArtifactStore.StoredArtifact stored = artifactStore.put(inspection.sourceSha256(), archive);
        AgentSkillVersionEntity entity = new AgentSkillVersionEntity();
        entity.setSkillId(skill.getId());
        entity.setVersion(version);
        entity.setStatus(STATUS_REVIEW_PENDING);
        entity.setSourceType(sourceType);
        entity.setSourceRef(sourceRef);
        entity.setSourceSha256(inspection.sourceSha256());
        entity.setContentTreeSha256(inspection.contentTreeSha256());
        entity.setArtifactKey(stored.artifactKey());
        entity.setArtifactSize(stored.size());
        entity.setDeclaredLicense(inspection.license());
        entity.setDeclaredCompatibility(inspection.compatibility());
        entity.setHasScripts(inspection.hasScripts());
        entity.setFrontmatterJson(json(inspection.frontmatter()));
        entity.setPackageManifestJson(json(inspection.manifest()));
        entity.setValidationReportJson(json(inspection.validationReport()));
        entity.setRiskReportJson(json(inspection.riskReport()));
        entity.setCompatibilityReportJson(json(inspection.compatibilityReport()));
        entity.setCreatedBy(operator);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        try {
            versionMapper.insert(entity);
        } catch (DuplicateKeyException concurrentVersionCreate) {
            AgentSkillVersionEntity existing = findVersion(skill.getId(), version);
            if (existing != null && inspection.sourceSha256().equals(existing.getSourceSha256())) {
                ensureArtifact(existing, archive);
                return new ImportResult(summary(skill), versionView(existing), false);
            }
            throw AgentSkillException.conflict(
                    "Skill version was imported concurrently with different content: " + publisher + "/"
                            + inspection.name() + "@" + version);
        }
        return new ImportResult(summary(skill), versionView(entity), true);
    }

    private void ensureArtifact(AgentSkillVersionEntity existing, byte[] archive) {
        AgentSkillArtifactStore.StoredArtifact stored = artifactStore.put(existing.getSourceSha256(), archive);
        if (!stored.artifactKey().equals(existing.getArtifactKey())
                || stored.size() != existing.getArtifactSize()) {
            throw new AgentSkillException("SKILL_ARTIFACT_IDENTITY_MISMATCH",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "The configured Skill artifact store does not match the immutable catalog version");
        }
    }

    @Transactional
    public VersionView review(Long skillId, Long versionId, ReviewCommand command) {
        if (command == null) {
            throw new AgentSkillException("SKILL_REVIEW_INVALID", HttpStatus.BAD_REQUEST,
                    "Skill review body is required");
        }
        AgentSkillVersionEntity version = requireVersionForUpdate(skillId, versionId);
        String decision = upper(command.decision(), "decision");
        if (!Set.of("APPROVE", "REJECT").contains(decision)) {
            throw new AgentSkillException("SKILL_REVIEW_INVALID", HttpStatus.BAD_REQUEST,
                    "Skill review decision must be APPROVE or REJECT");
        }
        if (!Set.of(STATUS_REVIEW_PENDING, STATUS_REJECTED).contains(version.getStatus())) {
            throw AgentSkillException.invalidState("Skill version cannot be reviewed from status " + version.getStatus());
        }
        String reviewer = operator(command.reviewer());
        LocalDateTime now = LocalDateTime.now();
        AgentSkillReviewEntity review = new AgentSkillReviewEntity();
        review.setSkillId(skillId);
        review.setSkillVersionId(versionId);
        review.setDecision(decision);
        review.setComment(trimToNull(command.comment(), 2000, "comment"));
        review.setFindingsJson(command.findings() == null ? null : json(command.findings()));
        review.setReviewer(reviewer);
        review.setCreatedAt(now);
        reviewMapper.insert(review);

        version.setStatus("APPROVE".equals(decision) ? STATUS_APPROVED : STATUS_REJECTED);
        version.setReviewedBy(reviewer);
        version.setReviewedAt(now);
        version.setUpdatedAt(now);
        versionMapper.updateById(version);
        touchSkill(skillId, reviewer, now);
        return versionView(version);
    }

    @Transactional
    public VersionView publish(Long skillId, Long versionId, String operator) {
        AgentSkillVersionEntity version = requireVersionForUpdate(skillId, versionId);
        if (STATUS_PUBLISHED.equals(version.getStatus())) {
            return versionView(version);
        }
        if (!STATUS_APPROVED.equals(version.getStatus())) {
            throw AgentSkillException.invalidState("Only an APPROVED Skill version can be published");
        }
        String actor = operator(operator);
        LocalDateTime now = LocalDateTime.now();
        version.setStatus(STATUS_PUBLISHED);
        version.setPublishedBy(actor);
        version.setPublishedAt(now);
        version.setUpdatedAt(now);
        versionMapper.updateById(version);

        AgentSkillEntity skill = requireSkill(skillId);
        skill.setLatestVersionId(versionId);
        if (skill.getDefaultVersionId() == null) {
            skill.setDefaultVersionId(versionId);
        }
        skill.setUpdatedBy(actor);
        skill.setUpdatedAt(now);
        skillMapper.updateById(skill);
        return versionView(version);
    }

    @Transactional
    public VersionView setDefault(Long skillId, Long versionId, String operator) {
        AgentSkillVersionEntity version = requireVersionForUpdate(skillId, versionId);
        if (!STATUS_PUBLISHED.equals(version.getStatus())) {
            throw AgentSkillException.invalidState("Only a PUBLISHED Skill version can be the default");
        }
        AgentSkillEntity skill = requireSkill(skillId);
        skill.setDefaultVersionId(versionId);
        skill.setUpdatedBy(operator(operator));
        skill.setUpdatedAt(LocalDateTime.now());
        skillMapper.updateById(skill);
        return versionView(version);
    }

    @Transactional
    public VersionView deprecate(Long skillId, Long versionId, String operator) {
        return terminalTransition(skillId, versionId, STATUS_DEPRECATED, operator, Set.of(STATUS_PUBLISHED));
    }

    @Transactional
    public VersionView revoke(Long skillId, Long versionId, String operator) {
        return terminalTransition(skillId, versionId, STATUS_REVOKED, operator,
                Set.of(STATUS_REVIEW_PENDING, STATUS_APPROVED, STATUS_REJECTED, STATUS_PUBLISHED, STATUS_DEPRECATED));
    }

    public VersionView latestPublished(String publisher, String name) {
        AgentSkillEntity skill = requireSkill(publisher(publisher), requiredName(name));
        AgentSkillVersionEntity version = skill.getLatestVersionId() == null
                ? null
                : versionMapper.selectById(skill.getLatestVersionId());
        if (version == null || !STATUS_PUBLISHED.equals(version.getStatus())) {
            version = versionMapper.selectOne(Wrappers.<AgentSkillVersionEntity>lambdaQuery()
                    .eq(AgentSkillVersionEntity::getSkillId, skill.getId())
                    .eq(AgentSkillVersionEntity::getStatus, STATUS_PUBLISHED)
                    .orderByDesc(AgentSkillVersionEntity::getPublishedAt)
                    .orderByDesc(AgentSkillVersionEntity::getId)
                    .last("LIMIT 1"));
        }
        if (version == null) {
            throw AgentSkillException.notFound("Published Skill version not found: " + publisher + "/" + name);
        }
        return versionView(version);
    }

    public byte[] packageBytes(Long skillId, Long versionId) {
        AgentSkillVersionEntity version = requireVersion(skillId, versionId);
        return artifactStore.get(version.getArtifactKey(), version.getSourceSha256());
    }

    public byte[] packageBytes(VersionView version) {
        return packageBytes(version.skillId(), version.id());
    }

    public FilePreview previewFile(Long skillId, Long versionId, String path) {
        VersionView version = version(skillId, versionId);
        AgentSkillPackageInspector.PackageFilePreview preview = packageInspector.previewFile(
                packageBytes(skillId, versionId), path);
        JsonNode expected = null;
        if (version.packageManifest() != null && version.packageManifest().path("files").isArray()) {
            for (JsonNode candidate : version.packageManifest().path("files")) {
                if (preview.path().equals(candidate.path("path").asText(null))) {
                    expected = candidate;
                    break;
                }
            }
        }
        if (expected == null
                || expected.path("size").asLong(-1L) != preview.size()
                || !preview.sha256().equalsIgnoreCase(expected.path("sha256").asText(""))) {
            throw new AgentSkillException("SKILL_METADATA_CORRUPTED", HttpStatus.INTERNAL_SERVER_ERROR,
                    "Stored Skill file does not match its package manifest");
        }
        return new FilePreview(preview.path(), preview.kind().name(), preview.size(), preview.sha256(),
                preview.previewable(), preview.truncated(), preview.content());
    }

    @Transactional
    public VersionView importTrustedBuiltin(byte[] archive, ImportCommand command) {
        ImportResult imported = importPackage(archive, command);
        // A rolling deployment can bootstrap the same built-in from several Control
        // instances. Re-read under the governance lock so a waiter observes the
        // state committed by the winner instead of replaying a stale review action.
        VersionView version = versionView(requireVersionForUpdate(
                imported.version().skillId(), imported.version().id()));
        if (STATUS_REVIEW_PENDING.equals(version.status()) || STATUS_REJECTED.equals(version.status())) {
            version = review(version.skillId(), version.id(),
                    new ReviewCommand("APPROVE", "ReachAI 内置 Skill 随平台构建发布。", null, "SYSTEM"));
        }
        if (STATUS_APPROVED.equals(version.status())) {
            version = publish(version.skillId(), version.id(), "SYSTEM");
        }
        return version;
    }

    private VersionView terminalTransition(Long skillId,
                                           Long versionId,
                                           String targetStatus,
                                           String operator,
                                           Set<String> allowedStatuses) {
        AgentSkillVersionEntity version = requireVersionForUpdate(skillId, versionId);
        if (targetStatus.equals(version.getStatus())) {
            return versionView(version);
        }
        if (!allowedStatuses.contains(version.getStatus())) {
            throw AgentSkillException.invalidState(
                    "Skill version cannot move from " + version.getStatus() + " to " + targetStatus);
        }
        String actor = operator(operator);
        LocalDateTime now = LocalDateTime.now();
        version.setStatus(targetStatus);
        version.setUpdatedAt(now);
        versionMapper.updateById(version);
        refreshPublishedPointers(skillId, actor, now);
        return versionView(version);
    }

    private void refreshPublishedPointers(Long skillId, String operator, LocalDateTime now) {
        AgentSkillVersionEntity fallback = versionMapper.selectOne(Wrappers.<AgentSkillVersionEntity>lambdaQuery()
                .eq(AgentSkillVersionEntity::getSkillId, skillId)
                .eq(AgentSkillVersionEntity::getStatus, STATUS_PUBLISHED)
                .orderByDesc(AgentSkillVersionEntity::getPublishedAt)
                .orderByDesc(AgentSkillVersionEntity::getId)
                .last("LIMIT 1"));
        AgentSkillEntity skill = requireSkill(skillId);
        Long fallbackId = fallback == null ? null : fallback.getId();
        AgentSkillVersionEntity currentDefault = skill.getDefaultVersionId() == null
                ? null
                : versionMapper.selectById(skill.getDefaultVersionId());
        Long defaultId = currentDefault != null && STATUS_PUBLISHED.equals(currentDefault.getStatus())
                ? currentDefault.getId()
                : fallbackId;
        // Wrapper SET is intentional: updateById ignores null fields and would leave stale pointers.
        skillMapper.update(null, Wrappers.<AgentSkillEntity>lambdaUpdate()
                .eq(AgentSkillEntity::getId, skillId)
                .set(AgentSkillEntity::getLatestVersionId, fallbackId)
                .set(AgentSkillEntity::getDefaultVersionId, defaultId)
                .set(AgentSkillEntity::getUpdatedBy, operator)
                .set(AgentSkillEntity::getUpdatedAt, now));
    }

    private void touchSkill(Long skillId, String operator, LocalDateTime now) {
        AgentSkillEntity skill = requireSkill(skillId);
        skill.setUpdatedBy(operator);
        skill.setUpdatedAt(now);
        skillMapper.updateById(skill);
    }

    private AgentSkillEntity requireSkill(Long id) {
        if (id == null) {
            throw AgentSkillException.notFound("Skill id is required");
        }
        AgentSkillEntity skill = skillMapper.selectById(id);
        if (skill == null) {
            throw AgentSkillException.notFound("Skill not found: " + id);
        }
        return skill;
    }

    private AgentSkillEntity requireSkill(String publisher, String name) {
        AgentSkillEntity skill = findSkill("PUBLIC:*", publisher, name);
        if (skill == null) {
            throw AgentSkillException.notFound("Skill not found: " + publisher + "/" + name);
        }
        return skill;
    }

    private AgentSkillEntity findSkill(String scopeKey, String publisher, String name) {
        return skillMapper.selectOne(Wrappers.<AgentSkillEntity>lambdaQuery()
                .eq(AgentSkillEntity::getScopeKey, scopeKey)
                .eq(AgentSkillEntity::getPublisher, publisher)
                .eq(AgentSkillEntity::getStandardName, name)
                .last("LIMIT 1"));
    }

    private AgentSkillVersionEntity requireVersion(Long skillId, Long versionId) {
        if (skillId == null || versionId == null) {
            throw AgentSkillException.notFound("Skill version id is required");
        }
        AgentSkillVersionEntity version = versionMapper.selectById(versionId);
        if (version == null || !skillId.equals(version.getSkillId())) {
            throw AgentSkillException.notFound("Skill version not found: " + skillId + "#" + versionId);
        }
        return version;
    }

    /** Serializes governance state transitions so stale concurrent decisions cannot bypass the state machine. */
    private AgentSkillVersionEntity requireVersionForUpdate(Long skillId, Long versionId) {
        if (skillId == null || versionId == null) {
            throw AgentSkillException.notFound("Skill version id is required");
        }
        AgentSkillVersionEntity version = versionMapper.selectOne(
                Wrappers.<AgentSkillVersionEntity>lambdaQuery()
                        .eq(AgentSkillVersionEntity::getId, versionId)
                        .eq(AgentSkillVersionEntity::getSkillId, skillId)
                        .last("FOR UPDATE"));
        if (version == null) {
            throw AgentSkillException.notFound("Skill version not found: " + skillId + "#" + versionId);
        }
        return version;
    }

    private AgentSkillVersionEntity findVersion(Long skillId, String version) {
        return versionMapper.selectOne(Wrappers.<AgentSkillVersionEntity>lambdaQuery()
                .eq(AgentSkillVersionEntity::getSkillId, skillId)
                .eq(AgentSkillVersionEntity::getVersion, version)
                .last("LIMIT 1"));
    }

    private SkillSummary summary(AgentSkillEntity skill) {
        long count = versionMapper.selectCount(Wrappers.<AgentSkillVersionEntity>lambdaQuery()
                .eq(AgentSkillVersionEntity::getSkillId, skill.getId()));
        return summary(skill, count);
    }

    private SkillSummary summary(AgentSkillEntity skill, long versionCount) {
        AgentSkillVersionEntity latest = versionMapper.selectOne(
                Wrappers.<AgentSkillVersionEntity>lambdaQuery()
                        .eq(AgentSkillVersionEntity::getSkillId, skill.getId())
                        .orderByDesc(AgentSkillVersionEntity::getCreatedAt)
                        .orderByDesc(AgentSkillVersionEntity::getId)
                        .last("LIMIT 1"));
        AgentSkillVersionEntity defaultVersion = skill.getDefaultVersionId() == null
                ? null : versionMapper.selectById(skill.getDefaultVersionId());
        return new SkillSummary(
                skill.getId(),
                skill.getPublisher(),
                skill.getStandardName(),
                skill.getDisplayName(),
                skill.getDescription(),
                skill.getVisibility(),
                skill.getOwnerUserId(),
                skill.getProjectCode(),
                skill.getStatus(),
                skill.getLatestVersionId(),
                skill.getDefaultVersionId(),
                versionCount,
                skill.getUpdatedAt(),
                latest == null ? null : latest.getVersion(),
                latest == null ? null : latest.getStatus(),
                defaultVersion == null ? null : defaultVersion.getVersion());
    }

    private VersionView versionView(AgentSkillVersionEntity version) {
        return new VersionView(
                version.getId(),
                version.getSkillId(),
                version.getVersion(),
                version.getStatus(),
                version.getSourceType(),
                version.getSourceRef(),
                version.getSourceSha256(),
                version.getContentTreeSha256(),
                version.getArtifactSize(),
                version.getDeclaredLicense(),
                version.getDeclaredCompatibility(),
                Boolean.TRUE.equals(version.getHasScripts()),
                jsonNode(version.getFrontmatterJson()),
                jsonNode(version.getPackageManifestJson()),
                jsonNode(version.getValidationReportJson()),
                jsonNode(version.getRiskReportJson()),
                jsonNode(version.getCompatibilityReportJson()),
                version.getReviewedBy(),
                version.getReviewedAt(),
                version.getPublishedBy(),
                version.getPublishedAt(),
                version.getCreatedAt(),
                version.getUpdatedAt());
    }

    private ReviewView reviewView(AgentSkillReviewEntity review) {
        return new ReviewView(
                review.getId(),
                review.getSkillId(),
                review.getSkillVersionId(),
                review.getDecision(),
                review.getComment(),
                jsonNode(review.getFindingsJson()),
                review.getReviewer(),
                review.getCreatedAt());
    }

    private String publisher(String value) {
        String publisher = StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "community";
        if (!PUBLISHER.matcher(publisher).matches()) {
            throw new AgentSkillException("SKILL_PUBLISHER_INVALID", HttpStatus.BAD_REQUEST,
                    "Skill publisher must use lowercase letters, numbers, dots, underscores, or hyphens");
        }
        return publisher;
    }

    private String requiredName(String value) {
        String name = trimToNull(value, 64, "name");
        if (name == null || !AgentSkillPackageInspector.STANDARD_NAME.matcher(name).matches()) {
            throw AgentSkillException.invalidPackage("Skill name is invalid");
        }
        return name;
    }

    private String version(String requested, String declared) {
        String value = firstText(trimToNull(requested, 64, "version"), trimToNull(declared, 64, "version"));
        value = firstText(value, "1.0.0");
        if (!VERSION.matcher(value).matches()) {
            throw new AgentSkillException("SKILL_VERSION_INVALID", HttpStatus.BAD_REQUEST,
                    "Skill version contains unsupported characters");
        }
        return value;
    }

    private String visibility(String value) {
        String visibility = StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "PRIVATE";
        if (!VISIBILITIES.contains(visibility)) {
            throw new AgentSkillException("SKILL_VISIBILITY_INVALID", HttpStatus.BAD_REQUEST,
                    "Skill visibility must be PRIVATE, PROJECT, SHARED, or PUBLIC");
        }
        return visibility;
    }

    private String projectCode(String visibility, String value) {
        String projectCode = trimToNull(value, 128, "projectCode");
        if ("PROJECT".equals(visibility) && projectCode == null) {
            throw new AgentSkillException("SKILL_PROJECT_REQUIRED", HttpStatus.BAD_REQUEST,
                    "projectCode is required for a PROJECT Skill");
        }
        if (!"PROJECT".equals(visibility) && projectCode != null) {
            throw new AgentSkillException("SKILL_PROJECT_INVALID", HttpStatus.BAD_REQUEST,
                    "projectCode is only valid for a PROJECT Skill");
        }
        return projectCode;
    }

    private String scopeKey(String visibility, Long ownerUserId, String projectCode) {
        return switch (visibility) {
            case "PRIVATE" -> {
                if (ownerUserId == null || ownerUserId <= 0L) {
                    throw new AgentSkillException("SKILL_OWNER_REQUIRED", HttpStatus.BAD_REQUEST,
                            "A PRIVATE Skill import requires the current platform user as owner");
                }
                yield "USER:" + ownerUserId;
            }
            case "PROJECT" -> "PROJECT:" + projectCode;
            case "SHARED" -> "SHARED:*";
            case "PUBLIC" -> "PUBLIC:*";
            default -> throw new IllegalStateException("Unsupported Skill visibility: " + visibility);
        };
    }

    private String sourceType(String value) {
        String sourceType = StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "UPLOAD";
        if (!SOURCE_TYPES.contains(sourceType)) {
            throw new AgentSkillException("SKILL_SOURCE_INVALID", HttpStatus.BAD_REQUEST,
                    "Skill sourceType must be UPLOAD, GIT, MARKET, or BUILTIN");
        }
        return sourceType;
    }

    private String operator(String value) {
        return firstText(trimToNull(value, 128, "operator"), "SYSTEM");
    }

    private String upper(String value, String field) {
        String normalized = trimToNull(value, 32, field);
        if (normalized == null) {
            throw new AgentSkillException("SKILL_REQUEST_INVALID", HttpStatus.BAD_REQUEST,
                    field + " is required");
        }
        return normalized.toUpperCase(Locale.ROOT);
    }

    private String upperOrNull(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : null;
    }

    private String trimToNull(String value, int maxLength, String field) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new AgentSkillException("SKILL_REQUEST_INVALID", HttpStatus.BAD_REQUEST,
                    field + " must not exceed " + maxLength + " characters");
        }
        return normalized;
    }

    private String firstText(String first, String second) {
        return StringUtils.hasText(first) ? first : second;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new AgentSkillException("SKILL_METADATA_SERIALIZATION_FAILED",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Skill metadata could not be serialized", exception);
        }
    }

    private JsonNode jsonNode(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return objectMapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new AgentSkillException("SKILL_METADATA_CORRUPTED",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Stored Skill metadata is invalid JSON", exception);
        }
    }
}
