package com.enterprise.ai.agent.identity;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
@RequiredArgsConstructor
public class EmbedTokenRevocationService {

    private final EmbedTokenRevocationMapper mapper;

    public void revoke(String jti, Instant expiresAt, String reason) {
        if (!StringUtils.hasText(jti)) {
            throw new IllegalArgumentException("jti is required");
        }
        boolean exists = mapper.selectCount(Wrappers.<EmbedTokenRevocationEntity>lambdaQuery()
                .eq(EmbedTokenRevocationEntity::getJti, jti)) > 0;
        if (exists) {
            return;
        }
        EmbedTokenRevocationEntity entity = new EmbedTokenRevocationEntity();
        entity.setJti(jti);
        entity.setReason(StringUtils.hasText(reason) ? reason : "REVOKED");
        entity.setExpiresAt(expiresAt == null ? null : LocalDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
        entity.setRevokedAt(LocalDateTime.now());
        mapper.insert(entity);
    }

    public boolean isRevoked(String jti) {
        if (!StringUtils.hasText(jti)) {
            return false;
        }
        return mapper.selectCount(Wrappers.<EmbedTokenRevocationEntity>lambdaQuery()
                .eq(EmbedTokenRevocationEntity::getJti, jti)
                .and(q -> q.isNull(EmbedTokenRevocationEntity::getExpiresAt)
                        .or()
                        .gt(EmbedTokenRevocationEntity::getExpiresAt, LocalDateTime.now()))) > 0;
    }
}
