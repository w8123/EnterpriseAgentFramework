package com.enterprise.ai.agent.platform.control.context;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ContextEvidenceService {

    private final ContextEvidenceMapper evidenceMapper;
    private final ContextAccessPolicyService accessPolicyService;

    @Transactional
    public ContextEvidenceResponse addEvidence(Long itemId, ContextEvidenceRequest request, ContextQueryRequest scope) {
        accessPolicyService.requireItemWriteAccess(itemId, scope);
        if (request == null || !StringUtils.hasText(request.getEvidenceType())) {
            throw new IllegalArgumentException("evidenceType is required");
        }
        ContextEvidenceEntity entity = new ContextEvidenceEntity();
        entity.setItemId(itemId);
        entity.setEvidenceType(request.getEvidenceType().trim().toUpperCase());
        entity.setEvidenceRef(request.getEvidenceRef());
        entity.setEvidenceExcerpt(request.getEvidenceExcerpt());
        entity.setTraceId(request.getTraceId());
        entity.setConfidence(request.getConfidence());
        entity.setMetadataJson(request.getMetadataJson());
        entity.setCreatedAt(LocalDateTime.now());
        evidenceMapper.insert(entity);
        return ContextViewMapper.toEvidenceResponse(entity);
    }

    public List<ContextEvidenceResponse> listEvidence(Long itemId, ContextQueryRequest scope) {
        accessPolicyService.requireItemReadAccess(itemId, scope);
        return evidenceMapper.selectList(
                        Wrappers.lambdaQuery(ContextEvidenceEntity.class)
                                .eq(ContextEvidenceEntity::getItemId, itemId)
                                .orderByDesc(ContextEvidenceEntity::getCreatedAt))
                .stream()
                .map(ContextViewMapper::toEvidenceResponse)
                .collect(Collectors.toList());
    }
}
