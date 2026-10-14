package com.enterprise.ai.capability.catalog.scan;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectToolEntity;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiAssetEntity;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiAssetMapper;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiSourceBindingEntity;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiSourceBindingMapper;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiSourceBindingStatus;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiSourceKind;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Refreshes one known scanner binding. Identity changes require full synchronization. */
@Service
@RequiredArgsConstructor
public class ScannerHttpApiSingleSourceIntakeService {
    private final ScannerHttpApiIntakeService scanner;
    private final HttpApiSourceBindingMapper bindings;
    private final HttpApiAssetMapper assets;

    public Plan prepare(HttpApiSourceKind kind, ScanProjectEntity project, ScanProjectToolEntity observation,
                        List<CapabilityScannerClient.HttpApiData> inventory) {
        if (kind != HttpApiSourceKind.CONTROLLER_SCAN && kind != HttpApiSourceKind.OPENAPI_SCAN)
            throw new IllegalArgumentException("单来源刷新必须使用明确的扫描来源类型");
        if (project == null || observation == null || !Objects.equals(project.getId(), observation.getProjectId())
                || !"HTTP_API".equals(observation.getAssetType()) || !"scanner".equals(observation.getSource()))
            throw new IllegalArgumentException("单来源刷新对象不属于当前项目的 HTTP API 扫描来源");
        String environment = StringUtils.hasText(project.getEnvironment()) ? project.getEnvironment().trim() : "default";
        List<HttpApiSourceBindingEntity> scoped = bindings.selectList(Wrappers.<HttpApiSourceBindingEntity>lambdaQuery()
                .eq(HttpApiSourceBindingEntity::getProjectId, project.getId())
                .eq(HttpApiSourceBindingEntity::getProjectCode, project.getProjectCode())
                .eq(HttpApiSourceBindingEntity::getEnvironment, environment)
                .eq(HttpApiSourceBindingEntity::getSourceKind, kind.name())
                .ne(HttpApiSourceBindingEntity::getStatus, HttpApiSourceBindingStatus.REMOVED.name()));
        List<Long> ids = scoped.stream().map(HttpApiSourceBindingEntity::getAssetId).filter(Objects::nonNull).distinct().toList();
        Map<Long, HttpApiAssetEntity> byId = ids.isEmpty() ? Map.of() : assets.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(HttpApiAssetEntity::getId, Function.identity()));
        String method = ScannerHttpApiSourceIdentity.method(observation.getHttpMethod());
        String route = ScannerHttpApiSourceIdentity.route(observation.getContextPath(), observation.getEndpointPath());
        List<HttpApiSourceBindingEntity> matches = scoped.stream().filter(binding -> {
            HttpApiAssetEntity asset = byId.get(binding.getAssetId());
            return asset != null && Objects.equals(project.getId(), binding.getProjectId())
                    && Objects.equals(project.getProjectCode(), binding.getProjectCode())
                    && Objects.equals(environment, binding.getEnvironment()) && kind.name().equals(binding.getSourceKind())
                    && Objects.equals(project.getId(), asset.getProjectId())
                    && Objects.equals(project.getProjectCode(), asset.getProjectCode())
                    && Objects.equals(environment, asset.getEnvironment())
                    && method.equals(asset.getHttpMethod()) && route.equals(asset.getRouteTemplate())
                    && ScannerHttpApiSourceIdentity.matches(kind, observation.getSourceLocation(), binding.getSourceLocation());
        }).toList();
        List<Long> matchingIds = matches.stream().map(HttpApiSourceBindingEntity::getId).toList();
        if (matches.size() != 1) return unresolved(kind, project, inventory == null ? null : List.of(), matchingIds,
                "无法唯一确定已有 API 来源绑定；请完整同步");
        if (inventory == null) return unresolved(kind, project, null, matchingIds,
                "扫描器未返回 HTTP API 清单；请完整同步以确认来源");
        HttpApiSourceBindingEntity binding = matches.get(0);
        List<CapabilityScannerClient.HttpApiData> candidates = inventory.stream().filter(Objects::nonNull)
                .filter(item -> method.equals(ScannerHttpApiSourceIdentity.method(item.httpMethod()))
                        && route.equals(ScannerHttpApiSourceIdentity.route(project.getContextPath(), item.endpointPath()))
                        && ScannerHttpApiSourceIdentity.matches(kind, observation.getSourceLocation(), item.sourceLocation()))
                .toList();
        if (candidates.size() != 1 || !Objects.equals(binding.getSourceKey(), candidates.get(0).sourceKey()))
            return unresolved(kind, project, List.of(), matchingIds,
                "已有 API 来源未被唯一重新确认；身份变化需完整同步");
        ScannerHttpApiIntakeService.Plan prepared = scanner.prepare(kind, project, candidates, false);
        if (!Objects.equals(byId.get(binding.getAssetId()).getIdentityHash(), prepared.operations().get(0).identityHash()))
            return unresolved(kind, project, List.of(), matchingIds, "API 映射条件或内容类型身份变化；请完整同步");
        return new Plan(prepared, List.of(),
                "本次仅刷新一个 API 来源；其他来源尚未在本次清单中重新确认");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public ScannerHttpApiIntakeService.Summary observe(Plan plan) {
        Objects.requireNonNull(plan, "Prepared single-source HTTP API plan is required");
        return scanner.observeSingle(plan.inventory(), plan.unconfirmedBindingIds(), plan.reason());
    }

    private Plan unresolved(HttpApiSourceKind kind, ScanProjectEntity project,
                            List<CapabilityScannerClient.HttpApiData> inventory, List<Long> unconfirmedBindingIds,
                            String reason) {
        return new Plan(scanner.prepare(kind, project, inventory, false), unconfirmedBindingIds, reason);
    }

    public record Plan(ScannerHttpApiIntakeService.Plan inventory, List<Long> unconfirmedBindingIds, String reason) {
        public Plan {
            Objects.requireNonNull(inventory, "Prepared scanner inventory is required");
            unconfirmedBindingIds = List.copyOf(unconfirmedBindingIds);
        }
    }
}
