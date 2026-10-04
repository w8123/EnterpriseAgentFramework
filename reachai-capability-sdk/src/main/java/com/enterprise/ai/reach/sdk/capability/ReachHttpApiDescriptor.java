package com.enterprise.ai.reach.sdk.capability;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Secret-free HTTP operation discovered from a framework adapter.
 *
 * <p>This is deliberately not a {@link ReachCapabilityDescriptor}: MVC discovery is source
 * evidence for the HTTP API asset, while {@code ReachCapabilityDescriptor} remains the declared
 * business-method contract.</p>
 */
public class ReachHttpApiDescriptor {

    private String sourceKey;
    private String sourceLocation;
    private String httpMethod;
    private String endpointPath;
    private List<String> consumes = new ArrayList<String>();
    private List<String> produces = new ArrayList<String>();
    private List<ReachHttpApiMappingCondition> mappingConditions = new ArrayList<ReachHttpApiMappingCondition>();
    private List<ReachHttpApiParameter> parameters = new ArrayList<ReachHttpApiParameter>();
    private ReachHttpApiRequestBody requestBody;
    private List<ReachHttpApiResponse> responses = new ArrayList<ReachHttpApiResponse>();
    /** UNKNOWN means the MVC scanner did not establish whether an authentication requirement exists. */
    private String authenticationState = "UNKNOWN";
    private List<String> authenticationSchemes = new ArrayList<String>();
    private List<String> requiredHeaderNames = new ArrayList<String>();
    private String sideEffect;

    public String getSourceKey() { return sourceKey; }
    public void setSourceKey(String sourceKey) { this.sourceKey = sourceKey; }
    public String getSourceLocation() { return sourceLocation; }
    public void setSourceLocation(String sourceLocation) { this.sourceLocation = sourceLocation; }
    public String getHttpMethod() { return httpMethod; }
    public void setHttpMethod(String httpMethod) { this.httpMethod = httpMethod; }
    public String getEndpointPath() { return endpointPath; }
    public void setEndpointPath(String endpointPath) { this.endpointPath = endpointPath; }
    public List<String> getConsumes() { return Collections.unmodifiableList(consumes); }
    public void setConsumes(List<String> consumes) { this.consumes = copy(consumes); }
    public List<String> getProduces() { return Collections.unmodifiableList(produces); }
    public void setProduces(List<String> produces) { this.produces = copy(produces); }
    public List<ReachHttpApiMappingCondition> getMappingConditions() { return Collections.unmodifiableList(mappingConditions); }
    public void setMappingConditions(List<ReachHttpApiMappingCondition> mappingConditions) {
        this.mappingConditions = mappingConditions == null ? new ArrayList<ReachHttpApiMappingCondition>()
                : new ArrayList<ReachHttpApiMappingCondition>(mappingConditions);
    }
    public List<ReachHttpApiParameter> getParameters() { return Collections.unmodifiableList(parameters); }
    public void setParameters(List<ReachHttpApiParameter> parameters) {
        this.parameters = parameters == null ? new ArrayList<ReachHttpApiParameter>()
                : new ArrayList<ReachHttpApiParameter>(parameters);
    }
    public ReachHttpApiRequestBody getRequestBody() { return requestBody; }
    public void setRequestBody(ReachHttpApiRequestBody requestBody) { this.requestBody = requestBody; }
    public List<ReachHttpApiResponse> getResponses() { return Collections.unmodifiableList(responses); }
    public void setResponses(List<ReachHttpApiResponse> responses) {
        this.responses = responses == null ? new ArrayList<ReachHttpApiResponse>()
                : new ArrayList<ReachHttpApiResponse>(responses);
    }
    public String getAuthenticationState() { return authenticationState; }
    public void setAuthenticationState(String authenticationState) { this.authenticationState = authenticationState; }
    public List<String> getAuthenticationSchemes() { return Collections.unmodifiableList(authenticationSchemes); }
    public void setAuthenticationSchemes(List<String> authenticationSchemes) { this.authenticationSchemes = copy(authenticationSchemes); }
    public List<String> getRequiredHeaderNames() { return Collections.unmodifiableList(requiredHeaderNames); }
    public void setRequiredHeaderNames(List<String> requiredHeaderNames) { this.requiredHeaderNames = copy(requiredHeaderNames); }
    public String getSideEffect() { return sideEffect; }
    public void setSideEffect(String sideEffect) { this.sideEffect = sideEffect; }

    private static List<String> copy(List<String> values) {
        return values == null ? new ArrayList<String>() : new ArrayList<String>(values);
    }
}
