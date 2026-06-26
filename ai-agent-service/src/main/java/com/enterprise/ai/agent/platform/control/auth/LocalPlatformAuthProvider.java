package com.enterprise.ai.agent.platform.control.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@RequiredArgsConstructor
public class LocalPlatformAuthProvider implements PlatformAuthProvider {

    private final PlatformAuthProperties properties;
    private final PlatformPasswordHasher passwordHasher;
    private final PlatformUserMapper userMapper;

    @Override
    public String providerType() {
        return "LOCAL";
    }

    @Override
    public PlatformUserProfile authenticate(PlatformLoginRequest request) {
        if (!properties.getLocal().isEnabled()) {
            throw new IllegalArgumentException("LOCAL provider is disabled");
        }
        PlatformUserEntity user = userMapper.selectOne(Wrappers.<PlatformUserEntity>lambdaQuery()
                .eq(PlatformUserEntity::getUsername, request.getUsername())
                .eq(PlatformUserEntity::getSourceProvider, "LOCAL")
                .last("LIMIT 1"));
        if (user == null || !"ACTIVE".equalsIgnoreCase(user.getStatus())
                || !passwordHasher.matches(request.getPassword(), user.getPasswordHash())) {
            throw new IllegalArgumentException("invalid username or password");
        }
        return new PlatformUserProfile(
                "LOCAL",
                user.getExternalSubject(),
                user.getUsername(),
                user.getDisplayName(),
                user.getEmail(),
                user.getMobile(),
                Set.of());
    }
}
