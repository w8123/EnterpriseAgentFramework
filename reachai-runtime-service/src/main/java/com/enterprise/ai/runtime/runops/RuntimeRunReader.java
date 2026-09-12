package com.enterprise.ai.runtime.runops;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class RuntimeRunReader implements RuntimeRunQuery {

    private final RuntimeRunMapper runMapper;

    @Override
    @Transactional(readOnly = true)
    public Optional<Run> findByTraceId(String traceId) {
        if (!StringUtils.hasText(traceId)) return Optional.empty();
        RuntimeRunEntity run = runMapper.selectOne(Wrappers.<RuntimeRunEntity>lambdaQuery()
                .select(RuntimeRunEntity::getTraceId, RuntimeRunEntity::getProjectCode,
                        RuntimeRunEntity::getSessionId, RuntimeRunEntity::getPageInstanceId,
                        RuntimeRunEntity::getEntryType, RuntimeRunEntity::getStatus)
                .eq(RuntimeRunEntity::getTraceId, traceId.trim())
                .last("LIMIT 1"));
        return Optional.ofNullable(run).map(value -> new Run(value.getTraceId(), value.getProjectCode(),
                value.getSessionId(), value.getPageInstanceId(), value.getEntryType(), value.getStatus()));
    }
}
