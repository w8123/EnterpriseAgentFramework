package com.enterprise.ai.capability.catalog.scan;

import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiAssetService;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiInventoryIntakeService;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiServiceScope;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiSourceKind;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;

/** One scanner intake; persisted project scope and explicit source kind remain authoritative. */
@Service
@RequiredArgsConstructor
public class ScannerHttpApiIntakeService {
    private final HttpApiInventoryIntakeService inventories;
    private final ScannerHttpApiContractAdapter contracts;

    public Plan prepare(HttpApiSourceKind kind, ScanProjectEntity project,
                        List<CapabilityScannerClient.HttpApiData> inventory,
                        Boolean inventoryComplete) {
        String label = label(kind);
        HttpApiServiceScope scope = new HttpApiServiceScope(project.getId(), project.getProjectCode(),
                StringUtils.hasText(project.getEnvironment()) ? project.getEnvironment().trim() : "default");
        var prepared = inventories.prepare(scope, kind, inventory, label + " HTTP API 扫描清单", item -> {
            if (!StringUtils.hasText(item.sourceRevision())) {
                throw new IllegalArgumentException(label + " HTTP API source revision is required");
            }
            return new HttpApiAssetService.ObserveRequest(scope, kind, item.sourceKey(),
                    item.sourceLocation(), item.sourceRevision(), contracts.contract(project, item), item.businessGroups());
        });
        return new Plan(inventory != null, inventory != null && Boolean.TRUE.equals(inventoryComplete),
                scope, kind, prepared.stream().map(item ->
                new PreparedOperation(item.request(), item.canonical().contractHash(), item.canonical().identityHash())).toList());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Summary observe(Plan plan) {
        Objects.requireNonNull(plan, "Prepared scanner HTTP API inventory is required");
        String reason = !plan.supported() ? "本次扫描器未返回 HTTP API 清单，旧来源尚未重新确认"
                : !plan.inventoryComplete() ? "本次 HTTP API 清单不完整；未报告的旧来源尚未重新确认" : null;
        return observe(plan, reason);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Summary observe(Plan plan, String reason) {
        Objects.requireNonNull(plan, "Prepared scanner HTTP API inventory is required");
        label(plan.sourceKind());
        var summary = inventories.observe(plan.scope(), plan.sourceKind(), plan.supported(),
                plan.inventoryComplete(), plan.operations().stream().map(PreparedOperation::request).toList(), reason);
        return new Summary(summary.supported(), summary.complete(), summary.observed(), summary.removed());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Summary observeSingle(Plan plan, List<Long> unconfirmedBindingIds, String reason) {
        Objects.requireNonNull(plan, "Prepared scanner HTTP API inventory is required");
        label(plan.sourceKind());
        if (plan.inventoryComplete()) throw new IllegalArgumentException("Single-source refresh is not a complete inventory");
        var summary = inventories.observeSingle(plan.scope(), plan.sourceKind(), plan.supported(),
                plan.operations().stream().map(PreparedOperation::request).toList(), unconfirmedBindingIds, reason);
        return new Summary(summary.supported(), summary.complete(), summary.observed(), summary.removed());
    }

    private String label(HttpApiSourceKind kind) {
        if (kind == HttpApiSourceKind.CONTROLLER_SCAN) return "Controller";
        if (kind == HttpApiSourceKind.OPENAPI_SCAN) return "OpenAPI";
        throw new IllegalArgumentException("Scanner intake requires CONTROLLER_SCAN or OPENAPI_SCAN");
    }

    public record Plan(boolean supported, boolean inventoryComplete, HttpApiServiceScope scope,
                       HttpApiSourceKind sourceKind, List<PreparedOperation> operations) {
        public Plan { operations = List.copyOf(operations); }
    }

    public record PreparedOperation(HttpApiAssetService.ObserveRequest request, String contractHash, String identityHash) { }

    public record Summary(boolean supported, boolean inventoryComplete, int observed, int removed) { }
}
