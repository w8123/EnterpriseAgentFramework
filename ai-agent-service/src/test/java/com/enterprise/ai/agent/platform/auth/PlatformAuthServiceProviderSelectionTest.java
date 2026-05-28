package com.enterprise.ai.agent.platform.auth;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlatformAuthServiceProviderSelectionTest {

    @Test
    void loginUsesProviderRequestedByClientInsteadOfOnlyGlobalDefault() {
        PlatformAuthProvider local = provider("LOCAL", null);
        PlatformAuthProvider oidc = provider("OIDC",
                new PlatformUserProfile("OIDC", "iam-1", "zhangsan", "Zhang San", "z@example.com", null, Set.of()));
        PlatformUserMapper userMapper = mock(PlatformUserMapper.class);
        when(userMapper.insert(any())).thenAnswer(invocation -> {
            PlatformUserEntity user = invocation.getArgument(0);
            user.setId(12L);
            return 1;
        });
        PlatformAuthService service = service(
                List.of(local, oidc),
                userMapper,
                mock(PlatformAuthProviderConfigService.class));

        PlatformLoginResult result = service.login(PlatformLoginRequest.builder()
                .providerType("OIDC")
                .idToken("token")
                .build());

        assertEquals("zhangsan", result.principal().username());
    }

    @Test
    void loginUsesActiveProviderCodeConfigAsRuntimeProviderConfig() {
        PlatformAuthProvider oidc = new PlatformAuthProvider() {
            @Override
            public String providerType() {
                return "OIDC";
            }

            @Override
            public PlatformUserProfile authenticate(PlatformLoginRequest request) {
                assertEquals("CORP_OIDC", request.getProviderCode());
                assertEquals("https://iam.example.com", request.getProviderConfig().get("issuerUri"));
                assertEquals("upn", request.getProviderConfig().get("usernameClaim"));
                return new PlatformUserProfile("OIDC", "iam-1", "zhangsan", "Zhang San", null, null, Set.of());
            }
        };
        PlatformAuthProviderConfigMapper providerMapper = mock(PlatformAuthProviderConfigMapper.class);
        PlatformAuthProviderConfigEntity providerConfig = new PlatformAuthProviderConfigEntity();
        providerConfig.setProviderCode("CORP_OIDC");
        providerConfig.setProviderType("OIDC");
        providerConfig.setStatus("ACTIVE");
        providerConfig.setConfigJson("{\"issuerUri\":\"https://iam.example.com\",\"usernameClaim\":\"upn\"}");
        when(providerMapper.selectOne(any())).thenReturn(providerConfig);
        PlatformUserMapper userMapper = mock(PlatformUserMapper.class);
        when(userMapper.insert(any())).thenAnswer(invocation -> {
            PlatformUserEntity user = invocation.getArgument(0);
            user.setId(13L);
            return 1;
        });
        PlatformAuthService service = service(List.of(oidc), userMapper, new PlatformAuthProviderConfigService(providerMapper));

        PlatformLoginResult result = service.login(PlatformLoginRequest.builder()
                .providerCode("CORP_OIDC")
                .idToken("token")
                .build());

        assertEquals("zhangsan", result.principal().username());
    }

    private PlatformAuthProvider provider(String type, PlatformUserProfile profile) {
        return new PlatformAuthProvider() {
            @Override
            public String providerType() {
                return type;
            }

            @Override
            public PlatformUserProfile authenticate(PlatformLoginRequest request) {
                return Optional.ofNullable(profile)
                        .orElseThrow(() -> new IllegalArgumentException(type + " should not be used"));
            }
        };
    }

    private PlatformAuthService service(List<PlatformAuthProvider> providers,
                                        PlatformUserMapper userMapper,
                                        PlatformAuthProviderConfigService providerConfigService) {
        return new PlatformAuthService(
                new PlatformAuthProperties(),
                mock(PlatformPasswordHasher.class),
                providers,
                userMapper,
                mock(PlatformRoleMapper.class),
                mock(PlatformUserRoleMapper.class),
                mock(PlatformPermissionMapper.class),
                mock(PlatformRolePermissionMapper.class),
                mock(PlatformLoginSessionMapper.class),
                providerConfigService);
    }
}
