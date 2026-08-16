package com.enterprise.ai.reach.spring;

import com.enterprise.ai.reach.sdk.annotation.ReachCapability;
import com.enterprise.ai.reach.sdk.annotation.ReachParam;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReachCapabilityInvokerTest {

    @Test
    void invokesAnnotatedBeanMethodByCapabilityName() {
        ReachCapabilityInvoker invoker = new ReachCapabilityInvoker(new Object[]{new ContractCapability()});
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("contractNo", "HT-001");

        Object result = invoker.invoke("contract.query", arguments);

        assertEquals("contract:HT-001", result);
    }

    @Test
    void bindsFlatToolInputToSingleDtoWithAnnotatedFields() {
        ReachCapabilityInvoker invoker = new ReachCapabilityInvoker(new Object[]{new MemoryCapability()});
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("resourceType", "team");
        arguments.put("resourceId", "TI-001");

        Object result = invoker.invoke("team.memory.resolve", arguments);

        assertEquals("team:TI-001", result);
    }

    @Test
    void stillAcceptsExplicitlyWrappedDtoInput() {
        ReachCapabilityInvoker invoker = new ReachCapabilityInvoker(new Object[]{new MemoryCapability()});
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("resourceType", "team");
        request.put("resourceId", "TI-002");
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("request", request);

        Object result = invoker.invoke("team.memory.resolve", arguments);

        assertEquals("team:TI-002", result);
    }

    static class ContractCapability {
        @ReachCapability(name = "contract.query")
        public String query(@ReachParam(name = "contractNo", required = true) String contractNo) {
            return "contract:" + contractNo;
        }
    }

    static class MemoryCapability {
        @ReachCapability(name = "team.memory.resolve")
        public String resolve(@ReachParam(name = "request", required = true) MemoryRequest request) {
            return request.getResourceType() + ":" + request.getResourceId();
        }
    }

    static class MemoryRequest {
        @ReachParam(name = "resourceType", required = true)
        private String resourceType;

        @ReachParam(name = "resourceId", required = true)
        private String resourceId;

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
    }
}
