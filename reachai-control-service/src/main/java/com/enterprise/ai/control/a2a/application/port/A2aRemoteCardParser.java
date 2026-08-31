package com.enterprise.ai.control.a2a.application.port;

import com.enterprise.ai.control.a2a.domain.remoteagent.A2aRemoteCardSnapshot;

public interface A2aRemoteCardParser {
    A2aRemoteCardSnapshot parse(byte[] body);
}
