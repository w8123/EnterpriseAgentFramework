package com.enterprise.ai.capability.catalog.semantic;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectMapper;
import com.enterprise.ai.agent.capability.catalog.semantic.SemanticDocEntity;
import com.enterprise.ai.agent.capability.catalog.semantic.SemanticDocMapper;
import com.enterprise.ai.capability.catalog.enrichment.CatalogSubject;
import com.enterprise.ai.capability.catalog.enrichment.CatalogSubjectResolver;
import com.enterprise.ai.capability.catalog.enrichment.CapabilityCatalogGroupingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Short, project-serialized document transactions, called through a separate Spring bean. */
@Service
@RequiredArgsConstructor
public class CapabilitySemanticDocumentStore {
    private final SemanticDocMapper docs;
    private final ScanProjectMapper projects;
    private final CatalogSubjectResolver subjects;
    private final CapabilityCatalogGroupingService grouping;

    @Transactional(readOnly = true)
    public Optional<SemanticDocEntity> find(CatalogSubject subject) {
        return Optional.ofNullable(read(subject));
    }

    /** A group has no source observations to adopt; its document read needs neither a project write lock nor a second aggregate resolve. */
    @Transactional(readOnly = true)
    public Optional<SemanticDocEntity> findGroup(CatalogSubject subject) {
        if (!CatalogSubject.GROUP.equals(subject.subjectType())) throw new IllegalArgumentException("业务分类主体类型无效");
        return Optional.ofNullable(read(subject));
    }

