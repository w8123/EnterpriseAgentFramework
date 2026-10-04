package com.enterprise.ai.capability.registry;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.agent.registry.RegistryContracts.HttpApiMappingConditionRegistration;
import com.enterprise.ai.agent.registry.RegistryContracts.HttpApiParameterRegistration;
import com.enterprise.ai.agent.registry.RegistryContracts.HttpApiRegistration;
import com.enterprise.ai.agent.registry.RegistryContracts.HttpApiRequestBodyRegistration;
import com.enterprise.ai.agent.registry.RegistryContracts.HttpApiResponseRegistration;
import com.enterprise.ai.agent.registry.RegistryContracts.HttpApiSyncSummary;
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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Adapts the Starter's complete MVC inventory to the HTTP API source-binding foundation.
 *
 * <p>The service has no Tool, business-method, ACL, or execution dependency. All contracts are
 * validated before an observation starts, and the enclosing Registry transaction provides the
 * atomic boundary for inventory observation and source removal.</p>
 */
@Service
public class StarterMvcHttpApiIntakeService {

    private static final int MAX_HTTP_APIS = 5000;

    private final HttpApiAssetService assetService;
    private final HttpApiSourceBindingMapper bindingMapper;
    private final ObjectMapper objectMapper;
    private final HttpApiInventoryStateService inventoryState;

    @Autowired
    public StarterMvcHttpApiIntakeService(HttpApiAssetService assetService,
                                         HttpApiSourceBindingMapper bindingMapper,
                                         ObjectMapper objectMapper,
                                         HttpApiInventoryStateService inventoryState) {
        this.assetService = assetService;
        this.bindingMapper = bindingMapper;
        this.objectMapper = objectMapper;
        this.inventoryState = inventoryState;
    }

    public StarterMvcHttpApiIntakeService(HttpApiAssetService assetService,
                                         HttpApiSourceBindingMapper bindingMapper,
                                         ObjectMapper objectMapper) {
        this(assetService, bindingMapper, objectMapper, null);
    }

