package com.enterprise.ai.control.identity;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Tells the system check page whether the bootstrap administrator still uses the public default password. */
@Component
public class PlatformDefaultAdminPasswordCheck {

    private final PlatformAuthProperties authProperties;
    private final PlatformUserMapper userMapper;
    private final PasswordEncoder platformPasswordEncoder;

    public PlatformDefaultAdminPasswordCheck(PlatformAuthProperties authProperties,
                                             PlatformUserMapper userMapper,
                                             PasswordEncoder platformPasswordEncoder) {
        this.authProperties = authProperties;
        this.userMapper = userMapper;
        this.platformPasswordEncoder = platformPasswordEncoder;
    }

    public String bootstrapUsername() {
        return authProperties.getLocal().getBootstrapAdmin().getUsername();
    }

    public boolean usesDefaultPassword() {
        PlatformUserEntity admin = userMapper.selectOne(new LambdaQueryWrapper<PlatformUserEntity>()
                .eq(PlatformUserEntity::getUsername, bootstrapUsername())
                .last("limit 1"));
        return admin != null && "ACTIVE".equals(admin.getStatus()) && matchesDefault(admin.getPasswordHash());
    }

    private boolean matchesDefault(String passwordHash) {
        if (!StringUtils.hasText(passwordHash)) {
            return false;
        }
        if (passwordHash.startsWith("{plain}")) {
            return PlatformAuthProperties.DEVELOPMENT_ADMIN_PASSWORD.equals(passwordHash.substring("{plain}".length()));
        }
        try {
            return platformPasswordEncoder.matches(PlatformAuthProperties.DEVELOPMENT_ADMIN_PASSWORD, passwordHash);
        } catch (IllegalArgumentException unsupportedHash) {
            return false;
        }
    }
}
