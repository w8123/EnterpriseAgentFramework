package com.enterprise.ai.capability.catalog.scan;

import com.enterprise.ai.agent.capability.catalog.scan.ScanProjectEntity;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiMappingConditionKind;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiMappingConditionOperator;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiOperationContract;
import com.enterprise.ai.capability.catalog.httpapi.HttpApiParameterLocation;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Decodes the shared Controller/OpenAPI scanner wire without assigning asset identity. */
@Component
public class ScannerHttpApiContractAdapter {
    public HttpApiOperationContract contract(ScanProjectEntity project, CapabilityScannerClient.HttpApiData item) {
        List<HttpApiOperationContract.MappingCondition> conditions = new ArrayList<>();
        for (CapabilityScannerClient.HttpApiMappingConditionData condition : defaultList(item.mappingConditions())) {
            if (condition == null) {
                throw new IllegalArgumentException("HTTP API mapping condition cannot be null");
            }
            conditions.add(new HttpApiOperationContract.MappingCondition(
                    enumValue(HttpApiMappingConditionKind.class, condition.kind(), "mapping condition kind"),
                    condition.name(), enumValue(HttpApiMappingConditionOperator.class, condition.operator(),
                    "mapping condition operator"), condition.value()));
        }
        List<HttpApiOperationContract.Parameter> parameters = new ArrayList<>();
        for (CapabilityScannerClient.HttpApiParameterData parameter : defaultList(item.parameters())) {
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
        HttpApiOperationContract.RequestBody requestBody = null;
        if (item.requestBody() != null) {
            CapabilityScannerClient.HttpApiRequestBodyData body = item.requestBody();
            if (!"BODY".equals(normalized(body.location()))) {
                throw new IllegalArgumentException("HTTP API requestBody location must be BODY");
            }
            requestBody = new HttpApiOperationContract.RequestBody(Boolean.TRUE.equals(body.required()), body.schema(),
                    body.contentTypes());
        }
        List<HttpApiOperationContract.Response> responses = new ArrayList<>();
        for (CapabilityScannerClient.HttpApiResponseData response : defaultList(item.responses())) {
            if (response == null) {
                throw new IllegalArgumentException("HTTP API response cannot be null");
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
            throw new IllegalArgumentException("HTTP API " + field + " is invalid", invalid);
        }
    }

    private static String normalized(String raw) {
        if (!StringUtils.hasText(raw)) {
            throw new IllegalArgumentException("HTTP API field is required");
        }
        return raw.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizedOrDefault(String raw, String fallback) {
        return StringUtils.hasText(raw) ? raw.trim().toUpperCase(Locale.ROOT) : fallback;
    }

    private static String normalizeContextPath(String raw) {
        if (!StringUtils.hasText(raw) || "/".equals(raw.trim())) {
            return "";
        }
        String value = raw.trim();
        return value.startsWith("/") ? value : "/" + value;
    }

}
