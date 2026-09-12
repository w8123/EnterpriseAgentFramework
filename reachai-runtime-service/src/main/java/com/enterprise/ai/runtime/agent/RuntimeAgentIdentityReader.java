package com.enterprise.ai.runtime.agent;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class RuntimeAgentIdentityReader implements RuntimeAgentIdentityQuery {
    private final RuntimeAgentMapper agents;

    @Override
    @Transactional(readOnly = true)
    public Optional<RuntimeAgentExecutionView> find(String idOrKeySlug) {
        if (!StringUtils.hasText(idOrKeySlug)) return Optional.empty();
        return Optional.ofNullable(agents.selectByIdOrKeySlug(idOrKeySlug.trim()))
                .map(RuntimeAgentExecutionView::fromEntity);
    }
}
