package com.enterprise.ai.runtime.execution;

import java.time.LocalDateTime;

/** Execution-owned port for closing Trace/RunOps state after interaction expiry. */
public interface RuntimeInteractionExpiryTracePort {

    void expireWaitingInteraction(String traceId,
                                  String interactionId,
                                  LocalDateTime expiredAt);
}