    /** Graph rebuild already holds the project row lock and a freshly resolved bulk contract view. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<SemanticDocEntity> findInLockedProject(CatalogSubject subject) { return Optional.ofNullable(read(subject)); }

    /** The formal acceptance transaction holds the project lock and has already persisted its owner/projection. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void adoptAcceptedOwner(Long projectId, String subjectType, Long subjectId) {
        CatalogSubject subject = subjects.resolve(projectId, subjectType, subjectId);
        if (!subject.operation() || !subject.accepted() || CatalogSubject.SOURCE_OBSERVATION.equals(subject.subjectType())) {
            throw new IllegalArgumentException("只能承接当前项目已接纳的正式能力");
        }
        readAndAdopt(subject);
    }

    private Optional<SemanticDocEntity> readAndAdopt(CatalogSubject subject) {
        grouping.adoptInLockedProject(subject);
        SemanticDocEntity existing = read(subject);
        if (!subject.accepted() || CatalogSubject.SOURCE_OBSERVATION.equals(subject.subjectType())) return Optional.ofNullable(existing);
        for (var observation : subject.observations()) {
            SemanticDocEntity candidate = docs.selectOne(Wrappers.<SemanticDocEntity>lambdaQuery()
                    .eq(SemanticDocEntity::getProjectId, subject.projectId()).eq(SemanticDocEntity::getSubjectType, CatalogSubject.SOURCE_OBSERVATION)
                    .eq(SemanticDocEntity::getSubjectId, observation.getId()).last("LIMIT 1"));
            if (candidate == null) continue;
            if (existing == null) {
                candidate.setSubjectType(subject.subjectType()); candidate.setSubjectId(subject.subjectId()); candidate.setSubjectKey(subject.subjectKey());
                candidate.setUpdateTime(nextVersion(candidate.getUpdateTime())); docs.updateById(candidate); existing = candidate;
            } else {
                existing.setContentMd(existing.getContentMd() + "\n\n## 来源候选说明：" + observation.getTitle() + "\n\n" + candidate.getContentMd());
                existing.setStatus(SemanticDocEntity.STATUS_EDITED);
                // Mixed prose requires fresh review; neither source's old fingerprint proves the whole document current.
                existing.setBasisContractHash(null); existing.setUpdateTime(nextVersion(existing.getUpdateTime())); docs.updateById(existing); docs.deleteById(candidate.getId());
            }
        }
        return Optional.ofNullable(existing);
    }

    public List<SemanticDocEntity> list(Long projectId) {
        return docs.selectList(Wrappers.<SemanticDocEntity>lambdaQuery()
                .select(SemanticDocEntity::getId, SemanticDocEntity::getProjectId, SemanticDocEntity::getSubjectType,
                        SemanticDocEntity::getSubjectId, SemanticDocEntity::getSubjectKey, SemanticDocEntity::getBasisContractHash,
                        SemanticDocEntity::getBasisAccepted, SemanticDocEntity::getPromptVersion, SemanticDocEntity::getModelName,
                        SemanticDocEntity::getTokenUsage, SemanticDocEntity::getStatus, SemanticDocEntity::getUpdateTime)
                .eq(SemanticDocEntity::getProjectId, projectId)
                .orderByAsc(SemanticDocEntity::getSubjectType).orderByAsc(SemanticDocEntity::getSubjectId));
    }

    @Transactional
    public void save(SemanticDocEntity doc, String previousVersion) {
        projects.lockCapabilityChanges(doc.getProjectId());
        CatalogSubject subject = subjects.resolve(doc.getProjectId(), doc.getSubjectType(), doc.getSubjectId());
        if (!Objects.equals(doc.getBasisContractHash(), subject.contractHash()) || !Objects.equals(doc.getSubjectKey(), subject.subjectKey())
                || !Objects.equals(doc.getBasisAccepted(), subject.accepted())) {
            throw new IllegalStateException("保存期间所属契约已变化，请刷新后重试");
        }
        SemanticDocEntity current = read(subject);
        if (doc.getId() == null) {
            if (current != null) throw new IllegalStateException("文档已被创建，请刷新后重试");
            doc.setCreateTime(doc.getUpdateTime()); docs.insert(doc);
        } else {
            if (current == null || !Objects.equals(doc.getId(), current.getId()) ||
                    !Objects.equals(previousVersion, CapabilitySemanticCatalogService.version(current))) {
                throw new IllegalStateException("文档已变化，请刷新后重试");
            }
            // Persisted versions, rather than the caller's clock tick, determine the next revision.
            if (doc.getUpdateTime() == null || !doc.getUpdateTime().isAfter(current.getUpdateTime())) doc.setUpdateTime(nextVersion(current.getUpdateTime()));
            var update = Wrappers.<SemanticDocEntity>lambdaUpdate().eq(SemanticDocEntity::getId, doc.getId()).eq(SemanticDocEntity::getProjectId, doc.getProjectId());
            update.eq(SemanticDocEntity::getSubjectType, doc.getSubjectType()).eq(SemanticDocEntity::getSubjectId, doc.getSubjectId());
            if (previousVersion == null) update.isNull(SemanticDocEntity::getUpdateTime);
            else update.eq(SemanticDocEntity::getUpdateTime, LocalDateTime.parse(previousVersion));
            if (docs.update(doc, update) != 1) throw new IllegalStateException("文档已变化，请刷新后重试");
        }
    }

    private SemanticDocEntity read(CatalogSubject subject) {
        SemanticDocEntity current = docs.selectOne(Wrappers.<SemanticDocEntity>lambdaQuery().eq(SemanticDocEntity::getProjectId, subject.projectId())
                .eq(SemanticDocEntity::getSubjectType, subject.subjectType()).eq(SemanticDocEntity::getSubjectId, subject.subjectId()).last("LIMIT 1"));
        if (current != null && !Objects.equals(subject.subjectKey(), current.getSubjectKey())) {
            throw new IllegalStateException("文档稳定引用与所属对象不一致，请检查目录数据");
        }
        return current;
    }
    private LocalDateTime now() { LocalDateTime value = LocalDateTime.now(); return value.withNano((value.getNano() / 1000) * 1000); }
    private LocalDateTime nextVersion(LocalDateTime previous) { LocalDateTime value = now(); return previous != null && !value.isAfter(previous) ? previous.plusNanos(1000) : value; }
}
