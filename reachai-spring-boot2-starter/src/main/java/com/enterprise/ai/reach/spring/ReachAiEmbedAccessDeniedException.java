package com.enterprise.ai.reach.spring;

/** A verified upstream HTTP denial, without exposing its response body. */
final class ReachAiEmbedAccessDeniedException extends IllegalStateException {
    ReachAiEmbedAccessDeniedException() {
        super("ReachAI Embed Token exchange denied");
    }
}
