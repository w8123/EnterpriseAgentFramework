package com.enterprise.ai.agent.platform.control.identity;

import com.enterprise.ai.agent.registry.RegistryCredentialEntity;
import com.enterprise.ai.agent.registry.RegistryCredentialMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmbedCorsFilterTest {

    @Test
    void allowsOnlyRegisteredEmbedOriginsOnPreflight() throws Exception {
        RegistryCredentialMapper mapper = mock(RegistryCredentialMapper.class);
        RegistryCredentialEntity credential = new RegistryCredentialEntity();
        credential.setStatus("ACTIVE");
        credential.setAllowedOriginsJson("[\"https://*.corp.example.com\"]");
        when(mapper.selectList(any())).thenReturn(List.of(credential));
        EmbedCorsFilter filter = new EmbedCorsFilter(mapper, new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/embed/chat/sessions");
        request.addHeader("Origin", "https://orders.corp.example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(204, response.getStatus());
        assertEquals("https://orders.corp.example.com", response.getHeader("Access-Control-Allow-Origin"));
        assertEquals("Authorization,Content-Type,Accept,X-ReachAI-App-Key,X-ReachAI-Timestamp,X-ReachAI-Nonce,X-ReachAI-Signature,X-EAF-App-Key,X-EAF-Timestamp,X-EAF-Nonce,X-EAF-Signature",
                response.getHeader("Access-Control-Allow-Headers"));
    }

    @Test
    void rejectsWildcardStarPolicyByNotAddingCorsHeaders() throws Exception {
        RegistryCredentialMapper mapper = mock(RegistryCredentialMapper.class);
        RegistryCredentialEntity credential = new RegistryCredentialEntity();
        credential.setStatus("ACTIVE");
        credential.setAllowedOriginsJson("[\"*\"]");
        when(mapper.selectList(any())).thenReturn(List.of(credential));
        EmbedCorsFilter filter = new EmbedCorsFilter(mapper, new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/embed/chat/sessions");
        request.addHeader("Origin", "https://evil.example.com");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(204, response.getStatus());
        assertNull(response.getHeader("Access-Control-Allow-Origin"));
    }
}