    /**
     * A null list is the old wire protocol: it deliberately carries no removal instruction.
     * A present empty list is a supported full inventory and can remove prior STARTER_MVC facts.
     */
    public Plan prepare(ScanProjectEntity project, String syncId, List<HttpApiRegistration> registrations) {
        HttpApiServiceScope scope = new HttpApiServiceScope(project.getId(), project.getProjectCode(),
                defaultString(project.getEnvironment(), "default"));
        if (registrations == null) return Plan.legacy(scope);
        if (registrations.size() > MAX_HTTP_APIS) {
            throw new IllegalArgumentException("HTTP API 同步清单不能超过 " + MAX_HTTP_APIS + " 项");
        }
        Set<String> sourceKeys = new HashSet<String>();
        List<PreparedOperation> operations = new ArrayList<PreparedOperation>();
        for (HttpApiRegistration registration : registrations) {
            if (registration == null) {
                throw new IllegalArgumentException("HTTP API 同步清单不能包含空项");
            }
            HttpApiOperationContract contract = contract(registration);
            HttpApiAssetService.ObserveRequest request = new HttpApiAssetService.ObserveRequest(scope,
                    HttpApiSourceKind.STARTER_MVC, registration.sourceKey(), registration.sourceLocation(), syncId, contract);
            HttpApiContractCanonicalizer.CanonicalHttpApiContract canonical = assetService.validate(request);
            String key = registration.sourceKey().trim().toLowerCase(Locale.ROOT);
            if (!sourceKeys.add(key)) {
                throw new IllegalArgumentException("HTTP API 同步清单包含重复来源标识");
            }
            operations.add(new PreparedOperation(request, safeSnapshotRegistration(registration, canonical)));
        }
        operations.sort(Comparator.comparing(item -> item.request().sourceKey()));
        return new Plan(true, scope, operations);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public HttpApiSyncSummary observe(Plan plan) {
        if (plan == null) {
            return new HttpApiSyncSummary(false, 0, 0, 0);
        }
        if (!plan.supported()) {
            recordInventory(plan, List.of());
            return new HttpApiSyncSummary(false, 0, 0, 0);
        }
        Set<String> reportedKeys = new HashSet<String>();
        List<Long> observedBindings = new ArrayList<>();
        for (PreparedOperation operation : plan.operations()) {
            observedBindings.add(assetService.observe(operation.request()).binding().getId());
            reportedKeys.add(operation.request().sourceKey());
        }
        int removed = 0;
        if (!plan.operations().isEmpty()) {
            HttpApiServiceScope scope = plan.operations().get(0).request().scope();
            removed = removeAbsentBindings(scope, reportedKeys);
        } else if (plan.scope() != null) {
            removed = removeAbsentBindings(plan.scope(), reportedKeys);
        }
        recordInventory(plan, observedBindings);
        return new HttpApiSyncSummary(true, plan.operations().size(), plan.operations().size(), removed);
    }

    private void recordInventory(Plan plan, List<Long> bindingIds) {
        if (inventoryState == null) return;
        inventoryState.record(plan.scope(), HttpApiSourceKind.STARTER_MVC, plan.supported(), plan.supported(),
                plan.supported() ? null : "本次 Starter 同步未包含 HTTP API 清单，旧来源尚未重新确认", bindingIds);
    }

    private int removeAbsentBindings(HttpApiServiceScope scope, Set<String> reportedKeys) {
        int removed = 0;
        List<HttpApiSourceBindingEntity> active = bindingMapper.selectList(
                Wrappers.<HttpApiSourceBindingEntity>lambdaQuery()
                        .eq(HttpApiSourceBindingEntity::getProjectId, scope.projectId())
                        .eq(HttpApiSourceBindingEntity::getProjectCode, scope.projectCode())
                        .eq(HttpApiSourceBindingEntity::getEnvironment, scope.environment())
                        .eq(HttpApiSourceBindingEntity::getSourceKind, HttpApiSourceKind.STARTER_MVC.name())
                        .ne(HttpApiSourceBindingEntity::getStatus, HttpApiSourceBindingStatus.REMOVED.name())
                        .orderByAsc(HttpApiSourceBindingEntity::getId));
        for (HttpApiSourceBindingEntity binding : active) {
            if (!reportedKeys.contains(binding.getSourceKey())) {
                HttpApiAssetService.Removal removal = assetService.remove(new HttpApiAssetService.RemoveRequest(
                        scope, HttpApiSourceKind.STARTER_MVC, binding.getSourceKey()));
                if (removal.changed()) {
                    removed++;
                }
            }
        }
        return removed;
    }

    private HttpApiOperationContract contract(HttpApiRegistration registration) {
        List<HttpApiOperationContract.MappingCondition> conditions = new ArrayList<HttpApiOperationContract.MappingCondition>();
        for (HttpApiMappingConditionRegistration condition : defaultList(registration.mappingConditions())) {
            if (condition == null) {
                throw new IllegalArgumentException("HTTP API mapping condition cannot be null");
            }
            conditions.add(new HttpApiOperationContract.MappingCondition(
                    enumValue(HttpApiMappingConditionKind.class, condition.kind(), "mapping condition kind"),
                    condition.name(), enumValue(HttpApiMappingConditionOperator.class, condition.operator(),
                    "mapping condition operator"), condition.value()));
        }
        List<HttpApiOperationContract.Parameter> parameters = new ArrayList<HttpApiOperationContract.Parameter>();
        for (HttpApiParameterRegistration parameter : defaultList(registration.parameters())) {
            if (parameter == null) {
                throw new IllegalArgumentException("HTTP API parameter cannot be null");
            }
            HttpApiParameterLocation location = enumValue(HttpApiParameterLocation.class, parameter.location(),
                    "parameter location");
            if (location == HttpApiParameterLocation.BODY) {
                throw new IllegalArgumentException("HTTP API BODY must use requestBody instead of parameters");
            }
            parameters.add(new HttpApiOperationContract.Parameter(parameter.name(), location,
                    Boolean.TRUE.equals(parameter.required()), parameter.schema(), parameter.contentTypes()));
        }
        HttpApiOperationContract.RequestBody body = null;
        if (registration.requestBody() != null) {
            HttpApiRequestBodyRegistration source = registration.requestBody();
            if (!"BODY".equals(normalized(source.location()))) {
                throw new IllegalArgumentException("HTTP API requestBody location must be BODY");
            }
            body = new HttpApiOperationContract.RequestBody(Boolean.TRUE.equals(source.required()), source.schema(),
                    source.contentTypes());
        }
        List<HttpApiOperationContract.Response> responses = new ArrayList<HttpApiOperationContract.Response>();
        for (HttpApiResponseRegistration response : defaultList(registration.responses())) {
            if (response == null) {
                throw new IllegalArgumentException("HTTP API response cannot be null");
            }
            responses.add(new HttpApiOperationContract.Response(response.status(), response.schema(), response.contentTypes()));
        }
        String authenticationState = normalizedOrDefault(registration.authenticationState(), "UNKNOWN");
        boolean required = "REQUIRED".equals(authenticationState);
        return new HttpApiOperationContract(registration.httpMethod(), registration.contextPath(), registration.endpointPath(),
                new HttpApiOperationContract.MappingConditions(registration.consumes(), registration.produces(), conditions),
                parameters, body, responses,
                new HttpApiOperationContract.AuthenticationRequirement(required, authenticationState,
                        registration.authenticationSchemes(), registration.requiredHeaderNames()),
                registration.sideEffect());
    }

    /** Stores canonical, sample-free facts rather than the raw external JSON in a Registry snapshot. */
    private HttpApiRegistration safeSnapshotRegistration(HttpApiRegistration original,
                                                          HttpApiContractCanonicalizer.CanonicalHttpApiContract canonical) {
        try {
            JsonNode root = objectMapper.readTree(canonical.contractJson());
            JsonNode identity = root.path("identity");
            JsonNode mapping = identity.path("mappingConditions");
            return new HttpApiRegistration(original.sourceKey(), original.sourceLocation(), identity.path("method").asText(),
                    null, identity.path("routeTemplate").asText(),
                    list(mapping.path("consumes"), new TypeReference<List<String>>() { }),
                    list(mapping.path("produces"), new TypeReference<List<String>>() { }),
                    list(mapping.path("conditions"), new TypeReference<List<HttpApiMappingConditionRegistration>>() { }),
                    list(root.path("parameters"), new TypeReference<List<HttpApiParameterRegistration>>() { }),
                    snapshotRequestBody(root.get("requestBody")),
                    list(root.path("responses"), new TypeReference<List<HttpApiResponseRegistration>>() { }),
                    root.path("authentication").path("state").asText(),
                    list(root.path("authentication").path("schemes"), new TypeReference<List<String>>() { }),
                    list(root.path("authentication").path("requiredHeaderNames"), new TypeReference<List<String>>() { }),
                    root.path("sideEffect").asText());
        } catch (Exception failure) {
            throw new IllegalArgumentException("HTTP API contract cannot be persisted as a safe snapshot", failure);
        }
    }

    /** The canonical contract deliberately omits the wire-only BODY discriminator; restore it in the safe receipt. */
    private HttpApiRequestBodyRegistration snapshotRequestBody(JsonNode body) {
        if (body == null || body.isNull()) {
            return null;
        }
        return new HttpApiRequestBodyRegistration("BODY", body.path("required").asBoolean(false),
                body.get("schema"), list(body.path("contentTypes"), new TypeReference<List<String>>() { }));
    }

    private <T> List<T> list(JsonNode node, TypeReference<List<T>> type) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        return objectMapper.convertValue(node, type);
    }

    private static <T> List<T> defaultList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, String raw, String field) {
        try {
            return Enum.valueOf(type, normalized(raw));
        } catch (Exception invalid) {
            throw new IllegalArgumentException("HTTP API " + field + " is invalid", invalid);
        }
    }

    private static String normalizedOrDefault(String raw, String fallback) {
        return StringUtils.hasText(raw) ? raw.trim().toUpperCase(Locale.ROOT) : fallback;
    }

    private static String normalized(String raw) {
        if (!StringUtils.hasText(raw)) {
            throw new IllegalArgumentException("HTTP API field is required");
        }
        return raw.trim().toUpperCase(Locale.ROOT);
    }

    private static String defaultString(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    public record Plan(boolean supported, HttpApiServiceScope scope, List<PreparedOperation> operations) {
        static Plan legacy(HttpApiServiceScope scope) {
            return new Plan(false, scope, List.of());
        }

        public List<HttpApiRegistration> snapshotRegistrations() {
            return operations.stream().map(PreparedOperation::snapshotRegistration).toList();
        }
    }

    public record PreparedOperation(HttpApiAssetService.ObserveRequest request,
                                    HttpApiRegistration snapshotRegistration) {
    }
}
