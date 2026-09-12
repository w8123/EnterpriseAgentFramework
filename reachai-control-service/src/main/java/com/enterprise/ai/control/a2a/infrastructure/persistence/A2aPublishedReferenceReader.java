package com.enterprise.ai.control.a2a.infrastructure.persistence;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.control.a2a.application.port.A2aPublishedReferenceQuery;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class A2aPublishedReferenceReader implements A2aPublishedReferenceQuery {
    private static final int LIMIT = 10_000;
    private final A2aPublicationMapper publications;
    private final A2aPublicationRevisionMapper revisions;

    @Override
    @Transactional(readOnly = true)
    public Evidence inspect() {
        var published = publications.selectList(Wrappers.<A2aPublicationEntity>lambdaQuery()
                .eq(A2aPublicationEntity::getStatus, "PUBLISHED")
                .orderByAsc(A2aPublicationEntity::getId).last("limit " + (LIMIT + 1)));
        boolean complete = published.size() <= LIMIT;
        var selected = published.stream().limit(LIMIT).toList();
        var ids = selected.stream().map(A2aPublicationEntity::getCurrentRevisionId)
                .filter(Objects::nonNull).distinct().toList();
        var frozen = ids.isEmpty() ? List.<A2aPublicationRevisionEntity>of() : revisions.selectBatchIds(ids);
        var byId = frozen.stream().collect(Collectors.toMap(A2aPublicationRevisionEntity::getId, Function.identity()));
        List<Binding> bindings = new ArrayList<>();
        for (var publication : selected) {
            var revision = byId.get(publication.getCurrentRevisionId());
            if (revision == null || revision.getAgentConfigVersionId() == null
                    || !Objects.equals(publication.getId(), revision.getPublicationId())) {
                complete = false; continue;
            }
            bindings.add(new Binding(publication.getId(), revision.getName(), publication.getAgentId(), revision.getAgentConfigVersionId()));
        }
        return new Evidence(complete, bindings);
    }
}
