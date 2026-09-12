package com.enterprise.ai.runtime.agent;

import java.util.Optional;

/** Stable Agent identity, with ID precedence over aliases and no display or publication lookup. */
public interface RuntimeAgentIdentityQuery {
    Optional<RuntimeAgentExecutionView> find(String idOrKeySlug);
}
