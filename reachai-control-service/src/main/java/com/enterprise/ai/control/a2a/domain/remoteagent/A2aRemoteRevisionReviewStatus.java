package com.enterprise.ai.control.a2a.domain.remoteagent;

import com.enterprise.ai.control.a2a.domain.A2aDomainText;

public enum A2aRemoteRevisionReviewStatus {
    PENDING,
    APPROVED,
    REJECTED,
    SUPERSEDED;

    public static A2aRemoteRevisionReviewStatus parse(String value) {
        return A2aDomainText.parseEnum(A2aRemoteRevisionReviewStatus.class, value,
                "remoteRevisionReviewStatus");
    }
}
