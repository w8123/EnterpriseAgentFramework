package com.enterprise.ai.capability.catalog.scan;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiAssetService;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiContractCanonicalizer;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiMappingConditionKind;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiMappingConditionOperator;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiOperationContract;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiParameterLocation;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiServiceScope;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiSourceBindingEntity;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiSourceBindingMapper;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiSourceBindingStatus;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiSourceKind;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiInventoryStateService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Adapts a complete-or-partial OpenAPI scan inventory to Capability-owned HTTP API observations.
 *
 * <p>The persisted scan project supplies the only trusted service scope. A null inventory is an
 * old scanner wire and never removes an {@link HttpApiSourceKind#OPENAPI_SCAN} binding. Partial
 * inventories may observe individually provable operations but cannot tombstone unreported ones.</p>
 */
@Service
public class OpenApiScanHttpApiIntakeService {

    private static final int MAX_HTTP_APIS = 5000;

    private final HttpApiAssetService assetService;
    private final HttpApiSourceBindingMapper bindingMapper;
    private final HttpApiInventoryStateService inventoryState;

    @Autowired
    public OpenApiScanHttpApiIntakeService(HttpApiAssetService assetService,
                                           HttpApiSourceBindingMapper bindingMapper,
                                           HttpApiInventoryStateService inventoryState) {
        this.assetService = assetService;
        this.bindingMapper = bindingMapper;
        this.inventoryState = inventoryState;
    }

    /** Existing isolated source-adapter tests can run without the new inventory ledger. */
    public OpenApiScanHttpApiIntakeService(HttpApiAssetService assetService,
                                           HttpApiSourceBindingMapper bindingMapper) {
        this(assetService, bindingMapper, null);
    }

    /** Validate every source fact before the caller persists legacy scan rows. */
    public Plan prepare(ScanProjectEntity project,
                        List<CapabilityScannerClient.HttpApiData> inventory,
                        Boolean inventoryComplete) {
        HttpApiServiceScope scope = new HttpApiServiceScope(project.getId(), project.getProjectCode(),
                defaultString(project.getEnvironment(), "default"));
        if (inventory == null) return Plan.legacy(scope);
        if (inventory.size() > MAX_HTTP_APIS) {
            throw new IllegalArgumentException("OpenAPI HTTP API 扫描清单不能超过 " + MAX_HTTP_APIS + " 项");
        }
        Set<String> sourceKeys = new HashSet<>();
        List<PreparedOperation> operations = new ArrayList<>();
        for (CapabilityScannerClient.HttpApiData item : inventory) {
            if (item == null) {
                throw new IllegalArgumentException("OpenAPI HTTP API 扫描清单不能包含空项");
            }
            requireText(item.sourceRevision(), "OpenAPI HTTP API source revision");
            HttpApiOperationContract contract = contract(project, item);
            HttpApiAssetService.ObserveRequest request = new HttpApiAssetService.ObserveRequest(scope,
                    HttpApiSourceKind.OPENAPI_SCAN, item.sourceKey(), item.sourceLocation(), item.sourceRevision(), contract);
            HttpApiContractCanonicalizer.CanonicalHttpApiContract canonical = assetService.validate(request);
            String normalizedKey = item.sourceKey() == null ? "" : item.sourceKey().trim().toLowerCase(Locale.ROOT);
            if (!sourceKeys.add(normalizedKey)) {
                throw new IllegalArgumentException("OpenAPI HTTP API 扫描清单包含重复来源标识");
            }
            operations.add(new PreparedOperation(request, canonical.contractHash()));
        }
        operations.sort(Comparator.comparing(item -> item.request().sourceKey()));
        return new Plan(true, Boolean.TRUE.equals(inventoryComplete), scope, List.copyOf(operations));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Summary observe(Plan plan) {
        if (plan == null) {
            return Summary.legacy();
        }
        if (!plan.supported()) {
            recordInventory(plan, List.of());
            return Summary.legacy();
        }
        Set<String> reportedKeys = new HashSet<>();
        List<Long> observedBindings = new ArrayList<>();
        for (PreparedOperation operation : plan.operations()) {
            observedBindings.add(assetService.observe(operation.request()).binding().getId());
            reportedKeys.add(operation.request().sourceKey());
        }
        int removed = plan.inventoryComplete() ? removeAbsentBindings(plan.scope(), reportedKeys) : 0;
        recordInventory(plan, observedBindings);
        return new Summary(true, plan.inventoryComplete(), plan.operations().size(), removed);
    }

    private void recordInventory(Plan plan, List<Long> bindingIds) {
        if (inventoryState == null) return;
        String reason = !plan.supported() ? "本次扫描器未返回 HTTP API 清单，旧来源尚未重新确认"
                : !plan.inventoryComplete() ? "本次 HTTP API 清单不完整；未报告的旧来源尚未重新确认" : null;
        inventoryState.record(plan.scope(), HttpApiSourceKind.OPENAPI_SCAN,
                plan.supported(), plan.inventoryComplete(), reason, bindingIds);
    }

    private int removeAbsentBindings(HttpApiServiceScope scope, Set<String> reportedKeys) {
        int removed = 0;
        List<HttpApiSourceBindingEntity> active = bindingMapper.selectList(
                Wrappers.<HttpApiSourceBindingEntity>lambdaQuery()
                        .eq(HttpApiSourceBindingEntity::getProjectId, scope.projectId())
                        .eq(HttpApiSourceBindingEntity::getProjectCode, scope.projectCode())
                        .eq(HttpApiSourceBindingEntity::getEnvironment, scope.environment())
                        .eq(HttpApiSourceBindingEntity::getSourceKind, HttpApiSourceKind.OPENAPI_SCAN.name())
                        .ne(HttpApiSourceBindingEntity::getStatus, HttpApiSourceBindingStatus.REMOVED.name())
                        .orderByAsc(HttpApiSourceBindingEntity::getId));
        for (HttpApiSourceBindingEntity binding : active) {
            if (!reportedKeys.contains(binding.getSourceKey())) {
                HttpApiAssetService.Removal removal = assetService.remove(new HttpApiAssetService.RemoveRequest(
                        scope, HttpApiSourceKind.OPENAPI_SCAN, binding.getSourceKey()));
                if (removal.changed()) {
                    removed++;
                }
            }
        }
        return removed;
    }

    private HttpApiOperationContract contract(ScanProjectEntity project, CapabilityScannerClient.HttpApiData item) {
        List<HttpApiOperationContract.MappingCondition> conditions = new ArrayList<>();
        for (CapabilityScannerClient.HttpApiMappingConditionData condition : defaultList(item.mappingConditions())) {
            if (condition == null) {
                throw new IllegalArgumentException("OpenAPI HTTP API mapping condition cannot be null");
            }
            conditions.add(new HttpApiOperationContract.MappingCondition(
                    enumValue(HttpApiMappingConditionKind.class, condition.kind(), "mapping condition kind"),
                    condition.name(), enumValue(HttpApiMappingConditionOperator.class, condition.operator(),
                    "mapping condition operator"), condition.value()));
        }
        List<HttpApiOperationContract.Parameter> parameters = new ArrayList<>();
        for (CapabilityScannerClient.HttpApiParameterData parameter : defaultList(item.parameters())) {
            if (parameter == null) {
                throw new IllegalArgumentException("OpenAPI HTTP API parameter cannot be null");
            }
            HttpApiParameterLocation location = enumValue(HttpApiParameterLocation.class, parameter.location(),
                    "parameter location");
            if (location == HttpApiParameterLocation.BODY) {
                throw new IllegalArgumentException("OpenAPI HTTP API BODY must use requestBody instead of parameters");
            }
            parameters.add(new HttpApiOperationContract.Parameter(parameter.name(), location,
                    Boolean.TRUE.equals(parameter.required()), parameter.schema(), parameter.contentTypes()));
        }
        HttpApiOperationContract.RequestBody requestBody = null;
        if (item.requestBody() != null) {
            CapabilityScannerClient.HttpApiRequestBodyData body = item.requestBody();
            if (!"BODY".equals(normalized(body.location()))) {
                throw new IllegalArgumentException("OpenAPI HTTP API requestBody location must be BODY");
            }
            requestBody = new HttpApiOperationContract.RequestBody(Boolean.TRUE.equals(body.required()), body.schema(),
                    body.contentTypes());
        }
        List<HttpApiOperationContract.Response> responses = new ArrayList<>();
        for (CapabilityScannerClient.HttpApiResponseData response : defaultList(item.responses())) {
            if (response == null) {
                throw new IllegalArgumentException("OpenAPI HTTP API response cannot be null");
            }
            responses.add(new HttpApiOperationContract.Response(response.status(), response.schema(), response.contentTypes()));
        }
        String authenticationState = normalizedOrDefault(item.authenticationState(), "UNKNOWN");
        return new HttpApiOperationContract(item.httpMethod(), normalizeContextPath(project.getContextPath()), item.endpointPath(),
                new HttpApiOperationContract.MappingConditions(item.consumes(), item.produces(), conditions),
                parameters, requestBody, responses,
                new HttpApiOperationContract.AuthenticationRequirement("REQUIRED".equals(authenticationState), authenticationState,
                        item.authenticationSchemes(), item.requiredHeaderNames()),
                item.sideEffect());
    }

    private static <T> List<T> defaultList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, String raw, String field) {
        try {
            return Enum.valueOf(type, normalized(raw));
        } catch (Exception invalid) {
            throw new IllegalArgumentException("OpenAPI HTTP API " + field + " is invalid", invalid);
        }
    }

    private static String normalized(String raw) {
        if (!StringUtils.hasText(raw)) {
            throw new IllegalArgumentException("OpenAPI HTTP API field is required");
        }
        return raw.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizedOrDefault(String raw, String fallback) {
        return StringUtils.hasText(raw) ? raw.trim().toUpperCase(Locale.ROOT) : fallback;
    }

    private static String defaultString(String raw, String fallback) {
        return StringUtils.hasText(raw) ? raw.trim() : fallback;
    }

    private static String normalizeContextPath(String raw) {
        if (!StringUtils.hasText(raw) || "/".equals(raw.trim())) {
            return "";
        }
        String value = raw.trim();
        return value.startsWith("/") ? value : "/" + value;
    }

    private static void requireText(String raw, String field) {
        if (!StringUtils.hasText(raw)) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    public record Plan(boolean supported,
                       boolean inventoryComplete,
                       HttpApiServiceScope scope,
                       List<PreparedOperation> operations) {
        static Plan legacy(HttpApiServiceScope scope) {
            return new Plan(false, false, scope, List.of());
        }
    }

    public record PreparedOperation(HttpApiAssetService.ObserveRequest request, String contractHash) {
    }

    public record Summary(boolean supported, boolean inventoryComplete, int observed, int removed) {
        static Summary legacy() {
            return new Summary(false, false, 0, 0);
        }
    }
}
