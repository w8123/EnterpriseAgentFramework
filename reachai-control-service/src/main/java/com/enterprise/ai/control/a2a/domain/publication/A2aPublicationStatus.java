package com.enterprise.ai.control.a2a.domain.publication;

import com.enterprise.ai.control.a2a.domain.A2aDomainText;

public enum A2aPublicationStatus {
    DRAFT,
    VALIDATING,
    READY,
    PUBLISHED,
    SUSPENDED,
    ARCHIVED;

    public static A2aPublicationStatus parse(String value) {
        return A2aDomainText.parseEnum(A2aPublicationStatus.class, value, "publicationStatus");
    }
}
