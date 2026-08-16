package com.enterprise.ai.reach.sdk.memory;

import com.enterprise.ai.reach.sdk.annotation.ReachParam;

/** JDK8-compatible input contract for a business-memory resolver Capability. */
public class ReachBusinessMemoryResolverRequest {

    @ReachParam(
            name = "resourceType",
            description = "Stable business resource type emitted by the Knowledge index",
            required = true)
    private String resourceType;

    @ReachParam(
            name = "resourceId",
            description = "Stable business resource id; authorize it for the current signed user",
            required = true,
            sensitive = true)
    private String resourceId;

    @ReachParam(
            name = "expectedSourceVersion",
            description = "Non-authoritative index version hint; always return the current source version",
            required = true)
    private String expectedSourceVersion;

    public ReachBusinessMemoryResolverRequest() {
    }

    public ReachBusinessMemoryResolverRequest(String resourceType,
                                              String resourceId,
                                              String expectedSourceVersion) {
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.expectedSourceVersion = expectedSourceVersion;
    }

    public String getResourceType() {
        return resourceType;
    }

    public void setResourceType(String resourceType) {
        this.resourceType = resourceType;
    }

    public String getResourceId() {
        return resourceId;
    }

    public void setResourceId(String resourceId) {
        this.resourceId = resourceId;
    }

    public String getExpectedSourceVersion() {
        return expectedSourceVersion;
    }

    public void setExpectedSourceVersion(String expectedSourceVersion) {
        this.expectedSourceVersion = expectedSourceVersion;
    }
}
