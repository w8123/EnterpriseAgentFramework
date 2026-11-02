package com.enterprise.ai.runtime.client.capability;

import com.enterprise.ai.common.internalauth.InternalServiceAuthHeaders;
import com.enterprise.ai.common.internalauth.InternalServiceHmac;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeCapabilityProjectLookupSigningTest {

    private static final String SECRET = "runtime-project-lookup-secret-32bytes!!";

    @Test
    void signsProjectLookupAsServiceGetWithoutIdentity() {
        Map<String, String> headers = new RuntimeCapabilityInternalAuthSigner(SECRET).signProjectLookup(7L);
        assertEquals(InternalServiceAuthHeaders.CALLER_RUNTIME, headers.get(InternalServiceAuthHeaders.CALLER));
        assertEquals(InternalServiceAuthHeaders.IDENTITY_SOURCE_RUNTIME_UNTRUSTED,
                headers.get(InternalServiceAuthHeaders.IDENTITY_SOURCE));
        assertEquals("", headers.get(InternalServiceAuthHeaders.IDENTITY_TENANT_ID));
        assertEquals("", headers.get(InternalServiceAuthHeaders.IDENTITY_USER_ID));
        String canonical = InternalServiceHmac.canonical("GET", "/internal/capability/projects/by-id/7",
                InternalServiceAuthHeaders.CALLER_RUNTIME, InternalServiceAuthHeaders.IDENTITY_SOURCE_RUNTIME_UNTRUSTED,
                "", "", headers.get(InternalServiceAuthHeaders.TIMESTAMP), headers.get(InternalServiceAuthHeaders.NONCE),
                InternalServiceHmac.bodySha256Hex(new byte[0]));
        assertEquals(InternalServiceHmac.sign(SECRET, canonical), headers.get(InternalServiceAuthHeaders.SIGNATURE));
    }

    @Test
    void rejectsInvalidProjectId() {
        var signer = new RuntimeCapabilityInternalAuthSigner(SECRET);
        assertThrows(IllegalArgumentException.class, () -> signer.signProjectLookup(null));
        assertThrows(IllegalArgumentException.class, () -> signer.signProjectLookup(0L));
    }
}
