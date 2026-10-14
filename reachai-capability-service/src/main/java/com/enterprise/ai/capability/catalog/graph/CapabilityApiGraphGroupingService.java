package com.enterprise.ai.capability.catalog.graph;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.capability.catalog.graph.ApiGraphNodeEntity;
import com.enterprise.ai.agent.capability.catalog.graph.ApiGraphNodeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Synchronizes grouping labels inside the grouping owner's project-locked transaction. */
@Service
@RequiredArgsConstructor
public class CapabilityApiGraphGroupingService {
    private final ApiGraphNodeMapper nodes;

    @Transactional(propagation = Propagation.MANDATORY)
    public void rename(Long projectId, Long groupId, String displayName) {
        ApiGraphNodeEntity node = groupNode(projectId, groupId);
        if (node != null) { node.setLabel(displayName); nodes.updateById(node); }
    }

    private ApiGraphNodeEntity groupNode(Long projectId, Long groupId) { return nodes.selectOne(Wrappers.<ApiGraphNodeEntity>lambdaQuery()
            .eq(ApiGraphNodeEntity::getProjectId, projectId).eq(ApiGraphNodeEntity::getKind, "MODULE").eq(ApiGraphNodeEntity::getRefId, groupId).last("LIMIT 1")); }
}
