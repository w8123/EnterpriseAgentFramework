package com.enterprise.ai.control.identity;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Creates exactly one LOCAL development administrator from explicit runtime
 * configuration. Production deployment templates disable LOCAL login and
 * bootstrap explicitly.
 */
@Component
@RequiredArgsConstructor
public class PlatformLocalBootstrapAdminInitializer implements ApplicationRunner {

    private final PlatformAuthProperties authProperties;
    private final PlatformUserMapper userMapper;
    private final PlatformRoleMapper roleMapper;
    private final PlatformUserRoleMapper userRoleMapper;
    private final PasswordEncoder platformPasswordEncoder;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        PlatformAuthProperties.BootstrapAdmin bootstrap = authProperties.getLocal().getBootstrapAdmin();
        if (bootstrap == null || !bootstrap.isEnabled()) {
            return;
        }
        if (!authProperties.localPasswordLoginEnabled()) {
            throw new IllegalStateException("LOCAL bootstrap admin requires reachai.auth.provider=LOCAL and local.enabled=true");
        }
        String username = requireBootstrapValue(bootstrap.getUsername(), "username");
        String password = requireBootstrapValue(bootstrap.getPassword(), "password");
        if (password.length() < 12 && !authProperties.usesBuiltInDevelopmentAdmin(username, password)) {
            throw new IllegalStateException("LOCAL bootstrap admin password must contain at least 12 characters");
        }
        if (!userRoleMapper.selectActiveGlobalPlatformAdministratorGrantsForUpdate().isEmpty()) {
            return;
        }
        PlatformUserEntity existing = userMapper.selectOne(new LambdaQueryWrapper<PlatformUserEntity>()
                .eq(PlatformUserEntity::getUsername, username)
                .last("limit 1"));
        if (existing != null) {
            throw new IllegalStateException(
                    "LOCAL bootstrap admin username already exists but no ACTIVE global PLATFORM_ADMIN exists: " + username);
        }
        PlatformRoleEntity adminRole = roleMapper.selectOne(new LambdaQueryWrapper<PlatformRoleEntity>()
                .eq(PlatformRoleEntity::getRoleCode, "PLATFORM_ADMIN")
                .last("limit 1"));
        if (adminRole == null || adminRole.getId() == null) {
            throw new IllegalStateException("PLATFORM_ADMIN role must exist before bootstrapping a LOCAL administrator");
        }

        LocalDateTime now = LocalDateTime.now();
        PlatformUserEntity user = new PlatformUserEntity();
        user.setUsername(username);
        user.setDisplayName(username);
        user.setStatus("ACTIVE");
        user.setSourceProvider("LOCAL");
        user.setExternalSubject("local:" + username);
        user.setPasswordHash(platformPasswordEncoder.encode(password));
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userMapper.insert(user);

        PlatformUserRoleEntity grant = new PlatformUserRoleEntity();
        grant.setUserId(user.getId());
        grant.setRoleId(adminRole.getId());
        grant.setScopeType("GLOBAL");
        grant.setScopeValue("*");
        grant.setCreatedAt(now);
        userRoleMapper.insert(grant);
    }

    private static String requireBootstrapValue(String value, String name) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException("LOCAL bootstrap admin " + name + " is required");
        }
        return value.trim();
    }
}
