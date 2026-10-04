package com.enterprise.ai.reach.sdk.capability;

import com.enterprise.ai.reach.sdk.annotation.ReachSideEffectLevel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Scanned-capability result holder. Collection setters store a defensive
 * snapshot of the incoming list, and getters return unmodifiable views, so
 * later mutation of the caller's list cannot change this descriptor.
 */
public class ReachCapabilityDescriptor {

    private String name;
    private String title;
    private String description;
    private String domain;
    private String module;
    private List<String> tags = new ArrayList<String>();
    private ReachSideEffectLevel sideEffect;
    private ReachCapabilityAssetType assetType;
    private List<String> requiredRoles = new ArrayList<String>();
    private int timeoutMs;
    private int retryLimit;
    private String className;
    private String methodName;
    private String httpMethod;
    private String endpointPath;
    private String requestBodyType;
    private String returnType;
    private List<ReachCapabilityParameter> parameters = new ArrayList<ReachCapabilityParameter>();

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public String getModule() {
        return module;
    }

    public void setModule(String module) {
        this.module = module;
    }

    public List<String> getTags() {
        return Collections.unmodifiableList(tags);
    }

    public void setTags(List<String> tags) {
        this.tags = tags == null ? new ArrayList<String>() : new ArrayList<String>(tags);
    }

    public ReachSideEffectLevel getSideEffect() {
        return sideEffect;
    }

    public void setSideEffect(ReachSideEffectLevel sideEffect) {
        this.sideEffect = sideEffect;
    }

    public ReachCapabilityAssetType getAssetType() {
        return assetType;
    }

    public void setAssetType(ReachCapabilityAssetType assetType) {
        this.assetType = assetType;
    }

    public List<String> getRequiredRoles() {
        return Collections.unmodifiableList(requiredRoles);
    }

    public void setRequiredRoles(List<String> requiredRoles) {
        this.requiredRoles = requiredRoles == null ? new ArrayList<String>() : new ArrayList<String>(requiredRoles);
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public int getRetryLimit() {
        return retryLimit;
    }

    public void setRetryLimit(int retryLimit) {
        this.retryLimit = retryLimit;
    }

    public String getClassName() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public String getMethodName() {
        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public void setHttpMethod(String httpMethod) {
        this.httpMethod = httpMethod;
    }

    public String getEndpointPath() {
        return endpointPath;
    }

    public void setEndpointPath(String endpointPath) {
        this.endpointPath = endpointPath;
    }

    public String getRequestBodyType() {
        return requestBodyType;
    }

    public void setRequestBodyType(String requestBodyType) {
        this.requestBodyType = requestBodyType;
    }

    public String getReturnType() {
        return returnType;
    }

    public void setReturnType(String returnType) {
        this.returnType = returnType;
    }

    public List<ReachCapabilityParameter> getParameters() {
        return Collections.unmodifiableList(parameters);
    }

    public void setParameters(List<ReachCapabilityParameter> parameters) {
        this.parameters = parameters == null
                ? new ArrayList<ReachCapabilityParameter>()
                : new ArrayList<ReachCapabilityParameter>(parameters);
    }
}
