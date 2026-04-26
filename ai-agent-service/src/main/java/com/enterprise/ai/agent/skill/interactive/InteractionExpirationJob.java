package com.enterprise.ai.agent.skill.interactive;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 将超时未完成的 skill_interaction 标记为 EXPIRED。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InteractionExpirationJob {

    private final SkillInteractionMapper skillInteractionMapper;

    @Scheduled(cron = "0 */5 * * * *")
    public void expireStale() {
        LocalDateTime now = LocalDateTime.now();
        var uw = new LambdaUpdateWrapper<SkillInteractionEntity>()
                .eq(SkillInteractionEntity::getStatus, SkillInteractionStatus.PENDING)
                .lt(SkillInteractionEntity::getExpiresAt, now)
                .set(SkillInteractionEntity::getStatus, SkillInteractionStatus.EXPIRED)
                .set(SkillInteractionEntity::getUpdatedAt, now);
        int n = skillInteractionMapper.update(null, uw);
        if (n > 0) {
            log.info("[InteractionExpirationJob] 标记 EXPIRED 条数: {}", n);
        }
    }
}
