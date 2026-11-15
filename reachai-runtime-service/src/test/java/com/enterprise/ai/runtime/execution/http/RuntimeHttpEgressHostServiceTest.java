package com.enterprise.ai.runtime.execution.http;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeHttpEgressHostServiceTest {

    @Test
    void reducesWhatAnAdminPastesToAHostAndRefusesMetadataAddresses() {
        assertEquals("erp.corp.local", RuntimeHttpEgressHostService.normalize(" http://ERP.corp.local:8080/api/login "));
        assertEquals("192.168.1.20", RuntimeHttpEgressHostService.normalize("192.168.1.20:8443"));
        assertEquals("localhost", RuntimeHttpEgressHostService.normalize("localhost"));
        assertThrows(IllegalArgumentException.class, () -> RuntimeHttpEgressHostService.normalize("169.254.169.254"));
        assertThrows(IllegalArgumentException.class, () -> RuntimeHttpEgressHostService.normalize("metadata.google.internal"));
        assertThrows(IllegalArgumentException.class, () -> RuntimeHttpEgressHostService.normalize("http://user:pw@erp"));
        assertThrows(IllegalArgumentException.class, () -> RuntimeHttpEgressHostService.normalize("*.corp.local"));
        assertThrows(IllegalArgumentException.class, () -> RuntimeHttpEgressHostService.normalize(""));
    }

    @Test
    void addedHostAppliesAtOnceAndIsStoredNormalized() {
        RuntimeHttpEgressHostMapper mapper = mock(RuntimeHttpEgressHostMapper.class);
        RuntimeHttpEgressHostService service = new RuntimeHttpEgressHostService(mapper);
        assertEquals(Set.of(), service.hosts());
        RuntimeHttpEgressHostEntity row = new RuntimeHttpEgressHostEntity();
        row.setHost("erp.corp.local");
        when(mapper.selectList(any())).thenReturn(List.of(row));

        assertEquals("erp.corp.local", service.add("https://ERP.corp.local/", "ERP", "platform:1:admin").getHost());

        verify(mapper).insert(any(RuntimeHttpEgressHostEntity.class));
        assertEquals(Set.of("erp.corp.local"), service.hosts());
    }

    @Test
    void consoleAllowedHostMayBePrivateOrLoopbackButNeverMetadata() throws Exception {
        WorkflowDnsResolver resolver = host -> new InetAddress[]{InetAddress.getByName(switch (host) {
            case "erp.corp.local" -> "192.168.1.20";
            case "same-box" -> "127.0.0.1";
            case "sneaky" -> "169.254.169.254";
            default -> "10.0.0.5";
        })};
        WorkflowHttpEgressPolicy policy = new WorkflowHttpEgressPolicy(false, "", 3, resolver);
        assertEquals(WorkflowHttpEgressPolicy.ERROR_DNS, assertThrows(WorkflowHttpEgressPolicy.WorkflowHttpEgressException.class,
                () -> policy.validateUri("http://erp.corp.local/api")).code());

        policy.setConsoleAllowlist(() -> Set.of("erp.corp.local", "same-box", "sneaky"));

        assertEquals("192.168.1.20", policy.validateUri("http://erp.corp.local/api").primaryAddress().getHostAddress());
        assertEquals("127.0.0.1", policy.validateUri("http://same-box:8080/api").primaryAddress().getHostAddress());
        assertThrows(WorkflowHttpEgressPolicy.WorkflowHttpEgressException.class, () -> policy.validateUri("http://sneaky/latest"));
        assertThrows(WorkflowHttpEgressPolicy.WorkflowHttpEgressException.class, () -> policy.validateUri("http://other/api"));
    }
}
