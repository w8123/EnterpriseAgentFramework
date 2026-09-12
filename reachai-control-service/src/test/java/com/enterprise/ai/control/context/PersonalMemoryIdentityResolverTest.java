package com.enterprise.ai.control.context;

import com.enterprise.ai.control.identity.PlatformPrincipal;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.enterprise.ai.control.identity.PlatformAuthenticatedSession;
import com.enterprise.ai.control.identity.PlatformConsoleAuthInterceptor;
import com.enterprise.ai.control.identity.PlatformUserEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PersonalMemoryIdentityResolverTest {

    @BeforeAll
    static void initializeMyBatisMetadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "personal-memory-identity"),
                ContextRuntimeUserMappingEntity.class);
    }

    @Test
    void derivesDefaultRuntimeOwnerFromServerAttestedSession() {
        ContextRuntimeUserMappingMapper mapper = mock(ContextRuntimeUserMappingMapper.class);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        PersonalMemoryIdentityResolver resolver = new PersonalMemoryIdentityResolver(mapper, "default");
        HttpServletRequest request = requestFor(userSession(7L));

        PersonalMemoryIdentityResolver.PersonalMemoryPrincipal principal = resolver.resolve(request, null);

        assertEquals("default", principal.tenantId());
        assertEquals("7", principal.runtimeUserId());
        assertEquals(7L, principal.platformUserId());
    }

    @Test
    void usesOnlyActiveMappingForNonDefaultTenant() {
        ContextRuntimeUserMappingMapper mapper = mock(ContextRuntimeUserMappingMapper.class);
        ContextRuntimeUserMappingEntity mapping = new ContextRuntimeUserMappingEntity();
        mapping.setTenantId("tenant-a");
        mapping.setPlatformUserId(7L);
        mapping.setRuntimeUserId("business-user-42");
        mapping.setStatus("ACTIVE");
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(mapping));
        PersonalMemoryIdentityResolver resolver = new PersonalMemoryIdentityResolver(mapper, "default");

        PersonalMemoryIdentityResolver.PersonalMemoryPrincipal principal =
                resolver.resolve(requestFor(userSession(7L)), "tenant-a");

        assertEquals("tenant-a", principal.tenantId());
        assertEquals("business-user-42", principal.runtimeUserId());
    }

    @Test
    void resolvesTheSameMappedOwnerForAnAttestedAgentGatewayUser() {
        ContextRuntimeUserMappingMapper mapper = mock(ContextRuntimeUserMappingMapper.class);
        ContextRuntimeUserMappingEntity mapping = new ContextRuntimeUserMappingEntity();
        mapping.setRuntimeUserId("canonical-user-7");
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(mapping));
        PersonalMemoryIdentityResolver resolver = new PersonalMemoryIdentityResolver(mapper, "default");
        PlatformPrincipal user = userSession(7L).user();

        PersonalMemoryIdentityResolver.PersonalMemoryPrincipal principal =
                resolver.resolveAttestedPlatformPrincipal(user, null, "default");

        assertEquals("default", principal.tenantId());
        assertEquals("canonical-user-7", principal.runtimeUserId());
        assertEquals(7L, principal.platformUserId());
    }

    @Test
    void rejectsUnauthenticatedAmbiguousAndUnmappedTenantRequests() {
        ContextRuntimeUserMappingMapper mapper = mock(ContextRuntimeUserMappingMapper.class);
        PersonalMemoryIdentityResolver resolver = new PersonalMemoryIdentityResolver(mapper, "default");
        HttpServletRequest unauthenticated = mock(HttpServletRequest.class);
        assertEquals(401, assertThrows(ResponseStatusException.class,
                () -> resolver.resolve(unauthenticated, null)).getStatusCode().value());

        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        assertEquals(403, assertThrows(ResponseStatusException.class,
                () -> resolver.resolve(requestFor(userSession(7L)), "tenant-b")).getStatusCode().value());

        ContextRuntimeUserMappingEntity first = new ContextRuntimeUserMappingEntity();
        first.setRuntimeUserId("u-1");
        ContextRuntimeUserMappingEntity second = new ContextRuntimeUserMappingEntity();
        second.setRuntimeUserId("u-2");
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(first, second));
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> resolver.resolve(requestFor(userSession(7L)), "tenant-b")).getStatusCode().value());
    }

    private static HttpServletRequest requestFor(PlatformAuthenticatedSession session) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(PlatformConsoleAuthInterceptor.SESSION_REQUEST_ATTRIBUTE)).thenReturn(session);
        return request;
    }

    private static PlatformAuthenticatedSession userSession(Long id) {
        PlatformUserEntity user = new PlatformUserEntity();
        user.setId(id);
        user.setUsername("user-" + id);
        return new PlatformAuthenticatedSession(PlatformPrincipal.fromUser(user), "platform-session", LocalDateTime.now().plusHours(1),
                List.of(), List.of(), List.of());
    }
}
