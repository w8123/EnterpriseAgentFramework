package com.enterprise.ai.control.identity;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformLocalBootstrapAdminInitializerTest {

    @Test
    void createsTheBuiltInDevelopmentAdministratorByDefault() throws Exception {
        PlatformAuthProperties properties = new PlatformAuthProperties();
        PlatformUserMapper userMapper = mock(PlatformUserMapper.class);
        PlatformRoleMapper roleMapper = mock(PlatformRoleMapper.class);
        PlatformUserRoleMapper userRoleMapper = mock(PlatformUserRoleMapper.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        PlatformRoleEntity platformAdmin = new PlatformRoleEntity();
        platformAdmin.setId(9L);
        platformAdmin.setRoleCode("PLATFORM_ADMIN");
        when(roleMapper.selectOne(any())).thenReturn(platformAdmin);
        when(passwordEncoder.encode("admin123")).thenReturn("$2a$12$development-hash");
        doAnswer(invocation -> {
            PlatformUserEntity user = invocation.getArgument(0);
            user.setId(17L);
            return 1;
        }).when(userMapper).insert(any(PlatformUserEntity.class));

        new PlatformLocalBootstrapAdminInitializer(
                properties, userMapper, roleMapper, userRoleMapper, passwordEncoder).run(arguments());

        verify(userMapper).insert(any(PlatformUserEntity.class));
        verify(userRoleMapper).insert(any(PlatformUserRoleEntity.class));
        verify(passwordEncoder).encode("admin123");
    }

    @Test
    void doesNothingUnlessExplicitlyEnabled() throws Exception {
        PlatformUserMapper userMapper = mock(PlatformUserMapper.class);
        initializer(properties(false, false, null, null), userMapper).run(arguments());

        verify(userMapper, never()).selectOne(any());
    }

    @Test
    void failsClosedForAnIncompleteBootstrapConfiguration() {
        PlatformAuthProperties properties = properties(true, true, "admin", "short");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> initializer(properties, mock(PlatformUserMapper.class)).run(arguments()));

        assertTrue(error.getMessage().contains("at least 12 characters"));
    }

    @Test
    void createsOneExplicitLocalAdministratorWithABcryptPassword() throws Exception {
        PlatformAuthProperties properties = properties(true, true, "bootstrap-admin", "safe-password-12");
        PlatformUserMapper userMapper = mock(PlatformUserMapper.class);
        PlatformRoleMapper roleMapper = mock(PlatformRoleMapper.class);
        PlatformUserRoleMapper userRoleMapper = mock(PlatformUserRoleMapper.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        PlatformRoleEntity platformAdmin = new PlatformRoleEntity();
        platformAdmin.setId(9L);
        platformAdmin.setRoleCode("PLATFORM_ADMIN");
        when(roleMapper.selectOne(any())).thenReturn(platformAdmin);
        when(passwordEncoder.encode("safe-password-12")).thenReturn("$2a$12$bootstrap-hash");
        doAnswer(invocation -> {
            PlatformUserEntity user = invocation.getArgument(0);
            user.setId(17L);
            return 1;
        }).when(userMapper).insert(any(PlatformUserEntity.class));

        new PlatformLocalBootstrapAdminInitializer(
                properties, userMapper, roleMapper, userRoleMapper, passwordEncoder).run(arguments());

        verify(userMapper).insert(any(PlatformUserEntity.class));
        verify(userRoleMapper).insert(any(PlatformUserRoleEntity.class));
        verify(passwordEncoder).encode("safe-password-12");
    }

    @Test
    void doesNotOverwriteAnythingWhenAnActiveGlobalAdministratorAlreadyExists() throws Exception {
        PlatformAuthProperties properties = properties(true, true, "bootstrap-admin", "safe-password-12");
        PlatformUserMapper userMapper = mock(PlatformUserMapper.class);
        PlatformUserRoleMapper userRoleMapper = mock(PlatformUserRoleMapper.class);
        when(userRoleMapper.selectActiveGlobalPlatformAdministratorGrantsForUpdate())
                .thenReturn(java.util.List.of(new PlatformUserRoleEntity()));

        new PlatformLocalBootstrapAdminInitializer(
                properties,
                userMapper,
                mock(PlatformRoleMapper.class),
                userRoleMapper,
                mock(PasswordEncoder.class)).run(arguments());

        verify(userMapper, never()).selectOne(any());
        verify(userMapper, never()).insert(any());
    }

    @Test
    void failsWhenBootstrapUsernameAlreadyExistsWithoutAnyGlobalAdministrator() {
        PlatformAuthProperties properties = properties(true, true, "bootstrap-admin", "safe-password-12");
        PlatformUserMapper userMapper = mock(PlatformUserMapper.class);
        PlatformUserEntity existing = new PlatformUserEntity();
        existing.setUsername("bootstrap-admin");
        when(userMapper.selectOne(any())).thenReturn(existing);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> initializer(properties, userMapper).run(arguments()));

        assertTrue(error.getMessage().contains("username already exists"));
    }

    private PlatformLocalBootstrapAdminInitializer initializer(PlatformAuthProperties properties,
                                                                PlatformUserMapper userMapper) {
        return new PlatformLocalBootstrapAdminInitializer(
                properties,
                userMapper,
                mock(PlatformRoleMapper.class),
                mock(PlatformUserRoleMapper.class),
                mock(PasswordEncoder.class));
    }

    private PlatformAuthProperties properties(boolean localEnabled,
                                              boolean bootstrapEnabled,
                                              String username,
                                              String password) {
        PlatformAuthProperties properties = new PlatformAuthProperties();
        properties.setProvider("LOCAL");
        properties.getLocal().setEnabled(localEnabled);
        properties.getLocal().getBootstrapAdmin().setEnabled(bootstrapEnabled);
        properties.getLocal().getBootstrapAdmin().setUsername(username);
        properties.getLocal().getBootstrapAdmin().setPassword(password);
        return properties;
    }

    private DefaultApplicationArguments arguments() {
        return new DefaultApplicationArguments(new String[0]);
    }
}
