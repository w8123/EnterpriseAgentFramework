package com.enterprise.ai.capability.catalog.httpapi;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.UUID;

/** Records what the latest scanner/sync actually confirmed; old bindings never imply freshness. */
@Service
@RequiredArgsConstructor
public class HttpApiInventoryStateService {
    private final HttpApiInventoryStateMapper states;
    private final HttpApiInventoryMemberMapper members;

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(HttpApiServiceScope scope, HttpApiSourceKind kind, boolean supported,
                       boolean complete, String reason, Collection<Long> observedBindingIds) {
        String token = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now().withNano(0);
        HttpApiInventoryStateEntity state = states.selectOne(Wrappers.<HttpApiInventoryStateEntity>lambdaQuery()
                .eq(HttpApiInventoryStateEntity::getProjectId, scope.projectId())
                .eq(HttpApiInventoryStateEntity::getProjectCode, scope.projectCode())
                .eq(HttpApiInventoryStateEntity::getEnvironment, scope.environment())
                .eq(HttpApiInventoryStateEntity::getSourceKind, kind.name()).last("LIMIT 1"));
        boolean created = state == null;
        if (created) {
            state = new HttpApiInventoryStateEntity();
            state.setProjectId(scope.projectId());
            state.setProjectCode(scope.projectCode());
            state.setEnvironment(scope.environment());
            state.setSourceKind(kind.name());
        }
        state.setInventoryToken(token);
        state.setSupported(supported);
        state.setComplete(complete);
        state.setReason(reason);
        state.setObservedAt(now);
        if (created) states.insert(state); else states.updateById(state);
        for (Long bindingId : observedBindingIds) {
            HttpApiInventoryMemberEntity member = members.selectOne(Wrappers.<HttpApiInventoryMemberEntity>lambdaQuery()
                    .eq(HttpApiInventoryMemberEntity::getBindingId, bindingId).last("LIMIT 1"));
            boolean newMember = member == null;
            if (newMember) {
                member = new HttpApiInventoryMemberEntity();
                member.setBindingId(bindingId);
            }
            member.setInventoryToken(token);
            member.setObservedAt(now);
            if (newMember) members.insert(member); else members.updateById(member);
        }
    }
}
