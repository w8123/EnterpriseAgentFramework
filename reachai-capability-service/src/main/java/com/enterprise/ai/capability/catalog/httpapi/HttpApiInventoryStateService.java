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
        for (Long bindingId : observedBindingIds) writeMember(bindingId, token, now);
    }

    /** A trusted subset updates its own members without changing other members' confirmation. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordSubset(HttpApiServiceScope scope, HttpApiSourceKind kind,
                             Collection<Long> confirmedBindingIds, Collection<Long> unconfirmedBindingIds,
                             String reason) {
        HttpApiInventoryStateEntity state = states.selectOne(Wrappers.<HttpApiInventoryStateEntity>lambdaQuery()
                .eq(HttpApiInventoryStateEntity::getProjectId, scope.projectId())
                .eq(HttpApiInventoryStateEntity::getProjectCode, scope.projectCode())
                .eq(HttpApiInventoryStateEntity::getEnvironment, scope.environment())
                .eq(HttpApiInventoryStateEntity::getSourceKind, kind.name()).last("LIMIT 1"));
        if (state == null || !Boolean.TRUE.equals(state.getSupported())) {
            // A prior unknown inventory cannot confer freshness on any unobserved old binding.
            record(scope, kind, !confirmedBindingIds.isEmpty(), false, reason, confirmedBindingIds);
            return;
        }
        LocalDateTime now = LocalDateTime.now().withNano(0);
        state.setObservedAt(now);
        if (!unconfirmedBindingIds.isEmpty()) {
            state.setComplete(false);
            state.setReason(reason);
        }
        states.updateById(state);
        for (Long bindingId : confirmedBindingIds) writeMember(bindingId, state.getInventoryToken(), now);
        String staleToken = UUID.randomUUID().toString();
        for (Long bindingId : unconfirmedBindingIds) writeMember(bindingId, staleToken, now);
    }

    private void writeMember(Long bindingId, String token, LocalDateTime observedAt) {
        HttpApiInventoryMemberEntity member = members.selectOne(Wrappers.<HttpApiInventoryMemberEntity>lambdaQuery()
                .eq(HttpApiInventoryMemberEntity::getBindingId, bindingId).last("LIMIT 1"));
        boolean created = member == null;
        if (created) { member = new HttpApiInventoryMemberEntity(); member.setBindingId(bindingId); }
        member.setInventoryToken(token);
        member.setObservedAt(observedAt);
        if (created) members.insert(member); else members.updateById(member);
    }
}
