package com.enterprise.ai.capability.catalog.httpapi;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Shared inventory lifecycle inside the caller's project-locked transaction. */
@Service
public class HttpApiInventoryIntakeService {
    private static final int MAX_OPERATIONS = 5000;

    private final HttpApiAssetService assets;
    private final HttpApiSourceBindingMapper bindings;
    private final HttpApiInventoryStateService inventories;

    public HttpApiInventoryIntakeService(HttpApiAssetService assets,
                                         HttpApiSourceBindingMapper bindings,
                                         HttpApiInventoryStateService inventories) {
        this.assets = Objects.requireNonNull(assets, "HTTP API asset service is required");
        this.bindings = Objects.requireNonNull(bindings, "HTTP API source mapper is required");
        this.inventories = Objects.requireNonNull(inventories, "HTTP API inventory state service is required");
    }

    /** Decode and validate the whole inventory before any source or scanner row is written. */
    public <T> List<PreparedOperation> prepare(HttpApiServiceScope scope, HttpApiSourceKind kind,
                                              List<T> inventory, String label,
                                              Function<T, HttpApiAssetService.ObserveRequest> decoder) {
        if (inventory == null) return List.of();
        if (inventory.size() > MAX_OPERATIONS) {
            throw new IllegalArgumentException(label + "不能超过 " + MAX_OPERATIONS + " 项");
        }
        List<PreparedOperation> operations = new ArrayList<>(inventory.size());
        Set<String> keys = new HashSet<>();
        for (T item : inventory) {
            if (item == null) throw new IllegalArgumentException(label + "不能包含空项");
            HttpApiAssetService.ObserveRequest request = decoder.apply(item);
            requireScope(scope, kind, request);
            var canonical = assets.validate(request);
            if (!keys.add(request.sourceKey().trim().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException(label + "包含重复来源标识");
            }
            operations.add(new PreparedOperation(request, canonical));
        }
        operations.sort(Comparator.comparing(item -> item.request().sourceKey()));
        return List.copyOf(operations);
    }

    /** An absent/partial inventory never removes a binding; a complete empty inventory can. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Summary observe(HttpApiServiceScope scope, HttpApiSourceKind kind,
                           boolean supported, boolean complete,
                           List<HttpApiAssetService.ObserveRequest> operations, String reason) {
        // Reject inconsistent internal plans before even the first observation mutates a fact.
        if (!supported && !operations.isEmpty()) {
            throw new IllegalArgumentException("Unsupported HTTP API inventory cannot contain operations");
        }
        operations.forEach(request -> requireScope(scope, kind, request));
        Set<String> reportedKeys = new HashSet<>();
        List<Long> observedBindings = new ArrayList<>(operations.size());
        for (HttpApiAssetService.ObserveRequest operation : operations) {
            observedBindings.add(assets.observe(operation).binding().getId());
            reportedKeys.add(operation.sourceKey());
        }
        int removed = supported && complete ? removeAbsentBindings(scope, kind, reportedKeys) : 0;
        inventories.record(scope, kind, supported, supported && complete,
                reason, observedBindings);
        return new Summary(supported, supported && complete, operations.size(), removed);
    }

    /** Single-source refresh never removes bindings or rotates an already supported inventory. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Summary observeSingle(HttpApiServiceScope scope, HttpApiSourceKind kind, boolean supported,
                                 List<HttpApiAssetService.ObserveRequest> operations,
                                 List<Long> unconfirmedBindingIds, String reason) {
        if (operations.size() > 1 || (!supported && !operations.isEmpty()))
            throw new IllegalArgumentException("Single-source HTTP API refresh requires at most one operation");
        operations.forEach(request -> requireScope(scope, kind, request));
        List<Long> confirmed = new ArrayList<>(operations.size());
        for (HttpApiAssetService.ObserveRequest request : operations) confirmed.add(assets.observe(request).binding().getId());
        inventories.recordSubset(scope, kind, confirmed, unconfirmedBindingIds, reason);
        return new Summary(supported, false, operations.size(), 0);
    }

    private int removeAbsentBindings(HttpApiServiceScope scope, HttpApiSourceKind kind,
                                     Set<String> reportedKeys) {
        int removed = 0;
        List<HttpApiSourceBindingEntity> active = bindings.selectList(
                Wrappers.<HttpApiSourceBindingEntity>lambdaQuery()
                        .eq(HttpApiSourceBindingEntity::getProjectId, scope.projectId())
                        .eq(HttpApiSourceBindingEntity::getProjectCode, scope.projectCode())
                        .eq(HttpApiSourceBindingEntity::getEnvironment, scope.environment())
                        .eq(HttpApiSourceBindingEntity::getSourceKind, kind.name())
                        .ne(HttpApiSourceBindingEntity::getStatus, HttpApiSourceBindingStatus.REMOVED.name())
                        .orderByAsc(HttpApiSourceBindingEntity::getId));
        for (HttpApiSourceBindingEntity binding : active) {
            if (!reportedKeys.contains(binding.getSourceKey()) && assets.remove(
                    new HttpApiAssetService.RemoveRequest(scope, kind, binding.getSourceKey())).changed()) {
                removed++;
            }
        }
        return removed;
    }

    private void requireScope(HttpApiServiceScope scope, HttpApiSourceKind kind,
                              HttpApiAssetService.ObserveRequest request) {
        if (scope == null || kind == null || request == null
                || !Objects.equals(scope, request.scope()) || kind != request.sourceKind()) {
            throw new IllegalArgumentException("HTTP API inventory operation does not belong to its source scope");
        }
    }

    public record PreparedOperation(HttpApiAssetService.ObserveRequest request,
                                    HttpApiContractCanonicalizer.CanonicalHttpApiContract canonical) { }

    public record Summary(boolean supported, boolean complete, int observed, int removed) { }
}
