package com.enterprise.ai.control.pageworkbench.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.FindingReport;
import com.enterprise.ai.control.pageworkbench.application.PageWorkbenchContract.FindingView;
import com.enterprise.ai.control.pageworkbench.domain.PageWorkbenchValues;
import com.enterprise.ai.control.pageworkbench.domain.PageWorkbenchValues.FindingStatus;
import com.enterprise.ai.control.pageworkbench.persistence.PageAnalysisFindingEntity;
import com.enterprise.ai.control.pageworkbench.persistence.PageAnalysisFindingMapper;
import com.enterprise.ai.control.pageworkbench.persistence.ProjectPageEntity;
import com.enterprise.ai.control.pageworkbench.persistence.ProjectPageMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class PageAnalysisApplicationService {

    public static final int MAXIMUM_FINDINGS = 3;

    private final PageAnalysisFindingMapper findingMapper;
    private final ProjectPageMapper pageMapper;
    private final ObjectMapper objectMapper;

    public List<FindingView> list(String projectCode, Long pageId, String status) {
        String normalizedProjectCode = requireText(projectCode, "projectCode");
        var query = Wrappers.<PageAnalysisFindingEntity>lambdaQuery()
                .eq(PageAnalysisFindingEntity::getProjectCode, normalizedProjectCode)
                .eq(pageId != null, PageAnalysisFindingEntity::getPageId, pageId)
                .eq(StringUtils.hasText(status),
                        PageAnalysisFindingEntity::getStatus,
                        StringUtils.hasText(status)
                                ? PageWorkbenchValues.requiredEnum(FindingStatus.class, status, "status").name()
                                : null)
                .orderByDesc(PageAnalysisFindingEntity::getUpdatedAt);
        return findingMapper.selectList(query).stream().map(this::toView).toList();
    }

    @Transactional
    public FindingView updateStatus(
            String projectCode,
            Long findingId,
            String status,
            String reviewedBy) {
        FindingStatus next = PageWorkbenchValues.requiredEnum(FindingStatus.class, status, "status");
        PageAnalysisFindingEntity finding = findingMapper.selectOne(
                Wrappers.<PageAnalysisFindingEntity>lambdaQuery()
                        .eq(PageAnalysisFindingEntity::getId, findingId)
                        .eq(PageAnalysisFindingEntity::getProjectCode, requireText(projectCode, "projectCode"))
                        .last("LIMIT 1"));
        if (finding == null) {
            throw new IllegalArgumentException("analysis finding not found: " + findingId);
        }
        finding.setStatus(next.name());
        finding.setReviewedBy(textOrNull(reviewedBy));
        finding.setReviewedAt(LocalDateTime.now());
        finding.setUpdatedAt(LocalDateTime.now());
        findingMapper.updateById(finding);
        return toView(finding);
    }

    @Transactional
    public int applyAnalysis(
            Long projectId,
            String projectCode,
            String taskId,
            String pageKey,
            List<FindingReport> reports) {
        List<FindingReport> normalized = reports == null ? List.of() : reports;
        if (normalized.size() > MAXIMUM_FINDINGS) {
            throw new IllegalArgumentException("a page analysis may return at most 3 findings");
        }
        ProjectPageEntity page = pageMapper.selectOne(Wrappers.<ProjectPageEntity>lambdaQuery()
                .eq(ProjectPageEntity::getProjectCode, requireText(projectCode, "projectCode"))
                .eq(ProjectPageEntity::getPageKey, requireText(pageKey, "pageKey"))
                .last("LIMIT 1"));
        if (page == null) {
            throw new IllegalArgumentException("analysis target page not found: " + pageKey);
        }
        if (!Objects.equals(projectId, page.getProjectId())) {
            throw new IllegalArgumentException("analysis target page project mismatch");
        }
        LocalDateTime now = LocalDateTime.now();
        for (FindingReport report : normalized) {
            if (report == null) {
                throw new IllegalArgumentException("finding report is required");
            }
            String findingKey = requireText(report.findingKey(), "findingKey");
            PageAnalysisFindingEntity entity = findingMapper.selectOne(
                    Wrappers.<PageAnalysisFindingEntity>lambdaQuery()
                            .eq(PageAnalysisFindingEntity::getPageId, page.getId())
                            .eq(PageAnalysisFindingEntity::getFindingKey, findingKey)
                            .last("LIMIT 1"));
            boolean create = entity == null;
            if (create) {
                entity = new PageAnalysisFindingEntity();
                entity.setFindingKey(findingKey);
                entity.setProjectId(projectId);
                entity.setProjectCode(projectCode);
                entity.setPageId(page.getId());
                entity.setPageKey(page.getPageKey());
                entity.setStatus(FindingStatus.UNREAD.name());
                entity.setCreatedAt(now);
            }
            String title = requireText(report.title(), "finding title");
            String confirmedFact = requireText(report.confirmedFact(), "confirmedFact");
            String technicalInference = textOrNull(report.technicalInference());
            String openQuestion = textOrNull(report.openQuestion());
            String useCase = textOrNull(report.useCase());
            String businessConfirmStatus = firstText(report.businessConfirmStatus(), "PENDING");
            String technicalFeasibility = firstText(report.technicalFeasibility(), "UNKNOWN");
            String operationRisk = firstText(report.operationRisk(), "UNKNOWN");
            String informationCompleteness = firstText(
                    report.informationCompleteness(),
                    "PARTIAL");
            String readScope = textOrNull(report.readScope());
            String writeScope = textOrNull(report.writeScope());
            String implementationReference = textOrNull(report.implementationReference());
            String acceptanceCriteria = textOrNull(report.acceptanceCriteria());
            String relatedPagesJson = writeJson(
                    report.relatedPages() == null ? List.of() : report.relatedPages());
            String evidenceJson = writeJson(report.evidence());
            String codeReferencesJson = writeJson(report.codeReferences());
            String category = textOrNull(report.category());
            if (!create && findingContentChanged(
                    entity,
                    taskId,
                    category,
                    title,
                    confirmedFact,
                    technicalInference,
                    openQuestion,
                    useCase,
                    businessConfirmStatus,
                    technicalFeasibility,
                    operationRisk,
                    informationCompleteness,
                    readScope,
                    writeScope,
                    implementationReference,
                    acceptanceCriteria,
                    relatedPagesJson,
                    evidenceJson,
                    codeReferencesJson)) {
                entity.setStatus(FindingStatus.UNREAD.name());
                entity.setReviewedBy(null);
                entity.setReviewedAt(null);
            }
            entity.setSourceTaskId(taskId);
            entity.setCategory(category);
            entity.setTitle(title);
            entity.setConfirmedFact(confirmedFact);
            entity.setTechnicalInference(technicalInference);
            entity.setOpenQuestion(openQuestion);
            entity.setUseCase(useCase);
            entity.setBusinessConfirmStatus(businessConfirmStatus);
            entity.setTechnicalFeasibility(technicalFeasibility);
            entity.setOperationRisk(operationRisk);
            entity.setInformationCompleteness(informationCompleteness);
            entity.setReadScope(readScope);
            entity.setWriteScope(writeScope);
            entity.setImplementationReference(implementationReference);
            entity.setAcceptanceCriteria(acceptanceCriteria);
            entity.setRelatedPagesJson(relatedPagesJson);
            entity.setEvidenceJson(evidenceJson);
            entity.setCodeReferencesJson(codeReferencesJson);
            entity.setUpdatedAt(now);
            if (create) {
                findingMapper.insert(entity);
            } else {
                findingMapper.updateById(entity);
            }
        }
        return normalized.size();
    }

    private static boolean findingContentChanged(
            PageAnalysisFindingEntity entity,
            String taskId,
            String category,
            String title,
            String confirmedFact,
            String technicalInference,
            String openQuestion,
            String useCase,
            String businessConfirmStatus,
            String technicalFeasibility,
            String operationRisk,
            String informationCompleteness,
            String readScope,
            String writeScope,
            String implementationReference,
            String acceptanceCriteria,
            String relatedPagesJson,
            String evidenceJson,
            String codeReferencesJson) {
        return !Objects.equals(entity.getSourceTaskId(), taskId)
                || !Objects.equals(entity.getCategory(), category)
                || !Objects.equals(entity.getTitle(), title)
                || !Objects.equals(entity.getConfirmedFact(), confirmedFact)
                || !Objects.equals(entity.getTechnicalInference(), technicalInference)
                || !Objects.equals(entity.getOpenQuestion(), openQuestion)
                || !Objects.equals(entity.getUseCase(), useCase)
                || !Objects.equals(entity.getBusinessConfirmStatus(), businessConfirmStatus)
                || !Objects.equals(entity.getTechnicalFeasibility(), technicalFeasibility)
                || !Objects.equals(entity.getOperationRisk(), operationRisk)
                || !Objects.equals(
                        entity.getInformationCompleteness(),
                        informationCompleteness)
                || !Objects.equals(entity.getReadScope(), readScope)
                || !Objects.equals(entity.getWriteScope(), writeScope)
                || !Objects.equals(
                        entity.getImplementationReference(),
                        implementationReference)
                || !Objects.equals(entity.getAcceptanceCriteria(), acceptanceCriteria)
                || !Objects.equals(entity.getRelatedPagesJson(), relatedPagesJson)
                || !Objects.equals(entity.getEvidenceJson(), evidenceJson)
                || !Objects.equals(entity.getCodeReferencesJson(), codeReferencesJson);
    }

    private FindingView toView(PageAnalysisFindingEntity entity) {
        return new FindingView(
                entity.getId(),
                entity.getFindingKey(),
                entity.getPageId(),
                entity.getPageKey(),
                entity.getSourceTaskId(),
                entity.getCategory(),
                entity.getTitle(),
                entity.getConfirmedFact(),
                entity.getTechnicalInference(),
                entity.getOpenQuestion(),
                entity.getUseCase(),
                entity.getBusinessConfirmStatus(),
                entity.getTechnicalFeasibility(),
                entity.getOperationRisk(),
                entity.getInformationCompleteness(),
                entity.getReadScope(),
                entity.getWriteScope(),
                entity.getImplementationReference(),
                entity.getAcceptanceCriteria(),
                readStringList(entity.getRelatedPagesJson()),
                readNode(entity.getEvidenceJson()),
                readNode(entity.getCodeReferencesJson()),
                entity.getStatus(),
                entity.getReviewedBy(),
                entity.getReviewedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("analysis value is not JSON serializable", ex);
        }
    }

    private JsonNode readNode(String json) {
        if (!StringUtils.hasText(json)) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException ex) {
            return objectMapper.createObjectNode();
        }
    }

    private List<String> readStringList(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    private static String requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private static String firstText(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private static String textOrNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
