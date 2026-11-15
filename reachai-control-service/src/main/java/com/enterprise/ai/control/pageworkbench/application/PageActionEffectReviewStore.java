package com.enterprise.ai.control.pageworkbench.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.control.pageworkbench.persistence.PageActionEffectGenerationMapper;
import com.enterprise.ai.control.pageworkbench.persistence.PageActionEffectReviewEntity;
import com.enterprise.ai.control.pageworkbench.persistence.PageActionEffectReviewMapper;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/** Review persistence stays with the page catalog; callers own authorization and the surrounding transaction. */
@Service
@RequiredArgsConstructor
public class PageActionEffectReviewStore {
    private final PageActionEffectGenerationMapper generations;
    private final PageActionEffectReviewMapper reviews;

    public Long lockGeneration(Long actionId) {
        return generations.lock(actionId);
    }

    public long generation(Long actionId, Long pageId) {
        Long current = generations.lock(actionId);
        if (current != null) return current;
        generations.create(actionId, pageId);
        return 0;
    }

    public void invalidateAction(Long actionId) {
        generations.invalidateAction(actionId);
    }

    public Review latest(Long actionId) {
        var row = reviews.selectOne(Wrappers.<PageActionEffectReviewEntity>lambdaQuery()
                .eq(PageActionEffectReviewEntity::getActionId, actionId)
                .orderByDesc(PageActionEffectReviewEntity::getId).last("LIMIT 1"));
        return row == null ? null : view(row);
    }

    public Review insert(Review review) {
        var row = new PageActionEffectReviewEntity();
        row.setActionId(review.getActionId());
        row.setPageId(review.getPageId());
        row.setProjectId(review.getProjectId());
        row.setProjectCode(review.getProjectCode());
        row.setPageKey(review.getPageKey());
        row.setActionKey(review.getActionKey());
        row.setRevision(review.getRevision());
        row.setDecision(review.getDecision());
        row.setEffect(review.getEffect());
        row.setCatalogHash(review.getCatalogHash());
        row.setImplementationVersion(review.getImplementationVersion());
        row.setTargetAlias(review.getTargetAlias());
        row.setFieldLabelsJson(review.getFieldLabelsJson());
        row.setActorUserId(review.getActorUserId());
        row.setActorSessionId(review.getActorSessionId());
        row.setCreatedAt(review.getCreatedAt());
        reviews.insert(row);
        return view(row);
    }

    private Review view(PageActionEffectReviewEntity row) {
        return Review.builder().id(row.getId()).actionId(row.getActionId()).pageId(row.getPageId())
                .projectId(row.getProjectId()).projectCode(row.getProjectCode()).pageKey(row.getPageKey())
                .actionKey(row.getActionKey()).revision(row.getRevision()).decision(row.getDecision())
                .effect(row.getEffect()).catalogHash(row.getCatalogHash())
                .implementationVersion(row.getImplementationVersion()).targetAlias(row.getTargetAlias())
                .fieldLabelsJson(row.getFieldLabelsJson()).actorUserId(row.getActorUserId())
                .actorSessionId(row.getActorSessionId()).createdAt(row.getCreatedAt()).build();
    }

    @Value
    @Builder(toBuilder = true)
    public static class Review {
        Long id;
        Long actionId;
        Long pageId;
        Long projectId;
        String projectCode;
        String pageKey;
        String actionKey;
        Long revision;
        String decision;
        String effect;
        String catalogHash;
        String implementationVersion;
        String targetAlias;
        String fieldLabelsJson;
        Long actorUserId;
        String actorSessionId;
        LocalDateTime createdAt;
    }
}
