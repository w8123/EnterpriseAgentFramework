package com.enterprise.ai.control.a2a.domain.publication;

import com.enterprise.ai.control.a2a.domain.A2aDomainText;

public enum A2aPublicationRevisionStatus {
    DRAFT,
    READY,
    PUBLISHED,
    ARCHIVED;

    public static A2aPublicationRevisionStatus parse(String value) {
        return A2aDomainText.parseEnum(A2aPublicationRevisionStatus.class, value,
                "publicationRevisionStatus");
    }
}
